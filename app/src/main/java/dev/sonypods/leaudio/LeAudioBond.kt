package dev.sonypods.leaudio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import com.mercury.sonypods.R
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.utils.ModuleText
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bonds the headset's LE Audio identity, the way it works by hand.
 *
 * Four steps, each one waiting on an event rather than a delay:
 *
 *  1. **SCANNING** — sweeps for LE Audio unicast announcements and picks the headset's LE-only
 *     identity out of them; see [LeAudioAnnouncementParser.pickPairingCandidate] for the criterion
 *     and why the advertisement is usually all there is.
 *  2. **CLEARING** — only once the target has actually been picked. `AdapterService.createBond`
 *     refuses any already-bonded address, and the second earbud's LE identity reuses the classic
 *     BD_ADDR, so that bond has to go — but destroying a working pairing before knowing the headset
 *     is even advertising leaves the user with neither.
 *  3. **BONDING** — `createBond()` on the `BluetoothDevice` the scan produced. No transport
 *     argument: TRANSPORT_AUTO lets the stack use the address type it learned while scanning, and a
 *     random static address leaves it only one transport to pick. The device object is never
 *     re-derived from its address either — `getRemoteDevice(String)` types every address as public.
 *  4. **SUCCESS** — permit the profile. The bond alone leaves the phone on A2DP; by hand the second
 *     half is the per-device "低功耗音频" switch in Bluetooth settings.
 *
 * Opens no GATT connection. Bonding does not need one — nRF Connect bonds this identity from its
 * scan list with `stopLeScan()` followed by `createBond()` and nothing else — and a module-owned
 * GATT client is what took the stack down with a SIGBUS in `bta_gattc_cfg_mtu_cmpl`.
 *
 * The sibling earbud is not this flow's business. CTKD brings the classic bond back on its own and
 * CSIS hands the rest of the set to the system.
 *
 * Runs in the `com.android.bluetooth` process: the bluetooth uid is what makes [LeAudioStack]'s two
 * calls available, and it keeps every step of the flow inside one process. Nothing here may throw —
 * a Throwable escaping this process takes the whole stack with it.
 */
