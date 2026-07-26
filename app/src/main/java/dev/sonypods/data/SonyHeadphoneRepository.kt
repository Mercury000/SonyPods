package dev.sonypods.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.sonypods.ble.DiscoveredSonyDevice
import dev.sonypods.ble.SonyBleClient
import dev.sonypods.ble.SonyBleClientListener
import dev.sonypods.ble.SonyBleConnectionInfo
import dev.sonypods.ble.UnsupportedEndpointDiagnostics
import dev.sonypods.headphones.ConnectedHeadphoneProfile
import dev.sonypods.headphones.EqUiCapability
import dev.sonypods.headphones.EqWriteContext
import dev.sonypods.headphones.eqUiCapability
import dev.sonypods.headphones.HeadphoneAdapterRegistry
import dev.sonypods.headphones.HeadphoneCommand
import dev.sonypods.headphones.HeadphoneFeature
import dev.sonypods.headphones.HeadphoneFormFactor
import dev.sonypods.headphones.HeadphoneTransport
import dev.sonypods.headphones.PlaybackDispatchStrategy
import dev.sonypods.headphones.TandemChannel
import dev.sonypods.media.MediaPlaybackController
import dev.sonypods.protocol.AmbientSoundMode
import dev.sonypods.protocol.DeviceInfoType
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.PlaybackControl
import dev.sonypods.protocol.PlaybackStatus
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.QuickAccessKey
import dev.sonypods.protocol.hexString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val EQ_BAND_STEP_CENTER = 10
private const val EQ_CLEAR_BASS_RAW_INDEX = 0
private const val EQ_FIRST_FREQUENCY_RAW_INDEX = 1
private const val PLAYBACK_STALE_RESPONSE_WINDOW_MS = 2_500L
private const val PLAYBACK_REFRESH_AFTER_COMMAND_MS = 1_200L
private const val PLAYBACK_RECONCILE_AFTER_COMMAND_MS = 2_800L
private const val PLAYBACK_HEARTBEAT_INTERVAL_MS = 30_000L

private data class PendingPlaybackStatus(
    val expected: PlaybackStatus,
    val ignoreOppositeUntilMs: Long,
)

data class DeviceInfoState(
    val modelName: String? = null,
    val firmwareVersion: String? = null,
    val seriesAndColor: String? = null,
    val modelColor: String? = null,
    val modelImageUrl: String? = null,
    val modelImageSourceColor: String? = null,
    val protocolReady: Boolean = false,
)

data class BatteryState(
    val single: Int? = null,
    val left: Int? = null,
    val right: Int? = null,
    val cradle: Int? = null,
    val raw: List<Int> = emptyList(),
)

data class NoiseControlState(
    val controlMode: NoiseControlMode? = null,
    val noiseCancellingEnabled: Boolean? = null,
    val ambientSoundEnabled: Boolean? = null,
    val ambientLevel: Int? = null,
    val ambientVoiceMode: Boolean = false,
    val raw: List<Int> = emptyList(),
)

data class EqState(
    val enabled: Boolean? = null,
    val preset: EqPresetId? = null,
    val presetType: EqEbbInquiredType = EqEbbInquiredType.PRESET_EQ,
    val clearBass: Int? = null,
    val bandSteps: List<Int> = emptyList(),
    val rawBandSteps: List<Int> = emptyList(),
    val bandStepCenter: Int = EQ_BAND_STEP_CENTER,
    val usesCustomEqPayload: Boolean = false,
    val raw: List<Int> = emptyList(),
)

data class LeaState(
    val enabled: String? = null,
    val streamingStatusL: String? = null,
    val streamingStatusR: String? = null,
    val pairedHistory: String? = null,
    val raw: List<Int> = emptyList(),
)

data class QuickAccessState(
    val lrKeyFunction: String? = null,
    val ncAmbKeyFunction: String? = null,
    val raw: List<Int> = emptyList(),
)

data class WearingState(
    val status: String? = null,
    val result: String? = null,
    val raw: List<Int> = emptyList(),
)

data class EndpointDiagnosticState(
    val reason: String,
    val serviceLabels: List<String> = emptyList(),
    val leAudioSwitchCompatibility: Int? = null,
    val friendlyName: String? = null,
    val publicAddress: String? = null,
    val rawReads: Map<String, String> = emptyMap(),
)

data class Table2DiagnosticState(
    val channel: String,
    val family: String,
    val command: Int,
    val inquiredType: Int?,
    val values: List<Int>,
    val rawHex: String,
)

data class FeatureStatus(
    val title: String,
    val description: String,
    val implemented: Boolean,
)

data class SonyHeadphoneUiState(
    val scanState: String = "Idle",
    val isScanning: Boolean = false,
    val permissionIssue: String? = null,
    val discoveredDevices: List<DiscoveredSonyDevice> = emptyList(),
    val knownDevices: List<DiscoveredSonyDevice> = emptyList(),
    val connectedDevice: DiscoveredSonyDevice? = null,
    val connectionInfo: SonyBleConnectionInfo? = null,
    val connectedProfile: ConnectedHeadphoneProfile? = null,
    val deviceInfo: DeviceInfoState = DeviceInfoState(),
    val batteryState: BatteryState = BatteryState(),
    val noiseControlState: NoiseControlState = NoiseControlState(),
    val eqState: EqState = EqState(),
    val eqUiCapability: EqUiCapability? = null,
    val leaState: LeaState = LeaState(),
    val quickAccessState: QuickAccessState = QuickAccessState(),
    val wearingState: WearingState = WearingState(),
    val playbackStatus: PlaybackStatus = PlaybackStatus.UNKNOWN,
    val endpointDiagnostic: EndpointDiagnosticState? = null,
    val table2Diagnostic: Table2DiagnosticState? = null,
    val supportedFeatures: List<FeatureStatus> = featureStatusesFor(null),
    val debugLogs: List<String> = emptyList(),
    val debugLogging: Boolean = true,
    val autoReconnect: Boolean = false,
    val strictSonyScanFilter: Boolean = false,
    val preferredProtocol: String = "Sony Tandem",
)

/**
 * @param resourceContext context used for our own resources (the model image catalog
 *   asset). When hosted inside the bluetooth process this is the module package context,
 *   because the host app's assets do not contain our files.
 * @param systemContext context used for the bluetooth/audio system services. Defaults to
 *   [resourceContext], which is correct when the repository runs in the module app.
 */
