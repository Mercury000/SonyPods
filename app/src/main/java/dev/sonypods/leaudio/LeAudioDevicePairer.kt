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
    private fun removeBondReflective(device: BluetoothDevice): Boolean {
        val loader = appContext.classLoader
        val viaAdapterService = runCatching {
            val serviceClass = loader.loadClass("com.android.bluetooth.btservice.AdapterService")
            val service = listOf("sAdapterService", "getAdapterService", "deprecatedGetAdapterService")
                .mapNotNull { name ->
                    runCatching {
                        serviceClass.getDeclaredField(name).apply { isAccessible = true }.get(null)
                    }.getOrElse {
                        runCatching {
                            serviceClass.getDeclaredMethod(name).apply { isAccessible = true }
                                .invoke(null)
                        }.getOrNull()
                    }
                }
                .firstOrNull { it != null }
            ?: error("AdapterService instance is not available")
            serviceClass
                .getMethod("removeBond", BluetoothDevice::class.java)
                .invoke(service, device) as? Boolean
        }
        viaAdapterService.exceptionOrNull()?.let { log("AdapterService.removeBond failed: ${it.unwrap()}") }
        if (viaAdapterService.getOrNull() == true) {
            log("AdapterService.removeBond(${device.address}) = true")
            return true
        }

        return runCatching {
            BluetoothDevice::class.java.getMethod("removeBond").invoke(device) as? Boolean
        }.onFailure { log("BluetoothDevice.removeBond failed: ${it.unwrap()}") }
            .getOrNull() ?: false
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
        if (viable.isEmpty()) {
            passIndex++
            runScanPass()
            return
        }
        // Every member of the coordinated set has to be bonded, not just the first one. A TWS
        // headset is two LE Audio devices in one CSIS group, and the stack builds a CIG with
        // one CIS per member: bonding a single earbud leaves the other one silent.
        pending = viable.toMutableList()
        addKnownControlMember()
        log("${pending.size} coordinated-set member(s) to bond: ${pending.joinToString { it.address }}")
        bondNextMember()
    }

    /**
     * Adds the earbud that reuses the headset's classic address, whether or not this scan saw it.
     *
     * That earbud advertises only intermittently — scans routinely come back with just the
     * other one — but its address is already known from the bonded set, so waiting to see it
     * announce is what kept its LE keys from ever being derived. It is appended last because
     * it needs a re-pair rather than a fresh bond.
     */
    @SuppressLint("MissingPermission")
    private fun addKnownControlMember() {
        val target = pending.firstOrNull() ?: return
        SonyDeviceService.linkLeAudioIdentities(adapter?.bondedDevices.orEmpty())
        val control = SonyDeviceService.resolveControlAddress(target.address)
        if (control == null || control.equals(target.address, ignoreCase = true)) return
        if (pending.any { it.address.equals(control, ignoreCase = true) }) return
        if (hasLeKeys(control)) {
            log("control identity $control already has LE keys; not re-pairing it")
            return
        }
        knownControlAddress = control
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
            } else {
                log("set member $address is bonded but has no LE keys; re-pairing for CTKD")
                beginCtkdRepair(device)
            }
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
            BluetoothDevice.BOND_NONE ->
                if (previous == BluetoothDevice.BOND_BONDING) {
                    onMemberFailed(device.address, ModuleText.get(appContext, R.string.pairer_bond_rejected))
                }
        }
    }

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
        // Record which identities belong to this headset while they are all in hand: from
        // here on the module must treat them as one device, not extra ones it cannot control.
        // The classic member is known by construction — it is the address the caller told us
        // to exclude — so link explicitly instead of re-classifying: the LE service discovery
        // this flow just ran has put ASCS into the classic bond's uuid cache, and a cached
        // ASCS is exactly what makes the classifier misread the control identity as LE.
        runCatching {
            val control = knownControlAddress
            if (control != null) {
                adapter?.bondedDevices.orEmpty().forEach { member ->
                    SonyDeviceService.linkLeAudioIdentity(member.address, control)
                }
            } else {
                SonyDeviceService.linkLeAudioIdentities(adapter?.bondedDevices.orEmpty())
            }
            log("LE Audio aliases: ${SonyDeviceService.leAudioAliasSnapshot()}")
        }
        val primary = bonded.firstOrNull()
        cleanUp()
        setStage(Stage.SUCCESS, message, primary)
    }

    /**
     * Asks LeAudioService to connect a set member.
     *
     * Needed for the member that is already bonded over BR/EDR: nothing else starts its LE
     * Audio connection, and without it the group keeps one empty CIS and one silent ear.
     *
     * The service refuses outright unless the device's cached UUIDs contain ASCS, and a
     * BR/EDR-bonded address only ever had SDP run against it — the earbud announces ASCS over
     * the air, but that never reaches the cache. So a refusal is answered with LE service
     * discovery and one retry rather than treated as final.
     */
    @SuppressLint("MissingPermission")
    private fun connectLeAudio(address: String, allowDiscoveryRetry: Boolean = true) {
        val localAdapter = adapter ?: return
        val device = runCatching { localAdapter.getRemoteDevice(address) }.getOrNull() ?: return
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
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }
        runCatching {
            localAdapter.getProfileProxy(appContext, listener, BluetoothProfile.LE_AUDIO)
        }.onFailure { log("could not bind LE_AUDIO proxy for connect: $it") }
    }

    /**
     * Runs LE-transport service discovery so ASCS reaches the device's UUID cache, then asks
     * LeAudioService to connect once more.
     */
    @SuppressLint("MissingPermission")
    private fun discoverLeServicesThenRetry(device: BluetoothDevice) {
        val address = device.address
        val fetched = runCatching {
            BluetoothDevice::class.java
                .getMethod("fetchUuidsWithSdp", Int::class.javaPrimitiveType)
                .invoke(device, BluetoothDevice.TRANSPORT_LE) as? Boolean
        }
        fetched.exceptionOrNull()?.let { log("fetchUuidsWithSdp(LE) unavailable for $address: $it") }
        log("fetchUuidsWithSdp(LE) on $address = ${fetched.getOrNull()}")

        // A GATT connect over LE discovers services even where the fetch API is unavailable,
        // and it is what brings the LE link up on an address that only ever had a BR/EDR one.
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
     * `BluetoothDevice` exposes no such query, and bond state alone cannot answer it: a
     * BR/EDR-only bond reports BOND_BONDED while the native LE Audio client still refuses the
     * device as "not bonded". The stack's own key store is the authority, and the engine runs
     * as the bluetooth uid that owns it.
     */
    private fun hasLeKeys(address: String): Boolean {
        val keys = runCatching {
            java.io.File(BT_CONFIG_PATH).useLines { lines ->
                btConfigSectionHasLeKeys(lines, address)
            }
        }
        keys.exceptionOrNull()?.let { log("could not read LE key state for $address: $it") }
        val result = keys.getOrNull()
        log("LE keys present for $address = $result")
        // Unknown reads as "present" so an unreadable key store never triggers a destructive
        // re-pair; the worst case is the same rejection the user already sees.
        return result != false
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
        knownControlAddress = null
        activeCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        activeCallback = null
        closeGattProvoke()
        abandonPendingBond()
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
        /** Long enough for the user to actually reset the headset after being told to. */
        const val SCAN_DEADLINE_MS = 120_000L
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
