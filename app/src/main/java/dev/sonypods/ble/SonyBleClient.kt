package dev.sonypods.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.sonypods.hook.Log
import androidx.core.content.ContextCompat
import dev.sonypods.device.IdentityType
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.device.UnifiedDeviceIdentityService
import dev.sonypods.headphones.TandemChannel
import dev.sonypods.protocol.LeaConnectionType
import dev.sonypods.protocol.SonyGatt
import dev.sonypods.protocol.hexString
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

data class DiscoveredSonyDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val source: String = "unknown",
    val bluetoothType: Int = BluetoothDevice.DEVICE_TYPE_UNKNOWN,
    val advertisedServices: List<String> = emptyList(),
    val isLikelyControlEndpoint: Boolean = false,
    val sonyAd: SonyAudioAdvertisement? = null,
)

data class SonyAudioAdvertisement(
    val version: Int,
    val raw: String,
    val androidLine: String? = null,
    val androidGattCapable: Boolean = false,
    val audioStream: String? = null,
    val leGattControlFlag: Boolean = false,
    val modelId: Int? = null,
    val classicHash: Long? = null,
) {
    val summary: String
        get() = buildList {
            add("Sony Audio AD v$version")
            androidLine?.let { add("Android=$it") }
            audioStream?.let { add("Stream=$it") }
            if (androidGattCapable) add("GATT line")
            if (leGattControlFlag) add("LE control flag")
            classicHash?.let { add("Hash=$it") }
        }.joinToString(", ")
}

data class SonyBleConnectionInfo(
    val mtu: Int = 23,
    val writableValueLength: Int? = null,
    val optimalMtu: Int? = null,
    val transport: String = "GATT_HPC",
    /** Channels the transport actually exposes (GATT endpoints + SPP). Used at
     * connection time to bind the neutral profile to the protocol generation
     * the hardware speaks, replacing the deleted static per-model templates. */
    val channels: Set<TandemChannel> = emptySet(),
    /**
     * For SPP connections: the exact SPP service UUID that the Android socket's
     * SDP handshake actually bound to. SC resolves the protocol generation from
     * this UUID (96cc203e… → TABLE_SET_1/V1, 956c7b26… → TABLE_SET_2/V2), NOT
     * from a static per-model name table. When present the repository must bind
     * to this generation instead of the channel-only heuristic, otherwise a
     * V1 SPP device (e.g. WH-1000XM4) gets V2 frames and hard-disconnects. */
    val sppUuid: UUID? = null,
)

data class GattTandemEndpoint(
    val channel: TandemChannel,
    val toAcc: BluetoothGattCharacteristic,
    val fromAcc: BluetoothGattCharacteristic,
)

internal data class SppSocketWithUuid(
    val socket: BluetoothSocket,
    val uuid: UUID,
)

/**
 * The resolved answer to "which identity carries Tandem, and over which link".
 *
 * Both halves are decided together, once, from one source each. They used to be decided in three
 * places that each re-asked overlapping questions about the same device: the identity retarget in
 * [SonyBleClient.connect], an SPP-vs-GATT predicate, and an ACL-transport picker. Nothing made
 * them agree, and when they disagreed nothing said so. A bonded WH-1000XM6 whose classic MAC
 * begins 0x58 was read as a random LE address by the second, which handed it to the third, which
 * answered TRANSPORT_AUTO, which let the stack open GATT on the classic bearer — where Sony
 * publishes no HPC service. The headset stayed uncontrollable for as long as it was paired and it
 * [reason] is logged on every connect. A route that is wrong should be readable at the moment it
 * is chosen, not inferred afterwards from which endpoint failed to turn up.
 */

/**
 * Connection transport mode mirroring Sony Sound Connect (SC):
 * - SPP: Classic Bluetooth RFCOMM socket.
 * - GATT: Bluetooth Low Energy GATT client.
 */
internal enum class TandemConnectionMode {
    GATT,
    SPP,
}

/**
 * The resolved answer to "which identity carries Tandem, and over which link".
 *
 * Modeled directly after Sound Connect (SC C14356p0.m61992j0):
 * - If LE Audio is actively connected: Target the LE Audio identity and use GATT.
 * - If LE Audio is not active (or classic pairing): Target the control identity and use SPP
 *   if an SPP service record is present, falling back to GATT if RFCOMM fails.
 * - If directed by headset migration (LEA_NTFY_PARAM 0x0E): Use the exact mode and address
 *   instructed by the accessory.
 */
private data class TandemRoute(
    val target: DiscoveredSonyDevice,
    val remote: BluetoothDevice,
    val mode: TandemConnectionMode,
    /** The advertised Sony SPP service record to bind, or null when this route is GATT. */
    val sppRecord: UUID?,
    val reason: String,
)

data class UnsupportedEndpointDiagnostics(
    val reason: String,
    val serviceLabels: List<String>,
    val leAudioSwitchCompatibility: Int? = null,
    val friendlyName: String? = null,
    val publicAddress: String? = null,
    val rawReads: Map<String, String> = emptyMap(),
)

internal fun tandemEndpointSupportState(services: Collection<UUID>): String? =
    if (services.any { it in supportedGattControlServices }) null else unsupportedTandemEndpointReason(services)

internal fun unsupportedTandemEndpointReason(services: Collection<UUID>): String {
    val labels = services.map { SonyGatt.serviceLabel(it) }
    return when {
        SonyGatt.TANDEM_V1_MC_SERVICE in services ->
            "Tandem V1 MC service was found, but no usable MC control endpoint could be registered. Services: ${labels.joinToString()}"
        SonyGatt.LE_AUDIO_CAPABILITY_FOR_HPC in services ->
            "This LE endpoint exposes LE Audio capability, not Tandem V2 HPC control. Try disabling LE Audio / using classic-only mode, then rescan."
        SonyGatt.BLUETOOTH_PAIRING_COMPLETE_NAME_SERVICE in services ->
            "This LE endpoint is a pairing/name endpoint, not Tandem V2 HPC control. Services: ${labels.joinToString()}"
        else -> "Tandem control service was not found. Services: ${labels.joinToString()}"
    }
}

private val supportedGattControlServices = setOf(
    SonyGatt.TANDEM_V2_HPC_SERVICE,
    SonyGatt.TANDEM_V1_MC_SERVICE,
)

interface SonyBleClientListener {
    fun onBluetoothUnavailable(reason: String)
    fun onUnsupportedEndpoint(diagnostics: UnsupportedEndpointDiagnostics)
    fun onDeviceFound(device: DiscoveredSonyDevice)
    fun onScanStateChanged(scanning: Boolean)
    fun onConnectionStateChanged(connected: Boolean, device: DiscoveredSonyDevice?)
    fun onReady(info: SonyBleConnectionInfo)
    /**
     * @param sourceAddress the device whose transport session produced this frame,
     *   captured when the session was created rather than read from the current
     *   connection. Sound Connect gets this for free: its dispatcher
     *   (`le0.C22925e`) is one instance per device and only feeds that device's
     *   own handlers, so a late frame from a headset that has since been replaced
     *   can never reach the new one's state. Null only for frames that arrived
     *   before any session existed.
     */
    fun onMessage(channel: TandemChannel, raw: ByteArray, sourceAddress: String?)
    fun onLog(message: String)
}

