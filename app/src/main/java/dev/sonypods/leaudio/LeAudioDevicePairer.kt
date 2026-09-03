package dev.sonypods.leaudio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.mercury.sonypods.R
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.device.UnifiedDeviceIdentityService
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import dev.sonypods.utils.ModuleText
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bonds the headset's LE-only identity so that Android will actually route audio over
 * LC3.
 *
 * Telling the headset to switch to LE Audio is only half of the job. A Sony headset then
 * advertises a separate, non-discoverable LE identity carrying the LE Audio unicast
 * announcement, and the phone has to bond *that* identity. Classic discovery — which is
 * all the system pairing screen runs — never surfaces it, so without this the phone keeps
 * its BR/EDR-only bond and stays on A2DP/LDAC.
 *
 * Runs in the `com.android.bluetooth` engine process, where the bluetooth uid makes the
 * privileged bonding and profile-policy calls available.
 */
class LeAudioDevicePairer(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStageChanged(stage: Stage, message: String, bondedAddress: String?)
        fun onLog(message: String)
    }

    enum class Stage { IDLE, SCANNING, PAIRING, SUCCESS, FAILED }

    private val appContext = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = runCatching {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    private var stage = Stage.IDLE
    private var targetName: String? = null
    private var reportedLeAddresses: List<String> = emptyList()
    private var excludeAddresses: List<String> = emptyList()
    private var passes: List<ScanPass> = emptyList()
    private var passIndex = 0
    private var activeCallback: ScanCallback? = null
    private val candidates = ConcurrentHashMap<String, LeAudioAnnouncementParser.Candidate>()
    private var bondTarget: BluetoothDevice? = null
    private var picked: LeAudioAnnouncementParser.Candidate? = null
    /** Coordinated-set members still to bond, and the outcome of the ones already tried. */
    private var pending = mutableListOf<LeAudioAnnouncementParser.Candidate>()
    private val bonded = mutableListOf<String>()
    private val failedMembers = mutableListOf<String>()
    /** LE service-discovery connections, which outlive the pairing stages on purpose: the
     * retry they feed is what gets the second earbud into the group. */
    private val leDiscoveryClients = mutableListOf<SonyLeAudioGattClient>()
    /** Address currently being re-paired to derive LE keys, if any. */
    private var ctkdRepairAddress: String? = null
    /** The bonded classic identity of the set, when this run knows it by construction. */
    private var knownControlAddress: String? = null
    /** Every viable member seen so far this run; the second earbud often advertises late. */
    private val collected = LinkedHashMap<String, LeAudioAnnouncementParser.Candidate>()
    /** When the first viable member appeared; the collection window is measured from it. */
    private var firstViableAtMs = 0L
    private var gattProvoke: SonyLeAudioGattClient? = null
    private var receiverRegistered = false
    /** Scanning keeps cycling its passes until this deadline, giving the user time to
     * finish resetting the headset before the flow gives up. */
    private var scanDeadline = 0L

    // Held as fields, not method references: `::foo` allocates a fresh object every time,
    // so removeCallbacks(::foo) would never cancel what postDelayed(::foo) scheduled, and a
    // stale timeout from one attempt would abort the next one.
    private val scanWindowElapsed = Runnable { finishScanPass() }
    private val bondTimeout = Runnable { onBondTimeout() }
    private val gattProvokeRunnable = Runnable { provokePairingOverGatt() }
    private val ctkdTimeout = Runnable { onCtkdTimeout() }

    /** Filtered first, unfiltered second: the offloaded service-data filter is not
     * reliable for every controller, and [SonyBleClient] already learned that lesson. */
    private data class ScanPass(val filtered: Boolean, val extended: Boolean)

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondStateChanged(intent)
                BluetoothDevice.ACTION_PAIRING_REQUEST -> onPairingRequest(intent)
            }
        }
    }

    fun isRunning(): Boolean = stage == Stage.SCANNING || stage == Stage.PAIRING

    /**
     * @param targetName the headset's advertised name, used to reject other people's earbuds.
     * @param reportedLeAddresses LE endpoint addresses the headset reported over Tandem.
     * @param excludeAddresses addresses already bonded over classic, which must never be the target.
     */
    fun start(
        targetName: String?,
        reportedLeAddresses: List<String>,
        excludeAddresses: List<String>,
    ) {
        if (isRunning()) {
            log("device-side pairing already running")
            return
        }
        val localAdapter = adapter
        if (localAdapter == null || !runCatching { localAdapter.isEnabled }.getOrDefault(false)) {
            fail(ModuleText.get(appContext, R.string.pairer_bluetooth_off))
            return
        }
        this.targetName = targetName
        this.reportedLeAddresses = reportedLeAddresses
        this.excludeAddresses = excludeAddresses
        // The excluded bonds ARE the classic identities of headsets being re-paired; knowing
        // one up front lets the success path link aliases without re-classifying (whose uuid
        // cache this very flow is about to pollute with ASCS).
        knownControlAddress = excludeAddresses.firstOrNull()
        candidates.clear()
        collected.clear()
        firstViableAtMs = 0L
        bondTarget = null
        picked = null
        pending.clear()
        bonded.clear()
        failedMembers.clear()
        passIndex = 0
        scanDeadline = android.os.SystemClock.elapsedRealtime() + SCAN_DEADLINE_MS
        passes = buildList {
            val extendedSupported = runCatching {
                localAdapter.isLeExtendedAdvertisingSupported
            }.getOrDefault(false)
            add(ScanPass(filtered = true, extended = extendedSupported))
            add(ScanPass(filtered = false, extended = extendedSupported))
            // If asking for extended results is what the controller rejected, a plain
            // legacy sweep is still worth one attempt before giving up.
            if (extendedSupported) add(ScanPass(filtered = false, extended = false))
        }
        registerReceiver()
        log(
            "device-side pairing started target=${targetName.orEmpty()} " +
                "reportedLe=$reportedLeAddresses exclude=$excludeAddresses"
        )
        // Scan before touching the classic bond. Clearing it first only cost the user their
        // connection whenever the scan then came up empty.
        runScanPass()
    }

    fun cancel() {
        if (stage == Stage.IDLE) return
        log("device-side pairing cancelled")
        closeLeDiscoveryClients()
        cleanUp()
        abandonPendingBond()
        setStage(Stage.IDLE, "", null)
    }

    private fun closeLeDiscoveryClients() {
        leDiscoveryClients.forEach { client -> runCatching { client.close() } }
        leDiscoveryClients.clear()
    }

    /** Removes a bond created by [start], so disabling LE Audio does not leave a stale
     * LE identity for the stack to keep reconnecting to. */
    @SuppressLint("MissingPermission")
    fun unpair(address: String): Boolean {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        if (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) ==
            BluetoothDevice.BOND_NONE
        ) {
            log("LE identity $address is not bonded; nothing to remove")
            return true
        }
        val removed = removeBondReflective(device)
        log("removeBond($address) = $removed")
        return removed
    }

    /**
     * Removes a bond, preferring the in-process `AdapterService` over the public API.
     *
     * `BluetoothDevice.removeBond()` goes out over Binder and came back as an
     * InvocationTargetException on this ROM, leaving the bond untouched. The engine already
     * runs inside `com.android.bluetooth`, so it can call `AdapterService.removeBond` directly
     * and skip that hop. That entry point also drops the whole CSIS group's bonds, which is
     * what a coordinated set needs anyway.
     *
     * The class must be resolved through the **host** classloader: a bare `Class.forName`
     * resolves against this module's loader, which cannot see host classes and fails with
     * ClassNotFoundException — exactly what silently broke the CTKD re-pair once.
     */
    /**
     * `BluetoothDevice.removeBond()` refuses to run on the main thread ("Must NOT be called on
     * main thread"), and this pairer drives everything from the main looper, so the call is
     * handed to a short-lived worker and waited on. Without this the bond is never cleared, CTKD
     * never derives LE keys, the re-pair times out and only one set member ends up paired.
     */
    private fun removeBondReflective(device: BluetoothDevice): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return invokeRemoveBond(device)
        var result = false
        val worker = Thread({ result = invokeRemoveBond(device) }, "sonypods-remove-bond")
        worker.start()
        runCatching { worker.join(REMOVE_BOND_TIMEOUT_MS) }
            .onFailure { log("removeBond join interrupted: ${it.unwrap()}") }
        return result
    }

    private fun invokeRemoveBond(device: BluetoothDevice): Boolean =
        runCatching {
            BluetoothDevice::class.java.getMethod("removeBond").invoke(device) as? Boolean
        }.onFailure { log("removeBond failed: ${it.unwrap()}") }
            .getOrNull() ?: false

    /**
     * Whether [address] is a random Bluetooth address, i.e. the headset's LE identity.
     *
     * See [succeedAll]: this is the only discriminator that neither the caller's exclude list
     * nor the ASCS-polluted service cache can get wrong.
     */
    private fun isRandomAddress(address: String?): Boolean {
        val first = address?.substringBefore(':')?.toIntOrNull(16) ?: return false
        return when (first shr 6) {
            0b11, 0b01, 0b00 -> true
            else -> false
        }
    }

    /** InvocationTargetException hides the reason a reflective call was rejected. */
    private fun Throwable.unwrap(): String {
        val cause = (this as? java.lang.reflect.InvocationTargetException)?.targetException ?: this
        return "${cause.javaClass.name}: ${cause.message}"
    }

    // ---- scanning ----

    @SuppressLint("MissingPermission")
    private fun runScanPass() {
        // The user is resetting the headset while this runs, so a single sweep of the passes
        // is not enough: keep cycling them until the deadline rather than failing at the
        // moment the headset happens to be rebooting.
        if (passIndex >= passes.size) {
            if (android.os.SystemClock.elapsedRealtime() < scanDeadline) {
                passIndex = 0
            } else {
                fail(ModuleText.get(appContext, R.string.pairer_identity_not_found))
                return
            }
        }
        val pass = passes.getOrNull(passIndex)
        if (pass == null) {
            fail(ModuleText.get(appContext, R.string.pairer_identity_not_found))
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            fail(ModuleText.get(appContext, R.string.pairer_scan_unavailable))
            return
        }
        setStage(Stage.SCANNING, ModuleText.get(appContext, R.string.pairer_scanning_for_identity), null)
        log("scan pass ${passIndex + 1}/${passes.size} filtered=${pass.filtered} extended=${pass.extended}")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                recordCandidate(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach(::recordCandidate)
            }

            override fun onScanFailed(errorCode: Int) {
                log("scan pass ${passIndex + 1} failed errorCode=$errorCode")
                handler.removeCallbacks(scanWindowElapsed)
                handler.post(scanWindowElapsed)
            }
        }
        activeCallback = callback

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

        val started = runCatching { scanner.startScan(filters, settings, callback) }.isSuccess
        if (!started) {
            log("scan pass ${passIndex + 1} could not be started")
            finishScanPass()
            return
        }
        handler.removeCallbacks(scanWindowElapsed)
        handler.postDelayed(scanWindowElapsed, SCAN_WINDOW_MS)
    }

    private fun recordCandidate(result: ScanResult?) {
        val address = result?.device?.address ?: return
        val announcement = LeAudioAnnouncementParser.parse(result.scanRecord?.bytes) ?: return
        if (!announcement.isUnicastAnnouncement) return
        candidates[address.uppercase()] =
            LeAudioAnnouncementParser.Candidate(address, announcement)
    }

    @SuppressLint("MissingPermission")
    private fun finishScanPass() {
        if (stage != Stage.SCANNING) return
        handler.removeCallbacks(scanWindowElapsed)
        activeCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        activeCallback = null

        val viable = LeAudioAnnouncementParser.pairingCandidates(
            candidates = candidates.values.toList(),
            targetName = targetName,
            reportedLeAddresses = reportedLeAddresses,
            excludeAddresses = excludeAddresses,
        )
        log(
            "scan pass ${passIndex + 1} saw ${candidates.size} LE Audio announcement(s): " +
                candidates.values.joinToString { describe(it) }
        )
        // The two earbuds advertise on their own schedule; the second one routinely shows up
        // seconds after the first. Collect across passes instead of bonding the moment one
        // member is viable — a set that bonds with a member missing stays single-eared.
        viable.forEach { collected.putIfAbsent(it.address.uppercase(), it) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (collected.isNotEmpty() && firstViableAtMs == 0L) firstViableAtMs = now
        val collecting = collected.isNotEmpty() &&
            now - firstViableAtMs < MEMBER_COLLECT_MS &&
            now < scanDeadline
        if (collecting) {
            passIndex++
            val remainingSec = ((firstViableAtMs + MEMBER_COLLECT_MS - now) / 1000L).toInt() + 1
            setStage(
                Stage.SCANNING,
                ModuleText.get(appContext, R.string.lea_pair_collecting, remainingSec),
                null,
            )
            runScanPass()
            return
        }
        if (collected.isEmpty()) {
            fail(ModuleText.get(appContext, R.string.pairer_identity_not_found))
            return
        }
        // Every member of the coordinated set has to be bonded, not just the first one. A TWS
        // headset is two LE Audio devices in one CSIS group, and the stack builds a CIG with
        // one CIS per member: bonding a single earbud leaves the other one silent.
        pending = LeAudioAnnouncementParser.pairingCandidates(
            candidates = collected.values.toList(),
            targetName = targetName,
            reportedLeAddresses = reportedLeAddresses,
            excludeAddresses = excludeAddresses,
        ).toMutableList()
        addKnownControlMember()
        putControlMemberFirst()
        log("${pending.size} coordinated-set member(s) to bond: ${pending.joinToString { it.address }}")
        bondNextMember()
    }

    /**
     * Moves the headset's control identity to the front of [pending].
     *
     * Its re-pair is what actually produces LE keys, for the whole headset: the device answers
     * a direct LE-transport bond by opening SMP over BR/EDR from its classic address instead
     * (CTKD), which collides with the in-flight LE bond and drops it — observed as
     * `smp_br_connect_callback: BDA=<classic> pairing_bda=<LE>` followed by `WAIT_AUTH_COMPLETE`
     * and BOND_NONE, plus a system "pairing failed" toast. Doing the classic identity first
     * performs that CTKD deliberately, and the LE members are then already keyed.
     *
     * Separate from [addKnownControlMember] because the control identity usually arrives from
     * the scan itself (it announces LE Audio like any other set member), in which case that
     * method returns early and never gets to order anything.
     */
    @SuppressLint("MissingPermission")
    private fun putControlMemberFirst() {
        val first = pending.firstOrNull() ?: return
        val control = pending.asSequence()
            .mapNotNull { SonyDeviceService.resolveControlAddress(it.address) }
            .firstOrNull { candidate -> pending.any { it.address.equals(candidate, ignoreCase = true) } }
            ?: return
        if (first.address.equals(control, ignoreCase = true)) return
        val index = pending.indexOfFirst { it.address.equals(control, ignoreCase = true) }
        if (index <= 0) return
        log("bonding control identity $control first; its CTKD re-pair is what derives LE keys")
        pending.add(0, pending.removeAt(index))
    }

    /**
     * Adds the earbud that reuses the headset's classic address, whether or not this scan saw it.
     *
     * That earbud advertises only intermittently — scans routinely come back with just the
     * other one — but its address is already known from the bonded set, so waiting to see it
     * announce is what kept its LE keys from ever being derived. [putControlMemberFirst] then
     * orders it ahead of the LE-only members.
     */
    @SuppressLint("MissingPermission")
    private fun addKnownControlMember() {
        val target = pending.firstOrNull() ?: return
        // The caller-supplied exclude address is authoritative and must win: this flow puts
        // ASCS into the classic bond's uuid cache, and a cached ASCS is exactly what makes the
        // name-based classifier read the control identity as the LE one. Re-classifying here
        // and overwriting [knownControlAddress] inverted the pair, and the inverted pair then
        // stopped SonyBleClient from retargeting, so Tandem ran on the LE identity until the
        // headset pushed 0x0D at it every few seconds.
        val known = knownControlAddress
        val control = known ?: SonyDeviceService.resolveControlAddress(target.address)
        if (control == null || control.equals(target.address, ignoreCase = true)) return
        if (pending.any { it.address.equals(control, ignoreCase = true) }) return
        if (hasLeKeys(control)) {
            log("control identity $control already has LE keys; not re-pairing it")
            return
        }
        if (known == null) knownControlAddress = control
        log("adding control identity $control as a set member; it has no LE keys yet")
        pending += target.copy(address = control)
    }

    // ---- bonding ----

    private fun bondNextMember() {
        val next = pending.removeFirstOrNull()
        if (next == null) {
            finishAllMembers()
            return
        }
        picked = next
        log("bonding set member ${describe(next)}")
        beginBond(next.address)
    }

    /**
     * Whether this scan saw the headset advertising the way it does in pairing mode.
     *
     * Pairing mode is a property of the headset, not of one identity, so any collected
     * announcement counts — and it has to be read from the scan rather than from the member
     * being bonded, because the control identity is usually injected by
     * [addKnownControlMember] with the other earbud's announcement copied onto it.
     *
     * The witnesses are the ones the successful run showed and the failing runs lacked:
     * a discoverable flag, a Swift Pair payload, or an RSI (a set member only publishes its
     * Resolvable Set Identifier while it is joinable).
     */
    private fun headsetIsInPairingMode(): Boolean {
        val witness = collected.values.firstOrNull { candidate ->
            val a = candidate.announcement
            a.isDiscoverable || a.hasSwiftPair || !a.rsi.isNullOrEmpty()
        }
        if (witness == null) {
            log("no collected announcement shows pairing mode (discoverable/SwiftPair/RSI)")
            return false
        }
        log("headset is in pairing mode per ${describe(witness)}")
        return true
    }

    private fun finishAllMembers() {
        if (bonded.isEmpty()) {
            fail(ModuleText.get(appContext, R.string.pairer_bond_failed))
            return
        }
        // A single bonded member still gives audio in one ear, which beats reporting failure
        // and leaving the user with nothing; the message says which case they are in.
        val message = if (failedMembers.isEmpty()) {
            ModuleText.get(appContext, R.string.pairer_success_all, bonded.size)
        } else {
            ModuleText.get(appContext, R.string.pairer_success_partial, bonded.size)
        }
        succeedAll(message)
    }

    @SuppressLint("MissingPermission")
    private fun beginBond(address: String) {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            onMemberFailed(address, ModuleText.get(appContext, R.string.pairer_address_unresolvable))
            return
        }
        bondTarget = device
        setStage(Stage.PAIRING, ModuleText.get(appContext, R.string.pairer_pairing_identity, address), null)

        if (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) ==
            BluetoothDevice.BOND_BONDED
        ) {
            // Already bonded over BR/EDR, so createBond is a no-op. If that bond has LE keys
            // the LE side can simply be brought up; if it has none the native client rejects
            // the connection outright with "Connecting <addr> when not bonded", and only a
            // re-pair while the headset is in LE Audio mode derives them (CTKD).
            if (hasLeKeys(address)) {
                log("set member $address is bonded and has LE keys; bringing up its LE Audio side")
                allowLeAudioProfile(address)
                connectLeAudio(address)
                onMemberBonded(address)
            } else if (!headsetIsInPairingMode()) {
                // Removing a live classic bond that cannot be re-established is worse than not
                // switching: the headset only answers a fresh pairing while it advertises in
                // pairing mode, so outside that window removeBond+createBond just times out
                // and leaves the user with no classic pairing at all. Observed 11:13 / 11:32
                // on 2026-09-02: BONDING for a full 30 s with no pairing request, bond gone.
                log("set member $address needs a CTKD re-pair but the headset is not in " +
                    "pairing mode; leaving its bond untouched")
                fail(ModuleText.get(appContext, R.string.pairer_needs_pairing_mode))
            } else {
                log("set member $address is bonded but has no LE keys; re-pairing for CTKD")
                beginCtkdRepair(device)
            }
            return
        }

        if (ctkdAlreadyProvidedKeys(address)) {
            log("set member $address has no LE keys after the control identity's CTKD re-pair; " +
                "a direct LE bond cannot derive them, skipping")
            onMemberFailed(address, ModuleText.get(appContext, R.string.pairer_bond_rejected))
            return
        }

        val requested = createBond(device)
        log("createBond($address) = $requested")
        handler.removeCallbacks(gattProvokeRunnable)
        handler.removeCallbacks(bondTimeout)
        handler.postDelayed(gattProvokeRunnable, GATT_PROVOKE_DELAY_MS)
        handler.postDelayed(bondTimeout, BOND_TIMEOUT_MS)
    }

    /**
     * Whether a direct LE-transport bond on [address] can still work.
     *
     * It cannot once the headset's classic identity has been CTKD-paired in this run: the
     * device hands out LE keys only through that cross-transport pairing, so a fresh LE bond
     * gets answered with SMP over BR/EDR and dies in the collision — the attempt costs a
     * system pairing dialog and a "pairing failed" toast and can never succeed. When CTKD has
     * already run, a member that still shows no keys has nothing left to try.
     */
    private fun ctkdAlreadyProvidedKeys(address: String): Boolean {
        val control = knownControlAddress ?: return false
        if (control.equals(address, ignoreCase = true)) return false
        return bonded.any { it.equals(control, ignoreCase = true) }
    }

    /**
     * TRANSPORT_LE keeps the bond on the identity that was actually scanned. The plain
     * overload is the fallback for builds that hide the transport variant.
     */
    @SuppressLint("MissingPermission")
    private fun createBond(device: BluetoothDevice): Boolean {
        val withTransport = runCatching {
            BluetoothDevice::class.java
                .getMethod("createBond", Int::class.javaPrimitiveType)
                .invoke(device, BluetoothDevice.TRANSPORT_LE) as? Boolean
        }
        withTransport.exceptionOrNull()?.let {
            log("createBond(TRANSPORT_LE) unavailable: ${it.unwrap()}")
        }
        if (withTransport.getOrNull() == true) return true
        return runCatching { device.createBond() }
            .onFailure { log("createBond() failed: ${it.unwrap()}") }
            .getOrDefault(false)
    }

    /**
     * A GATT connect makes the peripheral ask for pairing itself, which is how a BLE
     * debugging app gets this bond established. Used only when [createBond] has not
     * moved the bond state on its own.
     */
    @SuppressLint("MissingPermission")
    private fun provokePairingOverGatt() {
        if (stage != Stage.PAIRING) return
        val device = bondTarget ?: return
        if (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) !=
            BluetoothDevice.BOND_NONE
        ) {
            return
        }
        if (gattProvoke != null) return
        log("createBond did not start pairing; provoking it with a GATT connect")
        val client = SonyLeAudioGattClient(
            appContext,
            object : SonyLeAudioGattClient.Listener {
                override fun onReady() = log("GATT provoke connected")
                override fun onDisconnected(status: Int) = log("GATT provoke disconnected status=$status")
                override fun onFailure(reason: String) = log("GATT provoke failed: $reason")
                override fun onCharacteristicChanged(uuid: UUID, value: ByteArray) = Unit
                override fun onLog(message: String) = log("GATT provoke: $message")
            },
        )
        gattProvoke = client
        if (!client.connect(device.address)) {
            log("GATT provoke could not be started")
            closeGattProvoke()
        }
    }

    private fun onBondStateChanged(intent: Intent) {
        if (stage != Stage.PAIRING) return
        val device = intent.bluetoothDevice() ?: return
        val target = bondTarget ?: return
        if (!device.address.equals(target.address, ignoreCase = true)) return

        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
        val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
        log("bond state ${bondStateName(previous)} -> ${bondStateName(state)} for ${device.address}")

        if (device.address.equals(ctkdRepairAddress, ignoreCase = true)) {
            when (state) {
                BluetoothDevice.BOND_NONE ->
                    if (previous == BluetoothDevice.BOND_BONDED) {
                        onCtkdBondCleared(device)
                    } else if (previous == BluetoothDevice.BOND_BONDING) {
                        ctkdRepairAddress = null
                        handler.removeCallbacks(ctkdTimeout)
                        onMemberFailed(device.address, ModuleText.get(appContext, R.string.pairer_rebond_rejected))
                    }
                BluetoothDevice.BOND_BONDED -> onCtkdRepaired(device.address)
            }
            return
        }

        when (state) {
            BluetoothDevice.BOND_BONDED -> onMemberBonded(device.address)
            // A bond that lands and is immediately revoked (BONDED -> NONE) is the headset
            // refusing the LE-transport pairing after the fact. Treating only BONDING -> NONE
            // as failure left this case to the 30 s bond timeout, with a pointless GATT
            // provoke in between.
            BluetoothDevice.BOND_NONE ->
                if (previous == BluetoothDevice.BOND_BONDING ||
                    previous == BluetoothDevice.BOND_BONDED
                ) {
                    onMemberFailed(device.address, ModuleText.get(appContext, R.string.pairer_bond_rejected))
                }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onMemberBonded(address: String) {
        handler.removeCallbacks(bondTimeout)
        handler.removeCallbacks(gattProvokeRunnable)
        closeGattProvoke()
        bonded.add(address)
        allowLeAudioProfile(address)
        log("set member $address bonded (${bonded.size} so far)")

        bondNextMember()
    }

    private fun onMemberFailed(address: String, reason: String) {
        handler.removeCallbacks(bondTimeout)
        handler.removeCallbacks(gattProvokeRunnable)
        closeGattProvoke()
        failedMembers.add(address)
        log("set member $address failed: $reason")
        bondNextMember()
    }

    /** Just Works is the expected variant here; consent prompts are auto-confirmed so the
     * flow does not stall behind a system dialog the user never asked for. */
    @SuppressLint("MissingPermission")
    private fun onPairingRequest(intent: Intent) {
        if (stage != Stage.PAIRING) return
        val device = intent.bluetoothDevice() ?: return
        val target = bondTarget ?: return
        if (!device.address.equals(target.address, ignoreCase = true)) return
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        log("pairing request variant=$variant for ${device.address}")
        if (variant == PAIRING_VARIANT_CONSENT || variant == PAIRING_VARIANT_PASSKEY_CONFIRMATION) {
            val confirmed = runCatching { device.setPairingConfirmation(true) }.getOrDefault(false)
            log("setPairingConfirmation(true) = $confirmed")
        }
    }

    private fun onBondTimeout() {
        if (stage != Stage.PAIRING) return
        onMemberFailed(bondTarget?.address.orEmpty(), ModuleText.get(appContext, R.string.pairer_bond_timeout))
    }

    private fun succeedAll(message: String) {
        // Record which identities belong to this headset while they are all in hand.
        //
        // The direction is decided here from the Bluetooth address type, not from the caller's
        // exclude list and not from any service cache. The exclude list is the address the live
        // Tandem session happens to sit on, and when that session is on the LE identity (which
        // is exactly the state this flow runs in) it names the wrong side; `bonded` is no better,
        // because a control identity re-paired for CTKD lands in it too. Core spec puts the type
        // in the two most significant bits of the first octet — `11` static random, `01`
        // resolvable private, `00` non-resolvable private — so the headset's LE identity is the
        // random address and its control identity is the public one. Nothing else here survives
        // the LE service discovery this flow just ran, which puts ASCS into the classic bond's
        // uuid cache and makes every service-based test read the control identity as LE.
        runCatching {
            val candidates = (bonded + listOfNotNull(knownControlAddress)).distinct()
            val le = candidates.filter(::isRandomAddress)
            val control = candidates.firstOrNull { !isRandomAddress(it) }
            if (control != null && le.isNotEmpty()) {
                le.forEach { leAddress ->
                    SonyDeviceService.linkLeAudioIdentity(leAddress, control)
                }
                log("recorded identity pair: LE=$le, Control=$control")
            } else {
                log("identity pair not recorded: LE=$le control=$control from $candidates")
            }
            log("LE Audio aliases: ${SonyDeviceService.leAudioAliasSnapshot()}")
        }
        // After bonding, permit LE Audio on every identity of this headset and ask LeAudioService
        // to connect. The control identity is included even when its member step was reported as
        // failed: that step only decides whether *we* derived LE keys through it, while the bond
        // itself is intact and the system's per-device LE Audio switch reads THAT record. Leaving
        // it at CONNECTION_POLICY_FORBIDDEN is what made a successful pairing come up on LDAC
        // with the system switch off, until the user turned it on by hand.
        val identities = buildSet {
            addAll(bonded)
            knownControlAddress?.let(::add)
        }
        // SUCCESS waits for those connects. Publishing it the moment the bonds existed let the
        // UI open the detail page while LE service discovery and the LE_AUDIO connects were still
        // running, so the page came up against a session that was still being rebuilt.
        val primary = bonded.firstOrNull()
        var outstanding = identities.size
        val finish = {
            if (--outstanding <= 0) {
                cleanUp()
                setStage(Stage.SUCCESS, message, primary)
            }
        }
        if (identities.isEmpty()) {
            cleanUp()
            setStage(Stage.SUCCESS, message, primary)
            return
        }
        identities.forEach { address ->
            allowLeAudioProfile(address)
            connectLeAudio(address, allowDiscoveryRetry = false, onDone = finish)
        }
    }

    /**
     * Asks LeAudioService to connect a set member.
     *
     * Needed for the member that is already bonded over BR/EDR: nothing else starts its LE
     * Audio connection, and without it the group keeps one empty CIS and one silent ear.
     *
     * No pre-check on the device's cached UUIDs: `BluetoothDevice.getUuids()` reads the stack's
     * SDP cache, which a BR/EDR-bonded address never gets ASCS into — this flow's own LE GATT
     * discovery does see the full LE Audio service set, but that result does not land there. The
     * check therefore refused every call for the classic member forever, so its CIS stayed empty
     * and only one ear played. Whether the remote qualifies is LeAudioService's decision; if it
     * declines, the failure is logged and one LE discovery retry follows.
     */
    @SuppressLint("MissingPermission")
    private fun connectLeAudio(
        address: String,
        allowDiscoveryRetry: Boolean = true,
        onDone: () -> Unit = {},
    ) {
        val localAdapter = adapter ?: return onDone()
        val device = runCatching { localAdapter.getRemoteDevice(address) }.getOrNull()
            ?: return onDone()
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val connected = runCatching {
                    proxy.javaClass
                        .getMethod("connect", BluetoothDevice::class.java)
                        .invoke(proxy, device) as? Boolean
                }
                connected.exceptionOrNull()?.let { log("LE_AUDIO connect($address) failed: $it") }
                log("LE_AUDIO connect($address) = ${connected.getOrNull()}")
                runCatching { localAdapter.closeProfileProxy(BluetoothProfile.LE_AUDIO, proxy) }
                if (connected.getOrNull() != true && allowDiscoveryRetry) {
                    discoverLeServicesThenRetry(device)
                }
                onDone()
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }
        runCatching {
            localAdapter.getProfileProxy(appContext, listener, BluetoothProfile.LE_AUDIO)
        }.onFailure {
            log("could not bind LE_AUDIO proxy for connect: $it")
            onDone()
        }
    }

    /**
     * Runs LE-transport service discovery so ASCS reaches the device's UUID cache, then asks
     * LeAudioService to connect once more.
     *
     * A GATT connect over LE is the only mechanism used here. It discovers services and, unlike
     * `fetchUuidsWithSdp(TRANSPORT_LE)`, also brings the LE link up on an address that has only
     * ever had a BR/EDR one. Running both — as this did until 2026-09-02 — left two GATT clients
     * on one address, and the stack cannot survive that: the MTU exchange the stack
     * auto-requests for the second client ends in `bta_gattc_op_cmpl` "Push callbacks to clients
     * which are not notified before", which walks the first client's control block while it is
     * still mid-discovery and jumps through a garbage pointer (SIGBUS in
     * `bta_gattc_cfg_mtu_cmpl`, taking com.android.bluetooth down).
     */
    @SuppressLint("MissingPermission")
    private fun discoverLeServicesThenRetry(device: BluetoothDevice) {
        val address = device.address
        val client = SonyLeAudioGattClient(
            appContext,
            object : SonyLeAudioGattClient.Listener {
                override fun onReady() = log("LE service discovery connected for $address")
                override fun onDisconnected(status: Int) =
                    log("LE service discovery disconnected for $address status=$status")
                override fun onFailure(reason: String) =
                    log("LE service discovery failed for $address: $reason")
                override fun onCharacteristicChanged(uuid: UUID, value: ByteArray) = Unit
                override fun onLog(message: String) = log("LE discovery: $message")
            },
        )
        leDiscoveryClients += client
        if (!client.connect(address)) {
            log("LE service discovery could not be started for $address")
        }
        handler.postDelayed({
            runCatching { client.close() }
            leDiscoveryClients -= client
            val uuids = runCatching {
                device.uuids.orEmpty().joinToString { it.uuid.toString() }
            }.getOrDefault("<unavailable>")
            log("after LE discovery $address uuids=[$uuids]")
            connectLeAudio(address, allowDiscoveryRetry = false)
        }, LE_DISCOVERY_MS)
    }

    // ---- CTKD re-pair ----

    /**
     * Whether the stack holds LE keys for [address].
     *
     * Delegates to [UnifiedDeviceIdentityService.hasLeKeys] for centralized bt_config.conf parsing.
     */
    private fun hasLeKeys(address: String): Boolean {
        val result = UnifiedDeviceIdentityService.hasLeKeys(address)
        log("LE keys present for $address = $result")
        return result
    }

    /**
     * Removes the BR/EDR bond and pairs again so the headset derives LE keys across transports.
     *
     * This is the only way to give an already-bonded address an LE identity: `createBond` is a
     * no-op while the bond exists, and there is no API to start SMP on the LE transport for a
     * bonded device. Destructive on purpose — this address also carries Tandem and A2DP, so it
     * is restored by the re-pair that follows.
     */
    @SuppressLint("MissingPermission")
    private fun beginCtkdRepair(device: BluetoothDevice) {
        val address = device.address
        ctkdRepairAddress = address
        setStage(Stage.PAIRING, ModuleText.get(appContext, R.string.pairer_rebonding_for_lekey, address), null)
        runCatching {
            BluetoothDevice::class.java.getMethod("disconnect").invoke(device)
        }.onFailure { log("disconnect($address) unavailable: $it") }
        val returned = removeBondReflective(device)
        log(
            "removeBond($address) returned=$returned " +
                "state=${bondStateName(runCatching { device.bondState }.getOrDefault(-1))}"
        )
        handler.removeCallbacks(ctkdTimeout)
        handler.postDelayed(ctkdTimeout, CTKD_TIMEOUT_MS)
    }

    /** Called once the classic bond is confirmed gone, so the re-pair starts from BOND_NONE. */
    @SuppressLint("MissingPermission")
    private fun onCtkdBondCleared(device: BluetoothDevice) {
        val address = device.address
        log("classic bond cleared for $address; pairing again to derive LE keys")
        val requested = createBond(device)
        log("createBond($address) = $requested")
    }

    private fun onCtkdRepaired(address: String) {
        handler.removeCallbacks(ctkdTimeout)
        ctkdRepairAddress = null
        val leKeys = hasLeKeys(address)
        log("re-pair finished for $address; LE keys derived = $leKeys")
        if (leKeys) {
            allowLeAudioProfile(address)
            connectLeAudio(address)
            onMemberBonded(address)
        } else {
            onMemberFailed(address, ModuleText.get(appContext, R.string.pairer_lekey_missing_after_rebond))
        }
    }

    private fun onCtkdTimeout() {
        val address = ctkdRepairAddress ?: return
        ctkdRepairAddress = null
        val state = runCatching { adapter?.getRemoteDevice(address)?.bondState }.getOrNull()
        log("CTKD re-pair timed out for $address state=${bondStateName(state ?: -1)}")
        onMemberFailed(address, ModuleText.get(appContext, R.string.pairer_rebond_timeout))
    }

    /**
     * PhonePolicy should allow LE Audio on its own for an LE-only device, because that case
     * bypasses the (on HyperOS empty) allow list. This is a best-effort backstop for when
     * it does not.
     */
    @SuppressLint("MissingPermission")
    private fun allowLeAudioProfile(address: String) {
        val localAdapter = adapter ?: return
        val device = runCatching { localAdapter.getRemoteDevice(address) }.getOrNull() ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val policy = runCatching {
                    proxy.javaClass
                        .getMethod("getConnectionPolicy", BluetoothDevice::class.java)
                        .invoke(proxy, device) as? Int
                }.getOrNull()
                if (policy != null && policy <= CONNECTION_POLICY_FORBIDDEN) {
                    val applied = runCatching {
                        proxy.javaClass.getMethod(
                            "setConnectionPolicy",
                            BluetoothDevice::class.java,
                            Int::class.javaPrimitiveType,
                        ).invoke(proxy, device, CONNECTION_POLICY_ALLOWED) as? Boolean
                    }.getOrNull()
                    log("LE_AUDIO policy was $policy; setConnectionPolicy(ALLOWED) = $applied")
                } else {
                    log("LE_AUDIO policy for $address is $policy; leaving it alone")
                }
                runCatching { localAdapter.closeProfileProxy(BluetoothProfile.LE_AUDIO, proxy) }
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }
        runCatching {
            localAdapter.getProfileProxy(appContext, listener, BluetoothProfile.LE_AUDIO)
        }.onFailure { log("could not bind LE_AUDIO proxy: $it") }
    }

    // ---- plumbing ----

    private fun fail(message: String) {
        cleanUp()
        setStage(Stage.FAILED, message, null)
    }

    private fun setStage(next: Stage, message: String, bondedAddress: String?) {
        stage = next
        log("stage=$next ${message.ifEmpty { "-" }}")
        listener.onStageChanged(next, message, bondedAddress)
    }

    @SuppressLint("MissingPermission")
    private fun cleanUp() {
        handler.removeCallbacks(scanWindowElapsed)
        handler.removeCallbacks(bondTimeout)
        handler.removeCallbacks(gattProvokeRunnable)
        handler.removeCallbacks(ctkdTimeout)
        ctkdRepairAddress = null
        collected.clear()
        firstViableAtMs = 0L
        knownControlAddress = null
        activeCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        activeCallback = null
        closeGattProvoke()
        unregisterReceiver()
        candidates.clear()
    }

    /**
     * A bond left in BONDING keeps the LE link up, and the headset only serves one Tandem
     * session at a time — so an abandoned attempt is why SPP then fails to reconnect and the
     * module reports the headset as disconnected while it is playing audio.
     */
    @SuppressLint("MissingPermission")
    private fun abandonPendingBond() {
        val device = bondTarget ?: return
        bondTarget = null
        if (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) !=
            BluetoothDevice.BOND_BONDING
        ) {
            return
        }
        val cancelled = runCatching {
            BluetoothDevice::class.java.getMethod("cancelBondProcess").invoke(device) as? Boolean
        }.onFailure { log("cancelBondProcess unavailable: $it") }.getOrNull() ?: false
        log("cancelBondProcess(${device.address}) = $cancelled")
    }

    private fun closeGattProvoke() {
        gattProvoke?.let { client ->
            runCatching { client.close() }
        }
        gattProvoke = null
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        }
        receiverRegistered = runCatching {
            appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        }.isSuccess
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(bondReceiver) }
        receiverRegistered = false
    }

    private fun log(message: String) = listener.onLog("LEA-PAIR[$stage] $message")

    private fun describe(candidate: LeAudioAnnouncementParser.Candidate): String {
        val a = candidate.announcement
        return "${candidate.address}(name=${a.name.orEmpty()} flags=${a.flags} " +
            "type=${a.announcementType} rsi=${a.rsi.orEmpty()} swiftPair=${a.hasSwiftPair})"
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? = runCatching {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    }.getOrNull()

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN($state)"
    }

    private companion object {
        val ASCS_UUID: UUID = UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB")
        const val SCAN_WINDOW_MS = 8_000L
        /** Bounds the main thread's wait on the worker that clears a bond. */
        const val REMOVE_BOND_TIMEOUT_MS = 2_000L
        /** Long enough for the user to actually reset the headset after being told to. */
        const val SCAN_DEADLINE_MS = 120_000L
        /** Keeps collecting set members this long after the first one is viable: the second
         * earbud often starts advertising well after the first, and bonding early is what
         * produced single-eared sets. */
        const val MEMBER_COLLECT_MS = 12_000L
        const val GATT_PROVOKE_DELAY_MS = 6_000L
        const val BOND_TIMEOUT_MS = 30_000L
        const val LE_DISCOVERY_MS = 6_000L
        /** Removing the bond, pairing again and deriving keys is slower than a plain bond. */
        const val CTKD_TIMEOUT_MS = 45_000L
        const val BT_CONFIG_PATH = "/data/misc/bluedroid/bt_config.conf"
        const val PAIRING_VARIANT_CONSENT = 3
        const val PAIRING_VARIANT_PASSKEY_CONFIRMATION = 4
        const val CONNECTION_POLICY_FORBIDDEN = 0
        const val CONNECTION_POLICY_ALLOWED = 100
    }
}

/**
 * Whether the `bt_config.conf` section for [address] holds any LE key.
 *
 * Section-scoped on purpose: the file is one flat list of `[mac]` sections, and a plain search
 * for `LE_KEY_` would find another device's keys and wrongly conclude this one is ready —
 * skipping the re-pair that is the whole point.
 */
internal fun btConfigSectionHasLeKeys(lines: Sequence<String>, address: String): Boolean {
    val section = "[${address.trim()}]"
    var inSection = false
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            inSection = trimmed.equals(section, ignoreCase = true)
            continue
        }
        if (inSection && trimmed.startsWith("LE_KEY_")) return true
    }
    return false
}