class LeAudioBond(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStageChanged(stage: Stage, message: String, bondedAddress: String?)
        fun onLog(message: String)
    }

    enum class Stage { IDLE, SCANNING, CLEARING, BONDING, JOINING, SUCCESS, FAILED }

    private val appContext = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val stack = LeAudioStack(appContext) { log(it) }
    private val adapter: BluetoothAdapter? = runCatching {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    private var stage = Stage.IDLE

    /** The addresses the headset reported over Tandem, when it can report any at all. */
    private var targets: Set<String> = emptySet()

    /** The headset's advertised name, used to reject other models. */
    private var targetName: String? = null

    /** The bonded classic identity, dropped once — and only once — the target has been picked. */
    private var controlAddress: String? = null

    /** The device object the scan produced for the picked target, bonded as-is. */
    private var target: BluetoothDevice? = null

    private val candidates = ConcurrentHashMap<String, LeAudioAnnouncementParser.Candidate>()

    /** The scan's own [BluetoothDevice] per address; see the class comment for why it is kept. */
    private val candidateDevices = ConcurrentHashMap<String, BluetoothDevice>()

    /** Addresses this run saw bonded, in order; the first is the identity it dialled. */
    private val bonded = LinkedHashSet<String>()

    private var passes: List<ScanPass> = emptyList()
    private var passIndex = 0
    private var scanDeadlineAt = 0L
    private var scanCallback: ScanCallback? = null
    private var receiverRegistered = false

    /** Filtered first, unfiltered second: the offloaded service-data filter is what answered on the
     * first sweep of a LinkBuds S, but it is not guaranteed on every controller. */
    private data class ScanPass(val filtered: Boolean, val extended: Boolean)

    // Held as fields, not method references: `::foo` allocates a fresh object every time, so
    // removeCallbacks(::foo) would never cancel what postDelayed(::foo) scheduled.
    private val scanWindowElapsed = Runnable { finishScanPass() }
    private val scanRetry = Runnable { runScanPass() }
    private val clearTimeout = Runnable { onClearTimeout() }
    private val bondTimeout = Runnable { onBondTimeout() }
    private val joinDeadline = Runnable { finish(complete = false) }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching {
                when (intent?.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondStateChanged(intent)
                    ACTION_CSIS_SET_MEMBER_AVAILABLE -> onSetMemberAvailable(intent)
                }
            }
        }
    }

    fun isRunning(): Boolean = stage !in setOf(Stage.IDLE, Stage.SUCCESS, Stage.FAILED)

    /**
     * Every address this run touches, for the connect gate that keeps the rest of the module out.
     *
     * Measured on 2026-09-04: the LE bond brings an ACL up on the LE identity, that ACL event
     * reaches [dev.sonypods.hook.HeadsetStateDispatcher], `reconcileConnection` decides the control
     * identity "is connected but has no Tandem session", and the engine opens a Sony SPP socket to
     * it. The classic page that follows takes the controller and the LE pairing dies with
     * `SMP_CONN_TOUT` 70 ms later — which surfaces as the system's PIN dialog and a failed bond.
     * Both identities have to be off limits until the run ends.
     */
    fun involvedAddresses(): Set<String> = buildSet {
        controlAddress?.let(::add)
        addressOf(target)?.let(::add)
        addAll(targets)
        addAll(bonded)
    }

    /**
     * @param reportedLeAddresses `LeaState.leAudioAddresses` — only devices declaring
     *   supported-function 0x64 ever answer that query, so this is usually empty and the
     *   advertisement decides. See [LeAudioAnnouncementParser.pickPairingCandidate].
     * @param targetName the headset's advertised name, used to reject other models.
     * @param controlAddress the bonded classic identity. Required — this flow is only reachable from
     *   a bonded headset's detail page, so a null here is a wiring fault, not a state to recover
     *   from, and continuing would bond one earbud and orphan the other.
     */
    fun start(
        reportedLeAddresses: List<String>,
        targetName: String?,
        controlAddress: String?,
    ) {
        if (isRunning()) {
            log("already running")
            return
        }
        setStage(Stage.IDLE, "", null)
        target = null
        candidates.clear()
        candidateDevices.clear()
        bonded.clear()
        val localAdapter = adapter
        if (localAdapter == null || !runCatching { localAdapter.isEnabled }.getOrDefault(false)) {
            fail(ModuleText.get(appContext, R.string.pairer_bluetooth_off))
            return
        }
        val control = controlAddress?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (control == null) {
            log("no classic identity to free")
            fail(ModuleText.get(appContext, R.string.pairer_clear_failed))
            return
        }
        this.controlAddress = control
        this.targetName = targetName
        targets = reportedLeAddresses
            .mapNotNull { address -> address.trim().uppercase().takeIf { it.isNotEmpty() } }
            .filterNot { it == control }
            .toSet()
        passIndex = 0
        scanDeadlineAt = SystemClock.elapsedRealtime() + SCAN_DEADLINE_MS
        passes = buildList {
            val extended = runCatching { localAdapter.isLeExtendedAdvertisingSupported }
                .getOrDefault(false)
            add(ScanPass(filtered = true, extended = extended))
            add(ScanPass(filtered = false, extended = extended))
        }
        registerReceiver()
        log("started reportedLe=$targets name=${targetName.orEmpty()} control=$control")
        runScanPass()
    }

    fun cancel() {
        if (stage == Stage.IDLE) return
        log("cancelled")
        cleanUp()
        setStage(Stage.IDLE, "", null)
    }

    /**
     * Removes a bond this flow created, so disabling LE Audio leaves no LE identity behind.
     *
     * `AdapterService.removeBond` drops the whole CSIS group, so one call clears both earbuds.
     */
    fun unpair(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        if (bondStateOf(device) == BluetoothDevice.BOND_NONE) {
            log("LE identity $address is not bonded; nothing to remove")
            return true
        }
        log("AdapterService.removeBond($address) = ${stack.removeBond(device)}")
        return true
    }

    // ---- step 1: find the headset's LE-only identity ----

    /**
     * One 8-second sweep per pass, cycling until something is picked or the deadline passes.
     *
     * `setLegacy(false)` is the load-bearing setting: the default reports legacy advertisements
     * only. The filtered pass asks the controller for ASCS service data — that is the pass that
     * answered on the first sweep of a LinkBuds S — and the unfiltered pass follows it because
     * offloaded filtering is not guaranteed on every controller.
     */
    @SuppressLint("MissingPermission")
    private fun runScanPass() {
        // The user is resetting the headset while this runs, so one sweep of the passes is not
        // enough: keep cycling until the deadline rather than failing while the headset reboots.
        if (passIndex >= passes.size) {
            if (SystemClock.elapsedRealtime() >= scanDeadlineAt) {
                log("nothing viable in ${candidates.size} announcement(s)")
                fail(ModuleText.get(appContext, R.string.pairer_identity_not_found))
                return
            }
            passIndex = 0
        }
        val pass = passes.getOrNull(passIndex)
        val scanner = adapter?.bluetoothLeScanner
        if (pass == null || scanner == null) {
            fail(ModuleText.get(appContext, R.string.pairer_scan_unavailable))
            return
        }
        setStage(
            Stage.SCANNING,
            ModuleText.get(appContext, R.string.pairer_scanning_for_identity),
            null,
        )
        log("scan pass ${passIndex + 1}/${passes.size} filtered=${pass.filtered} extended=${pass.extended}")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                runCatching { recordCandidate(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                runCatching { results?.forEach(::recordCandidate) }
            }

            override fun onScanFailed(errorCode: Int) {
                log("scan pass ${passIndex + 1} failed errorCode=$errorCode")
                handler.removeCallbacks(scanWindowElapsed)
                handler.postDelayed(scanWindowElapsed, SCAN_RETRY_MS)
            }
        }
        scanCallback = callback
        val filters = if (pass.filtered) {
            listOf(
                ScanFilter.Builder()
                    .setServiceData(ParcelUuid(ASCS_UUID), ByteArray(0), ByteArray(0))
                    .build()
            )
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .apply { if (pass.extended) setLegacy(false) }
            .build()
        if (!runCatching { scanner.startScan(filters, settings, callback) }.isSuccess) {
            log("scan pass ${passIndex + 1} could not be started")
            scanCallback = null
            passIndex++
            // Never retry synchronously. A controller that rejects every start would recurse
            // through the passes until the deadline, and this runs on the bluetooth process's main
            // looper — the overflow takes the stack down with it.
            handler.removeCallbacks(scanRetry)
            handler.postDelayed(scanRetry, SCAN_RETRY_MS)
            return
        }
        handler.removeCallbacks(scanWindowElapsed)
        handler.postDelayed(scanWindowElapsed, SCAN_WINDOW_MS)
    }

    /** Keeps the scan's own [BluetoothDevice]; see the class comment for why it is not rebuilt. */
    private fun recordCandidate(result: ScanResult?) {
        val device = result?.device ?: return
        val address = addressOf(device) ?: return
        val announcement = LeAudioAnnouncementParser.parse(
            runCatching { result.scanRecord?.bytes }.getOrNull()
        ) ?: return
        if (!announcement.isUnicastAnnouncement) return
        candidates[address] =
            LeAudioAnnouncementParser.Candidate(address, announcement, result.rssi)
        candidateDevices[address] = device
    }

    @SuppressLint("MissingPermission")
    private fun finishScanPass() {
        if (stage != Stage.SCANNING) return
        stopScan()
        val seen = candidates.values.toList()
        log("scan pass ${passIndex + 1} saw ${seen.size} announcement(s): " +
            seen.joinToString(transform = ::describe))
        val picked = LeAudioAnnouncementParser.pickPairingCandidate(
            candidates = seen,
            targetName = targetName,
            reportedLeAddresses = targets,
            excludeAddresses = listOfNotNull(controlAddress),
        )
        val device = picked?.address?.let { candidateDevices[it] }
        if (picked == null || device == null) {
            passIndex++
            runScanPass()
            return
        }
        target = device
        log("bonding LE identity ${describe(picked)}")
        clearClassicBond()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(scanWindowElapsed)
        handler.removeCallbacks(scanRetry)
        scanCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        scanCallback = null
    }

    // ---- step 2: free the classic address ----

    /**
     * Drops the classic bond, now that the target is known to be there.
     *
     * `AdapterService.createBond` returns false for any already-bonded address on this ROM: the AOSP
     * branch that would add a missing transport to a dual-mode bond sits behind an early
     * `if (bondState != BOND_NONE) return bondState == BOND_BONDING;` and never runs. The second
     * earbud's LE identity is this very address, so it has to be free.
     *
     * A public-typed device object is right here, unlike for the LE identity: the classic identity
     * is the headset's BR/EDR address.
     */
    @SuppressLint("MissingPermission")
    private fun clearClassicBond() {
        val address = controlAddress ?: return
        val device = remoteDevice(address)
        if (device == null) {
            fail(ModuleText.get(appContext, R.string.pairer_clear_failed))
            return
        }
        setStage(
            Stage.CLEARING,
            ModuleText.get(appContext, R.string.pairer_clearing_classic_bond),
            null,
        )
        if (bondStateOf(device) == BluetoothDevice.BOND_NONE) {
            log("classic bond on $address is already gone")
            beginBond()
            return
        }
        log("AdapterService.removeBond($address) = ${stack.removeBond(device)}")
        handler.removeCallbacks(clearTimeout)
        handler.postDelayed(clearTimeout, CLEAR_TIMEOUT_MS)
    }

    private fun onClearTimeout() {
        if (stage != Stage.CLEARING) return
        log("classic bond ${controlAddress.orEmpty()} did not clear")
        fail(ModuleText.get(appContext, R.string.pairer_clear_failed))
    }

    // ---- step 3: bond ----

    @SuppressLint("MissingPermission")
    private fun beginBond() {
        val device = target ?: return
        val address = addressOf(device).orEmpty()
        // Recorded before the bond, not after it: the headset brings its GATT link up on this
        // identity within milliseconds of the bond completing, and whoever reads the identity first
        // decides whether the capability tableset is taken from the control identity or built from
        // the LEA-only list this one answers with. The pair is a fact about the headset either way —
        // it holds whether or not this bond succeeds — so there is nothing optimistic about it.
        controlAddress?.let { control ->
            runCatching { SonyDeviceService.linkLeAudioIdentity(address, control) }
                .onFailure { log("identity link failed: $it") }
        }
        setStage(
            Stage.BONDING,
            ModuleText.get(appContext, R.string.pairer_pairing_identity, address),
            null,
        )
        log("createBond($address) = ${runCatching { device.createBond() }.getOrDefault(false)}")
        handler.removeCallbacks(bondTimeout)
        handler.postDelayed(bondTimeout, BOND_TIMEOUT_MS)
    }

    private fun onBondTimeout() {
        if (stage != Stage.BONDING) return
        log("bond ${addressOf(target).orEmpty()} timed out")
        fail(ModuleText.get(appContext, R.string.pairer_bond_rejected))
    }

    private fun onBondStateChanged(intent: Intent) {
        val device = intent.bluetoothDevice() ?: return
        val address = addressOf(device) ?: return
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
        val previous =
            intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
        log("bond ${bondStateName(previous)} -> ${bondStateName(state)} for $address")

        if (stage == Stage.CLEARING && address == controlAddress) {
            if (state != BluetoothDevice.BOND_NONE) return
            handler.removeCallbacks(clearTimeout)
            log("classic bond cleared")
            beginBond()
            return
        }
        // The stack bonds the sibling itself once CSIS reports it, and on a Sony TWS headset that
        // sibling is the classic identity this run freed. Its BONDED is what completes the set.
        if (stage == Stage.JOINING) {
            if (address == controlAddress && state == BluetoothDevice.BOND_BONDED) {
                bonded += address
                handler.removeCallbacks(joinDeadline)
                log("the stack bonded the sibling $address; set complete")
                finish(complete = true)
                return
            }
            // The headset can revoke the bond this run just made while the set is still forming.
            // Reporting a set at that point would publish an identity the stack no longer holds.
            if (address == addressOf(target) && state == BluetoothDevice.BOND_NONE) {
                handler.removeCallbacks(joinDeadline)
                fail(ModuleText.get(appContext, R.string.pairer_bond_rejected))
            }
            return
        }
        if (stage != Stage.BONDING || address != addressOf(target)) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> onFirstMemberBonded(address)
            // BONDED -> NONE is the headset revoking the bond after the fact; treating only
            // BONDING -> NONE as failure would leave that case to the timeout.
            BluetoothDevice.BOND_NONE ->
                if (previous == BluetoothDevice.BOND_BONDING ||
                    previous == BluetoothDevice.BOND_BONDED
                ) {
                    fail(ModuleText.get(appContext, R.string.pairer_bond_rejected))
                }
        }
    }

    // ---- step 4: let the stack finish the set, then hand it LE Audio ----

    /**
     * Waits for the sibling earbud instead of declaring victory on one bond.
     *
     * Measured on 2026-09-04, twice, once through nRF Connect and once through this flow: `CsipSet
     * CoordinatorService` reports the second member ~390 ms after the first bond completes, and
     * `android.uid.system` bonds it 10 ms later with `createBond(TRANSPORT_LE)`. Nothing here has to
     * bond it — but everything here has to wait for it, because the set is what carries a stream and
     * because permitting the profile before CSIS has read the SIRK is what stopped that report from
     * ever arriving.
     */
    private fun onFirstMemberBonded(address: String) {
        handler.removeCallbacks(bondTimeout)
        bonded += address
        log("bonded $address; waiting for the stack to bring in the rest of the set")
        setStage(Stage.JOINING, ModuleText.get(appContext, R.string.pairer_joining_set), address)
        handler.removeCallbacks(joinDeadline)
        handler.postDelayed(joinDeadline, JOIN_DEADLINE_MS)
    }

    private fun onSetMemberAvailable(intent: Intent) {
        if (stage != Stage.JOINING) return
        val address = addressOf(intent.bluetoothDevice()) ?: return
        log("CSIS reports set member $address in group ${intent.getIntExtra(EXTRA_CSIS_GROUP_ID, -1)}")
    }

    /**
     * Hands the finished set to the system's LE Audio permission — the step that actually routes
     * LC3, and the one the user otherwise has to perform by hand in Bluetooth settings.
     *
     * Only for a whole set. One bonded earbud with LE Audio permitted is worse than the A2DP the
     * headset already had, so an incomplete set keeps the bond, says so, and changes nothing.
     */
    private fun finish(complete: Boolean) {
        val primary = bonded.firstOrNull()
        val control = controlAddress
        val whole = complete || siblingBonded()
        log("set ${if (whole) "complete" else "incomplete"} with ${bonded.size} bond(s) from this run")
        if (whole && control != null) {
            // Anchored on the control identity, not on "whatever is connected": nothing is connected
            // at this point, and the engine's fallback to the live session address is null here.
            // leAudioPolicyDevices resolves the LE half from the identity pair recorded in beginBond.
            log("permitting LE Audio on the finished set via $control")
            runCatching { SonyBridge.setLeAudioPolicyAllowed(appContext, true, control) }
                .onFailure { log("LE Audio permission request failed: $it") }
        }
        val message = if (whole) "" else ModuleText.get(appContext, R.string.pairer_set_incomplete)
        cleanUp()
        setStage(Stage.SUCCESS, message, primary)
    }

    /** Whether the classic identity the run freed has been bonded again by the stack. */
    @SuppressLint("MissingPermission")
    private fun siblingBonded(): Boolean {
        val control = controlAddress ?: return false
        val device = remoteDevice(control) ?: return false
        return bondStateOf(device) == BluetoothDevice.BOND_BONDED
    }

    // ---- plumbing ----

    private fun fail(message: String) {
        log("failed: $message")
        cleanUp()
        setStage(Stage.FAILED, message, null)
    }

    private fun setStage(next: Stage, message: String, bondedAddress: String?) {
        stage = next
        log("stage=$next ${message.ifEmpty { "-" }}")
        listener.onStageChanged(next, message, bondedAddress)
    }

    private fun cleanUp() {
        handler.removeCallbacks(clearTimeout)
        handler.removeCallbacks(bondTimeout)
        handler.removeCallbacks(joinDeadline)
        stopScan()
        unregisterReceiver()
        // Nothing below survives a finished run. Callers that log a target do so before this.
        target = null
        targets = emptySet()
        targetName = null
        controlAddress = null
        candidates.clear()
        candidateDevices.clear()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = runCatching {
            appContext.registerReceiver(
                bondReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
                    addAction(ACTION_CSIS_SET_MEMBER_AVAILABLE)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.isSuccess
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(bondReceiver) }
        receiverRegistered = false
    }

    private fun remoteDevice(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    private fun addressOf(device: BluetoothDevice?): String? =
        runCatching { device?.address?.uppercase() }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun bondStateOf(device: BluetoothDevice): Int =
        runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)

    private fun Intent.bluetoothDevice(): BluetoothDevice? = runCatching {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    }.getOrNull()

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN($state)"
    }

    /** What a candidate's advertisement carried — the line that makes a failed run readable. */
    private fun describe(candidate: LeAudioAnnouncementParser.Candidate): String {
        val a = candidate.announcement
        return "${candidate.address}(name=${a.name.orEmpty()} flags=${a.flags} " +
            "type=${a.announcementType} rsi=${a.rsi.orEmpty()} swiftPair=${a.hasSwiftPair} " +
            "rssi=${candidate.rssi})"
    }

    private fun log(message: String) = listener.onLog("LEA-BOND[$stage] $message")

    private companion object {
        const val ACTION_CSIS_SET_MEMBER_AVAILABLE =
            "android.bluetooth.action.CSIS_SET_MEMBER_AVAILABLE"
        const val EXTRA_CSIS_GROUP_ID = "android.bluetooth.extra.CSIS_GROUP_ID"

        /** Audio Stream Control Service, the offloaded filter of the first scan pass. */
        val ASCS_UUID: UUID = UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB")
        const val SCAN_WINDOW_MS = 8_000L
        /** Backs off a rejected scan start instead of retrying it inline. */
        const val SCAN_RETRY_MS = 1_000L
        /** Long enough for the user to actually reset the headset after being told to. */
        const val SCAN_DEADLINE_MS = 120_000L
        const val CLEAR_TIMEOUT_MS = 8_000L
        const val BOND_TIMEOUT_MS = 30_000L
        /** CSIS has to connect the first member and scan by SIRK before the sibling appears;
         * measured at ~3 s end to end, so this is generous rather than tight. */
        const val JOIN_DEADLINE_MS = 30_000L
    }
}