class SonyBleClient(
    private val context: Context,
    private val listener: SonyBleClientListener,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner?
        get() = adapter?.bluetoothLeScanner

    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var connectedDevice: DiscoveredSonyDevice? = null
    private var toAcc: BluetoothGattCharacteristic? = null
    private var fromAcc: BluetoothGattCharacteristic? = null
    private val gattEndpoints: MutableMap<TandemChannel, GattTandemEndpoint> = mutableMapOf()
    private var writableValueLength: Int? = null
    private var optimalMtu: Int? = null
    private var negotiatedMtu: Int = 23
    private var handshakeStep: HandshakeStep = HandshakeStep.Idle
    private var determineMtuNotificationEnabled = false
    /**
     * GATT clients whose `close()` is waiting for an in-flight MTU exchange to land.
     *
     * `close()` is what unregisters the client and lets the stack recycle its registration
     * control block. Doing that while our own MTU request is still outstanding leaves the
     * pending operation pointing at a freed rcb, and when the response arrives
     * `bta_gattc_op_cmpl` walks "clients which are not notified before" and jumps through the
     * dangling callback — four SIGBUS/BUS_ADRALN aborts of com.android.bluetooth in
     * `bta_gattc_cfg_mtu_cmpl` came from exactly that window. `disconnect()` still runs
     * immediately; only the unregister waits, so the link drops on time and the stale client
     * merely lingers for one round trip.
     */
    private val gattAwaitingMtuSettle = mutableSetOf<BluetoothGatt>()
    private var unsupportedProbe: UnsupportedEndpointProbe? = null
    private var sppTransport: SonySppTransport? = null
    /** Invalidates an in-progress RFCOMM connect when this client is closed. */
    private val sppConnectGeneration = AtomicLong(0L)
    @Volatile private var sppConnectThread: Thread? = null
    @Volatile private var pendingSppSocket: BluetoothSocket? = null
    private val writeQueue = ConcurrentLinkedQueue<PendingTandemWrite>()
    /** One framed Tandem session per GATT endpoint, created once the handshake completes. */
    private val gattSessions = ConcurrentHashMap<TandemChannel, SonyGattTandemSession>()
    private val pendingNotifyEndpoints = ArrayDeque<GattTandemEndpoint>()
    private var handshakeTimeout: Runnable? = null
    private var writeTimeout: Runnable? = null
    private val writeStateLock = Any()
    private var writeGeneration = 0L
    private var activeWriteGeneration: Long? = null
    @Volatile private var writing = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val serviceUuids = result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString() }.orEmpty()
            val serviceList = result.scanRecord?.serviceUuids?.map { SonyGatt.serviceLabel(it.uuid) }.orEmpty()
            val name = safeDeviceName(device) ?: result.scanRecord?.deviceName
            val manufacturerData = result.scanRecord?.manufacturerSummary().orEmpty()
            val serviceData = result.scanRecord?.serviceDataSummary().orEmpty()
            val sonyAd = result.scanRecord?.sonyAudioAdvertisement()
            val found = DiscoveredSonyDevice(
                name = name ?: "Unknown BLE device",
                address = device.address,
                rssi = result.rssi,
                source = "ble-scan",
                bluetoothType = device.type,
                advertisedServices = serviceList,
                isLikelyControlEndpoint = sonyAd?.leGattControlFlag == true ||
                    serviceList.any {
                        it == "TANDEM_V2_HPC_SERVICE" ||
                            it == "TANDEM_V2_MC_SERVICE" ||
                            it == "TANDEM_V1_MC_SERVICE"
                    },
                sonyAd = sonyAd,
            )
            log(
                "BLE result callbackType=$callbackType name=${found.name} address=${found.address} " +
                    "rssi=${found.rssi} services=[$serviceUuids] manufacturer=[$manufacturerData] " +
                    "serviceData=[$serviceData] sonyAd=${sonyAd?.summary.orEmpty()} raw=${sonyAd?.raw.orEmpty()}"
            )
            if (SonyDeviceService.isSony(
                    address = device.address,
                    name = name,
                    serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty(),
                    hasSonyAdvertisement = sonyAd != null,
                )
            ) {
                listener.onDeviceFound(found)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onScanStateChanged(false)
            log("BLE scan failed errorCode=$errorCode")
            listener.onBluetoothUnavailable("BLE scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (this@SonyBleClient.gatt !== gatt) {
                    log("Ignoring stale GATT connected callback")
                    return
                }
                log("GATT connected; discovering services")
                listener.onConnectionStateChanged(true, connectedDevice)
                if (!gatt.discoverServices()) {
                    failGattSession(gatt, "Failed to enqueue GATT service discovery")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (this@SonyBleClient.gatt !== gatt) {
                    log("Ignoring stale GATT disconnected callback")
                    return
                }
                log("GATT disconnected: status=$status")
                cancelAllTimeouts()
                synchronized(writeStateLock) {
                    activeWriteGeneration = null
                }
                writeQueue.clear()
                closeGattSessions()
                pendingNotifyEndpoints.clear()
                writing = false
                toAcc = null
                fromAcc = null
                gattEndpoints.clear()
                val mtuPending = handshakeStep == HandshakeStep.RequestMtu
                handshakeStep = HandshakeStep.Idle
                determineMtuNotificationEnabled = false
                writableValueLength = null
                optimalMtu = null
                negotiatedMtu = 23
                unsupportedProbe = null
                closeGattAfterMtuSettles(gatt, mtuPending)
                this@SonyBleClient.gatt = null
                listener.onConnectionStateChanged(false, connectedDevice)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@SonyBleClient.gatt !== gatt) {
                log("Ignoring stale GATT services callback")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed status=$status")
                failGattSession(gatt, "Service discovery failed: $status")
                return
            }
            val services = gatt.services.map { it.uuid }
            val service = gatt.getService(SonyGatt.TANDEM_V2_HPC_SERVICE)
            if (service != null) {
                log("Tandem V2 HPC service discovered")
                val hpcSpec = TandemGattRouting.endpointSpecFor(TandemChannel.GATT_V2_HPC)
                toAcc = service.getCharacteristic(hpcSpec.toAccUuid)
                fromAcc = service.getCharacteristic(hpcSpec.fromAccUuid)
                if (toAcc != null && fromAcc != null) {
                    gattEndpoints[TandemChannel.GATT_V2_HPC] = GattTandemEndpoint(
                        channel = TandemChannel.GATT_V2_HPC,
                        toAcc = toAcc!!,
                        fromAcc = fromAcc!!,
                    )
                } else {
                    val characteristics = service.characteristics.joinToString { it.uuid.toString() }
                    log("Tandem V2 HPC characteristics incomplete. Available=[$characteristics]")
                }
            }
            discoverMcEndpoints(gatt)
            if (gattEndpoints.isEmpty()) {
                val labels = services.joinToString { SonyGatt.serviceLabel(it) }
                val reason = unsupportedTandemEndpointReason(services)
                log("No usable Tandem GATT endpoint on the LE bearer. Available services=[$labels]")
                beginUnsupportedEndpointProbe(gatt, services, reason)
                return
            }
            log("Tandem GATT endpoints discovered: ${gattEndpoints.keys.joinToString()}")
            beginTandemHandshake(gatt)
        }

        private fun discoverMcEndpoints(gatt: BluetoothGatt) {
            for (channel in listOf(TandemChannel.GATT_V2_MC, TandemChannel.GATT_V1_MC)) {
                val spec = TandemGattRouting.endpointSpecFor(channel)
                val service = gatt.getService(spec.serviceUuid) ?: continue
                val mcToAcc = service.getCharacteristic(spec.toAccUuid)
                val mcFromAcc = service.getCharacteristic(spec.fromAccUuid)
                if (mcToAcc != null && mcFromAcc != null) {
                    gattEndpoints[channel] = GattTandemEndpoint(
                        channel = channel,
                        toAcc = mcToAcc,
                        fromAcc = mcFromAcc,
                    )
                    log("MC endpoint registered: $channel")
                } else {
                    log("MC service $channel found but characteristics incomplete")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Before the staleness check: a client held open only to let its MTU request land is
            // by definition no longer the live one, and this reply is what releases it.
            finishDeferredGattClose(gatt, "MTU settled")
            if (this@SonyBleClient.gatt !== gatt) {
                log("Ignoring stale GATT MTU callback")
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            } else {
                log("MTU request failed: requestedResult=$mtu status=$status; retaining mtu=$negotiatedMtu")
            }
            log("MTU changed: mtu=$mtu status=$status")
            if (handshakeStep == HandshakeStep.RequestMtu) {
                enableDetermineMtuNotifications(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (this@SonyBleClient.gatt !== gatt) return
            handleCharacteristicRead(gatt, characteristic.uuid, value, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (this@SonyBleClient.gatt !== gatt) return
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (this@SonyBleClient.gatt !== gatt) return
            val active = synchronized(writeStateLock) {
                if (activeWriteGeneration == null) {
                    false
                } else {
                    activeWriteGeneration = null
                    writing = false
                    cancelWriteTimeoutLocked()
                    true
                }
            }
            if (!active) {
                log("Ignoring stale BLE write callback for ${characteristic.uuid}")
                return
            }
            log("Write ${characteristic.uuid}: status=$status")
            val success = status == BluetoothGatt.GATT_SUCCESS
            // Only the session that issued this write may advance: with two endpoints bound, a
            // completion on one would otherwise release the other's in-flight slot and let two
            // frames interleave on the same sequence number.
            sessionForToAcc(characteristic)?.onWriteComplete(success)
            if (!success) {
                failGattSession(gatt, "BLE write failed for ${characteristic.uuid}: $status")
                return
            }
            drainWriteQueue()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (this@SonyBleClient.gatt !== gatt) return
            log("Notify descriptor ${descriptor.uuid}: status=$status")
            when {
                descriptor.characteristic?.uuid == SonyGatt.DETERMINE_MTU &&
                    handshakeStep == HandshakeStep.EnableDetermineMtu -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failGattSession(gatt, "Failed to enable DETERMINE_MTU notification: $status")
                        return
                    }
                    determineMtuNotificationEnabled = true
                    handshakeStep = HandshakeStep.WaitDetermineMtu
                    scheduleHandshakeTimeout(
                        gatt,
                        HandshakeStep.WaitDetermineMtu,
                        DETERMINE_MTU_READY_TIMEOUT_MS,
                        "Timed out waiting for DETERMINE_MTU ready notification",
                    )
                }
                descriptor.characteristic?.uuid == SonyGatt.DETERMINE_MTU &&
                    handshakeStep == HandshakeStep.DisableDetermineMtu -> {
                    cancelHandshakeTimeout()
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failGattSession(gatt, "Failed to disable DETERMINE_MTU notification: $status")
                        return
                    }
                    determineMtuNotificationEnabled = false
                    readWritableValueLength(gatt)
                }
                handshakeStep == HandshakeStep.EnableTandemNotifications &&
                    TandemGattRouting.fromAccChannel(
                        endpoints = gattEndpoints,
                        serviceUuid = descriptor.characteristic?.service?.uuid,
                        characteristicUuid = descriptor.characteristic?.uuid,
                    ) != null -> {
                    cancelHandshakeTimeout()
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        val channel = TandemGattRouting.fromAccChannel(
                            endpoints = gattEndpoints,
                            serviceUuid = descriptor.characteristic?.service?.uuid,
                            characteristicUuid = descriptor.characteristic?.uuid,
                        )
                        failGattSession(gatt, "Failed to enable Tandem notification for $channel: $status")
                        return
                    }
                    enableNextTandemNotification(gatt)
                }
            }
        }
    }

    fun startScan(strictSonyServiceFilter: Boolean) {
        if (!hasScanPermission()) {
            listener.onBluetoothUnavailable("Bluetooth scan permission is missing")
            return
        }
        val adapter = adapter
        val scanner = scanner
        if (scanner == null || adapter?.isEnabled != true) {
            listener.onBluetoothUnavailable("Bluetooth is disabled or unavailable")
            return
        }
        log("Start discovery strictServiceFilter=$strictSonyServiceFilter")
        enumerateKnownDevices(adapter)

        val filters = emptyList<ScanFilter>()
        if (strictSonyServiceFilter) {
            log(
                "Strict Sony service filter requested, but Sony official discovery uses an unfiltered " +
                    "scan plus manufacturer-data parsing; keeping filters empty."
            )
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        listener.onScanStateChanged(true)
        log("BLE scan starting filters=${filters.size}")
        scanner.startScan(filters, settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning || !hasScanPermission()) return
        scanner?.stopScan(scanCallback)
        scanning = false
        listener.onScanStateChanged(false)
    }

    fun connect(address: String) {
        connect(
            DiscoveredSonyDevice(
                name = "Sony audio device",
                address = address,
                rssi = 0,
                source = "manual-connect",
            )
        )
    }

    /**
     * A headset-directed Tandem target migration (LEA_NTFY_PARAM 0x0E): connect
     * the identity the headset itself named, over the transport it named.
     *
     * The transport half mirrors Sound Connect exactly — `C12260d.b.mo52710a`
     * maps the frame's ConnectionType straight onto `ConnectionMode.SPP` /
     * `ConnectionMode.GATT`, with no link heuristic in the path.
     *
     * The address half is ours, not SC's. SC hands the named address to
     * `changeTandemConnectionProfile` (`C14356p0.m62041c0`) which only logs it:
     * the method arms a post-disconnect consumer and calls `m62052t0()`
     * (`disconnectAllDevices`), and the consumer reconnects with the *device id
     * the disconnect flow hands back* (`m61964R0` → `m62060y0`). So SC re-dials
     * whatever accessory it was already on and merely changes the transport.
     * That works for SC because it only ever holds the one target; our engine
     * reaches a headset under either bonded identity, so the named address is
     * the only thing that says which one Tandem is moving to. Dial it verbatim
     * and skip the control-identity retarget [connect] would otherwise apply.
     */
    fun connectTandemTarget(address: String, connectionType: dev.sonypods.protocol.LeaConnectionType) {
        connect(
            DiscoveredSonyDevice(
                name = "Sony audio device",
                address = address,
                rssi = 0,
                source = "tandem-migration",
                isLikelyControlEndpoint = true,
            ),
            tandemMigration = connectionType,
        )
    }

    /**
     * Resolves the one route, or null when the request names an address this adapter cannot reach.
     *
     * Exactly mirrors Sony Sound Connect (C14356p0.m61992j0 & C12260d):
     * 1. If this is a headset-directed migration (LEA_NTFY_PARAM 0x0E):
     *    Adhere directly to the ConnectionType (SPP or GATT) and target address requested by the accessory.
     * 2. If LE Audio profile is actively connected:
     *    Target the LE Audio identity and connect via GATT.
     * 3. If LE Audio is not active (classic audio / non-LEA mode):
     *    Target the control identity. If the target exposes a Sony Tandem SPP service record,
     *    choose SPP; otherwise choose GATT.
     */
    @SuppressLint("MissingPermission")
    private fun resolveTandemRoute(
        device: DiscoveredSonyDevice,
        tandemMigration: LeaConnectionType?,
    ): TandemRoute? {
        val requested = adapter?.getRemoteDevice(device.address) ?: return null
        val leAudioActive = isLeAudioConnected(requested)

        val (target, identity, selectedMode) = when {
            tandemMigration != null -> {
                val mode = when (tandemMigration) {
                    LeaConnectionType.SPP -> TandemConnectionMode.SPP
                    LeaConnectionType.BLE_GATT -> TandemConnectionMode.GATT
                    else -> TandemConnectionMode.GATT
                }
                Triple(device, "headset-directed migration", mode)
            }
            leAudioActive -> {
                Triple(resolveLeAudioTarget(device), "LE Audio identity", TandemConnectionMode.GATT)
            }
            else -> {
                // Non-LE Audio: target control identity and prefer SPP if supported
                val controlTarget = resolveControlTarget(device)
                val controlRemote = if (controlTarget.address.equals(device.address, ignoreCase = true)) {
                    requested
                } else {
                    adapter?.getRemoteDevice(controlTarget.address)
                }
                val spp = controlRemote?.let(::advertisedSppRecord)
                val mode = if (spp != null) TandemConnectionMode.SPP else TandemConnectionMode.GATT
                Triple(controlTarget, "control identity", mode)
            }
        }

        val remote = if (target.address.equals(device.address, ignoreCase = true)) {
            requested
        } else {
            adapter?.getRemoteDevice(target.address) ?: return null
        }
        val sppRecord = advertisedSppRecord(remote)

        val reason = when {
            tandemMigration != null -> "$identity requested $selectedMode"
            leAudioActive -> "$identity active; selected GATT"
            sppRecord != null -> "$identity advertises Sony SPP record $sppRecord"
            else -> "$identity advertises no Sony SPP record; selected GATT"
        }

        return TandemRoute(
            target = target,
            remote = remote,
            mode = selectedMode,
            sppRecord = sppRecord,
            reason = reason,
        )
    }

    /**
     * The Sony Tandem SPP service record this device advertises, or null when it advertises none.
     *
     * Read from the device's own SDP, cached by the stack at bonding. Checks official TableSet 1
     * and TableSet 2 UUIDs as well as endian-reversed UUID variants (SC C5731a).
     */
    @SuppressLint("MissingPermission")
    private fun advertisedSppRecord(remote: BluetoothDevice): UUID? {
        val advertised = runCatching { remote.uuids }.getOrNull().orEmpty().map { it.uuid }.toSet()
        return OFFICIAL_SPP_UUIDS.firstOrNull { it in advertised }
    }

    fun connect(device: DiscoveredSonyDevice, tandemMigration: LeaConnectionType? = null) {
        if (!hasConnectPermission()) {
            listener.onBluetoothUnavailable("Bluetooth connect permission is missing")
            return
        }
        stopScan()
        val route = resolveTandemRoute(device, tandemMigration)
        if (route == null) {
            listener.onBluetoothUnavailable("Cannot resolve remote device ${device.address}")
            return
        }
        val target = route.target
        val remote = route.remote
        log(
            "Tandem route: ${device.address} -> ${target.address} via " +
                "${route.mode} — ${route.reason}"
        )

        if (route.mode == TandemConnectionMode.SPP) {
            val sppRecord = route.sppRecord ?: advertisedSppRecord(remote)
            if (sppRecord != null) {
                connectSpp(target, remote, sppRecord)
                return
            }
            log("Route indicated SPP but no advertised SPP record found; falling back to GATT")
        }

        connectGatt(target, remote)
    }

    /**
     * Connects to [target] via BLE GATT using TRANSPORT_LE.
     * Shared by normal GATT routing and SPP failure fallback (SC C14356p0.m61976X0).
     */
    @SuppressLint("MissingPermission")
    private fun connectGatt(target: DiscoveredSonyDevice, remote: BluetoothDevice) {
        connectedDevice = target.copy(
            name = if (target.name == "Unknown BLE device") {
                safeDeviceName(remote) ?: "Sony audio device"
            } else {
                target.name
            },
            address = remote.address,
            bluetoothType = if (remote.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                remote.type
            } else {
                target.bluetoothType
            },
        )
        disconnect()
        connectedDevice = connectedDevice?.copy(address = remote.address)
        log(
            "Connecting GATT to ${target.address} type=${remote.type} source=${target.source} " +
                "sonyAd=${target.sonyAd?.summary.orEmpty()}"
        )
        // TRANSPORT_LE, never AUTO. Both identities of a dual-bonded headset report
        // DEVICE_TYPE_DUAL, so AUTO can aim at the classic bearer — and Sony publishes no Tandem
        // service there, so a GATT session that lands on it discovers nothing and every command
        // afterwards answers "Tandem channel is not ready". Every route that reaches this point has
        // already established that LE is the link it wants; AUTO would hand that decision back to
        // the stack. Sound Connect hardcodes GattConnectionTransport.LE for the same reason.
        gatt = if (Build.VERSION.SDK_INT >= 37) {
            val settings = BluetoothGattConnectionSettings.Builder()
                .setAutoConnectEnabled(false)
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .build()
            remote.connectGatt(settings, context.mainExecutor, gattCallback)
        } else {
            @Suppress("DEPRECATION")
            remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    fun disconnect() {
        closeGatt(notify = true)
    }

    /** Generation teardown: cancel callbacks and close every transport without notifying consumers. */
    fun close() {
        timeoutHandler.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt(notify = false)
        // The client is retained by the repository singleton across a libxposed
        // generation reload.  Do not carry the old target into the next generation;
        // the next connect() call must publish a fresh target before any callbacks.
        connectedDevice = null
    }

    private fun closeGatt(notify: Boolean) {
        cancelAllTimeouts()
        closeSpp(notify = false)
        writeQueue.clear()
        closeGattSessions()
        pendingNotifyEndpoints.clear()
        synchronized(writeStateLock) {
            activeWriteGeneration = null
            writing = false
        }
        toAcc = null
        fromAcc = null
        gattEndpoints.clear()
        val mtuPending = handshakeStep == HandshakeStep.RequestMtu
        handshakeStep = HandshakeStep.Idle
        determineMtuNotificationEnabled = false
        writableValueLength = null
        optimalMtu = null
        negotiatedMtu = 23
        unsupportedProbe = null
        gatt?.disconnect()
        gatt?.let { closeGattAfterMtuSettles(it, mtuPending) }
        gatt = null
        if (notify) {
            listener.onConnectionStateChanged(false, connectedDevice)
        }
    }

    fun refreshUnsupportedEndpointProbe() {
        val activeGatt = gatt
        if (activeGatt == null) {
            listener.onBluetoothUnavailable("No GATT connection is available for endpoint diagnostics")
            return
        }
        val services = activeGatt.services.map { it.uuid }
        if (services.any { it in supportedGattControlServices } && gattEndpoints.isNotEmpty()) {
            beginTandemHandshake(activeGatt)
        } else {
            beginUnsupportedEndpointProbe(activeGatt, services, unsupportedTandemEndpointReason(services))
        }
    }

    fun send(bytes: ByteArray) {
        log("TX ${bytes.hexString()}")
        val transport = sppTransport
        if (transport != null) {
            transport.send(bytes)
            return
        }
        writeToChannel(defaultGattWriteChannel(), bytes)
    }

    fun sendToChannel(channel: TandemChannel, bytes: ByteArray) {
        log("TX $channel ${bytes.hexString()}")
        val transport = sppTransport
        if (transport != null) {
            transport.send(bytes)
            return
        }
        writeToChannel(channel, bytes)
    }

    fun availableChannels(): Set<TandemChannel> {
        val channels = mutableSetOf<TandemChannel>()
        if (sppTransport != null) channels.add(TandemChannel.SPP_MDR)
        channels.addAll(gattEndpoints.keys)
        return channels
    }

    /**
     * True while any command already accepted for this connection has not been fully
     * transmitted: queued for a GATT write, mid-write, or waiting for its Tandem ACK.
     *
     * Both transports serialize frames behind the ACK, so a twenty-command connection
     * burst leaves the phone over several seconds. The initial-value gate uses this to
     * tell "the headset has answered everything we asked" apart from "we are still
     * asking" — without it, the pause between two commands looks like the end of the
     * exchange and the UI opens on values that have not been requested yet.
     */
    fun hasOutstandingWrites(): Boolean {
        sppTransport?.let { return it.hasOutstandingWrites() }
        if (synchronized(writeStateLock) { writing || writeQueue.isNotEmpty() }) return true
        return gattSessions.values.any { it.hasOutstandingWrites() }
    }

    private fun writeToChannel(channel: TandemChannel, bytes: ByteArray) {
        val session = gattSessions[channel]
        if (session != null) {
            session.send(bytes)
            return
        }
        // Sound Connect funnels every command through the single live reliability pipe no
        // matter which table a command belongs to — sendToChannel already does the same
        // while SPP is up. A Table2/MC command routed at an endpoint this connection lacks
        // must therefore ride the one framed session instead of vanishing, or its domain
        // waits forever for replies to requests that were never sent.
        val preferred = defaultGattWriteChannel()
        val alternate = gattSessions[preferred] ?: gattSessions.values.firstOrNull()
        if (alternate != null) {
            log("Routing $channel frame over $preferred endpoint")
            alternate.send(bytes)
            return
        }
        if (channel !in gattEndpoints && sppTransport == null) {
            listener.onBluetoothUnavailable("Channel $channel is not available (available: ${availableChannels()})")
            return
        }
        // A GATT endpoint carries framed Tandem, exactly as RFCOMM does: Sound Connect hands both
        // transports to the same framer. The session owns framing, sequencing and ACK retries —
        // including splitting a frame that outgrows the writable value length, so the payload is
        // not size-checked against it here.
        if (!TandemGattProtocolRules.canWrite(bytes.size, writableValueLength)) {
            listener.onBluetoothUnavailable(
                "Tandem payload is ${bytes.size} bytes, exceeding writable limit $writableValueLength"
            )
            return
        }
        writeQueue.add(PendingTandemWrite(channel, bytes))
        drainWriteQueue()
    }

    /**
     * Unregister [target], but not before an MTU request we issued has been answered.
     * See [gattAwaitingMtuSettle]; the wait is a posted callback, never a blocking one — this
     * runs on com.android.bluetooth threads.
     */
    private fun closeGattAfterMtuSettles(target: BluetoothGatt, mtuPending: Boolean) {
        if (!mtuPending) {
            runCatching { target.close() }
            return
        }
        log("Deferring GATT close until the outstanding MTU exchange settles")
        gattAwaitingMtuSettle += target
        timeoutHandler.postDelayed({ finishDeferredGattClose(target, "timeout") }, MTU_SETTLE_TIMEOUT_MS)
    }

    /** Idempotent: whichever of the reply or the timeout arrives first performs the close. */
    private fun finishDeferredGattClose(target: BluetoothGatt, reason: String) {
        if (!gattAwaitingMtuSettle.remove(target)) return
        log("Closing deferred GATT client ($reason)")
        runCatching { target.close() }
    }

    /**
     * Whether an LE Audio session is actually up for either identity of this headset.
     *
     * Sound Connect keys its SPP-vs-GATT table on this state — its fallback line is
     * literally "LEA device but LE Audio not connected" — so a bond alone must never
     * read as connected. A bonded-but-idle LE identity still shows an ACL (background
     * GATT scans, our own previous control link, vendor keep-alives), so a raw
     * isConnected() stays true long after LC3 audio has stopped, which is what used to
     * drag LDAC-mode connections onto the GATT path.
     *
     * Primary signal is the profile-scoped connected list, which only contains devices
     * with a live LE Audio profile connection. Some builds answer it empty even while
     * the session is up, so the fallback asks the adapter whether the profile holds any
     * connection at all before trusting identity liveness — with LC3 down the adapter
     * drops out first (every identity leaves the profile), so the stale-ACL trap stays
     * closed.
     */
    @SuppressLint("MissingPermission")
    private fun isLeAudioConnected(remote: BluetoothDevice): Boolean {
        val address = runCatching { remote.address }.getOrNull() ?: return false
        val candidates = buildSet {
            add(address.uppercase())
            val identity = UnifiedDeviceIdentityService.getIdentity(address)
            identity?.pairedAddress?.let { add(it.uppercase()) }
            UnifiedDeviceIdentityService.resolveControlAddress(address).let { add(it.uppercase()) }
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val leAudioAddresses = mutableSetOf<String>()
        var profileListUsable = true
        if (manager != null) {
            for (profileId in intArrayOf(BluetoothProfile.LE_AUDIO, QUALCOMM_LE_AUDIO_PROFILE)) {
                runCatching { manager.getConnectedDevices(profileId) }
                    .onFailure {
                        if (profileId == BluetoothProfile.LE_AUDIO) profileListUsable = false
                        log("LE Audio connected-devices query failed for profile $profileId: ${it.message}")
                    }
                    .getOrDefault(emptyList())
                    .forEach { device ->
                        device.address?.uppercase()?.let(leAudioAddresses::add)
                    }
            }
        } else {
            profileListUsable = false
        }
        val connectedByProfile = profileListUsable && candidates.any { it in leAudioAddresses }
        if (profileListUsable) {
            log(
                "LE Audio profile check: connected=${connectedByProfile} " +
                    "identities=$candidates profileDevices=$leAudioAddresses"
            )
            return connectedByProfile
        }
        // Adapter-level fallback: the profile holds a connection for someone, so a
        // candidate identity showing a live ASCS-capable link genuinely is LE Audio.
        val adapterState = runCatching {
            adapter?.getProfileConnectionState(BluetoothProfile.LE_AUDIO)
        }.onFailure {
            log("LE Audio profile-state query failed: ${it.message}")
        }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
        val connectedByAdapter = adapterState == BluetoothProfile.STATE_CONNECTED &&
            candidates.any { candidate ->
                runCatching {
                    val device = adapter?.getRemoteDevice(candidate) ?: return@runCatching false
                    device.uuids.orEmpty().any { it.uuid.toString() == ASCS_SERVICE_STRING } &&
                        BluetoothDevice::class.java.getMethod("isConnected").invoke(device) == true
                }.onFailure {
                    log("LE Audio identity probe failed for $candidate: ${it.message}")
                }.getOrDefault(false)
            }
        log(
            "LE Audio adapter-level check: connected=${connectedByAdapter} " +
                "profileState=$adapterState identities=$candidates"
        )
        return connectedByAdapter
    }

    @SuppressLint("MissingPermission")
    private fun connectSpp(
        selected: DiscoveredSonyDevice,
        selectedRemote: BluetoothDevice,
        sppRecord: UUID,
    ) {
        // No second identity decision here, and no second look at what the device advertises. Both
        // were settled by [resolveTandemRoute]: this is reached only because [advertisedSppRecord]
        // found [sppRecord] on exactly this device. What used to stand here re-derived the classic
        // half from the bt_config identity type, `DEVICE_TYPE_*` and, failing those, by matching the
        // bonded devices' names against the requested one — two bonded units of the same model make
        // that last step pick the wrong headset, and none of the three says whether the device it
        // lands on speaks SPP at all.
        connectedDevice = selected.copy(
            name = safeDeviceName(selectedRemote) ?: selected.name.removePrefix("LE_"),
            address = selectedRemote.address,
            source = "${selected.source}/spp",
            bluetoothType = selectedRemote.type,
            isLikelyControlEndpoint = true,
        )
        disconnect()
        connectedDevice = connectedDevice?.copy(address = selectedRemote.address)
        val generation = sppConnectGeneration.get()
        val connectThread = Thread {
            try {
                adapter?.cancelDiscovery()
                log(
                    "Connecting SPP to ${selectedRemote.address} record=$sppRecord " +
                        "name=${safeDeviceName(selectedRemote).orEmpty()}"
                )
                val socket = selectedRemote.createRfcommSocketToServiceRecord(sppRecord)
                if (sppConnectThread !== Thread.currentThread()) {
                    runCatching { socket.close() }
                    return@Thread
                }
                pendingSppSocket = socket
                if (sppConnectGeneration.get() != generation) {
                    runCatching { socket.close() }
                    return@Thread
                }
                socket.connect()
                pendingSppSocket = null
                if (sppConnectGeneration.get() != generation) {
                    runCatching { socket.close() }
                    return@Thread
                }
                // Publish the connected device BEFORE the read loop starts: the headphone
                // pushes its first frame immediately, and onMessage() requires
                // connectedDevice/connectedProfile to be present (ensureConnectedProfile
                // throws otherwise, killing the SPP thread and the whole probe).
                listener.onConnectionStateChanged(true, connectedDevice)
                var createdTransport: SonySppTransport? = null
                val sppOwner = connectedDevice?.address
                val transport = SonySppTransport(
                    socket = socket,
                    onPayload = { payload -> listener.onMessage(TandemChannel.SPP_MDR, payload, sppOwner) },
                    onClosed = { reason ->
                        log(reason ?: "SPP transport closed")
                        // Object identity alone is not enough. A new connection publishes its
                        // transport a few lines below this callback's registration, so an older
                        // socket closing in that window still matched `sppTransport === null`
                        // and cleared the session that had just come up — the channel table
                        // went empty while state stayed "connected", and every Tandem frame was
                        // dropped as "channel not available". The generation says whether this
                        // callback belongs to the attempt that is still current.
                        if (sppConnectGeneration.get() != generation) {
                            log("ignoring close of superseded SPP transport")
                        } else if (sppTransport === createdTransport) {
                            sppTransport = null
                            listener.onConnectionStateChanged(false, connectedDevice)
                        }
                    },
                    log = ::log,
                ).also { createdTransport = it }
                if (sppConnectGeneration.get() != generation) {
                    transport.close()
                    return@Thread
                }
                sppTransport = transport
                transport.start()
                log("SPP connected")
                listener.onConnectionStateChanged(true, connectedDevice)
                listener.onReady(
                    SonyBleConnectionInfo(
                        mtu = SPP_WRITABLE_VALUE_LENGTH,
                        transport = "SPP",
                        channels = setOf(TandemChannel.SPP_MDR),
                        sppUuid = sppRecord,
                    )
                )
            } catch (e: IOException) {
                log("SPP connection failed: ${e.message}; falling back to GATT (SC parity)")
                val current = sppConnectGeneration.get() == generation
                closeSpp(notify = false)
                if (current) {
                    timeoutHandler.post {
                        if (sppConnectGeneration.get() == generation) {
                            log("Executing GATT fallback for ${selected.address} (remote=${selectedRemote.address})")
                            connectGatt(selected, selectedRemote)
                        }
                    }
                }
            } catch (e: SecurityException) {
                log("SPP permission failure: ${e.message}")
                val current = sppConnectGeneration.get() == generation
                closeSpp(notify = current)
                if (current) listener.onBluetoothUnavailable("Bluetooth connect permission failed for SPP")
            } catch (t: Throwable) {
                // This thread lives in com.android.bluetooth; anything escaping it reaches the
                // system process' default handler and takes the whole Bluetooth stack down.
                Thread.interrupted()
                runCatching {
                    log("SPP connect aborted: ${t.javaClass.simpleName}: ${t.message}; falling back to GATT")
                    val current = sppConnectGeneration.get() == generation
                    closeSpp(notify = false)
                    if (current) {
                        timeoutHandler.post {
                            if (sppConnectGeneration.get() == generation) {
                                connectGatt(selected, selectedRemote)
                            }
                        }
                    }
                }
            } finally {
                if (sppConnectThread === Thread.currentThread()) {
                    sppConnectThread = null
                    pendingSppSocket = null
                }
            }
        }.also {
            sppConnectThread = it
            it.start()
        }
    }

    /**
     * Opens the RFCOMM socket on the record the device advertises.
     *
     * Only advertised records are tried. Blindly attempting the other generation's UUID as well
     * used to be the fallback here, which meant a device that advertises nothing bindable still got
     * two connect attempts — and reaching this function at all now means [advertisedSppRecord]
     * found a record, so there is nothing left for a fallback to do.
     */
    private fun createSppSocket(device: BluetoothDevice): SppSocketWithUuid {
        val advertised = device.uuids.orEmpty().map { it.uuid }.toSet()
        val candidates = OFFICIAL_SPP_UUIDS.filter { it in advertised }
        var lastError: IOException? = null
        for (uuid in candidates.distinct()) {
            try {
                log("SPP create socket uuid=$uuid")
                return SppSocketWithUuid(device.createRfcommSocketToServiceRecord(uuid), uuid)
            } catch (e: IOException) {
                lastError = e
                log("SPP create socket failed uuid=$uuid error=${e.message}")
            }
        }
        throw lastError ?: IOException("No advertised Sony SPP record could create a socket")
    }

    private fun closeSpp(notify: Boolean) {
        sppConnectGeneration.incrementAndGet()
        // Interrupting the connect thread is fatal in this process: BluetoothSocket.connect()
        // runs BluetoothStorageManager's runBlocking inline, which rethrows the interrupt and
        // kills com.android.bluetooth. Closing the socket is what aborts a pending connect().
        sppConnectThread = null
        pendingSppSocket?.let { runCatching { it.close() } }
        pendingSppSocket = null
        val transport = sppTransport
        sppTransport = null
        transport?.close()
        if (notify) {
            listener.onConnectionStateChanged(false, connectedDevice)
        }
    }

    private fun beginTandemHandshake(gatt: BluetoothGatt) {
        cancelAllTimeouts()
        writableValueLength = null
        optimalMtu = null
        determineMtuNotificationEnabled = false
        handshakeStep = HandshakeStep.ReadOptimalMtu
        val characteristic = gatt.getService(SonyGatt.TANDEM_V2_HPC_SERVICE)
            ?.getCharacteristic(SonyGatt.OPTIMAL_MTU)
        if (characteristic == null) {
            log("OPTIMAL_MTU missing; requesting default large MTU")
            requestLargeMtu(gatt)
            return
        }
        log("Handshake: read OPTIMAL_MTU")
        if (!gatt.readCharacteristic(characteristic)) {
            failGattSession(gatt, "Failed to enqueue OPTIMAL_MTU read")
            return
        }
        scheduleHandshakeTimeout(
            gatt,
            HandshakeStep.ReadOptimalMtu,
            OPTIMAL_MTU_TIMEOUT_MS,
            "Timed out reading OPTIMAL_MTU",
        )
    }

    private fun handleCharacteristicRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        unsupportedProbe?.let { probe ->
            handleUnsupportedProbeRead(gatt, probe, uuid, value, status)
            return
        }
        if (uuid == SonyGatt.OPTIMAL_MTU || uuid == SonyGatt.WRITABLE_VALUE_LENGTH) {
            cancelHandshakeTimeout()
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failGattSession(gatt, "Read $uuid failed: $status")
            return
        }
        when (uuid) {
            SonyGatt.OPTIMAL_MTU -> {
                val parsed = value.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
                optimalMtu = parsed
                log("Read $uuid = ${value.hexString()} parsed=$parsed")
                requestLargeMtu(gatt)
                return
            }
            SonyGatt.WRITABLE_VALUE_LENGTH -> {
                val parsed = TandemGattProtocolRules.parseWritableValueLength(value)
                if (parsed == null) {
                    failGattSession(
                        gatt,
                        "Invalid WRITABLE_VALUE_LENGTH ${value.hexString()}; expected 2-byte big-endian value with value+3 in 20..512",
                    )
                    return
                }
                writableValueLength = parsed
                log("Read $uuid = ${value.hexString()} parsed=$parsed")
                enableTandemNotifications(gatt)
                return
            }
        }
        log("Read $uuid = ${value.hexString()}")
    }

    /**
     * Creates a framed Tandem session for every discovered GATT endpoint.
     *
     * Done once the handshake has settled the writable value length, because that is the fragment
     * size the framer splits against — the same value `je0.C19229b.mo850j0()` reports.
     */
    private fun createGattSessions() {
        gattSessions.values.forEach { it.close() }
        gattSessions.clear()
        // Bind the owning device now, the way SC's per-device dispatcher does: a
        // frame this session reassembles later belongs to THIS headset even if
        // the connection has since moved on.
        val sessionOwner = connectedDevice?.address
        gattEndpoints.forEach { (channel, endpoint) ->
            gattSessions[channel] = SonyGattTandemSession(
                channel = channel,
                writableValueLength = writableValueLength,
                writeBytes = { bytes -> enqueueGattWrite(endpoint, bytes) },
                onPayload = { ch, payload -> listener.onMessage(ch, payload, sessionOwner) },
                onFailure = { reason -> listener.onBluetoothUnavailable(reason) },
                log = ::log,
                scheduleTimeout = { delayMs, action ->
                    timeoutHandler.postDelayed({ action() }, delayMs)
                },
            )
        }
        log("Framed Tandem sessions ready for ${gattSessions.keys}")
    }

    /** The session whose TO_ACC characteristic this is, if any. */
    private fun sessionForToAcc(characteristic: BluetoothGattCharacteristic): SonyGattTandemSession? {
        val channel = gattEndpoints.entries
            .firstOrNull { (_, endpoint) -> endpoint.toAcc.uuid == characteristic.uuid }
            ?.key
            ?: return null
        return gattSessions[channel]
    }

    /** Sequence state and pending retries must not survive into the next connection. */
    private fun closeGattSessions() {
        gattSessions.values.forEach { it.close() }
        gattSessions.clear()
    }

    /** Queues raw bytes for the endpoint's TO_ACC characteristic, already framed by the session. */
    private fun enqueueGattWrite(endpoint: GattTandemEndpoint, bytes: ByteArray): Boolean {
        writeQueue.add(PendingTandemWrite(endpoint.channel, bytes))
        drainWriteQueue()
        return true
    }

    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val uuid = characteristic.uuid
        if (uuid == SonyGatt.DETERMINE_MTU) {
            log("Handshake: DETERMINE_MTU notification ${value.hexString()}")
            if (
                handshakeStep == HandshakeStep.WaitDetermineMtu &&
                determineMtuNotificationEnabled &&
                TandemGattProtocolRules.isDetermineReady(value)
            ) {
                cancelHandshakeTimeout()
                disableDetermineMtuNotifications(gatt)
            } else if (handshakeStep == HandshakeStep.WaitDetermineMtu) {
                log("Handshake: ignoring unexpected DETERMINE_MTU payload ${value.hexString()}")
            }
            return
        }
        val channel = TandemGattRouting.fromAccChannel(
            endpoints = gattEndpoints,
            serviceUuid = characteristic.service?.uuid,
            characteristicUuid = uuid,
        ) ?: TandemGattRouting.fromAccChannelFor(characteristic.service?.uuid, uuid)
            ?: gattEndpoints.keys.singleOrNull()
            ?: defaultGattWriteChannel()
        // Notifications carry framed Tandem, and one frame can span several of them, so the
        // session reassembles and unframes before anything reaches the protocol layer.
        val session = gattSessions[channel]
        if (session != null) {
            session.onNotification(value)
            return
        }
        listener.onMessage(channel, value, connectedDevice?.address)
    }

    private fun beginUnsupportedEndpointProbe(
        gatt: BluetoothGatt,
        services: List<UUID>,
        reason: String,
    ) {
        val serviceLabels = services.map { SonyGatt.serviceLabel(it) }
        unsupportedProbe = UnsupportedEndpointProbe(reason, serviceLabels)
        log("Unsupported endpoint probe starting. reason=$reason")
        gatt.services.forEach { service ->
            val chars = service.characteristics.joinToString { characteristic ->
                "${SonyGatt.characteristicLabel(characteristic.uuid)} props=0x${characteristic.properties.toString(16)}"
            }
            log("Probe service ${SonyGatt.serviceLabel(service.uuid)} chars=[$chars]")
        }
        if (!readNextUnsupportedProbeCharacteristic(gatt)) {
            finishUnsupportedEndpointProbe()
        }
    }

    private fun handleUnsupportedProbeRead(
        gatt: BluetoothGatt,
        probe: UnsupportedEndpointProbe,
        uuid: UUID,
        value: ByteArray,
        status: Int,
    ) {
        val label = SonyGatt.characteristicLabel(uuid)
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val hex = value.hexString()
            probe.rawReads[label] = hex
            when (uuid) {
                SonyGatt.LE_AUDIO_SWITCH_SUPPORTED_COMPATIBILITY -> {
                    probe.leAudioSwitchCompatibility = value.firstOrNull()?.toInt()?.and(0xFF)
                    log("Probe read $label compatibility=${probe.leAudioSwitchCompatibility} raw=$hex")
                }
                SonyGatt.COMPLETE_BLUETOOTH_FRIENDLY_NAME -> {
                    probe.friendlyName = parseFriendlyName(value)
                    log("Probe read $label name=${probe.friendlyName.orEmpty()} raw=$hex")
                }
                SonyGatt.BLUETOOTH_PUBLIC_ADDRESS -> {
                    probe.publicAddress = value.toString(Charsets.UTF_8).trim('\u0000')
                    log("Probe read $label publicAddress=${probe.publicAddress.orEmpty()} raw=$hex")
                }
                else -> log("Probe read $label raw=$hex")
            }
        } else {
            log("Probe read $label failed status=$status")
            probe.rawReads[label] = "read failed: $status"
        }
        if (!readNextUnsupportedProbeCharacteristic(gatt)) {
            finishUnsupportedEndpointProbe()
        }
    }

    private fun readNextUnsupportedProbeCharacteristic(gatt: BluetoothGatt): Boolean {
        val probe = unsupportedProbe ?: return false
        while (probe.nextIndex < UNSUPPORTED_PROBE_CHARACTERISTICS.size) {
            val uuid = UNSUPPORTED_PROBE_CHARACTERISTICS[probe.nextIndex++]
            val label = SonyGatt.characteristicLabel(uuid)
            val characteristic = findReadableCharacteristic(gatt, uuid)
            if (characteristic == null) {
                probe.rawReads[label] = "missing or not readable"
                continue
            }
            log("Probe read $label")
            if (gatt.readCharacteristic(characteristic)) {
                return true
            }
            probe.rawReads[label] = "read rejected"
        }
        return false
    }

    private fun finishUnsupportedEndpointProbe() {
        val probe = unsupportedProbe ?: return
        val diagnostics = UnsupportedEndpointDiagnostics(
            reason = probe.reason,
            serviceLabels = probe.serviceLabels,
            leAudioSwitchCompatibility = probe.leAudioSwitchCompatibility,
            friendlyName = probe.friendlyName,
            publicAddress = probe.publicAddress,
            rawReads = probe.rawReads.toMap(),
        )
        log(
            "Unsupported endpoint probe complete: compatibility=${diagnostics.leAudioSwitchCompatibility} " +
                "friendlyName=${diagnostics.friendlyName.orEmpty()} publicAddress=${diagnostics.publicAddress.orEmpty()}"
        )
        listener.onUnsupportedEndpoint(diagnostics)
    }

    private fun findReadableCharacteristic(
        gatt: BluetoothGatt,
        uuid: UUID,
    ): BluetoothGattCharacteristic? =
        gatt.services.asSequence()
            .flatMap { it.characteristics.asSequence() }
            .firstOrNull {
                it.uuid == uuid &&
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
            }

    private fun parseFriendlyName(value: ByteArray): String? {
        if (value.size < 3) return null
        return value.copyOfRange(2, value.size)
            .toString(Charsets.UTF_8)
            .trim('\u0000')
            .ifBlank { null }
    }

    private fun requestLargeMtu(gatt: BluetoothGatt) {
        handshakeStep = HandshakeStep.RequestMtu
        val requested = (optimalMtu ?: 517).coerceIn(23, 517)
        log("Handshake: request MTU $requested")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (!gatt.requestMtu(requested)) {
                log("requestMtu returned false; continuing with current MTU")
                enableDetermineMtuNotifications(gatt)
            }
        } else {
            enableDetermineMtuNotifications(gatt)
        }
    }

    private fun enableDetermineMtuNotifications(gatt: BluetoothGatt) {
        val characteristic = gatt.getService(SonyGatt.TANDEM_V2_HPC_SERVICE)
            ?.getCharacteristic(SonyGatt.DETERMINE_MTU)
        if (characteristic == null || characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) == null) {
            log("DETERMINE_MTU notification endpoint missing; reading WRITABLE_VALUE_LENGTH directly")
            readWritableValueLength(gatt)
            return
        }
        handshakeStep = HandshakeStep.EnableDetermineMtu
        log("Handshake: enable DETERMINE_MTU notification")
        if (!writeNotificationState(gatt, characteristic, enabled = true)) {
            failGattSession(gatt, "Failed to enqueue DETERMINE_MTU notification enable")
            return
        }
        scheduleHandshakeTimeout(
            gatt,
            HandshakeStep.EnableDetermineMtu,
            DETERMINE_MTU_READY_TIMEOUT_MS,
            "Timed out enabling DETERMINE_MTU notification",
        )
    }

    private fun disableDetermineMtuNotifications(gatt: BluetoothGatt) {
        val characteristic = gatt.getService(SonyGatt.TANDEM_V2_HPC_SERVICE)
            ?.getCharacteristic(SonyGatt.DETERMINE_MTU)
        if (characteristic == null || characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) == null) {
            determineMtuNotificationEnabled = false
            readWritableValueLength(gatt)
            return
        }
        handshakeStep = HandshakeStep.DisableDetermineMtu
        log("Handshake: disable DETERMINE_MTU notification")
        if (!writeNotificationState(gatt, characteristic, enabled = false)) {
            failGattSession(gatt, "Failed to enqueue DETERMINE_MTU notification disable")
            return
        }
        scheduleHandshakeTimeout(
            gatt,
            HandshakeStep.DisableDetermineMtu,
            DISABLE_DETERMINE_MTU_TIMEOUT_MS,
            "Timed out disabling DETERMINE_MTU notification",
        )
    }

    private fun readWritableValueLength(gatt: BluetoothGatt) {
        handshakeStep = HandshakeStep.ReadWritableValueLength
        val characteristic = gatt.getService(SonyGatt.TANDEM_V2_HPC_SERVICE)
            ?.getCharacteristic(SonyGatt.WRITABLE_VALUE_LENGTH)
        if (characteristic == null) {
            log("WRITABLE_VALUE_LENGTH missing; enabling Tandem notifications")
            enableTandemNotifications(gatt)
            return
        }
        log("Handshake: read WRITABLE_VALUE_LENGTH")
        if (!gatt.readCharacteristic(characteristic)) {
            failGattSession(gatt, "Failed to enqueue WRITABLE_VALUE_LENGTH read")
            return
        }
        scheduleHandshakeTimeout(
            gatt,
            HandshakeStep.ReadWritableValueLength,
            WRITABLE_VALUE_LENGTH_TIMEOUT_MS,
            "Timed out reading WRITABLE_VALUE_LENGTH",
        )
    }

    private fun enableTandemNotifications(gatt: BluetoothGatt) {
        cancelHandshakeTimeout()
        handshakeStep = HandshakeStep.EnableTandemNotifications
        pendingNotifyEndpoints.clear()
        val orderedChannels = TandemGattRouting.notificationOrder(gattEndpoints.keys)
        gattEndpoints.values
            .sortedBy { endpoint -> orderedChannels.indexOf(endpoint.channel) }
            .forEach { pendingNotifyEndpoints.addLast(it) }
        enableNextTandemNotification(gatt)
    }

    private fun enableNextTandemNotification(gatt: BluetoothGatt) {
        cancelHandshakeTimeout()
        val endpoint = pendingNotifyEndpoints.removeFirstOrNull()
        if (endpoint == null) {
            handshakeStep = HandshakeStep.Ready
            createGattSessions()
            listener.onReady(
                SonyBleConnectionInfo(
                    mtu = negotiatedMtu,
                    writableValueLength = writableValueLength,
                    optimalMtu = optimalMtu,
                    transport = gattTransportLabel(),
                    channels = gattEndpoints.keys.toSet(),
                )
            )
            return
        }
        val characteristic = endpoint.fromAcc
        log("Handshake: enable ${endpoint.channel} ${SonyGatt.characteristicLabel(characteristic.uuid)} notification")
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            failGattSession(gatt, "Failed to enable local Tandem notification for ${endpoint.channel}")
            return
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            enableNextTandemNotification(gatt)
            return
        }
        if (!writeDescriptorValue(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            failGattSession(gatt, "Failed to enqueue Tandem notification enable for ${endpoint.channel}")
            return
        }
        scheduleHandshakeTimeout(
            gatt,
            HandshakeStep.EnableTandemNotifications,
            ENABLE_TANDEM_NOTIFICATION_TIMEOUT_MS,
            "Timed out enabling Tandem notification for ${endpoint.channel}",
        )
    }

    private fun writeNotificationState(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, enabled)) return false
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return false
        val value = if (enabled) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        return writeDescriptorValue(gatt, descriptor, value)
    }

    private fun writeDescriptorValue(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
    } else {
        @Suppress("DEPRECATION")
        descriptor.value = value
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(descriptor)
    }

    private fun drainWriteQueue() {
        val gatt = gatt ?: return
        val pending: PendingTandemWrite
        val generation: Long
        synchronized(writeStateLock) {
            if (writing) return
            pending = writeQueue.poll() ?: return
            generation = ++writeGeneration
            activeWriteGeneration = generation
            writing = true
        }
        // Framed writes arrive here already split by the session against the same limit, so only
        // an unframed payload can still legitimately exceed it.
        val framed = gattSessions.containsKey(pending.channel)
        if (!framed && !TandemGattProtocolRules.canWrite(pending.bytes.size, writableValueLength)) {
            synchronized(writeStateLock) {
                if (activeWriteGeneration == generation) {
                    activeWriteGeneration = null
                    writing = false
                }
            }
            listener.onBluetoothUnavailable(
                "Tandem payload is ${pending.bytes.size} bytes, exceeding writable limit $writableValueLength"
            )
            drainWriteQueue()
            return
        }
        val endpoint = gattEndpoints[pending.channel]
        if (endpoint == null) {
            synchronized(writeStateLock) {
                if (activeWriteGeneration == generation) {
                    activeWriteGeneration = null
                    writing = false
                }
            }
            listener.onBluetoothUnavailable("Channel ${pending.channel} is not available (available: ${availableChannels()})")
            drainWriteQueue()
            return
        }
        val characteristic = endpoint.toAcc
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                pending.bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = pending.bytes
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!accepted) {
            synchronized(writeStateLock) {
                if (activeWriteGeneration == generation) {
                    activeWriteGeneration = null
                    writing = false
                    cancelWriteTimeoutLocked()
                }
            }
            failGattSession(gatt, "Failed to enqueue BLE write")
            return
        }
        scheduleWriteTimeout(gatt, characteristic.uuid, generation)
    }

    private fun scheduleHandshakeTimeout(
        gatt: BluetoothGatt,
        expectedStep: HandshakeStep,
        timeoutMs: Long,
        reason: String,
    ) {
        cancelHandshakeTimeout()
        val timeout = Runnable {
            if (this.gatt === gatt && handshakeStep == expectedStep) {
                failGattSession(gatt, reason)
            }
        }
        handshakeTimeout = timeout
        timeoutHandler.postDelayed(timeout, timeoutMs)
    }

    private fun cancelHandshakeTimeout() {
        handshakeTimeout?.let(timeoutHandler::removeCallbacks)
        handshakeTimeout = null
    }

    private fun scheduleWriteTimeout(
        gatt: BluetoothGatt,
        characteristicUuid: UUID,
        generation: Long,
    ) {
        synchronized(writeStateLock) {
            if (this.gatt !== gatt || activeWriteGeneration != generation) return
            cancelWriteTimeoutLocked()
            val timeout = Runnable {
                val timedOut = synchronized(writeStateLock) {
                    if (this.gatt !== gatt || activeWriteGeneration != generation || !writing) {
                        false
                    } else {
                        activeWriteGeneration = null
                        writing = false
                        writeTimeout = null
                        true
                    }
                }
                if (timedOut) {
                    failGattSession(gatt, "Timed out completing BLE write for $characteristicUuid")
                }
            }
            writeTimeout = timeout
            timeoutHandler.postDelayed(timeout, WRITE_COMPLETION_TIMEOUT_MS)
        }
    }

    private fun cancelWriteTimeout() {
        synchronized(writeStateLock) {
            cancelWriteTimeoutLocked()
        }
    }

    private fun cancelWriteTimeoutLocked() {
        writeTimeout?.let(timeoutHandler::removeCallbacks)
        writeTimeout = null
    }

    private fun cancelAllTimeouts() {
        cancelHandshakeTimeout()
        cancelWriteTimeout()
    }

    private fun failGattSession(gatt: BluetoothGatt, reason: String) {
        if (this.gatt !== gatt) return
        log(reason)
        listener.onBluetoothUnavailable(reason)
        closeGatt(notify = true)
    }

    private fun defaultGattWriteChannel(): TandemChannel =
        when {
            TandemChannel.GATT_V2_HPC in gattEndpoints -> TandemChannel.GATT_V2_HPC
            TandemChannel.GATT_V1_MC in gattEndpoints -> TandemChannel.GATT_V1_MC
            TandemChannel.GATT_V2_MC in gattEndpoints -> TandemChannel.GATT_V2_MC
            else -> TandemChannel.GATT_V2_HPC
        }

    private fun gattTransportLabel(): String =
        if (TandemChannel.GATT_V2_HPC in gattEndpoints) {
            "GATT_HPC"
        } else {
            "GATT_MC"
        }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun enumerateKnownDevices(adapter: BluetoothAdapter) {
        if (!hasConnectPermission()) {
            log("Known-device enumeration skipped: BLUETOOTH_CONNECT permission is missing")
            return
        }

        val bonded = adapter.bondedDevices.orEmpty()
        log("Bonded devices count=${bonded.size}")
        bonded.forEach { device ->
            recordKnownDevice("bonded", device, rssi = 0)
        }

        runCatching {
            bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        }.onSuccess { devices ->
            log("Connected GATT devices count=${devices.size}")
            devices.forEach { device ->
                recordKnownDevice("connected-gatt", device, rssi = 0)
            }
        }.onFailure {
            log("Connected GATT lookup failed: ${it.message}")
        }

        requestProfileDevices(adapter, BluetoothProfile.A2DP, "connected-a2dp")
        requestProfileDevices(adapter, BluetoothProfile.HEADSET, "connected-headset")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requestProfileDevices(adapter, BluetoothProfile.HEARING_AID, "connected-hearing-aid")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestProfileDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        source: String,
    ) {
        val serviceListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                val devices = proxy.connectedDevices.orEmpty()
                log("$source devices count=${devices.size}")
                devices.forEach { device ->
                    recordKnownDevice(source, device, rssi = 0)
                }
                adapter.closeProfileProxy(profileId, proxy)
            }

            override fun onServiceDisconnected(profileId: Int) {
                log("$source profile disconnected id=$profileId")
            }
        }
        val requested = adapter.getProfileProxy(context, serviceListener, profile)
        log("$source profile proxy requested=$requested")
    }

    @SuppressLint("MissingPermission")
    private fun recordKnownDevice(source: String, device: BluetoothDevice, rssi: Int) {
        val name = safeDeviceName(device)
        val uuids = device.uuids?.joinToString { it.uuid.toString() }.orEmpty()
        log(
            "Known device source=$source name=${name ?: "<unknown>"} address=${device.address} " +
                "type=${device.type} bond=${device.bondState} uuids=[$uuids]"
        )
        if (!SonyDeviceService.isSony(device)) return

        // A headset on LE Audio bonds twice, and only the non-LE identity exposes Sony's
        // private services — the LE one carries LC3 audio and nothing controllable. Report it
        // under its control counterpart instead of as a device of its own, which would
        // otherwise be offered as a connection target that can never complete a session.
        if (SonyDeviceService.isLeAudioIdentity(device)) {
            val control = SonyDeviceService.resolveControlAddress(device.address)
            if (control == null || control.equals(device.address, ignoreCase = true)) {
                log("Skipping LE Audio identity ${device.address}: no control identity bonded")
                return
            }
            log("Folding LE Audio identity ${device.address} into control identity $control")
            return
        }

        listener.onDeviceFound(
            DiscoveredSonyDevice(
                name = name ?: "Sony audio device",
                address = device.address,
                rssi = rssi,
                source = source,
                bluetoothType = device.type,
                advertisedServices = device.uuids?.map { it.uuid.toString() }.orEmpty(),
                isLikelyControlEndpoint = runCatching {
                    val idType = UnifiedDeviceIdentityService.getIdentityType(device.address)
                    idType == dev.sonypods.device.IdentityType.CLASSIC ||
                        idType == dev.sonypods.device.IdentityType.DUAL
                }.getOrDefault(false) ||
                    device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    device.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                    name?.startsWith("LE_", ignoreCase = true) == true,
            )
        )
    }

    /**
     * Maps an LE Audio identity onto the identity that actually carries Tandem.
     *
     * Returns [device] unchanged for anything else, including when no control identity is
     * bonded — failing to connect is more honest than silently targeting another headset.
     */
    @SuppressLint("MissingPermission")
    private fun resolveControlTarget(device: DiscoveredSonyDevice): DiscoveredSonyDevice {
        val control = UnifiedDeviceIdentityService.resolveControlAddress(device.address)
        if (control.equals(device.address, ignoreCase = true)) return device
        val remote = adapter?.bondedDevices.orEmpty()
            .firstOrNull { it.address.equals(control, ignoreCase = true) }
        log("Retargeting LE Audio identity ${device.address} to control identity $control")
        return device.copy(
            address = control,
            name = remote?.let(::safeDeviceName) ?: device.name.removePrefix("LE_"),
            bluetoothType = remote?.type ?: device.bluetoothType,
            advertisedServices = remote?.uuids?.map { it.uuid.toString() }.orEmpty(),
            isLikelyControlEndpoint = true,
        )
    }

    /**
     * The headset's LE Audio identity, for when that is the half serving Tandem.
     *
     * Falls back to [device] whenever the pair is unknown or its LE half is not bonded: connecting
     * the requested address is recoverable, targeting an address this run cannot account for is not.
     */
    @SuppressLint("MissingPermission")
    private fun resolveLeAudioTarget(device: DiscoveredSonyDevice): DiscoveredSonyDevice {
        val leAddress = UnifiedDeviceIdentityService.leAudioAddressFor(device.address)
            ?: return device
        if (leAddress.equals(device.address, ignoreCase = true)) return device
        val remote = adapter?.bondedDevices.orEmpty()
            .firstOrNull { it.address.equals(leAddress, ignoreCase = true) }
        if (remote == null) {
            log("LE Audio identity $leAddress is not bonded; staying on ${device.address}")
            return device
        }
        log("LE Audio is up; retargeting ${device.address} to LE Audio identity $leAddress")
        return device.copy(
            address = leAddress,
            name = safeDeviceName(remote) ?: device.name,
            bluetoothType = remote.type,
            advertisedServices = remote.uuids?.map { it.uuid.toString() }.orEmpty(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String? =
        if (hasConnectPermission()) device.name else null

    private fun log(message: String) {
        // Transport frames (RX/TX/ACK/write status) fire for every NTFY the
        // headset pushes unprompted — battery alone arrives every ~15s — so
        // logcat only sees them at DEBUG level. The listener copy feeds the
        // debug ring buffer unconditionally.
        Log.d(LOG_TAG, message)
        listener.onLog(message)
    }

    private fun android.bluetooth.le.ScanRecord.manufacturerSummary(): String {
        val data = manufacturerSpecificData ?: return ""
        return (0 until data.size()).joinToString { index ->
            val id = data.keyAt(index)
            val bytes = data.valueAt(index)
            "0x${id.toString(16)}:${bytes.hexString()}"
        }
    }

    private fun android.bluetooth.le.ScanRecord.serviceDataSummary(): String =
        serviceData?.entries.orEmpty().joinToString { (uuid, bytes) ->
            "${uuid.uuid}:${bytes.hexString()}"
        }

    private fun android.bluetooth.le.ScanRecord.sonyAudioAdvertisement(): SonyAudioAdvertisement? {
        val fromRawRecord = extractSonyAudioManufacturerPayloads(bytes ?: byteArrayOf())
            .firstNotNullOfOrNull { parseSonyAudioV2Advertisement(it) }
        if (fromRawRecord != null) return fromRawRecord

        val direct = manufacturerSpecificData?.get(SONY_AUDIO_MANUFACTURER_ID)
        return direct?.let { parseSonyAudioV2Advertisement(it) }
    }

    private fun extractSonyAudioManufacturerPayloads(record: ByteArray): List<ByteArray> {
        val parsed = mutableListOf<ByteArray>()
        var pendingV2: ByteArray? = null
        var index = 0
        while (index < record.size) {
            val length = record[index].toInt() and 0xFF
            if (length == 0) break
            val typeIndex = index + 1
            val nextIndex = index + length + 1
            if (typeIndex >= record.size || nextIndex > record.size) break
            val type = record[typeIndex].toInt() and 0xFF
            if (type == AD_TYPE_MANUFACTURER_SPECIFIC && length >= 4) {
                val manufacturerId =
                    (record[index + 2].toInt() and 0xFF) or
                        ((record[index + 3].toInt() and 0xFF) shl 8)
                if (manufacturerId == SONY_AUDIO_MANUFACTURER_ID) {
                    val payload = record.copyOfRange(index + 4, nextIndex)
                    when {
                        payload.isSonyAudioV2Start() -> {
                            pendingV2 = payload
                            parsed += payload
                        }
                        payload.isSonyAudioV1Start() -> parsed += payload
                        pendingV2 != null -> {
                            val combined = pendingV2 + payload
                            pendingV2 = combined
                            parsed += combined
                        }
                    }
                }
            }
            index = nextIndex
        }
        return parsed
    }

    private fun ByteArray.isSonyAudioV1Start(): Boolean =
        size >= SONY_AUDIO_HEAD.size + 1 &&
            this[0] == SONY_AUDIO_HEAD[0] &&
            this[1] == SONY_AUDIO_HEAD[1] &&
            this[2].toInt() == 1

    private fun ByteArray.isSonyAudioV2Start(): Boolean =
        size >= SONY_AUDIO_HEAD.size + 1 &&
            this[0] == SONY_AUDIO_HEAD[0] &&
            this[1] == SONY_AUDIO_HEAD[1] &&
            this[2].toInt() == 2

    private fun parseSonyAudioV2Advertisement(payload: ByteArray): SonyAudioAdvertisement? {
        if (!payload.isSonyAudioV2Start() || payload.size < 4) return null
        val chunkCount = payload[3].toInt() and 0xFF
        if (chunkCount < 1) return null

        var index = 4
        var androidLine: String? = null
        var androidGattCapable = false
        var audioStream: String? = null
        var leGattControlFlag = false
        var modelId: Int? = null
        var classicHash: Long? = null

        repeat(chunkCount) {
            if (index >= payload.size) return@repeat
            val header = payload[index].toInt() and 0xFF
            val bodyLength = (header and 0xF0) ushr 4
            val chunkType = header and 0x0F
            val bodyStart = index + 1
            val bodyEnd = bodyStart + bodyLength
            if (bodyLength <= 0 || bodyEnd > payload.size) {
                index = payload.size
                return@repeat
            }
            when (chunkType) {
                SONY_CHUNK_BASIC_INFORMATION -> {
                    if (bodyLength == 11) {
                        modelId = payload[bodyStart].toInt() and 0xFF
                    }
                }
                SONY_CHUNK_TANDEM_TRANSMITTING_LINE -> {
                    if (bodyLength == 3 || bodyLength == 4) {
                        val android = payload[bodyStart].toInt() and 0xFF
                        androidLine = transmittingLineLabel(android and 0x0F)
                        audioStream = audioStreamLabel(android and 0xF0)
                        androidGattCapable = (android and 0x0F) == 1 || (android and 0x0F) == 3
                        val bluetoothSpec = payload[bodyStart + 2].toInt() and 0xFF
                        leGattControlFlag = (bluetoothSpec and 0x01) == 0x01
                    }
                }
                SONY_CHUNK_CLASSIC_BLUETOOTH_HASH -> {
                    if (bodyLength == 4 || bodyLength == 8) {
                        classicHash = unsignedInt(payload, bodyStart)
                    }
                }
            }
            index = bodyEnd
        }

        if (index != payload.size) {
            log("Sony Audio AD parse warning: consumed=$index size=${payload.size} raw=${payload.hexString()}")
        }
        return SonyAudioAdvertisement(
            version = 2,
            raw = payload.hexString(),
            androidLine = androidLine,
            androidGattCapable = androidGattCapable,
            audioStream = audioStream,
            leGattControlFlag = leGattControlFlag,
            modelId = modelId,
            classicHash = classicHash,
        )
    }

    private fun transmittingLineLabel(code: Int): String =
        when (code) {
            0 -> "SPP"
            1 -> "GATT"
            3 -> "SPP_OR_GATT"
            else -> "UNKNOWN(0x${code.toString(16)})"
        }

    private fun audioStreamLabel(code: Int): String =
        when (code) {
            0x00 -> "A2DP"
            0x10 -> "LE_AUDIO"
            0x20 -> "A2DP_OR_LE_AUDIO"
            else -> "UNKNOWN(0x${code.toString(16)})"
        }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private enum class HandshakeStep {
        Idle,
        ReadOptimalMtu,
        RequestMtu,
        EnableDetermineMtu,
        WaitDetermineMtu,
        DisableDetermineMtu,
        ReadWritableValueLength,
        EnableTandemNotifications,
        Ready,
    }

    private data class UnsupportedEndpointProbe(
        val reason: String,
        val serviceLabels: List<String>,
        var nextIndex: Int = 0,
        var leAudioSwitchCompatibility: Int? = null,
        var friendlyName: String? = null,
        var publicAddress: String? = null,
        val rawReads: MutableMap<String, String> = linkedMapOf(),
    )

    companion object {
        private const val LOG_TAG = "SonyPods-Engine"
        private const val AD_TYPE_MANUFACTURER_SPECIFIC = 0xFF
        private const val SONY_AUDIO_MANUFACTURER_ID = 0x012D
        private val SONY_AUDIO_HEAD = byteArrayOf(0x04, 0x00)
        private const val SONY_CHUNK_BASIC_INFORMATION = 0x00
        private const val SONY_CHUNK_TANDEM_TRANSMITTING_LINE = 0x03
        private const val SONY_CHUNK_CLASSIC_BLUETOOTH_HASH = 0x05
        private const val SPP_WRITABLE_VALUE_LENGTH = 1024
        private const val OPTIMAL_MTU_TIMEOUT_MS = 2_000L
        /** Ceiling on how long a close waits for its MTU reply; observed replies land ≤1.4 s. */
        private const val MTU_SETTLE_TIMEOUT_MS = 2_000L
        private const val DETERMINE_MTU_READY_TIMEOUT_MS = 10_000L
        private const val DISABLE_DETERMINE_MTU_TIMEOUT_MS = 5_000L
        private const val WRITABLE_VALUE_LENGTH_TIMEOUT_MS = 2_000L
        private const val ENABLE_TANDEM_NOTIFICATION_TIMEOUT_MS = 5_000L
        private const val WRITE_COMPLETION_TIMEOUT_MS = 500L
        /** Qualcomm's private LE Audio profile id (mirrors LeAudioProfileGateway). */
        private const val QUALCOMM_LE_AUDIO_PROFILE = 32
        /** Audio Stream Control Service, for adapter-level LE Audio identity checks. */
        private const val ASCS_SERVICE_STRING = "0000184e-0000-1000-8000-00805f9b34fb"
        /**
         * Official Sony SPP UUIDs (TableSet 2 and TableSet 1).
         * Mirrors Sound Connect C18016i and C5731a, including byte-reversed UUIDs
         * to handle Android Bluetooth stack endianness quirks.
         */
        val SPP_UUID_TABLE_SET_2_NORMAL: UUID = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2")
        val SPP_UUID_TABLE_SET_2_REVERSED: UUID = UUID.fromString("e2b63c39-7db1-3fb0-a84b-9ad4267b6c95")
        val SPP_UUID_TABLE_SET_1_NORMAL: UUID = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba")
        val SPP_UUID_TABLE_SET_1_REVERSED: UUID = UUID.fromString("ba69e0f5-16e3-2db3-ad46-68503e20cc96")

        private val OFFICIAL_SPP_UUIDS = listOf(
            SPP_UUID_TABLE_SET_2_NORMAL,
            SPP_UUID_TABLE_SET_1_NORMAL,
            SPP_UUID_TABLE_SET_2_REVERSED,
            SPP_UUID_TABLE_SET_1_REVERSED,
        )
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val UNSUPPORTED_PROBE_CHARACTERISTICS = listOf(
            SonyGatt.LE_AUDIO_SWITCH_SUPPORTED_COMPATIBILITY,
            SonyGatt.BLUETOOTH_CONNECTION,
            SonyGatt.BLUETOOTH_MODE,
            SonyGatt.BLUETOOTH_CONNECTION_STATUS,
            SonyGatt.BLUETOOTH_MODE_STATUS,
            SonyGatt.COMPLETE_BLUETOOTH_FRIENDLY_NAME,
            SonyGatt.SYNC_BLUETOOTH_FRIENDLY_NAME_INDEX,
            SonyGatt.BLUETOOTH_PUBLIC_ADDRESS,
            SonyGatt.LE_AD_PACKET_IDENTIFIER,
            SonyGatt.LE_AD_PACKET_IDENTIFIER_LEFT,
            SonyGatt.LE_AD_PACKET_IDENTIFIER_RIGHT,
            SonyGatt.TARGET_ANNOUNCEMENT_LE_AD,
        )
    }
}
