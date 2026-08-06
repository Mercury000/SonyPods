package dev.sonypods.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
import android.util.Log
import androidx.core.content.ContextCompat
import dev.sonypods.headphones.TandemChannel
import dev.sonypods.protocol.SonyGatt
import dev.sonypods.protocol.hexString
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

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
    fun onMessage(channel: TandemChannel, raw: ByteArray)
    fun onLog(message: String)
}

class SonyBleClient(
    private val context: Context,
    private val listener: SonyBleClientListener,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
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
    private var unsupportedProbe: UnsupportedEndpointProbe? = null
    private var sppTransport: SonySppTransport? = null
    private val writeQueue = ConcurrentLinkedQueue<PendingTandemWrite>()
    private val pendingNotifyEndpoints = ArrayDeque<GattTandemEndpoint>()
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
            if (isSonyCandidate(name) || found.isLikelyControlEndpoint || sonyAd != null) {
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
                log("GATT connected; discovering services")
                listener.onConnectionStateChanged(true, connectedDevice)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT disconnected: status=$status")
                writeQueue.clear()
                pendingNotifyEndpoints.clear()
                writing = false
                toAcc = null
                fromAcc = null
                gattEndpoints.clear()
                handshakeStep = HandshakeStep.Idle
                determineMtuNotificationEnabled = false
                listener.onConnectionStateChanged(false, connectedDevice)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed status=$status")
                listener.onBluetoothUnavailable("Service discovery failed: $status")
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
                log("No usable Tandem GATT endpoint. Available services=[$labels]")
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
            negotiatedMtu = mtu
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
            handleCharacteristicRead(gatt, characteristic.uuid, value, status)
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleCharacteristicRead(gatt, characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(characteristic, value)
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleCharacteristicChanged(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            log("Write ${characteristic.uuid}: status=$status")
            writing = false
            drainWriteQueue()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            log("Notify descriptor ${descriptor.uuid}: status=$status")
            when {
                descriptor.characteristic?.uuid == SonyGatt.DETERMINE_MTU &&
                    handshakeStep == HandshakeStep.EnableDetermineMtu -> {
                    determineMtuNotificationEnabled = status == BluetoothGatt.GATT_SUCCESS
                    readWritableValueLength(gatt)
                }
                descriptor.characteristic?.uuid == SonyGatt.DETERMINE_MTU &&
                    handshakeStep == HandshakeStep.DisableDetermineMtu -> {
                    readWritableValueLength(gatt)
                }
                handshakeStep == HandshakeStep.EnableTandemNotifications &&
                    TandemGattRouting.fromAccChannel(
                        endpoints = gattEndpoints,
                        serviceUuid = descriptor.characteristic?.service?.uuid,
                        characteristicUuid = descriptor.characteristic?.uuid,
                    ) != null -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        val channel = TandemGattRouting.fromAccChannel(
                            endpoints = gattEndpoints,
                            serviceUuid = descriptor.characteristic?.service?.uuid,
                            characteristicUuid = descriptor.characteristic?.uuid,
                        )
                        listener.onBluetoothUnavailable("Failed to enable Tandem notification for $channel: $status")
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

    fun connect(device: DiscoveredSonyDevice) {
        if (!hasConnectPermission()) {
            listener.onBluetoothUnavailable("Bluetooth connect permission is missing")
            return
        }
        stopScan()
        val remote = adapter?.getRemoteDevice(device.address)
        if (remote == null) {
            listener.onBluetoothUnavailable("Cannot resolve remote device ${device.address}")
            return
        }
        if (shouldUseSpp(device, remote)) {
            connectSpp(device, remote)
            return
        }
        connectedDevice = device.copy(
            name = if (device.name == "Unknown BLE device") {
                safeDeviceName(remote) ?: "Sony audio device"
            } else {
                device.name
            },
            address = remote.address,
            bluetoothType = if (remote.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                remote.type
            } else {
                device.bluetoothType
            },
        )
        disconnect()
        connectedDevice = connectedDevice?.copy(address = remote.address)
        val transport = preferredTransport(remote, device)
        log(
            "Connecting to ${device.address} type=${remote.type} transport=${transportLabel(transport)} " +
                "source=${device.source} sonyAd=${device.sonyAd?.summary.orEmpty()}"
        )
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            remote.connectGatt(context, false, gattCallback, transport)
        } else {
            remote.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        closeGatt(notify = true)
    }

    private fun closeGatt(notify: Boolean) {
        closeSpp(notify = false)
        writeQueue.clear()
        pendingNotifyEndpoints.clear()
        writing = false
        toAcc = null
        fromAcc = null
        gattEndpoints.clear()
        handshakeStep = HandshakeStep.Idle
        determineMtuNotificationEnabled = false
        unsupportedProbe = null
        gatt?.disconnect()
        gatt?.close()
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

    private fun writeToChannel(channel: TandemChannel, bytes: ByteArray) {
        if (channel !in gattEndpoints && sppTransport == null) {
            listener.onBluetoothUnavailable("Channel $channel is not available (available: ${availableChannels()})")
            return
        }
        writeQueue.add(PendingTandemWrite(channel, bytes))
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun shouldUseSpp(device: DiscoveredSonyDevice, remote: BluetoothDevice): Boolean {
        if (device.sonyAd?.leGattControlFlag == true) return false
        val hasMdrSppUuid = remote.uuids.orEmpty().any { it.uuid == MDR_SPP_MARKER_UUID }
        val classicCandidate = remote.type == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
            remote.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
            device.source.startsWith("connected-a2dp") ||
            device.source.startsWith("connected-headset")
        return hasMdrSppUuid || classicCandidate || device.sonyAd?.androidLine?.contains("SPP") == true
    }

    @SuppressLint("MissingPermission")
    private fun connectSpp(selected: DiscoveredSonyDevice, selectedRemote: BluetoothDevice) {
        val classicRemote = resolveSppRemoteDevice(selected, selectedRemote)
        if (classicRemote == null) {
            listener.onBluetoothUnavailable("No paired classic Sony SPP device matches ${selected.name}")
            return
        }
        connectedDevice = selected.copy(
            name = safeDeviceName(classicRemote) ?: selected.name.removePrefix("LE_"),
            address = classicRemote.address,
            source = "${selected.source}/spp",
            bluetoothType = classicRemote.type,
            isLikelyControlEndpoint = true,
        )
        disconnect()
        connectedDevice = connectedDevice?.copy(address = classicRemote.address)
        Thread {
            try {
                adapter?.cancelDiscovery()
                log(
                    "Connecting SPP to ${classicRemote.address} name=${safeDeviceName(classicRemote).orEmpty()} " +
                        "uuids=${classicRemote.uuids?.joinToString { it.uuid.toString() }.orEmpty()}"
                )
                val spp = createSppSocket(classicRemote)
                val socket = spp.socket
                socket.connect()
                val sppUuid = spp.uuid
                // Publish the connected device BEFORE the read loop starts: the headphone
                // pushes its first frame immediately, and onMessage() requires
                // connectedDevice/connectedProfile to be present (ensureConnectedProfile
                // throws otherwise, killing the SPP thread and the whole probe).
                listener.onConnectionStateChanged(true, connectedDevice)
                sppTransport = SonySppTransport(
                    socket = socket,
                    onPayload = { payload -> listener.onMessage(TandemChannel.SPP_MDR, payload) },
                    onClosed = { reason ->
                        log(reason ?: "SPP transport closed")
                        sppTransport = null
                        listener.onConnectionStateChanged(false, connectedDevice)
                    },
                    log = ::log,
                ).also { it.start() }
                log("SPP connected")
                listener.onConnectionStateChanged(true, connectedDevice)
                listener.onReady(
                    SonyBleConnectionInfo(
                        mtu = SPP_WRITABLE_VALUE_LENGTH,
                        transport = "SPP",
                        channels = setOf(TandemChannel.SPP_MDR),
                        sppUuid = sppUuid,
                    )
                )
            } catch (e: IOException) {
                log("SPP connection failed: ${e.message}")
                closeSpp(notify = true)
                listener.onBluetoothUnavailable("SPP connection failed: ${e.message}")
            } catch (e: SecurityException) {
                log("SPP permission failure: ${e.message}")
                closeSpp(notify = true)
                listener.onBluetoothUnavailable("Bluetooth connect permission failed for SPP")
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun resolveSppRemoteDevice(
        selected: DiscoveredSonyDevice,
        selectedRemote: BluetoothDevice,
    ): BluetoothDevice? {
        if (selectedRemote.type == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
            selectedRemote.type == BluetoothDevice.DEVICE_TYPE_DUAL
        ) {
            return selectedRemote
        }
        val targetName = selected.name.removePrefix("LE_")
        return adapter?.bondedDevices.orEmpty()
            .firstOrNull { device ->
                val name = safeDeviceName(device).orEmpty()
                device.type != BluetoothDevice.DEVICE_TYPE_LE &&
                    (name.equals(targetName, ignoreCase = true) || isSonyCandidate(name))
            }
    }

    @SuppressLint("MissingPermission")
    private fun createSppSocket(device: BluetoothDevice): SppSocketWithUuid {
        val advertised = device.uuids.orEmpty().map { it.uuid }.toSet()
        val candidates = OFFICIAL_SPP_UUIDS.filter { it in advertised } +
            OFFICIAL_SPP_UUIDS.filter { it !in advertised }
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
        throw lastError ?: IOException("No SPP UUID could create a socket")
    }

    private fun closeSpp(notify: Boolean) {
        val transport = sppTransport
        sppTransport = null
        transport?.close()
        if (notify) {
            listener.onConnectionStateChanged(false, connectedDevice)
        }
    }

    private fun beginTandemHandshake(gatt: BluetoothGatt) {
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
            listener.onBluetoothUnavailable("Failed to read OPTIMAL_MTU")
        }
    }

    private fun handleCharacteristicRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        unsupportedProbe?.let { probe ->
            handleUnsupportedProbeRead(gatt, probe, uuid, value, status)
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            listener.onBluetoothUnavailable("Read $uuid failed: $status")
            return
        }
        val parsed = value.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        when (uuid) {
            SonyGatt.OPTIMAL_MTU -> {
                optimalMtu = parsed
                log("Read $uuid = ${value.hexString()} parsed=$parsed")
                requestLargeMtu(gatt)
                return
            }
            SonyGatt.WRITABLE_VALUE_LENGTH -> {
                writableValueLength = parsed
                log("Read $uuid = ${value.hexString()} parsed=$parsed")
                enableTandemNotifications(gatt)
                return
            }
        }
        log("Read $uuid = ${value.hexString()}")
    }

    private fun handleCharacteristicChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val uuid = characteristic.uuid
        if (uuid == SonyGatt.DETERMINE_MTU) {
            log("Handshake: DETERMINE_MTU notification ${value.hexString()}")
            if (determineMtuNotificationEnabled) {
                gatt?.let(::readWritableValueLength)
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
        listener.onMessage(channel, value)
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

    private fun preferredTransport(device: BluetoothDevice, discovered: DiscoveredSonyDevice): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when (device.type) {
                BluetoothDevice.DEVICE_TYPE_LE -> BluetoothDevice.TRANSPORT_LE
                BluetoothDevice.DEVICE_TYPE_CLASSIC,
                BluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothDevice.TRANSPORT_AUTO
                else -> if (
                    discovered.source == "ble-scan" ||
                    discovered.sonyAd?.androidGattCapable == true ||
                    discovered.sonyAd?.leGattControlFlag == true
                ) {
                    BluetoothDevice.TRANSPORT_LE
                } else {
                    BluetoothDevice.TRANSPORT_AUTO
                }
            }
        } else {
            0
        }

    private fun transportLabel(transport: Int): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when (transport) {
                BluetoothDevice.TRANSPORT_LE -> "LE"
                BluetoothDevice.TRANSPORT_BREDR -> "BREDR"
                BluetoothDevice.TRANSPORT_AUTO -> "AUTO"
                else -> transport.toString()
            }
        } else {
            "DEFAULT"
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
        if (characteristic == null) {
            log("DETERMINE_MTU missing; reading WRITABLE_VALUE_LENGTH directly")
            readWritableValueLength(gatt)
            return
        }
        handshakeStep = HandshakeStep.EnableDetermineMtu
        log("Handshake: enable DETERMINE_MTU notification")
        writeNotificationState(gatt, characteristic, enabled = true)
    }

    private fun readWritableValueLength(gatt: BluetoothGatt) {
        if (determineMtuNotificationEnabled) {
            val determine = gatt.getService(SonyGatt.TANDEM_V2_HPC_SERVICE)
                ?.getCharacteristic(SonyGatt.DETERMINE_MTU)
            if (determine != null) {
                handshakeStep = HandshakeStep.DisableDetermineMtu
                determineMtuNotificationEnabled = false
                log("Handshake: disable DETERMINE_MTU notification")
                writeNotificationState(gatt, determine, enabled = false)
                return
            }
        }
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
            listener.onBluetoothUnavailable("Failed to read WRITABLE_VALUE_LENGTH")
        }
    }

    private fun enableTandemNotifications(gatt: BluetoothGatt) {
        handshakeStep = HandshakeStep.EnableTandemNotifications
        pendingNotifyEndpoints.clear()
        val orderedChannels = TandemGattRouting.notificationOrder(gattEndpoints.keys)
        gattEndpoints.values
            .sortedBy { endpoint -> orderedChannels.indexOf(endpoint.channel) }
            .forEach { pendingNotifyEndpoints.addLast(it) }
        enableNextTandemNotification(gatt)
    }

    private fun enableNextTandemNotification(gatt: BluetoothGatt) {
        val endpoint = pendingNotifyEndpoints.removeFirstOrNull()
        if (endpoint == null) {
            handshakeStep = HandshakeStep.Ready
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
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            enableNextTandemNotification(gatt)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun writeNotificationState(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ) {
        gatt.setCharacteristicNotification(characteristic, enabled)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            if (enabled) {
                readWritableValueLength(gatt)
            } else {
                readWritableValueLength(gatt)
            }
            return
        }
        val value = if (enabled) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun drainWriteQueue() {
        val gatt = gatt ?: return
        if (writing) return
        val pending = writeQueue.poll() ?: return
        val endpoint = gattEndpoints[pending.channel]
        if (endpoint == null) {
            listener.onBluetoothUnavailable("Channel ${pending.channel} is not available (available: ${availableChannels()})")
            drainWriteQueue()
            return
        }
        val characteristic = endpoint.toAcc
        writing = true
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                pending.bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = pending.bytes
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!accepted) {
            writing = false
            listener.onBluetoothUnavailable("Failed to enqueue BLE write")
        }
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
        if (!isSonyCandidate(name)) return

        listener.onDeviceFound(
            DiscoveredSonyDevice(
                name = name ?: "Sony audio device",
                address = device.address,
                rssi = rssi,
                source = source,
                bluetoothType = device.type,
                advertisedServices = device.uuids?.map { it.uuid.toString() }.orEmpty(),
                isLikelyControlEndpoint = device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    device.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                    name?.startsWith("LE_", ignoreCase = true) == true,
            )
        )
    }

    private fun isSonyCandidate(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase().orEmpty()
        return normalized.contains("sony") ||
            normalized.contains("linkbuds") ||
            normalized.startsWith("wf-") ||
            normalized.startsWith("wh-") ||
            normalized.startsWith("wi-") ||
            normalized.startsWith("xba-") ||
            normalized.startsWith("mdr-")
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String? =
        if (hasConnectPermission()) device.name else null

    private fun log(message: String) {
        Log.i(LOG_TAG, message)
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
        private const val LOG_TAG = "OpenBuds"
        private const val AD_TYPE_MANUFACTURER_SPECIFIC = 0xFF
        private const val SONY_AUDIO_MANUFACTURER_ID = 0x012D
        private val SONY_AUDIO_HEAD = byteArrayOf(0x04, 0x00)
        private const val SONY_CHUNK_BASIC_INFORMATION = 0x00
        private const val SONY_CHUNK_TANDEM_TRANSMITTING_LINE = 0x03
        private const val SONY_CHUNK_CLASSIC_BLUETOOTH_HASH = 0x05
        private const val SPP_WRITABLE_VALUE_LENGTH = 1024
        private val MDR_SPP_MARKER_UUID: UUID =
            UUID.fromString("443cce33-e85d-4b85-8d53-6e319ede53ae")
        private val OFFICIAL_SPP_UUIDS = listOf(
            UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2"),
            UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba"),
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