class SonyHeadphoneRepository private constructor(
    resourceContext: Context,
    systemContext: Context = resourceContext,
) : SonyBleClientListener {
    private val appContext = systemContext.applicationContext ?: systemContext
    private val client = SonyBleClient(appContext, this)
    private val mediaController = MediaPlaybackController(appContext)
    private val modelImageCatalog = SonyModelImageCatalog(resourceContext.applicationContext ?: resourceContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackRefreshRunnable = Runnable { refreshPlaybackStatusAfterCommand() }
    private val playbackReconcileRunnable = Runnable { refreshPlaybackStatusAfterCommand() }
    private val playbackHeartbeatRunnable = Runnable { sendPlaybackHeartbeat() }
    private val _state = MutableStateFlow(SonyHeadphoneUiState())
    private var pendingPlaybackStatus: PendingPlaybackStatus? = null
    private var playbackHeartbeatActive = false

    val state: StateFlow<SonyHeadphoneUiState> = _state.asStateFlow()

    fun startScan() {
        _state.update {
            it.copy(
                permissionIssue = null,
                discoveredDevices = emptyList(),
                endpointDiagnostic = null,
                table2Diagnostic = null,
            )
        }
        val strictFilter = _state.value.strictSonyScanFilter
        appendLog("Scan requested strictSonyScanFilter=$strictFilter")
        client.startScan(strictFilter)
    }

    fun stopScan() {
        client.stopScan()
    }

    fun connect(device: DiscoveredSonyDevice) {
        if (!device.isLikelyControlEndpoint && !device.source.startsWith("ble-scan")) {
            appendLog(
                "Classic endpoint ${device.name} selected; trying direct GATT first. " +
                    "LE_ endpoint remains available for diagnostics."
            )
        }
        appendLog("Connect requested: ${device.name} (${device.address})")
        _state.update { it.copy(endpointDiagnostic = null, table2Diagnostic = null, permissionIssue = null) }
        client.connect(device)
    }

    fun connect(address: String, name: String = "Sony audio device") {
        appendLog("Debug connect requested: $name ($address)")
        _state.update { it.copy(endpointDiagnostic = null, table2Diagnostic = null, permissionIssue = null) }
        client.connect(
            DiscoveredSonyDevice(
                name = name,
                address = address,
                rssi = 0,
                source = "debug-adb",
                isLikelyControlEndpoint = true,
            )
        )
    }

    fun disconnect() {
        appendLog("Disconnect requested")
        client.disconnect()
    }

    fun refreshBasics() {
        if (!_state.value.deviceInfo.protocolReady) {
            if (_state.value.connectedDevice != null && _state.value.endpointDiagnostic != null) {
                appendLog("Refresh requested for unsupported endpoint; rerunning GATT diagnostics")
                client.refreshUnsupportedEndpointProbe()
            } else {
                onBluetoothUnavailable("Sony Tandem channel is not ready; cannot refresh device state.")
            }
            return
        }
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildRefreshCommands(profile)
            .forEach(::sendCommand)
        updatePlaybackStatusFromAudioManager()
    }

    fun setNoiseControlMode(mode: NoiseControlMode) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change noise control.")
            return
        }
        if (!canWrite(HeadphoneFeature.NOISE_CONTROL)) {
            appendLog("Noise control write is disabled for current profile")
            return
        }
        val current = _state.value.noiseControlState
        val level = current.ambientLevel?.takeIf { it > 0 }?.coerceIn(1, 20) ?: 10
        val ambientMode = if (current.ambientVoiceMode) AmbientSoundMode.VOICE else AmbientSoundMode.NORMAL
        _state.update {
            it.copy(noiseControlState = it.noiseControlState.forMode(mode).copy(ambientLevel = level))
        }
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetNoiseControlModeCommands(profile, mode, level, ambientMode)
            .forEach(::sendCommand)
        refreshNoiseControlStateAfterWrite(profile)
    }

    fun setNoiseCancelling(enabled: Boolean) =
        setNoiseControlMode(if (enabled) NoiseControlMode.NOISE_CANCELLING else NoiseControlMode.OFF)

    fun setAmbientSound(enabled: Boolean) =
        setNoiseControlMode(if (enabled) NoiseControlMode.AMBIENT_SOUND else NoiseControlMode.OFF)

    fun setAmbientLevel(level: Int) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change ambient level.")
            return
        }
        if (!canWrite(HeadphoneFeature.AMBIENT_LEVEL)) {
            appendLog("Ambient level write is disabled for current profile")
            return
        }
        val current = _state.value.noiseControlState
        val mode = if (current.ambientVoiceMode) AmbientSoundMode.VOICE else AmbientSoundMode.NORMAL
        val clamped = level.coerceIn(1, 20)
        _state.update {
            it.copy(noiseControlState = it.noiseControlState.forMode(NoiseControlMode.AMBIENT_SOUND).copy(ambientLevel = clamped))
        }
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            clamped,
            mode,
        ).forEach(::sendCommand)
        refreshNoiseControlStateAfterWrite(profile)
    }

    fun setAmbientVoiceMode(enabled: Boolean) {
        if (!canWrite(HeadphoneFeature.AMBIENT_VOICE_MODE)) {
            appendLog("Ambient voice mode write is disabled for current profile")
            return
        }
        if (!_state.value.deviceInfo.protocolReady) {
            _state.update {
                it.copy(noiseControlState = it.noiseControlState.copy(ambientVoiceMode = enabled))
            }
            return
        }
        val current = _state.value.noiseControlState
        val level = current.ambientLevel?.takeIf { it > 0 }?.coerceIn(1, 20) ?: 10
        val ambientMode = if (enabled) AmbientSoundMode.VOICE else AmbientSoundMode.NORMAL
        _state.update {
            it.copy(noiseControlState = it.noiseControlState.forMode(NoiseControlMode.AMBIENT_SOUND).copy(
                ambientLevel = level,
                ambientVoiceMode = enabled,
            ))
        }
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            level,
            ambientMode,
        ).forEach(::sendCommand)
        refreshNoiseControlStateAfterWrite(profile)
    }

    fun setEqPreset(preset: EqPresetId) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change EQ preset.")
            return
        }
        if (!canWrite(HeadphoneFeature.EQ)) {
            appendLog("EQ preset write is disabled for current profile")
            return
        }
        val profile = ensureConnectedProfile()
        val context = currentEqWriteContext()
        _state.update {
            it.copy(eqState = it.eqState.copy(preset = preset, enabled = preset != EqPresetId.OFF))
        }
        HeadphoneAdapterRegistry.buildSetEqPresetCommands(profile, preset, context)
            .forEach(::sendCommand)
        refreshEqState()
    }

    fun setClearBass(level: Int) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change Clear Bass.")
            return
        }
        if (!canWrite(HeadphoneFeature.CLEAR_BASS)) {
            appendLog("Clear Bass write is disabled for current profile")
            return
        }
        val clamped = level.coerceIn(-10, 10)
        val profile = ensureConnectedProfile()
        val context = currentEqWriteContext()
        _state.update {
            it.copy(eqState = it.eqState.withClearBassSynced(clamped))
        }
        HeadphoneAdapterRegistry.buildSetClearBassCommands(profile, clamped, context)
            .forEach(::sendCommand)
        refreshEqState()
    }

    fun setCustomEqBand(index: Int, level: Int) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change custom EQ.")
            return
        }
        if (!canWrite(HeadphoneFeature.EQ)) {
            appendLog("Custom EQ write is disabled for current profile")
            return
        }
        val eq = _state.value.eqState
        val rawIndex = index + EQ_FIRST_FREQUENCY_RAW_INDEX
        if (index !in eq.bandSteps.indices || rawIndex !in eq.rawBandSteps.indices) {
            appendLog("CUSTOM EQ band change ignored index=$index bands=${eq.bandSteps.size} raw=${eq.rawBandSteps.size}")
            return
        }
        val targetPreset = eq.bandEditPreset()
        val rawSteps = eq.rawBandSteps.toMutableList()
        rawSteps[rawIndex] = displayEqStepToRaw(level)
        updateEqBands(rawSteps, targetPreset)
        sendEqBandSteps("SET CUSTOM EQ band ${index + 1}=${level.coerceIn(-10, 10)}", rawSteps, targetPreset)
        refreshEqState()
    }

    fun runDebugAction(action: String, rawHex: String? = null) {
        appendLog("Debug action requested: $action raw=${rawHex.orEmpty()}")
        when (action.lowercase()) {
            "nc" -> setNoiseControlMode(NoiseControlMode.NOISE_CANCELLING)
            "ambient" -> setNoiseControlMode(NoiseControlMode.AMBIENT_SOUND)
            "off" -> setNoiseControlMode(NoiseControlMode.OFF)
            "eq_bass" -> setEqPreset(EqPresetId.BASS)
            "eq_bright" -> setEqPreset(EqPresetId.BRIGHT)
            "clear_bass" -> setClearBass((state.value.eqState.clearBass ?: 0) + 1)
            "eq_band" -> setCustomEqBand(0, (state.value.eqState.bandSteps.firstOrNull() ?: 0) + 1)
            "battery_tandem" -> HeadphoneAdapterRegistry.buildRefreshBatteryCommands(ensureConnectedProfile())
                .firstOrNull()
                ?.let { sendCommandIfReady(it.copy(label = "DEBUG ${it.label}")) }
                ?: appendLog("Debug battery action ignored: current profile has no battery query")
            "raw" -> rawHex?.hexToByteArrayOrNull()?.let {
                val channel = _state.value.connectedProfile?.defaultResponseChannel()
                    ?: client.availableChannels().firstOrNull()
                    ?: TandemChannel.SPP_MDR
                sendCommandIfReady(HeadphoneCommand("DEBUG RAW", it, channel))
            }
                ?: appendLog("Debug raw action ignored: invalid hex")
            else -> appendLog("Unknown debug action: $action")
        }
    }

    fun playbackPrevious() {
        if (!canWrite(HeadphoneFeature.PLAYBACK_CONTROL)) return
        clearPendingPlaybackTransition()
        dispatchPlayback(PlaybackControl.TRACK_DOWN, mediaFallback = { mediaController.previous() })
        schedulePlaybackStateRefresh()
    }

    fun playbackPlayPause() {
        if (!canWrite(HeadphoneFeature.PLAYBACK_CONTROL)) return
        val wasPlaying = _state.value.playbackStatus == PlaybackStatus.PLAYING
        val control = if (wasPlaying) PlaybackControl.PAUSE else PlaybackControl.PLAY
        beginPlaybackStatusTransition(
            if (control == PlaybackControl.PAUSE) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
        )
        dispatchPlayback(control, mediaFallback = { mediaController.playPause() })
        schedulePlaybackStateRefresh()
        schedulePlaybackStateReconcile()
    }

    fun playbackNext() {
        if (!canWrite(HeadphoneFeature.PLAYBACK_CONTROL)) return
        clearPendingPlaybackTransition()
        dispatchPlayback(PlaybackControl.TRACK_UP, mediaFallback = { mediaController.next() })
        schedulePlaybackStateRefresh()
    }

    fun setDebugLogging(enabled: Boolean) {
        _state.update { it.copy(debugLogging = enabled) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        _state.update { it.copy(autoReconnect = enabled) }
    }

    fun setStrictSonyScanFilter(enabled: Boolean) {
        _state.update { it.copy(strictSonyScanFilter = enabled) }
    }

    private fun sendCommand(command: HeadphoneCommand) {
        appendLog("${command.label} [${command.channel}] -> ${command.bytes.hexString()}")
        client.sendToChannel(command.channel, command.bytes)
    }

    private fun sendCommandIfReady(command: HeadphoneCommand) {
        if (_state.value.deviceInfo.protocolReady) {
            sendCommand(command)
        }
    }

    private fun dispatchPlayback(control: PlaybackControl, mediaFallback: () -> Unit) {
        val profile = ensureConnectedProfile()
        val commands = if (_state.value.deviceInfo.protocolReady) {
            HeadphoneAdapterRegistry.buildPlaybackCommands(profile, control)
        } else {
            emptyList()
        }
        if (commands.isNotEmpty() && profile.playbackDispatchStrategy != PlaybackDispatchStrategy.ANDROID_MEDIA_FALLBACK) {
            appendLog("PLAYBACK ${control.name} via Tandem")
            commands.forEach(::sendCommand)
            return
        }
        if (profile.playbackDispatchStrategy != PlaybackDispatchStrategy.TANDEM_ONLY) {
            appendLog("PLAYBACK ${control.name} via Android media fallback")
            mediaFallback()
        }
    }

    private fun currentEqWriteContext(): EqWriteContext {
        val eqState = _state.value.eqState
        return EqWriteContext(rawBandSteps = eqState.rawBandSteps, preset = eqState.preset)
    }

    override fun onBluetoothUnavailable(reason: String) {
        _state.update {
            it.copy(permissionIssue = reason, scanState = "Blocked", isScanning = false)
        }
        appendLog(reason)
    }

    override fun onUnsupportedEndpoint(diagnostics: UnsupportedEndpointDiagnostics) {
        _state.update {
            it.copy(
                permissionIssue = diagnostics.reason,
                scanState = "Unsupported endpoint",
                isScanning = false,
                connectionInfo = null,
                connectedProfile = null,
                deviceInfo = DeviceInfoState(),
                batteryState = BatteryState(),
                noiseControlState = NoiseControlState(),
                eqState = EqState(),
                eqUiCapability = null,
                playbackStatus = PlaybackStatus.UNKNOWN,
                endpointDiagnostic = EndpointDiagnosticState(
                    reason = diagnostics.reason,
                    serviceLabels = diagnostics.serviceLabels,
                    leAudioSwitchCompatibility = diagnostics.leAudioSwitchCompatibility,
                    friendlyName = diagnostics.friendlyName,
                    publicAddress = diagnostics.publicAddress,
                    rawReads = diagnostics.rawReads,
                ),
                table2Diagnostic = null,
                supportedFeatures = featureStatusesFor(null),
            )
        }
        appendLog(diagnostics.reason)
    }

    override fun onDeviceFound(device: DiscoveredSonyDevice) {
        _state.update { current ->
            val index = current.discoveredDevices.indexOfFirst { it.address == device.address }
            val nextKnown = current.knownDevices.mergeKnownDevice(device)
            if (index >= 0) {
                val updated = current.discoveredDevices.toMutableList()
                updated[index] = mergeDevice(updated[index], device)
                current.copy(
                    discoveredDevices = updated.sortedByConnectionPriority(),
                    knownDevices = nextKnown,
                )
            } else {
                current.copy(
                    discoveredDevices = (current.discoveredDevices + device).sortedByConnectionPriority(),
                    knownDevices = nextKnown,
                )
            }
        }
    }

    override fun onScanStateChanged(scanning: Boolean) {
        _state.update {
            it.copy(isScanning = scanning, scanState = if (scanning) "Scanning" else "Idle")
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, device: DiscoveredSonyDevice?) {
        if (!connected) {
            clearPendingPlaybackTransition()
            mainHandler.removeCallbacks(playbackRefreshRunnable)
            stopPlaybackHeartbeat()
        }
        _state.update {
            val deviceInfo = if (connected) {
                it.deviceInfo.withResolvedModelImage(device)
            } else {
                DeviceInfoState()
            }
            val profile = if (connected && device != null) {
                HeadphoneAdapterRegistry.resolve(device, deviceInfo.modelName)
            } else {
                null
            }
            val resolvedDeviceInfo = deviceInfo.withProfileFallback(profile)
            val systemBattery = if (connected && device != null &&
                profile?.capabilities?.formFactor == HeadphoneFormFactor.HEADSET
            ) {
                readSystemBatteryLevel(device.address)
            } else {
                null
            }
            it.copy(
                connectedDevice = if (connected) device else null,
                knownDevices = if (connected && device != null) {
                    it.knownDevices.mergeKnownDevice(device)
                } else {
                    it.knownDevices
                },
                connectionInfo = if (connected) it.connectionInfo else null,
                connectedProfile = profile,
                deviceInfo = resolvedDeviceInfo,
                batteryState = if (connected) {
                    systemBattery?.let { level -> BatteryState(single = level, raw = listOf(level)) } ?: it.batteryState
                } else {
                    BatteryState()
                },
                noiseControlState = if (connected) it.noiseControlState else NoiseControlState(),
                eqState = if (connected) it.eqState else EqState(),
                eqUiCapability = if (connected) profile?.eqUiCapability else null,
                playbackStatus = if (connected) it.playbackStatus else PlaybackStatus.UNKNOWN,
                endpointDiagnostic = if (connected) it.endpointDiagnostic else null,
                table2Diagnostic = if (connected) it.table2Diagnostic else null,
                permissionIssue = if (connected) it.permissionIssue else null,
                scanState = if (connected) "Connected" else "Idle",
                supportedFeatures = featureStatusesFor(profile),
            )
        }
    }

    override fun onReady(info: SonyBleConnectionInfo) {
        _state.update {
            val profile = (it.connectedProfile ?: it.connectedDevice?.let { device ->
                HeadphoneAdapterRegistry.resolve(device, it.deviceInfo.modelName)
            })?.copy(transport = info.transport.toHeadphoneTransport())
            it.copy(
                connectionInfo = info,
                connectedProfile = profile,
                eqUiCapability = profile?.eqUiCapability,
                deviceInfo = it.deviceInfo.copy(protocolReady = true),
                endpointDiagnostic = null,
                table2Diagnostic = null,
                permissionIssue = null,
                supportedFeatures = featureStatusesFor(profile),
            )
        }
        appendLog("Tandem channel ready: transport=${info.transport}, mtu=${info.mtu}, writable=${info.writableValueLength}")
        refreshBasics()
    }

    override fun onMessage(channel: TandemChannel, raw: ByteArray) {
        appendLog("RX [$channel] ${raw.hexString()}")
        val profile = _state.value.connectedProfile ?: ensureConnectedProfile()
        when (val parsed = HeadphoneAdapterRegistry.parse(profile, channel, raw)) {
            is ParsedTandemResponse.DeviceInfo -> applyDeviceInfo(parsed)
            is ParsedTandemResponse.CommonStatus -> applyCommonStatus(parsed)
            is ParsedTandemResponse.Battery -> applyBattery(parsed)
            is ParsedTandemResponse.EqEbb -> applyEqEbb(parsed)
            is ParsedTandemResponse.EqEbbExtendedInfo -> applyEqEbbExtendedInfo(parsed)
            is ParsedTandemResponse.NoiseControl -> applyNoise(parsed)
            is ParsedTandemResponse.PlaybackAck -> applyPlayback(parsed)
            is ParsedTandemResponse.LeaStatus -> applyLeaStatus(parsed)
            is ParsedTandemResponse.LeaPairedHistoryStatus -> applyLeaPairedHistory(parsed)
            is ParsedTandemResponse.QuickAccess -> applyQuickAccess(parsed)
            is ParsedTandemResponse.WearingStatus -> applyWearingStatus(parsed)
            is ParsedTandemResponse.Unknown -> applyKnownOrUnknown(parsed)
            is ParsedTandemResponse.Table2Common -> applyTable2Diagnostic(channel, parsed)
            is ParsedTandemResponse.Table2Generic -> applyTable2Diagnostic(channel, parsed)
        }
    }

    override fun onLog(message: String) {
        appendLog(message, writeLogcat = false)
    }

    private fun applyDeviceInfo(response: ParsedTandemResponse.DeviceInfo) {
        appendLog("Device info ${response.type} text=${response.text} raw=${response.raw.hexString()}")
        _state.update { current ->
            val info = current.deviceInfo
            val updatedInfo = when (response.type) {
                DeviceInfoType.MODEL_NAME -> info.copy(modelName = response.text ?: info.modelName)
                DeviceInfoType.FW_VERSION -> info.copy(firmwareVersion = response.text ?: info.firmwareVersion)
                DeviceInfoType.SERIES_AND_COLOR_INFO -> {
                    val seriesAndColor = response.text ?: info.seriesAndColor
                    info.copy(
                        seriesAndColor = seriesAndColor,
                        modelColor = parseModelColor(seriesAndColor) ?: info.modelColor,
                    )
                }
                else -> info
            }.withResolvedModelImage(current.connectedDevice)
                .withProfileFallback(current.connectedProfile)
            val profile = current.connectedDevice?.let { device ->
                HeadphoneAdapterRegistry.resolve(device, updatedInfo.modelName)
                    .copy(transport = current.connectionInfo?.transport.toHeadphoneTransport())
            } ?: current.connectedProfile
            current.copy(
                deviceInfo = updatedInfo,
                connectedProfile = profile,
                supportedFeatures = featureStatusesFor(profile),
            )
        }
    }

    private fun applyCommonStatus(response: ParsedTandemResponse.CommonStatus) {
        appendLog("Common status ${response.type} text=${response.text} values=${response.values} raw=${response.raw.hexString()}")
        if (response.type != dev.sonypods.protocol.CommonInquiredType.DISPLAY_FW_VERSION) return
        _state.update { current ->
            current.copy(
                deviceInfo = current.deviceInfo.copy(
                    firmwareVersion = response.text ?: current.deviceInfo.firmwareVersion,
                ).withResolvedModelImage(current.connectedDevice).withProfileFallback(current.connectedProfile)
            )
        }
    }

    private fun DeviceInfoState.withResolvedModelImage(device: DiscoveredSonyDevice?): DeviceInfoState {
        val preferredModelName = modelName ?: device?.name?.removePrefix("LE_")
        val match = modelImageCatalog.resolve(preferredModelName, modelColor ?: parseModelColor(seriesAndColor))
        return copy(
            modelImageUrl = match?.imageUrl,
            modelImageSourceColor = match?.sourceColor,
            modelColor = modelColor ?: match?.modelColor ?: parseModelColor(seriesAndColor),
        )
    }

    private fun DeviceInfoState.withProfileFallback(profile: ConnectedHeadphoneProfile?): DeviceInfoState =
        if (profile == null) {
            this
        } else {
            copy(
                modelName = modelName ?: profile.modelName,
                seriesAndColor = seriesAndColor ?: profile.series?.let { "$it / ${modelColor ?: "Default"}" },
                modelColor = modelColor ?: "Default",
            )
        }

    private fun applyBattery(response: ParsedTandemResponse.Battery) {
        _state.update { current ->
            val battery = current.batteryState
            current.copy(
                batteryState = when (response.kind) {
                    PowerInquiredType.BATTERY -> battery.copy(
                        single = response.values.firstOrNull(),
                        raw = response.values,
                    )
                    PowerInquiredType.LEFT_RIGHT_BATTERY -> battery.copy(
                        left = response.values.getOrNull(0),
                        right = response.values.getOrNull(1),
                        raw = response.values,
                    )
                    PowerInquiredType.CRADLE_BATTERY -> battery.copy(
                        cradle = response.values.firstOrNull(),
                        raw = response.values,
                    )
                    else -> battery.copy(raw = response.values)
                }
            )
        }
    }

    private fun applyEqEbb(response: ParsedTandemResponse.EqEbb) {
        appendLog(
            "EQ/EBB notification type=${response.type} enabled=${response.enabled} " +
                "preset=${response.preset} clearBass=${response.clearBass} bands=${response.bandSteps} values=${response.values}"
        )
        _state.update { current ->
            val hasEqBands = response.bandSteps.isNotEmpty() &&
                response.type in setOf(
                    EqEbbInquiredType.PRESET_EQ,
                    EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE,
                    EqEbbInquiredType.PRESET_EQ_AND_ERRORCODE,
                    EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE,
                    EqEbbInquiredType.CUSTOM_EQ,
                    EqEbbInquiredType.EBB,
                )
            val displayedBands = if (hasEqBands) {
                displayEqBands(response.bandSteps)
            } else {
                current.eqState.bandSteps
            }
            val clearBassFromEq = if (hasEqBands && response.bandSteps.size > EQ_CLEAR_BASS_RAW_INDEX) {
                displayEqStep(response.bandSteps[EQ_CLEAR_BASS_RAW_INDEX])
            } else {
                null
            }
            current.copy(
                eqState = current.eqState.copy(
                    enabled = response.enabled ?: current.eqState.enabled,
                    preset = response.preset ?: current.eqState.preset,
                    presetType = when (response.type) {
                        EqEbbInquiredType.PRESET_EQ,
                        EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE,
                        EqEbbInquiredType.PRESET_EQ_AND_ERRORCODE,
                        EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE,
                        EqEbbInquiredType.EBB -> response.type
                        else -> current.eqState.presetType
                    },
                    clearBass = clearBassFromEq ?: response.clearBass ?: current.eqState.clearBass,
                    bandSteps = displayedBands,
                    rawBandSteps = if (hasEqBands) response.bandSteps else current.eqState.rawBandSteps,
                    usesCustomEqPayload = if (response.type == EqEbbInquiredType.CUSTOM_EQ) true else current.eqState.usesCustomEqPayload,
                    raw = response.values,
                )
            )
        }
    }

    private fun applyEqEbbExtendedInfo(response: ParsedTandemResponse.EqEbbExtendedInfo) {
        appendLog(
            "EQ/EBB extended type=${response.type} bands=${response.bands} values=${response.values}"
        )
    }

    private fun sendEqBandSteps(label: String, rawSteps: List<Int>, preset: EqPresetId?) {
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetEqBandCommands(profile, rawSteps, preset, currentEqWriteContext())
            .forEach { sendCommand(it.copy(label = label)) }
    }

    private fun refreshNoiseControlState() {
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildRefreshNoiseControlCommands(profile)
            .forEach(::sendCommand)
    }

    private fun refreshNoiseControlStateAfterWrite(profile: ConnectedHeadphoneProfile) {
        if (profile.capabilities.queryNoiseControlParams) {
            refreshNoiseControlState()
        } else {
            appendLog("NC/ASM write sent; current mode is kept from local selection because this profile only reports capability status")
        }
    }

    private fun refreshEqState() {
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildRefreshEqCommands(profile)
            .forEach(::sendCommand)
    }

    private fun refreshPlaybackState() {
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildRefreshPlaybackCommands(profile)
            .forEach(::sendCommandIfReady)
    }

    private fun refreshPlaybackStatusAfterCommand() {
        if (_state.value.connectedDevice == null) return
        if (shouldUseTandemPlaybackStatus()) {
            refreshPlaybackState()
        } else {
            updatePlaybackStatusFromAudioManager(force = true)
        }
    }

    private fun schedulePlaybackStateRefresh() {
        mainHandler.removeCallbacks(playbackRefreshRunnable)
        mainHandler.postDelayed(playbackRefreshRunnable, PLAYBACK_REFRESH_AFTER_COMMAND_MS)
        // Reset heartbeat timer to avoid querying right after a command-triggered refresh
        if (playbackHeartbeatActive) {
            mainHandler.removeCallbacks(playbackHeartbeatRunnable)
            mainHandler.postDelayed(playbackHeartbeatRunnable, PLAYBACK_HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun schedulePlaybackStateReconcile() {
        mainHandler.removeCallbacks(playbackReconcileRunnable)
        mainHandler.postDelayed(playbackReconcileRunnable, PLAYBACK_RECONCILE_AFTER_COMMAND_MS)
    }

    private fun sendPlaybackHeartbeat() {
        if (!playbackHeartbeatActive) return
        if (_state.value.playbackStatus != PlaybackStatus.PLAYING) {
            stopPlaybackHeartbeat()
            return
        }
        if (!_state.value.deviceInfo.protocolReady) return
        appendLog("Playback heartbeat: GET playback status")
        refreshPlaybackState()
        mainHandler.postDelayed(playbackHeartbeatRunnable, PLAYBACK_HEARTBEAT_INTERVAL_MS)
    }

    private fun startPlaybackHeartbeat() {
        if (playbackHeartbeatActive) return
        if (!shouldUseTandemPlaybackStatus()) return
        playbackHeartbeatActive = true
        appendLog("Playback heartbeat started (interval=${PLAYBACK_HEARTBEAT_INTERVAL_MS}ms)")
        mainHandler.postDelayed(playbackHeartbeatRunnable, PLAYBACK_HEARTBEAT_INTERVAL_MS)
    }

    private fun stopPlaybackHeartbeat() {
        if (!playbackHeartbeatActive) return
        playbackHeartbeatActive = false
        mainHandler.removeCallbacks(playbackHeartbeatRunnable)
        appendLog("Playback heartbeat stopped")
    }

    private fun maybeStartPlaybackHeartbeat(status: PlaybackStatus) {
        when (status) {
            PlaybackStatus.PLAYING -> startPlaybackHeartbeat()
            else -> stopPlaybackHeartbeat()
        }
    }

    private fun clearPendingPlaybackTransition() {
        pendingPlaybackStatus = null
        mainHandler.removeCallbacks(playbackReconcileRunnable)
    }

    private fun beginPlaybackStatusTransition(expected: PlaybackStatus) {
        mainHandler.removeCallbacks(playbackReconcileRunnable)
        pendingPlaybackStatus = PendingPlaybackStatus(
            expected = expected,
            ignoreOppositeUntilMs = SystemClock.elapsedRealtime() + PLAYBACK_STALE_RESPONSE_WINDOW_MS,
        )
        _state.update { it.copy(playbackStatus = expected) }
    }

    private fun updateEqBands(rawSteps: List<Int>, preset: EqPresetId? = _state.value.eqState.preset) {
        _state.update {
            it.copy(
                eqState = it.eqState.copy(
                    preset = preset ?: it.eqState.preset,
                    rawBandSteps = rawSteps,
                    bandSteps = displayEqBands(rawSteps),
                    clearBass = if (rawSteps.size > EQ_CLEAR_BASS_RAW_INDEX) {
                        displayEqStep(rawSteps[EQ_CLEAR_BASS_RAW_INDEX])
                    } else {
                        it.eqState.clearBass
                    },
                )
            )
        }
    }

    private fun applyNoise(response: ParsedTandemResponse.NoiseControl) {
        _state.update { current ->
            current.copy(
                noiseControlState = current.noiseControlState.copy(
                    noiseCancellingEnabled = response.enabled
                        ?: current.noiseControlState.noiseCancellingEnabled,
                    ambientSoundEnabled = response.ambientSoundEnabled
                        ?: current.noiseControlState.ambientSoundEnabled,
                    ambientLevel = response.ambientLevel?.takeIf { it > 0 }
                        ?: current.noiseControlState.ambientLevel,
                    ambientVoiceMode = when (response.ambientMode) {
                        AmbientSoundMode.VOICE -> true
                        AmbientSoundMode.NORMAL -> false
                        null -> current.noiseControlState.ambientVoiceMode
                    },
                    controlMode = response.controlMode
                        ?: current.noiseControlState.controlMode,
                    raw = response.values,
                )
            )
        }
    }

    private fun applyPlayback(response: ParsedTandemResponse.PlaybackAck) {
        val sourceLabel = if (response.isUnsolicited) "NTFY" else "RET"
        appendLog("Playback notification [$sourceLabel] ${response.values} status=${response.status}")
        if (response.status != PlaybackStatus.UNKNOWN) {
            applyPlaybackStatus(response.status, source = "Tandem", isUnsolicited = response.isUnsolicited)
        } else {
            updatePlaybackStatusFromAudioManager()
        }
    }

    private fun applyLeaStatus(response: ParsedTandemResponse.LeaStatus) {
        appendLog("LEA status ${response.type} enabled=${response.enabled} streamingL=${response.streamingStatusL} streamingR=${response.streamingStatusR}")
        _state.update { current ->
            current.copy(leaState = current.leaState.copy(
                enabled = response.enabled?.name ?: current.leaState.enabled,
                streamingStatusL = response.streamingStatusL?.name ?: current.leaState.streamingStatusL,
                streamingStatusR = response.streamingStatusR?.name ?: current.leaState.streamingStatusR,
                raw = response.values,
            ))
        }
    }

    private fun applyLeaPairedHistory(response: ParsedTandemResponse.LeaPairedHistoryStatus) {
        appendLog("LEA paired history ${response.type} pairedHistory=${response.pairedHistory}")
        _state.update { current ->
            current.copy(leaState = current.leaState.copy(
                pairedHistory = response.pairedHistory?.name ?: current.leaState.pairedHistory,
                raw = response.values,
            ))
        }
    }

    private fun applyQuickAccess(response: ParsedTandemResponse.QuickAccess) {
        appendLog("Quick Access key=${response.key} function=${response.function}")
        _state.update { current ->
            val functionName = response.function?.name
            current.copy(quickAccessState = when (response.key) {
                QuickAccessKey.L_R_KEY -> current.quickAccessState.copy(lrKeyFunction = functionName)
                QuickAccessKey.NC_AMB_KEY -> current.quickAccessState.copy(ncAmbKeyFunction = functionName)
                else -> current.quickAccessState
            }.copy(raw = response.values))
        }
    }

    private fun applyWearingStatus(response: ParsedTandemResponse.WearingStatus) {
        appendLog("Wearing status=${response.status} result=${response.result}")
        _state.update { current ->
            current.copy(wearingState = current.wearingState.copy(
                status = response.status?.name ?: current.wearingState.status,
                result = response.result?.name ?: current.wearingState.result,
                raw = response.values,
            ))
        }
    }

    private fun applyTable2Diagnostic(channel: TandemChannel, response: ParsedTandemResponse) {
        appendLog("Table2 ${response::class.simpleName} channel=$channel raw=${response.raw.hexString()}")
        val diagnostic = table2DiagnosticStateFor(channel, response) ?: return
        _state.update { it.copy(table2Diagnostic = diagnostic) }
    }

    private fun applyKnownOrUnknown(response: ParsedTandemResponse.Unknown) {
        when (response.command) {
            PLAY_NTFY_PARAM -> appendLog(
                "Playback metadata notification len=${response.payload.size}",
                writeLogcat = false,
            )
            LEA_NTFY_STATUS -> appendLog(
                "LE Audio status notification ${response.payload.hexString()}",
                writeLogcat = false,
            )
            else -> appendLog(
                "Unhandled response command=${response.command} payload=${response.payload.hexString()}"
            )
        }
    }

    private fun updatePlaybackStatusFromAudioManager(force: Boolean = false) {
        if (!force && shouldUseTandemPlaybackStatus()) return
        applyPlaybackStatus(mediaController.currentFallbackStatus(), source = "AudioManager")
    }

    private fun shouldUseTandemPlaybackStatus(): Boolean {
        val current = _state.value
        val profile = current.connectedProfile ?: return false
        return current.deviceInfo.protocolReady &&
            profile.supports(HeadphoneFeature.PLAYBACK_CONTROL) &&
            profile.playbackDispatchStrategy != PlaybackDispatchStrategy.ANDROID_MEDIA_FALLBACK
    }

    private fun applyPlaybackStatus(status: PlaybackStatus, source: String, isUnsolicited: Boolean = false) {
        val pending = pendingPlaybackStatus
        if (pending != null) {
            val now = SystemClock.elapsedRealtime()
            if (now <= pending.ignoreOppositeUntilMs) {
                if (status != pending.expected) {
                    appendLog(
                        "Ignored stale playback status $status from $source while waiting for ${pending.expected}"
                    )
                    return
                }
                _state.update { it.copy(playbackStatus = status) }
                maybeStartPlaybackHeartbeat(status)
                return
            }
            pendingPlaybackStatus = null
        }

        // Cross-validation: when headphones send an unsolicited NTFY_STATUS that contradicts
        // our current PLAYING state, verify against AudioManager before accepting.
        if (isUnsolicited && source == "Tandem" &&
            status == PlaybackStatus.PAUSED &&
            _state.value.playbackStatus == PlaybackStatus.PLAYING
        ) {
            val audioActive = mediaController.currentFallbackStatus() == PlaybackStatus.PLAYING
            if (audioActive) {
                appendLog("NTFY PAUSED from headphones but AudioManager says PLAYING — " +
                    "re-querying Tandem before accepting")
                refreshPlaybackState()
                return
            }
        }

        _state.update { it.copy(playbackStatus = status) }
        maybeStartPlaybackHeartbeat(status)
    }

    private fun ensureConnectedProfile(): ConnectedHeadphoneProfile {
        _state.value.connectedProfile?.let { return it }
        val device = _state.value.connectedDevice ?: error("No connected device")
        val profile = HeadphoneAdapterRegistry.resolve(device, _state.value.deviceInfo.modelName)
            .copy(transport = _state.value.connectionInfo?.transport.toHeadphoneTransport())
        _state.update {
            it.copy(
                connectedProfile = profile,
                eqUiCapability = profile.eqUiCapability,
                supportedFeatures = featureStatusesFor(profile),
            )
        }
        return profile
    }

    private fun canWrite(feature: HeadphoneFeature): Boolean {
        val profile = _state.value.connectedProfile ?: return false
        return HeadphoneAdapterRegistry.canWrite(profile, feature)
    }

    @SuppressLint("MissingPermission")
    private fun readSystemBatteryLevel(address: String): Int? {
        if (address.isBlank()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return runCatching {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = manager.adapter?.getRemoteDevice(address) ?: return@runCatching null
            val level = device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int
            level?.takeIf { it in 0..100 }
        }.getOrNull()
    }

    private fun appendLog(message: String, writeLogcat: Boolean = true) {
        if (writeLogcat) {
            Log.i(LOG_TAG, message)
        }
        _state.update { current ->
            if (!current.debugLogging) current else current.copy(
                debugLogs = (listOf(message) + current.debugLogs).take(80)
            )
        }
    }

    companion object {
        @Volatile
        private var instance: SonyHeadphoneRepository? = null

        fun getInstance(
            resourceContext: Context,
            systemContext: Context = resourceContext,
        ): SonyHeadphoneRepository {
            return instance ?: synchronized(this) {
                instance ?: SonyHeadphoneRepository(resourceContext, systemContext).also { instance = it }
            }
        }

        const val LOG_TAG = "OpenBuds"
        const val PLAY_NTFY_PARAM = 0xA9
        const val LEA_NTFY_STATUS = 0x45

        fun mergeDevice(old: DiscoveredSonyDevice, new: DiscoveredSonyDevice): DiscoveredSonyDevice =
            old.copy(
                name = if (new.name != "Unknown BLE device") new.name else old.name,
                rssi = if (new.rssi != 0) new.rssi else old.rssi,
                source = if (new.source != "unknown") new.source else old.source,
                bluetoothType = if (new.bluetoothType != 0) new.bluetoothType else old.bluetoothType,
                advertisedServices = (old.advertisedServices + new.advertisedServices).distinct(),
                isLikelyControlEndpoint = old.isLikelyControlEndpoint || new.isLikelyControlEndpoint,
                sonyAd = new.sonyAd ?: old.sonyAd,
            )

        fun List<DiscoveredSonyDevice>.mergeKnownDevice(device: DiscoveredSonyDevice): List<DiscoveredSonyDevice> {
            val index = indexOfFirst { it.address == device.address }
            val merged = if (index >= 0) mergeDevice(this[index], device) else device
            return (listOf(merged) + filterNot { it.address == device.address })
                .sortedByConnectionPriority()
                .take(12)
        }

        fun List<DiscoveredSonyDevice>.sortedByConnectionPriority(): List<DiscoveredSonyDevice> =
            sortedWith(
                compareByDescending<DiscoveredSonyDevice> {
                    it.sonyAd?.androidGattCapable == true || it.sonyAd?.leGattControlFlag == true
                }.thenByDescending {
                    it.isLikelyControlEndpoint
                }.thenByDescending {
                    it.sonyAd != null
                }.thenByDescending {
                    it.rssi
                }
            )

        fun parseModelColor(seriesAndColor: String?): String? =
            seriesAndColor
                ?.substringAfter("/", "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }
}

private fun ConnectedHeadphoneProfile?.supports(feature: HeadphoneFeature): Boolean =
    this?.supports(feature) == true

private fun String?.toHeadphoneTransport(): HeadphoneTransport =
    when (this) {
        "SPP" -> HeadphoneTransport.SPP
        "GATT_HPC" -> HeadphoneTransport.GATT_HPC
        "GATT_MC" -> HeadphoneTransport.GATT_MC
        "UNSUPPORTED_LE_ENDPOINT" -> HeadphoneTransport.UNSUPPORTED_LE_ENDPOINT
        else -> HeadphoneTransport.UNKNOWN
    }

fun table2DiagnosticStateFor(
    channel: TandemChannel,
    response: ParsedTandemResponse,
): Table2DiagnosticState? =
    when (response) {
        is ParsedTandemResponse.Table2Common -> Table2DiagnosticState(
            channel = channel.name,
            family = response.family,
            command = response.command,
            inquiredType = null,
            values = response.values,
            rawHex = response.raw.hexString(),
        )
        is ParsedTandemResponse.Table2Generic -> Table2DiagnosticState(
            channel = channel.name,
            family = response.family,
            command = response.raw.table2CommandByte(),
            inquiredType = response.inquiredType,
            values = response.values,
            rawHex = response.raw.hexString(),
        )
        else -> null
    }

private fun ByteArray.table2CommandByte(): Int =
    when {
        size >= 2 && (this[0].toInt() and 0xFF) in setOf(0x0E, 0x0F) -> this[1].toInt() and 0xFF
        isNotEmpty() -> this[0].toInt() and 0xFF
        else -> -1
    }

private fun String.hexToByteArrayOrNull(): ByteArray? {
    val cleaned = replace("0x", "", ignoreCase = true)
        .replace(Regex("[^0-9A-Fa-f]"), "")
    if (cleaned.length % 2 != 0) return null
    return runCatching {
        cleaned.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }.getOrNull()
}

private fun NoiseControlState.forMode(mode: NoiseControlMode): NoiseControlState =
    copy(
        controlMode = mode,
        noiseCancellingEnabled = mode == NoiseControlMode.NOISE_CANCELLING,
        ambientSoundEnabled = mode == NoiseControlMode.AMBIENT_SOUND,
    )

private fun EqState.bandEditPreset(): EqPresetId =
    when (preset) {
        EqPresetId.CUSTOM,
        EqPresetId.USER_SETTING1,
        EqPresetId.USER_SETTING2 -> preset
        else -> EqPresetId.CUSTOM
    }

internal fun EqState.withClearBassSynced(level: Int): EqState {
    val clamped = level.coerceIn(-10, 10)
    val syncedRawSteps = rawBandSteps.takeIf { it.size > EQ_CLEAR_BASS_RAW_INDEX }
        ?.toMutableList()
        ?.also { it[EQ_CLEAR_BASS_RAW_INDEX] = displayEqStepToRaw(clamped) }
        ?: rawBandSteps
    return copy(
        clearBass = clamped,
        rawBandSteps = syncedRawSteps,
        bandSteps = if (syncedRawSteps !== rawBandSteps) {
            displayEqBands(syncedRawSteps)
        } else {
            bandSteps
        },
    )
}

internal fun displayEqStep(rawStep: Int): Int =
    (rawStep - EQ_BAND_STEP_CENTER).coerceIn(-10, 10)

internal fun displayEqBands(rawSteps: List<Int>): List<Int> {
    val displaySteps = rawSteps.map(::displayEqStep)
    return if (displaySteps.size > EQ_FIRST_FREQUENCY_RAW_INDEX) {
        displaySteps.drop(EQ_FIRST_FREQUENCY_RAW_INDEX)
    } else {
        displaySteps
    }
}

internal fun displayEqStepToRaw(displayStep: Int): Int =
    (displayStep.coerceIn(-10, 10) + EQ_BAND_STEP_CENTER).coerceIn(0, 255)

fun featureStatusesFor(profile: ConnectedHeadphoneProfile?): List<FeatureStatus> = listOf(
    FeatureStatus("扫描与连接", profile?.let { "${it.protocolName} via ${it.transport}" } ?: "BLE scan, GATT/SPP discovery", true),
    FeatureStatus("设备信息", "Model, firmware, protocol basics", profile.supports(HeadphoneFeature.DEVICE_INFO)),
    FeatureStatus("电量", "Single/headset, left/right, and cradle-compatible reads", profile.supports(HeadphoneFeature.BATTERY)),
    FeatureStatus("降噪开关", "NC/ASM gated by current device profile", profile.supports(HeadphoneFeature.NOISE_CONTROL)),
    FeatureStatus("环境声等级", "ASM seamless level when confirmed writable", profile.supports(HeadphoneFeature.AMBIENT_LEVEL)),
    FeatureStatus("播放控制", "Play, pause, previous, next", profile.supports(HeadphoneFeature.PLAYBACK_CONTROL)),
    FeatureStatus("EQ / Clear Bass", "Preset EQ, custom EQ, and Clear Bass", profile.supports(HeadphoneFeature.EQ)),
    FeatureStatus("LE Audio 状态", "Connection type, streaming status, paired history", profile.supports(HeadphoneFeature.LEA_STATUS)),
    FeatureStatus("Quick Access", "Customizable button actions L/R and NC/AMB keys", profile.supports(HeadphoneFeature.QUICK_ACCESS)),
    FeatureStatus("佩戴检测", "Earpiece fitting and wearing detection status", profile.supports(HeadphoneFeature.WEARING_STATUS)),
    FeatureStatus("Sense / AutoPlay / Multipoint / FOTA", "Advanced modules reserved", false),
)
