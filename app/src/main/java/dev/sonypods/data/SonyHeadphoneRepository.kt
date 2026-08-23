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
import android.content.SharedPreferences
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
import dev.sonypods.headphones.HeadphoneProtocolVariant
import dev.sonypods.headphones.HeadphoneTransport
import dev.sonypods.headphones.PlaybackDispatchStrategy
import dev.sonypods.headphones.MultipointDeviceAction
import dev.sonypods.headphones.buildFeatureBindings
import dev.sonypods.headphones.SonyCapabilityProbe
import dev.sonypods.headphones.SonyTandemHeadphoneAdapter
import dev.sonypods.headphones.TandemChannel
import dev.sonypods.leaudio.LeAudioSwitchCoordinator
import dev.sonypods.leaudio.LeAudioDevicePairer
import dev.sonypods.leaudio.LeAudioProfileGateway
import dev.sonypods.media.MediaPlaybackController
import dev.sonypods.config.CapabilityCacheEntry
import dev.sonypods.config.CapabilityValueCache
import dev.sonypods.config.CapabilityProbeCache
import dev.sonypods.config.EqBandInfoCache
import dev.sonypods.config.FunctionCode
import dev.sonypods.config.GeneralSettingCapabilityCache
import dev.sonypods.config.GestureActionCapabilityCache
import dev.sonypods.config.GestureKeyCapabilityCache
import dev.sonypods.config.GesturePresetCapabilityCache
import dev.sonypods.config.QuickAccessActionCapabilityCache
import dev.sonypods.config.QuickAccessCapabilityCache
import dev.sonypods.protocol.AmbientSoundMode
import dev.sonypods.protocol.DeviceInfoType
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.NoiseAdaptiveSensitivity
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.PlaybackControl
import dev.sonypods.protocol.PlaybackDetailedDataType
import dev.sonypods.protocol.PlaybackName
import dev.sonypods.protocol.PlaybackNameStatus
import dev.sonypods.protocol.PlaybackStatus
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.QuickAccessKey
import dev.sonypods.protocol.QuickAccessServiceCatalog
import dev.sonypods.protocol.AssignableSettingsType
import dev.sonypods.protocol.AssignableSettingsAction
import dev.sonypods.protocol.AssignableSettingsActionCapability
import dev.sonypods.protocol.AssignableSettingsFunction
import dev.sonypods.protocol.AssignableSettingsKey
import dev.sonypods.protocol.AssignableSettingsMapping
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.AssignableSettingsKeyCapability
import dev.sonypods.protocol.AssignableSettingsActionFunction
import dev.sonypods.protocol.SonySupportedFunction
import dev.sonypods.protocol.SonyTandemConstants
import dev.sonypods.protocol.SonyTandemV2Table1Protocol
import dev.sonypods.protocol.MultipointDevice
import dev.sonypods.protocol.hexString
import dev.sonypods.protocol.unsigned
import dev.sonypods.protocol.unsignedList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

private const val ALERT_INQUIRED_TYPE_FIXED = 0x00
private const val ALERT_INQUIRED_TYPE_LEFT_RIGHT = 0x02
private const val ALERT_INQUIRED_TYPE_FOREGROUND = 0x04
private const val ALERT_INQUIRED_TYPE_FLEXIBLE = 0x06

private fun fixedLeAudioAlertTargetsLeAudio(messageType: Int): Boolean =
    messageType in setOf(
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_LE_AUDIO_AND_CLASSIC_FROM_LE_AUDIO,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_ENTER_PAIRING_WITH_LE_AUDIO_LIMITATIONS,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_LIMITATIONS,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA_WAKE_WORD,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_QUICK_ACCESS,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA_AND_QUICK_ACCESS,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_PDM,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA_AND_PDM,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_QUICK_ACCESS_AND_PDM,
        SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA_QUICK_ACCESS_AND_PDM,
    )

private fun flexibleLeAudioAlertTargetsLeAudio(messageType: Int): Boolean =
    messageType in setOf(
        SonyTandemV2Table1Protocol.FLEXIBLE_ENTER_PAIRING_WITH_LE_AUDIO_LIMITATION,
        SonyTandemV2Table1Protocol.FLEXIBLE_CHANGE_CONNECTION_WITH_LE_AUDIO_LIMITATION,
        SonyTandemV2Table1Protocol.FLEXIBLE_CHANGE_STANDBY_TO_LE_AUDIO_CLASSIC,
        SonyTandemV2Table1Protocol.FLEXIBLE_ENTER_PAIRING_WITH_CONNECTION_MODE,
        SonyTandemV2Table1Protocol.LE_AUDIO_FLEXIBLE_MESSAGE_TYPE_TO_LE,
    )
private const val EQ_BAND_STEP_CENTER = 10
private const val EQ_CLEAR_BASS_RAW_INDEX = 0
private const val EQ_FIRST_FREQUENCY_RAW_INDEX = 1
private const val PLAYBACK_STALE_RESPONSE_WINDOW_MS = 2_500L
private const val PLAYBACK_REFRESH_AFTER_COMMAND_MS = 1_200L
private const val PLAYBACK_RECONCILE_AFTER_COMMAND_MS = 2_800L
private const val PLAYBACK_METADATA_REFETCH_DELAY_MS = 50L
private const val GESTURE_REFRESH_AFTER_WRITE_MS = 450L
/** Keep the optimistic GS value alive while the device asks for reconnection
 * confirmation and while the link is being re-established. */
private const val MULTIPOINT_TOGGLE_RECONCILE_TIMEOUT_MS = 5_000L
private const val QUICK_ACCESS_CONFIRM_TIMEOUT_MS = 2_000L
/** How long to wait for CONNECT_RET_CAPABILITY_INFO before falling back to the
 * full RET_SUPPORT_FUNCTION probe (some models/FW may not reply). */
private const val CAPABILITY_INFO_TIMEOUT_MS = 2_500L
private const val SUPPORT_FUNCTION_TIMEOUT_MS = 2_500L
private val MULTIPOINT_ADDRESS = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

private fun List<CapabilityValueCache>.replaceCapabilityValue(value: CapabilityValueCache): List<CapabilityValueCache> =
    filterNot {
        it.domain == value.domain && it.inquiredTypeCode == value.inquiredTypeCode
    } + value

/** SC `SourceSwitchControlResult`: SUCCESS 0x00 … FAIL_GIVE_PRIORITY_TO_VOICE_ASSISTANT 0x04. */
private fun sourceSwitchResultLabel(code: Int): String = when (code) {
    0x00 -> "切换成功"
    0x01 -> "切换失败"
    0x02 -> "通话中，暂时无法切换"
    0x03 -> "目标设备未连接音频（A2DP）"
    0x04 -> "语音助手使用中，暂时无法切换"
    else -> "未知结果（0x%02X）".format(code)
}

private fun multipointResultLabel(code: Int): String = when (code) {
    0x00 -> "断开成功"
    0x01 -> "断开失败"
    0x02 -> "正在断开"
    0x03 -> "断开忙"
    0x10 -> "连接成功"
    0x11 -> "连接失败"
    0x12 -> "正在连接"
    0x13 -> "连接忙"
    0x20 -> "取消注册成功"
    0x21 -> "取消注册失败"
    0x22 -> "正在取消注册"
    0x23 -> "取消注册忙"
    0x30 -> "配对成功"
    0x31 -> "配对失败"
    0x32 -> "正在配对"
    0x33 -> "配对忙"
    else -> "未知结果（0x%02X）".format(code)
}

private data class PendingPlaybackStatus(
    val expected: PlaybackStatus,
    val ignoreOppositeUntilMs: Long,
)

private enum class MultipointToggleDecision {
    AWAITING_CONFIRMATION,
    CONFIRMED,
    CANCELLED,
}

private data class PendingMultipointToggle(
    val address: String,
    val original: Boolean,
    val target: Boolean,
    val decision: MultipointToggleDecision = MultipointToggleDecision.AWAITING_CONFIRMATION,
)

data class DeviceInfoState(
    val modelName: String? = null,
    val firmwareVersion: String? = null,
    val seriesAndColor: String? = null,
    val modelColor: String? = null,
    val modelColorCode: Int? = null,
    val modelImageUrl: String? = null,
    val modelImageSourceColor: String? = null,
    val protocolReady: Boolean = false,
    /** Runtime protocol version reported by RET_PROTOCOL_INFO (2 bytes BE). */
    val protocolVersion: Int? = null,
    /** Whether [protocolVersion] passed the SC whitelist check (null until checked). */
    val protocolVersionAccepted: Boolean? = null,
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
    val noiseAdaptiveEnabled: Boolean = false,
    val noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.STANDARD,
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
    /** The persistent Sony LE Audio setting reported by LEA_NTFY_PARAM (0x49). */
    val enabled: String? = null,
    /** The currently active connection/stream mode reported by LEA_RET_STATUS (0x43). */
    val connectionEnabled: String? = null,
    val streamingStatusL: String? = null,
    val streamingStatusR: String? = null,
    val pairedHistory: String? = null,
    /** LE endpoint addresses returned by the Sony LEA capability query. */
    val leAudioAddresses: List<String> = emptyList(),
    val raw: List<Int> = emptyList(),
)

/** Device Alert confirmation or the post-switch pairing guide. */
data class LeAudioPendingAlert(
    val targetEnabled: Boolean,
    /** null means the app-local confirmation shown before Sony 0x48. */
    val inquiredType: Int? = null,
    val messageType: Int? = null,
    val itemCodes: List<Int> = emptyList(),
    val actionType: Int? = null,
    /** Exact device notification frame; retained for protocol-complete replies. */
    val raw: ByteArray = byteArrayOf(),
)

/** Progress of bonding the headset's LE-only identity on the phone side. */
data class LeAudioDevicePairState(
    val stage: LeAudioDevicePairer.Stage = LeAudioDevicePairer.Stage.IDLE,
    val message: String = "",
    /** The LE identity this module bonded, so disabling LE Audio can remove it again. */
    val bondedAddress: String? = null,
)

internal fun LeaState.withConnectionStatus(response: ParsedTandemResponse.LeaStatus): LeaState =
    copy(
        // A 0x43 status is useful as an initial fallback, but must never
        // overwrite the authoritative 0x49 setting once it has been observed.
        enabled = enabled ?: response.enabled?.name,
        connectionEnabled = response.enabled?.name ?: connectionEnabled,
        streamingStatusL = response.streamingStatusL?.name ?: streamingStatusL,
        streamingStatusR = response.streamingStatusR?.name ?: streamingStatusR,
        raw = response.values,
    )

internal fun LeaState.withSettingNotification(
    response: ParsedTandemResponse.LeaParameterNotification,
): LeaState =
    if (response.setting == 0x0C && response.enabled != null) {
        copy(enabled = response.enabled.name, raw = response.values)
    } else {
        this
    }

data class QuickAccessState(
    val lrKeyFunction: String? = null,
    val ncAmbKeyFunction: String? = null,
    val key: QuickAccessKey? = null,
    val type: AssignableSettingsType? = null,
    val actions: List<QuickAccessActionState> = emptyList(),
    val functionCodes: List<Int> = emptyList(),
    val enabled: Boolean? = null,
    val raw: List<Int> = emptyList(),
)

/** One Quick Access action.  Function IDs are intentionally raw integers: SAR
 * services can be added by Sound Connect without a module update. */
data class QuickAccessActionState(
    val action: AssignableSettingsAction,
    val currentFunctionCode: Int?,
    val defaultFunctionCode: Int,
    val availableFunctionCodes: List<Int>,
)

data class GestureOperationsState(
    val capabilities: List<AssignableSettingsKeyCapability> = emptyList(),
    val presets: List<AssignableSettingsPreset> = emptyList(),
    val enabled: List<Boolean> = emptyList(),
    val mappings: List<AssignableSettingsMapping> = emptyList(),
    val rawCapability: List<Int> = emptyList(),
    val rawPresets: List<Int> = emptyList(),
    val rawStatus: List<Int> = emptyList(),
    val rawMappings: List<Int> = emptyList(),
) {
    /** Build the UI-facing per-key model without exposing raw protocol layout. */
    fun uiKeys(): List<GestureOperationKey> {
        // EXT_PARAM has no physical-key byte.  The official app associates the
        // returned entries with the capability/current-preset order.  Consume
        // each mapping at most once so two controls that happen to use the same
        // preset do not accidentally display the same action table.
        val usedMappingIndices = mutableSetOf<Int>()
        return capabilities.mapIndexed { index, capability ->
            val currentPreset = presets.getOrNull(index) ?: capability.defaultPreset
            val mappingIndex = mappings.indices.firstOrNull { mappingIndex ->
                mappingIndex == index &&
                    mappingIndex !in usedMappingIndices &&
                    mappings[mappingIndex].preset == currentPreset
            } ?: mappings.indices.firstOrNull { mappingIndex ->
                mappingIndex !in usedMappingIndices && mappings[mappingIndex].preset == currentPreset
            }
            mappingIndex?.let(usedMappingIndices::add)
            val currentMappings = mappingIndex?.let { mappings[it].mappings }.orEmpty()
        val actions = capability.actionsByPreset[currentPreset].orEmpty().map { action ->
            val currentFunction = currentMappings.firstOrNull { it.action == action.action }?.function
                ?: action.defaultFunction
            GestureOperationAction(
                action = action.action,
                function = currentFunction,
                availableFunctions = action.availableFunctions,
            )
        }
        GestureOperationKey(
            key = capability.key,
            type = capability.type,
            enabled = enabled.getOrNull(index),
            currentPreset = currentPreset,
            availablePresets = capability.presets,
            actions = actions,
        )
        }
    }
}

data class MultipointState(
    val supported: Boolean = false,
    val inquiredType: Int? = null,
    val maxPairedDevices: Int = 0,
    val maxConnectedDevices: Int = 0,
    val supportsFileTransfer: Boolean? = null,
    val enabled: Boolean? = null,
    val pairingMode: Boolean = false,
    /** Connected devices sorted by connectedStatus ascending (SC `lg0.s.e`). */
    val connectedDevices: List<MultipointDevice> = emptyList(),
    /** Paired-but-not-connected history (connectedStatus == 0). */
    val historyDevices: List<MultipointDevice> = emptyList(),
    /** connectedStatus value of the playback-right holder, 0 = none. */
    val playbackRight: Int = 0,
    /** Address of the device holding the playback right, if any. */
    val activeSourceAddress: String? = null,
    val result: String? = null,
    val resultAddress: String? = null,
    val sourceSwitchEnabled: Boolean? = null,
    val fixedSourceAddress: String? = null,
    val sourceSwitchResult: String? = null,
    val musicHandOverEnabled: Boolean? = null,
    /** "同时连接2台设备" — the V2 Table1 General Setting multipoint toggle
     * (GS slot matched by title "MULTIPOINT_SETTING"); null = slot unknown. */
    val multipointEnabled: Boolean? = null,
    /** User-requested toggle target while a GS write is being reconciled. While
     * non-null, the UI keeps showing this value and stale device reports do not
     * overwrite it. The repository clears it only after the device reaches the
     * confirmed or cancelled value. */
    val pendingMultipointToggle: Boolean? = null,
    /** Pending device alert (V2 Table1 AlertMessageType 6/7) awaiting app reply;
     * non-null while the reconnection confirmation dialog is outstanding. */
    val pendingAlertMessageType: Int? = null,
    val raw: List<Int> = emptyList(),
)

data class GestureOperationKey(
    val key: AssignableSettingsKey,
    val type: dev.sonypods.protocol.AssignableSettingsType,
    val enabled: Boolean?,
    val currentPreset: AssignableSettingsPreset,
    val availablePresets: List<AssignableSettingsPreset>,
    val actions: List<GestureOperationAction>,
)

data class GestureOperationAction(
    val action: AssignableSettingsAction,
    val function: AssignableSettingsFunction,
    val availableFunctions: List<AssignableSettingsFunction>,
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

data class PlaybackState(
    /** RET/NTFY_STATUS enable bit; null = unknown (treated as enabled). */
    val enabled: Boolean? = null,
    /** null = UNSETTLED/unknown; "" = NOTHING (UI shows an "unknown" placeholder). */
    val track: String? = null,
    val album: String? = null,
    val artist: String? = null,
    /** Parsed for wire fidelity; the official card never displays genre. */
    val genre: String? = null,
    val musicVolume: Int? = null,
    /** 0 = the device has no volume control (hide the volume row). */
    val musicVolumeStep: Int = 0,
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
    val leAudioPendingAlert: LeAudioPendingAlert? = null,
    val leAudioSwitchPending: Boolean = false,
    val leAudioDevicePairState: LeAudioDevicePairState = LeAudioDevicePairState(),
    val quickAccessState: QuickAccessState = QuickAccessState(),
    val gestureOperationsState: GestureOperationsState = GestureOperationsState(),
    val multipointState: MultipointState = MultipointState(),
    val wearingState: WearingState = WearingState(),
    val playbackStatus: PlaybackStatus = PlaybackStatus.UNKNOWN,
    val playbackState: PlaybackState = PlaybackState(),
    val endpointDiagnostic: EndpointDiagnosticState? = null,
    val table2Diagnostic: Table2DiagnosticState? = null,
    val supportedFeatures: List<FeatureStatus> = featureStatusesFor(null),
    val debugLogs: List<String> = emptyList(),
    val debugLogging: Boolean = true,
    val autoReconnect: Boolean = false,
    val strictSonyScanFilter: Boolean = false,
    val preferredProtocol: String = "Sony Tandem",
    /**
     * True once the connection-time capability probe has finished (either from a
     * cache restore when the CONNECT_RET_CAPABILITY_INFO counter matched, or from
     * the full RET_SUPPORT_FUNCTION probe). The detail UI is gated on this so it
     * never opens against an empty half-probed profile.
     */
    val probeComplete: Boolean = false,
)

/**
 * @param resourceContext base module context. It is retained as the primary constructor
 *   context for callers hosted in the Bluetooth process.
 * @param systemContext context used for Bluetooth/audio system services. Defaults to
 *   [resourceContext], which is correct when the repository runs in the module app.
 */
class SonyHeadphoneRepository private constructor(
    resourceContext: Context,
    systemContext: Context = resourceContext,
    remoteModelInfoReader: (() -> String?)? = null,
    private val debugLogForwarder: ((String) -> Unit)? = null,
) : SonyBleClientListener {
    private val appContext = systemContext.applicationContext ?: systemContext
    private val client = SonyBleClient(appContext, this)
    private val mediaController = MediaPlaybackController(appContext)
    private val modelImageCatalog = SonyModelImageCatalog(remoteModelInfoReader)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val leAudioProfileGateway = LeAudioProfileGateway(appContext)
    private val _state = MutableStateFlow(SonyHeadphoneUiState())
    private val leAudioCoordinator = LeAudioSwitchCoordinator(
        object : LeAudioSwitchCoordinator.Callbacks {
            override fun requestPairedHistory(): Boolean {
                val commands = HeadphoneAdapterRegistry
                    .buildRefreshLeaPairedHistoryCommands(ensureConnectedProfile())
                if (commands.isEmpty()) return false
                commands.forEach(::sendCommand)
                return true
            }

            override fun requestLeAudioProfileReady(
                onReady: (dev.sonypods.leaudio.LeAudioProfileGateway.Platform) -> Unit,
            ): Boolean =
                leAudioProfileGateway.request(onReady)

            override fun sendHeadsetCommand(
                enabled: Boolean,
                changeConnectionMethod: Boolean,
            ): Boolean = sendLeAudioHeadsetCommand(enabled, changeConnectionMethod)

            override fun onPairingGuideRequired(enabled: Boolean, pairedHistory: String?) {
                appendLog("LE Audio pairing guide required enabled=$enabled pairedHistory=${pairedHistory.orEmpty()}")
                _state.update {
                    it.copy(
                        leAudioSwitchPending = false,
                        leAudioPendingAlert = LeAudioPendingAlert(targetEnabled = enabled),
                    )
                }
            }

            override fun onFinished(success: Boolean, message: String) {
                appendLog("LE Audio switch ${if (success) "finished" else "failed"}: $message")
                _state.update { current ->
                    current.copy(
                        // A device Alert is a separate 0x98 transaction. Do not
                        // erase it when the 0x49 setting observer completes.
                        leAudioPendingAlert = current.leAudioPendingAlert
                            ?.takeIf { it.inquiredType != null },
                        leAudioSwitchPending = false,
                    )
                }
                mainHandler.postDelayed({
                    if (_state.value.deviceInfo.protocolReady &&
                        client.availableChannels().isNotEmpty()
                    ) {
                        refreshBasics()
                    }
                }, LE_AUDIO_REFRESH_AFTER_SWITCH_MS)
            }

            override fun onLog(message: String) {
                appendLog(message)
            }

            override fun shouldSkipPairingGuide(): Boolean {
                val skip = skipLeAudioPairingGuide
                skipLeAudioPairingGuide = false
                return skip
            }

        },
    )
    private val leAudioDevicePairer = LeAudioDevicePairer(
        appContext,
        object : LeAudioDevicePairer.Listener {
            override fun onStageChanged(
                stage: LeAudioDevicePairer.Stage,
                message: String,
                bondedAddress: String?,
            ) {
                _state.update { current ->
                    current.copy(
                        leAudioDevicePairState = LeAudioDevicePairState(
                            stage = stage,
                            message = message,
                            // Keep the address the pairer bonded so a later disable can
                            // remove it; a failure must not erase an earlier success.
                            bondedAddress = bondedAddress
                                ?: current.leAudioDevicePairState.bondedAddress,
                        ),
                    )
                }
            }

            override fun onLog(message: String) {
                appendLog(message)
            }
        },
    )
    private val playbackRefreshRunnable = Runnable { refreshPlaybackStatusAfterCommand() }
    private val playbackReconcileRunnable = Runnable { refreshPlaybackStatusAfterCommand() }
    // Official behaviour: a v1 metadata NTFY carries no content, so re-GET the
    // whole playback block; 50ms debounce coalesces notification bursts.
    private val playbackMetadataRefetchRunnable = Runnable {
        if (_state.value.deviceInfo.protocolReady && _state.value.connectedDevice != null) {
            refreshPlaybackState()
        }
    }
    private var pendingPlaybackStatus: PendingPlaybackStatus? = null
    private var pendingQuickAccessFunctionCodes: List<Int>? = null
    /** Connection-scoped request; unlike the UI state, this survives the brief
     * disconnect/reconnect caused by a confirmed multipoint change. */
    private var pendingMultipointToggle: PendingMultipointToggle? = null
    private val multipointToggleReconcileRunnable = Runnable { reconcileMultipointToggleTimeout() }
    private val quickAccessConfirmTimeoutRunnable = Runnable {
        val expected = pendingQuickAccessFunctionCodes ?: return@Runnable
        val actual = _state.value.quickAccessState.functionCodes
        appendLog("Quick Access write was not confirmed expected=$expected actual=$actual")
        pendingQuickAccessFunctionCodes = null
        if (_state.value.deviceInfo.protocolReady && _state.value.connectedDevice != null) {
            refreshBasics()
        }
    }

    // ── Capability-probe cache (SC `exchanged_capabilities` semantics) ──

    /**
     * Framework-backed remote-preference provider (hook-side, read-only). Wired by
     * the host; null when the engine runs outside a hooked process (plain app).
     */
    @Volatile
    private var prefsProvider: (() -> SharedPreferences?)? = null

    /** Sink for the encoded cache map; the host broadcasts it to the app process,
     * which is the only side allowed to write the shared remote-prefs store. */
    @Volatile
    private var cacheSink: ((String) -> Unit)? = null

    /** Hook-host fallback invoked only when the current model has no catalog image. */
    @Volatile
    private var modelCatalogFallbackRequester: ((String?, String?, Int?) -> Unit)? = null

    /** In-process cache overlay: consulted before the prefs store on every connect. */
    private val capabilityCache = ConcurrentHashMap<String, CapabilityCacheEntry>()

    private var awaitingCapabilityInfo = false
    private var pendingCapabilityCounter: Int? = null
    private var pendingCapabilityIdentifier = ""
    /** Set only by the official device-originated flexible Alert type 13. */
    private var skipLeAudioPairingGuide = false
    private val pendingSupportFunctionTables = mutableSetOf<dev.sonypods.protocol.SonyTable>()
    private val supportFunctionsByTable = mutableMapOf<dev.sonypods.protocol.SonyTable, List<SonySupportedFunction>>()
    private val supportFunctionTimeoutRunnable = Runnable {
        if (pendingSupportFunctionTables.isNotEmpty()) {
            appendLog("Support-function probe timed out; using received tables=${supportFunctionsByTable.keys}")
            finishSupportFunctionProbe()
        }
    }
    private val capabilityInfoTimeoutRunnable = Runnable {
        if (awaitingCapabilityInfo) {
            awaitingCapabilityInfo = false
            appendLog("GET_CAPABILITY_INFO timed out; falling back to full support-function probe")
            // A hot-reload or a transport failure may invalidate the repository while
            // this delayed callback is still queued.  Do not let the fallback probe
            // resurrect a stale profile (or throw "No connected device") against a
            // closed GATT/SPP session.
            val current = _state.value
            if (current.connectedDevice == null || client.availableChannels().isEmpty()) {
                appendLog("Capability fallback skipped: no live Sony transport")
                return@Runnable
            }
            runCatching { ensureConnectedProfile() }
                .onSuccess(::runProbeFromSupportFunction)
                .onFailure { appendLog("Capability fallback skipped: ${it.message}") }
        }
    }

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

    /** Releases all Bluetooth and Handler resources owned by this generation. */
    fun close() {
        leAudioCoordinator.cancel()
        leAudioDevicePairer.cancel()
        leAudioProfileGateway.close()
        clearSupportFunctionProbeState()
        mainHandler.removeCallbacksAndMessages(null)
        client.close()
        pendingMultipointToggle = null
        // SonyBleClient.close() intentionally does not notify its listener because
        // it is used for generation teardown.  The singleton repository is reused by
        // the next libxposed generation, so explicitly clear every connection-scoped
        // value here; otherwise protocolReady remains true while availableChannels()
        // is empty and the next generation will never reconnect.
        onConnectionStateChanged(connected = false, device = null)
        pendingPlaybackStatus = null
        pendingQuickAccessFunctionCodes = null
        prefsProvider = null
        cacheSink = null
        modelCatalogFallbackRequester = null
    }

    /**
     * Wire the framework-backed remote-preference provider (hook-side read-only
     * store). Called by the host once the LSPosed remote-prefs bridge is up.
     */
    fun attachPrefsProvider(provider: (() -> SharedPreferences?)?) {
        prefsProvider = provider
        // Seed the in-process overlay from whatever the store already has (e.g. a
        // cache persisted before a scope restart), so a reconnect shortly after the
        // bridge comes up benefits without waiting for a fresh probe.
        refreshCapabilityCacheFromPrefs()
    }

    /** Wire the sink that carries the encoded cache to the app process for durable
     * persistence into the shared remote-prefs store. */
    fun attachCapabilityCacheSink(sink: ((String) -> Unit)?) {
        cacheSink = sink
    }

    /**
     * Wire the Hook-side Remote File reader for the cloud model catalog. The reader
     * must be backed by XposedInterface.openRemoteFile(); ordinary module-app files
     * and SharedPreferences are not visible from this process.
     */
    fun attachModelInfoReader(reader: (() -> String?)?) {
        modelImageCatalog.attachRemoteReader(reader)
        _state.update { current ->
            current.copy(deviceInfo = current.deviceInfo.withResolvedModelImage(current.connectedDevice))
        }
    }

    fun attachModelCatalogFallback(requester: ((String?, String?, Int?) -> Unit)?) {
        modelCatalogFallbackRequester = requester
    }

    /** Ask the host-local fallback to fetch the catalog only when resolution failed. */
    fun ensureModelImageCatalogIfNeeded() {
        val info = _state.value.deviceInfo
        if (info.modelImageUrl == null) {
            modelCatalogFallbackRequester?.invoke(
                info.modelName,
                info.modelColor,
                info.modelColorCode,
            )
        }
    }

    /** Reload the published cloud catalog and re-resolve the connected device image. */
    fun refreshModelImageCatalog(): Boolean {
        val refreshed = modelImageCatalog.refresh()
        if (refreshed) {
            _state.update { current ->
                current.copy(deviceInfo = current.deviceInfo.withResolvedModelImage(current.connectedDevice))
            }
        }
        return refreshed
    }

    /** Re-read the cache map from the remote-prefs store into the in-process overlay. */
    fun refreshCapabilityCacheFromPrefs() {
        val entries = CapabilityProbeCache.readAll(runCatching { prefsProvider?.invoke() }.getOrNull())
        if (entries.isNotEmpty()) {
            capabilityCache.putAll(entries)
            appendLog("Capability cache loaded ${entries.size} entries from prefs", writeLogcat = false)
        }
    }

    /** Install a cache map pushed from the app process by value (survives an empty
     * remote-prefs read in the hook process). */
    fun installCapabilityCache(json: String) {
        val entries = CapabilityProbeCache.decode(json)
        if (entries.isNotEmpty()) {
            capabilityCache.clear()
            capabilityCache.putAll(entries)
            appendLog("Capability cache installed ${entries.size} entries via broadcast", writeLogcat = false)
        }
    }

    private fun readCapabilityCache(address: String): CapabilityCacheEntry? {
        capabilityCache[address]?.let { return it }
        return CapabilityProbeCache.readAll(runCatching { prefsProvider?.invoke() }.getOrNull())[address]
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
        if (client.availableChannels().isEmpty()) {
            onBluetoothUnavailable("Sony Tandem channel is no longer available; reconnect required.")
            return
        }
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildRefreshCommands(profile)
            .forEach(::sendCommand)
        updatePlaybackStatusFromAudioManager()
    }

    /**
     * Connection-time capability probe (mirrors SC C29903d/C30916e).
     *
     * SC first sends CONNECT_GET_CAPABILITY_INFO (0x02) and compares the returned
     * capability counter against the persisted one for this device; on a match the
     * per-domain capability probe is omitted and the cached tableset restored
     * ("Omit the getting capability"), otherwise the full RET_SUPPORT_FUNCTION
     * probe runs and its result is persisted. We mirror that: send GET_CAPABILITY_INFO
     * first, restore from cache on a counter match, and fall back to the full
     * support-function probe on a mismatch or when the device never replies.
     */
    private fun probeCapabilities() {
        if (awaitingCapabilityInfo) return
        val profile = ensureConnectedProfile()
        val address = _state.value.connectedDevice?.address
        if (address != null && address.isNotBlank()) {
            val capabilityInfoCommand = runCatching {
                SonyCapabilityProbe.buildGetCapabilityInfoCommand(profile)
            }.getOrNull()
            if (capabilityInfoCommand != null) {
                awaitingCapabilityInfo = true
                appendLog("Sending GET_CAPABILITY_INFO (SC counter gate)")
                sendCommand(capabilityInfoCommand)
                mainHandler.removeCallbacks(capabilityInfoTimeoutRunnable)
                mainHandler.postDelayed(capabilityInfoTimeoutRunnable, CAPABILITY_INFO_TIMEOUT_MS)
                return
            }
        }
        runProbeFromSupportFunction(profile)
    }

    /** The RET_SUPPORT_FUNCTION-driven probe (used on counter mismatch / no reply). */
    private fun runProbeFromSupportFunction(profile: ConnectedHeadphoneProfile) {
        clearSupportFunctionProbeState()
        val supportCommands = runCatching {
            SonyCapabilityProbe.buildGetSupportFunctionCommands(profile, client.availableChannels())
        }.getOrElse { emptyList() }
        if (supportCommands.isEmpty()) {
            appendLog("No support-function probe for ${profile.protocolName}; falling back to direct refresh")
            clearSupportFunctionProbeState()
            markProbeComplete()
            refreshBasics()
            return
        }
        supportCommands.forEach { command ->
            pendingSupportFunctionTables += if (command.channel == TandemChannel.GATT_V2_MC) {
                dev.sonypods.protocol.SonyTable.NO_2
            } else {
                dev.sonypods.protocol.SonyTable.NO_1
            }
        }
        mainHandler.postDelayed(supportFunctionTimeoutRunnable, SUPPORT_FUNCTION_TIMEOUT_MS)
        appendLog("Probing support function (SC C29903d/C30916e capability sequence)")
        supportCommands.forEach(::sendCommand)
    }

    /** CONNECT_RET_CAPABILITY_INFO (0x03): the capability counter gate. */
    private fun applyConnectCapabilityInfo(response: ParsedTandemResponse.ConnectCapabilityInfo) {
        if (!awaitingCapabilityInfo) return
        awaitingCapabilityInfo = false
        mainHandler.removeCallbacks(capabilityInfoTimeoutRunnable)
        val address = _state.value.connectedDevice?.address.orEmpty()
        val cached = readCapabilityCache(address)
        val identifierMatches = cached?.identifier.isNullOrBlank() ||
            response.identifier.isBlank() ||
            cached.identifier == response.identifier
        if (cached != null && cached.counter == response.capabilityCounter && identifierMatches && restoreProfileFromCache(cached)) {
            appendLog(
                "Capability counter ${response.capabilityCounter} matches cache " +
                    "(identifier=${response.identifier}) → omit capability probe; restoring profile"
            )
            clearSupportFunctionProbeState()
            markProbeComplete()
            refreshBasics()
            return
        }
        appendLog(
            "Capability counter ${response.capabilityCounter} cache=${cached?.counter ?: "none"} " +
                "(identifier=${response.identifier}) → start get capability"
        )
        pendingCapabilityCounter = response.capabilityCounter
        pendingCapabilityIdentifier = response.identifier
        runProbeFromSupportFunction(ensureConnectedProfile())
    }

    /** Re-derive the probe-derived profile from a cached function list. Returns
     * false when the cached entry has no functions (nothing to restore). */
    private fun restoreProfileFromCache(entry: CapabilityCacheEntry): Boolean {
        if (entry.functions.isEmpty()) return false
        val profile = _state.value.connectedProfile ?: return false
        val functions = SonyCapabilityProbe.restoreFunctions(profile, entry.functions)
        if (functions.isEmpty()) return false
        val restored = SonyCapabilityProbe.applyToProfile(profile, functions, profile.transport)
            .withCachedCapabilityDetails(entry)
            .withCachedMultipointSlot(entry)
        _state.update {
            val quickAccess = entry.quickAccessCapability?.toQuickAccessState(it.quickAccessState)
            val gestureCapabilities = entry.gestureCapabilities.toGestureCapabilities()
            it.copy(
                connectedProfile = restored,
                eqUiCapability = restored.eqUiCapability,
                supportedFeatures = featureStatusesFor(restored),
                playbackState = if (entry.playVolumeStep > 0) {
                    it.playbackState.copy(musicVolumeStep = entry.playVolumeStep)
                } else {
                    it.playbackState
                },
                quickAccessState = quickAccess ?: it.quickAccessState,
                gestureOperationsState = if (gestureCapabilities.isNotEmpty()) {
                    it.gestureOperationsState.copy(capabilities = gestureCapabilities)
                } else {
                    it.gestureOperationsState
                },
                multipointState = it.multipointState.copy(
                    supported = it.multipointState.supported || entry.multipointTypeCode != null,
                    inquiredType = entry.multipointTypeCode ?: it.multipointState.inquiredType,
                    maxPairedDevices = entry.maxPairedDevices.takeIf { value -> value > 0 }
                        ?: it.multipointState.maxPairedDevices,
                    maxConnectedDevices = entry.maxConnectedDevices.takeIf { value -> value > 0 }
                        ?: it.multipointState.maxConnectedDevices,
                    supportsFileTransfer = entry.supportsFileTransfer
                        ?: it.multipointState.supportsFileTransfer,
                ),
            )
        }
        appendLog(
            "Restored profile from cache: ${functions.size} functions, " +
                "battery=${restored.capabilities.batteryQueries}, writableNC=${restored.protocolEvidence.count { it.startsWith("probe:NCASM") }}",
            writeLogcat = false,
        )
        return true
    }

    /** Restore a freshly-resolved (neutral) profile from any cached probe result
     * for this address, so an early connection-state event does not reset
     * formFactor to UNKNOWN. Returns the input unchanged when there is nothing
     * to restore. */
    private fun resolveFromCache(resolved: ConnectedHeadphoneProfile, address: String?): ConnectedHeadphoneProfile {
        if (address.isNullOrBlank()) return resolved
        val entry = readCapabilityCache(address) ?: return resolved
        if (entry.functions.isEmpty()) {
            return resolved.withCachedCapabilityDetails(entry).withCachedMultipointSlot(entry)
        }
        val functions = SonyCapabilityProbe.restoreFunctions(resolved, entry.functions)
        if (functions.isEmpty()) {
            return resolved.withCachedCapabilityDetails(entry).withCachedMultipointSlot(entry)
        }
        return SonyCapabilityProbe.applyToProfile(resolved, functions, resolved.transport)
            .withCachedCapabilityDetails(entry)
            .withCachedMultipointSlot(entry)
    }

    /** Restore capability details that cannot be re-derived from the support-function list. */
    private fun ConnectedHeadphoneProfile.withCachedCapabilityDetails(entry: CapabilityCacheEntry): ConnectedHeadphoneProfile {
        val currentEq = capabilities.eqConfig
        val cachedPresets = entry.eqAvailablePresetCodes.mapNotNull { code ->
            EqPresetId.entries.firstOrNull { it.code.toInt() and 0xFF == code }
        }
        val cachedBandCount = entry.eqBandInfo.size.takeIf { it > 0 } ?: currentEq.bandCount
        val restoredCapabilities = capabilities.copy(
            features = if (entry.multipointTypeCode != null || entry.maxConnectedDevices > 0) {
                capabilities.features + HeadphoneFeature.MULTIPOINT
            } else {
                capabilities.features
            },
            eqConfig = currentEq.copy(
                availablePresets = cachedPresets.ifEmpty { currentEq.availablePresets },
                bandCount = cachedBandCount,
                hasClearBass = entry.eqHasClearBass ?: currentEq.hasClearBass,
            ),
        )
        return copy(
            capabilities = restoredCapabilities,
            featureBindings = buildFeatureBindings(featureProtocolMap, restoredCapabilities),
            multipointTypeCode = entry.multipointTypeCode ?: multipointTypeCode,
        )
    }

    private fun QuickAccessCapabilityCache.toQuickAccessState(current: QuickAccessState): QuickAccessState? {
        val key = QuickAccessKey.entries.firstOrNull { it.code.toInt() and 0xFF == keyCode }
            ?: return null
        val type = AssignableSettingsType.entries.firstOrNull { it.code.toInt() and 0xFF == typeCode }
            ?: return null
        return current.copy(
            key = key,
            type = type,
            actions = actions.mapNotNull { action ->
                AssignableSettingsAction.entries.firstOrNull {
                    it.code.toInt() and 0xFF == action.actionCode
                }?.let {
                    QuickAccessActionState(
                        action = it,
                        currentFunctionCode = current.functionCodes.getOrNull(actions.indexOf(action)),
                        defaultFunctionCode = action.defaultFunctionCode,
                        availableFunctionCodes = action.availableFunctionCodes,
                    )
                }
            },
        )
    }

    private fun List<GestureKeyCapabilityCache>.toGestureCapabilities(): List<AssignableSettingsKeyCapability> =
        mapNotNull { keyCache ->
            val key = AssignableSettingsKey.entries.firstOrNull {
                it.code.toInt() and 0xFF == keyCache.keyCode
            } ?: return@mapNotNull null
            val type = AssignableSettingsType.entries.firstOrNull {
                it.code.toInt() and 0xFF == keyCache.typeCode
            } ?: return@mapNotNull null
            val defaultPreset = AssignableSettingsPreset.entries.firstOrNull {
                it.code.toInt() and 0xFF == keyCache.defaultPresetCode
            } ?: return@mapNotNull null
            val actionsByPreset = keyCache.actionsByPreset.mapNotNull { presetCache ->
                val preset = AssignableSettingsPreset.entries.firstOrNull {
                    it.code.toInt() and 0xFF == presetCache.presetCode
                } ?: return@mapNotNull null
                val actions = presetCache.actions.mapNotNull { actionCache ->
                    val action = AssignableSettingsAction.entries.firstOrNull {
                        it.code.toInt() and 0xFF == actionCache.actionCode
                    } ?: return@mapNotNull null
                    val defaultFunction = AssignableSettingsFunction.entries.firstOrNull {
                        it.code.toInt() and 0xFF == actionCache.defaultFunctionCode
                    } ?: return@mapNotNull null
                    val functions = actionCache.availableFunctionCodes.mapNotNull { code ->
                        AssignableSettingsFunction.entries.firstOrNull {
                            it.code.toInt() and 0xFF == code
                        }
                    }
                    AssignableSettingsActionCapability(
                        action = action,
                        defaultFunction = defaultFunction,
                        availableFunctions = functions.ifEmpty { listOf(defaultFunction) },
                    )
                }
                preset to actions
            }.toMap()
            AssignableSettingsKeyCapability(
                key = key,
                type = type,
                defaultPreset = defaultPreset,
                presets = keyCache.presets.mapNotNull { code ->
                    AssignableSettingsPreset.entries.firstOrNull {
                        it.code.toInt() and 0xFF == code
                    }
                },
                actionsByPreset = actionsByPreset,
            )
        }

    private fun ConnectedHeadphoneProfile.withCachedMultipointSlot(entry: CapabilityCacheEntry): ConnectedHeadphoneProfile =
        entry.multipointGsSlot.takeIf { it >= 0 }?.let { copy(multipointGsSlot = it) } ?: this

    /** Persist the current probe result (counter + function list) for this device. */
    private fun saveCapabilityCache(functions: List<SonySupportedFunction>) {
        val address = _state.value.connectedDevice?.address ?: return
        val counter = pendingCapabilityCounter ?: return
        val profile = _state.value.connectedProfile ?: return
        val previous = capabilityCache[address]
        val eqConfig = profile.capabilities.eqConfig
        val entry = CapabilityCacheEntry(
            counter = counter,
            identifier = pendingCapabilityIdentifier,
            variant = profile.protocolName,
            transport = profile.transport.name,
            functions = functions.map {
                FunctionCode(it.code.toInt() and 0xFF, it.order, it.table.name)
            },
            // The PLAY capability RET may not have arrived yet at probe-save time;
            // keep whatever was learned before, applyPlaybackCapability updates it.
            playVolumeStep = _state.value.playbackState.musicVolumeStep.takeIf { it > 0 }
                ?: capabilityCache[address]?.playVolumeStep
                ?: -1,
            multipointGsSlot = profile.multipointGsSlot ?: capabilityCache[address]?.multipointGsSlot ?: -1,
            multipointEnabled = _state.value.multipointState.multipointEnabled
                ?: capabilityCache[address]?.multipointEnabled,
            capabilityValues = previous?.capabilityValues.orEmpty(),
            eqBandInfo = previous?.eqBandInfo.orEmpty(),
            eqAvailablePresetCodes = eqConfig.availablePresets.map { it.code.toInt() and 0xFF },
            eqHasClearBass = eqConfig.hasClearBass,
            playbackSupportsButtons = previous?.playbackSupportsButtons,
            playbackSupportsMetadata = previous?.playbackSupportsMetadata,
            quickAccessCapability = previous?.quickAccessCapability,
            gestureCapabilities = previous?.gestureCapabilities.orEmpty(),
            multipointTypeCode = _state.value.multipointState.inquiredType
                ?: previous?.multipointTypeCode,
            maxPairedDevices = _state.value.multipointState.maxPairedDevices
                .takeIf { it > 0 } ?: previous?.maxPairedDevices ?: 0,
            maxConnectedDevices = _state.value.multipointState.maxConnectedDevices
                .takeIf { it > 0 } ?: previous?.maxConnectedDevices ?: 0,
            supportsFileTransfer = _state.value.multipointState.supportsFileTransfer
                ?: previous?.supportsFileTransfer,
            generalSettingCapability = previous?.generalSettingCapability,
            savedAtMs = System.currentTimeMillis(),
        )
        capabilityCache[address] = entry
        appendLog("Capability cache saved for $address counter=$counter functions=${functions.size}", writeLogcat = false)
        cacheSink?.invoke(CapabilityProbeCache.encode(capabilityCache))
    }

    /** Update one device's persisted capability entry without losing fields from
     * the earlier support-function save. */
    private fun updateCapabilityCache(address: String, transform: (CapabilityCacheEntry) -> CapabilityCacheEntry) {
        val current = capabilityCache[address] ?: return
        val updated = transform(current).copy(savedAtMs = System.currentTimeMillis())
        if (updated == current) return
        capabilityCache[address] = updated
        cacheSink?.invoke(CapabilityProbeCache.encode(capabilityCache))
    }

    /** Update the small amount of multipoint state that is useful before the
     * first refresh response on the next connection. Capability cache writes are
     * durable in the app process, so this also removes the visible GS discovery
     * delay after a scope/process restart. */
    private fun saveMultipointCache() {
        val address = _state.value.connectedDevice?.address ?: return
        val current = capabilityCache[address] ?: return
        val profile = _state.value.connectedProfile
        val multipoint = _state.value.multipointState
        val updated = current.copy(
            multipointGsSlot = profile?.multipointGsSlot ?: current.multipointGsSlot,
            multipointEnabled = multipoint.multipointEnabled ?: current.multipointEnabled,
            savedAtMs = System.currentTimeMillis(),
        )
        if (updated == current) return
        capabilityCache[address] = updated
        cacheSink?.invoke(CapabilityProbeCache.encode(capabilityCache))
    }

    private fun markProbeComplete() {
        if (_state.value.probeComplete) return
        _state.update { it.copy(probeComplete = true) }
        appendLog("Capability probe complete", writeLogcat = false)
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
        HeadphoneAdapterRegistry.buildSetNoiseControlModeCommands(
            profile,
            mode,
            level,
            ambientMode,
            current.noiseAdaptiveEnabled,
            current.noiseAdaptiveSensitivity,
        ).forEach(::sendCommand)
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
            current.noiseAdaptiveEnabled,
            current.noiseAdaptiveSensitivity,
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
            current.noiseAdaptiveEnabled,
            current.noiseAdaptiveSensitivity,
        ).forEach(::sendCommand)
        refreshNoiseControlStateAfterWrite(profile)
    }

    fun setNoiseAdaptive(enabled: Boolean) {
        if (!canWrite(HeadphoneFeature.NOISE_ADAPTIVE)) {
            appendLog("Noise adaptive write is disabled for current profile")
            return
        }
        if (!_state.value.deviceInfo.protocolReady) {
            _state.update {
                it.copy(noiseControlState = it.noiseControlState.copy(noiseAdaptiveEnabled = enabled))
            }
            return
        }
        val current = _state.value.noiseControlState
        val level = current.ambientLevel?.takeIf { it > 0 }?.coerceIn(1, 20) ?: 10
        val ambientMode = if (current.ambientVoiceMode) AmbientSoundMode.VOICE else AmbientSoundMode.NORMAL
        _state.update {
            it.copy(noiseControlState = it.noiseControlState.forMode(NoiseControlMode.AMBIENT_SOUND).copy(
                ambientLevel = level,
                noiseAdaptiveEnabled = enabled,
            ))
        }
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            level,
            ambientMode,
            enabled,
            current.noiseAdaptiveSensitivity,
        ).forEach(::sendCommand)
        refreshNoiseControlStateAfterWrite(profile)
    }

    fun setNoiseAdaptiveSensitivity(sensitivity: NoiseAdaptiveSensitivity) {
        if (!canWrite(HeadphoneFeature.NOISE_ADAPTIVE)) {
            appendLog("Noise adaptive sensitivity write is disabled for current profile")
            return
        }
        if (!_state.value.deviceInfo.protocolReady) {
            _state.update {
                it.copy(noiseControlState = it.noiseControlState.copy(noiseAdaptiveSensitivity = sensitivity))
            }
            return
        }
        val current = _state.value.noiseControlState
        val level = current.ambientLevel?.takeIf { it > 0 }?.coerceIn(1, 20) ?: 10
        val ambientMode = if (current.ambientVoiceMode) AmbientSoundMode.VOICE else AmbientSoundMode.NORMAL
        _state.update {
            it.copy(noiseControlState = it.noiseControlState.forMode(NoiseControlMode.AMBIENT_SOUND).copy(
                ambientLevel = level,
                // Sound Connect forces the NA toggle ON in the frame that
                // changes sensitivity; mirror that so the write is consistent.
                noiseAdaptiveEnabled = true,
                noiseAdaptiveSensitivity = sensitivity,
            ))
        }
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            level,
            ambientMode,
            true,
            sensitivity,
        ).forEach(::sendCommand)
        refreshNoiseControlStateAfterWrite(profile)
    }

    fun setMultipointPairingMode(enabled: Boolean) {
        if (!_state.value.deviceInfo.protocolReady || !canWrite(HeadphoneFeature.MULTIPOINT)) return
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetMultipointPairingModeCommands(profile, enabled).forEach(::sendCommand)
        _state.update { it.copy(multipointState = it.multipointState.copy(pairingMode = enabled)) }
        scheduleMultipointRefresh()
    }

    fun setSourceSwitchEnabled(enabled: Boolean) {
        if (!_state.value.deviceInfo.protocolReady || !canWrite(HeadphoneFeature.MULTIPOINT)) return
        HeadphoneAdapterRegistry.buildSetSourceSwitchCommands(ensureConnectedProfile(), enabled).forEach(::sendCommand)
        _state.update { it.copy(multipointState = it.multipointState.copy(sourceSwitchEnabled = enabled)) }
        scheduleMultipointRefresh()
    }

    /** Toggle "同时连接2台设备" (V2 Table1 GS multipoint slot). */
    fun setMultipointEnabled(enabled: Boolean) {
        if (!_state.value.deviceInfo.protocolReady || !canWrite(HeadphoneFeature.MULTIPOINT)) return
        val profile = ensureConnectedProfile()
        val address = _state.value.connectedDevice?.address
        val current = _state.value.multipointState
        val existing = pendingMultipointToggle?.takeIf { it.address.equals(address, ignoreCase = true) }
        if (existing != null) {
            appendLog("Ignoring multipoint toggle while the previous request is pending")
            return
        }
        val original = current.multipointEnabled ?: return
        if (profile.multipointGsSlot == null || address.isNullOrBlank()) {
            appendLog("Cannot toggle 2-device multipoint: GS slot not discovered")
            return
        }
        val commands = HeadphoneAdapterRegistry.buildSetMultipointEnabledCommands(profile, enabled)
        if (commands.isEmpty()) return

        pendingMultipointToggle = PendingMultipointToggle(
            address = address,
            original = original,
            target = enabled,
        )
        _state.update {
            it.copy(
                multipointState = it.multipointState.copy(
                    multipointEnabled = enabled,
                    pendingMultipointToggle = enabled,
                ),
            )
        }
        // Send only after publishing the optimistic state, so a quick device
        // notification cannot win a race with the user's tap.
        commands.forEach(::sendCommand)
        mainHandler.removeCallbacks(multipointToggleReconcileRunnable)
        mainHandler.postDelayed(multipointToggleReconcileRunnable, MULTIPOINT_TOGGLE_RECONCILE_TIMEOUT_MS)
        scheduleMultipointRefresh()
    }

    /** Reply to the device's pending multipoint alert (7=reconnect, 6=LDAC disable):
     * ALERT_SET_PARAM [0x98, 0x00, msgType, action]. POSITIVE lets the device
     * execute the requested change; NEGATIVE cancels it. Clears the pending state. */
    fun replyMultipointAlert(positive: Boolean) {
        if (!_state.value.deviceInfo.protocolReady || !canWrite(HeadphoneFeature.MULTIPOINT)) return
        val messageType = _state.value.multipointState.pendingAlertMessageType ?: return
        val pendingToggle = pendingMultipointToggle
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildReplyAlertCommand(profile, messageType, positive).forEach(::sendCommand)
        val decided = pendingToggle?.copy(
            decision = if (positive) {
                MultipointToggleDecision.CONFIRMED
            } else {
                MultipointToggleDecision.CANCELLED
            },
        )
        pendingMultipointToggle = decided
        _state.update {
            it.copy(
                multipointState = it.multipointState.copy(
                    pendingAlertMessageType = null,
                    // Confirm: keep showing the optimistic target until the
                    // device reports it after reconnect. Cancel: immediately
                    // restore the value from before the user's tap.
                    // A cancelled request is no longer optimistic from the
                    // UI's point of view, even though the private transaction
                    // remains alive to reject a stale device report.
                    pendingMultipointToggle = decided?.target?.takeIf { positive },
                    multipointEnabled = if (positive) {
                        decided?.target ?: it.multipointState.multipointEnabled
                    } else {
                        decided?.original ?: it.multipointState.multipointEnabled
                    },
                ),
            )
        }
        if (pendingToggle != null) {
            // The alert is now the source of truth for this transaction; do not
            // let the pre-alert fallback timer make a late cancel ineffective.
            mainHandler.removeCallbacks(multipointToggleReconcileRunnable)
            mainHandler.postDelayed(multipointToggleReconcileRunnable, MULTIPOINT_TOGGLE_RECONCILE_TIMEOUT_MS)
        }
        scheduleMultipointRefresh()
    }

    fun setFixedSource(address: String) {
        if (!_state.value.deviceInfo.protocolReady || !canWrite(HeadphoneFeature.MULTIPOINT)) return
        HeadphoneAdapterRegistry.buildSetFixedSourceCommand(ensureConnectedProfile(), address).forEach(::sendCommand)
        _state.update { it.copy(multipointState = it.multipointState.copy(fixedSourceAddress = address)) }
        scheduleMultipointRefresh()
    }

    fun setMusicHandOverEnabled(enabled: Boolean) {
        if (!_state.value.deviceInfo.protocolReady || !canWrite(HeadphoneFeature.MULTIPOINT)) return
        HeadphoneAdapterRegistry.buildSetMusicHandOverCommands(ensureConnectedProfile(), enabled).forEach(::sendCommand)
        _state.update { it.copy(multipointState = it.multipointState.copy(musicHandOverEnabled = enabled)) }
        scheduleMultipointRefresh()
    }

    fun connectMultipointDevice(address: String) =
        sendMultipointDeviceAction(address, MultipointDeviceAction.CONNECT)

    fun disconnectMultipointDevice(address: String) =
        sendMultipointDeviceAction(address, MultipointDeviceAction.DISCONNECT)

    fun unpairMultipointDevice(address: String) =
        sendMultipointDeviceAction(address, MultipointDeviceAction.UNPAIR)

    private fun sendMultipointDeviceAction(address: String, action: MultipointDeviceAction) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot manage dual-device connections.")
            return
        }
        val multipoint = _state.value.multipointState
        if (!multipoint.supported || !canWrite(HeadphoneFeature.MULTIPOINT)) {
            appendLog("Dual-device management is unavailable for current profile")
            return
        }
        if (!MULTIPOINT_ADDRESS.matches(address)) {
            appendLog("Ignoring multipoint ${action.name}: invalid address=$address")
            return
        }
        HeadphoneAdapterRegistry.buildSetMultipointDeviceCommand(
            ensureConnectedProfile(),
            address,
            action,
        ).forEach(::sendCommand)
        scheduleMultipointRefresh()
    }

    private fun scheduleMultipointRefresh() {
        mainHandler.postDelayed({
            if (_state.value.deviceInfo.protocolReady && _state.value.connectedDevice != null) refreshBasics()
        }, GESTURE_REFRESH_AFTER_WRITE_MS)
    }

    /** Send the Sony Tandem USER_POWER_OFF command and let the headset close the
     * transport itself. Do not refresh or explicitly disconnect afterwards: the
     * power-off frame must finish writing before the link disappears. */
    fun powerOff() {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot power off.")
            return
        }
        if (!canWrite(HeadphoneFeature.POWER_OFF)) {
            appendLog("Power-off write is disabled for current profile")
            return
        }
        val commands = HeadphoneAdapterRegistry.buildPowerOffCommands(ensureConnectedProfile())
        if (commands.isEmpty()) {
            appendLog("Power-off command unavailable for current protocol")
            return
        }
        appendLog("Sending Sony USER_POWER_OFF; headset is expected to disconnect")
        commands.forEach(::sendCommand)
    }

    /** Run Sony's complete phone/headset hand-over instead of only toggling the
     * headset bit. State is updated solely from real Tandem replies. */
    fun setLeAudioEnabled(enabled: Boolean) {
        if (!_state.value.deviceInfo.protocolReady || !canWrite(HeadphoneFeature.LEA_STATUS)) {
            appendLog("LE Audio write unavailable")
            return
        }
        _state.update { it.copy(leAudioSwitchPending = true) }
        val current = _state.value
        if (enabled) {
            leAudioCoordinator.start(current.leaState.pairedHistory)
        } else {
            // Drop our LE bond before the headset leaves LE Audio, so the stack is not
            // reconnecting an LE identity that is about to stop announcing.
            leAudioDevicePairer.cancel()
            unpairLeAudioDevice()
            leAudioCoordinator.disable()
        }
    }

    /** Completes the official-style LE Audio confirmation transaction. */
    fun replyLeAudioAlert(positive: Boolean) {
        val pending = _state.value.leAudioPendingAlert ?: return
        if (pending.inquiredType == null) {
            // This is the Qualcomm-only pairing guide shown after the setting
            // observer completes. It is not an ALERT_NTFY_PARAM transaction,
            // so never manufacture a 0x98 reply for it.
            appendLog("LE Audio pairing guide ${if (positive) "confirmed" else "cancelled"}")
            if (!positive) leAudioCoordinator.cancel()
            _state.update { it.copy(leAudioPendingAlert = null, leAudioSwitchPending = leAudioCoordinator.isRunning()) }
            if (positive) {
                mainHandler.post {
                    if (_state.value.deviceInfo.protocolReady && client.availableChannels().isNotEmpty()) {
                        refreshBasics()
                    }
                }
            }
            return
        }
        if (pending.messageType != null) {
            val profile = runCatching { ensureConnectedProfile() }.getOrNull()
            if (profile != null) {
                val alert: ParsedTandemResponse? = when (pending.inquiredType) {
                    ALERT_INQUIRED_TYPE_FIXED -> ParsedTandemResponse.AlertFixedMessage(
                        pending.messageType, pending.actionType ?: 0, pending.raw,
                    )
                    ALERT_INQUIRED_TYPE_LEFT_RIGHT -> ParsedTandemResponse.AlertFixedMessageWithLeftRightSelection(
                        pending.messageType, pending.actionType ?: 0, pending.raw,
                    )
                    ALERT_INQUIRED_TYPE_FOREGROUND -> ParsedTandemResponse.AlertForegroundMessage(
                        pending.messageType, pending.actionType ?: 0, pending.raw,
                    )
                    ALERT_INQUIRED_TYPE_FLEXIBLE -> ParsedTandemResponse.AlertFlexibleMessage(
                        pending.messageType, pending.itemCodes, pending.actionType ?: 0, pending.raw,
                    )
                    else -> null
                }
                alert?.let {
                    HeadphoneAdapterRegistry.buildReplyAlertCommand(profile, it, positive).forEach(::sendCommand)
                }
            }
        }
        if (!positive) {
            leAudioCoordinator.cancel()
        }
        _state.update {
            it.copy(
                leAudioPendingAlert = null,
                leAudioSwitchPending = leAudioCoordinator.isRunning(),
            )
        }
    }

    private fun sendLeAudioHeadsetCommand(
        enabled: Boolean,
        changeConnectionMethod: Boolean,
    ): Boolean {
        val commands = HeadphoneAdapterRegistry.buildSetLeAudioEnabledCommands(
            profile = ensureConnectedProfile(),
            enabled = enabled,
            changeConnectionMethod = changeConnectionMethod,
        )
        if (commands.isEmpty()) return false
        appendLog(
            "Sending Sony LE Audio ${if (enabled) "enable" else "disable"} " +
                "changeConnectionMethod=$changeConnectionMethod; " +
                "waiting for headset confirmation"
        )
        commands.forEach(::sendCommand)
        return true
    }

    /**
     * Raises the same pairing guide the switch-driven hand-over shows.
     *
     * Resetting the headset is part of that guide, and for in-ear models resetting means
     * putting the buds back in the case — which drops the connection. Anything hosted on the
     * connected-device page dies with it, so this routes through the pending-alert state that
     * the top-level dialog already observes.
     */
    fun showLeAudioPairingGuide() {
        if (_state.value.leAudioPendingAlert != null) return
        appendLog("LE Audio pairing guide requested from device detail")
        _state.update { it.copy(leAudioPendingAlert = LeAudioPendingAlert(targetEnabled = true)) }
    }

    /**
     * Bonds the headset's LE-only identity: the phone-side half of the LE Audio hand-over.
     *
     * Sony exposes that identity as a separate, non-discoverable LE advertiser. Classic
     * discovery — all the system pairing screen runs — never surfaces it, so without this
     * the phone keeps its BR/EDR-only bond and stays on A2DP no matter what the headset
     * was told to do.
     */
    fun startLeAudioDevicePairing() {
        val current = _state.value
        leAudioDevicePairer.start(
            targetName = current.connectedDevice?.name,
            reportedLeAddresses = current.leaState.leAudioAddresses,
            excludeAddresses = listOfNotNull(current.connectedDevice?.address),
        )
    }

    /** Drops the LE identity bonded by [startLeAudioDevicePairing]. */
    fun unpairLeAudioDevice(address: String? = null) {
        val target = address ?: _state.value.leAudioDevicePairState.bondedAddress
        if (target == null) {
            appendLog("no module-created LE Audio bond to remove")
            return
        }
        if (leAudioDevicePairer.unpair(target)) {
            _state.update { it.copy(leAudioDevicePairState = LeAudioDevicePairState()) }
        }
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

    fun setGesturePreset(keyCode: Int, presetCode: Int) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change gesture preset.")
            return
        }
        if (!canWrite(HeadphoneFeature.GESTURE_OPERATIONS)) {
            appendLog("Gesture preset write is disabled for current profile")
            return
        }
        val gesture = _state.value.gestureOperationsState
        val keys = gesture.uiKeys()
        val keyIndex = keys.indexOfFirst { it.key.code.toInt() and 0xFF == keyCode }
        val target = keys.getOrNull(keyIndex) ?: return
        val preset = target.availablePresets.firstOrNull { it.code.toInt() and 0xFF == presetCode }
            ?: return
        val presets = currentGesturePresetsForWrite().toMutableList()
        if (keyIndex !in presets.indices) return
        presets[keyIndex] = preset
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetGesturePresetsCommands(profile, presets).forEach(::sendCommand)
        scheduleGestureRefresh()
    }

    /** Set one Quick Access slot while preserving all other raw service IDs. */
    fun setQuickAccessFunction(actionIndex: Int, functionCode: Int) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change Quick Access.")
            return
        }
        if (!canWrite(HeadphoneFeature.QUICK_ACCESS)) {
            appendLog("Quick Access write is disabled for current profile")
            return
        }
        val quickAccess = _state.value.quickAccessState
        val action = quickAccess.actions.getOrNull(actionIndex)
        if (action == null || functionCode !in 0..0xFF) {
            appendLog("Quick Access function $functionCode is invalid for action $actionIndex")
            return
        }
        // The capability table describes the slot/action and is not the complete
        // SAR service directory. A previously unselected service can be absent
        // here even though Sound Connect still allows it to be assigned.
        val currentFunctionCode = quickAccess.functionCodes.getOrNull(actionIndex)
            ?: action.currentFunctionCode
        val accepted = QuickAccessServiceCatalog.isKnown(functionCode) ||
            functionCode in action.availableFunctionCodes ||
            functionCode == currentFunctionCode
        if (!accepted) {
            appendLog("Quick Access function $functionCode is not in catalog or capability action $actionIndex")
            return
        }
        val functionCodes = quickAccess.actions.mapIndexed { index, item ->
            quickAccess.functionCodes.getOrNull(index) ?: item.defaultFunctionCode
        }.toMutableList()
        if (actionIndex !in functionCodes.indices) return
        functionCodes[actionIndex] = functionCode
        val profile = ensureConnectedProfile()
        val commands = HeadphoneAdapterRegistry.buildSetQuickAccessFunction(profile, functionCodes)
        if (commands.isEmpty()) {
            appendLog("Quick Access write produced no command")
            return
        }
        pendingQuickAccessFunctionCodes = functionCodes.toList()
        mainHandler.removeCallbacks(quickAccessConfirmTimeoutRunnable)
        commands.forEach(::sendCommand)
        mainHandler.postDelayed(quickAccessConfirmTimeoutRunnable, QUICK_ACCESS_CONFIRM_TIMEOUT_MS)
        mainHandler.postDelayed({
            if (_state.value.deviceInfo.protocolReady && _state.value.connectedDevice != null) {
                refreshBasics()
            }
        }, GESTURE_REFRESH_AFTER_WRITE_MS)
    }

    /**
     * Select the NC/NCSS/ambient/off states cycled by an ambient-sound gesture.
     * Sony encodes the selected set as one AssignableSettingsFunction; the
     * ordinary gesture action itself remains read-only in the UI.
     */
    fun setGestureAmbientModes(selected: Set<GestureNoiseControlMode>) {
        if (selected.size < 2) {
            appendLog("Ambient gesture selection ignored: at least two modes are required")
            return
        }
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change gesture ambient modes.")
            return
        }
        if (!canWrite(HeadphoneFeature.GESTURE_OPERATIONS)) {
            appendLog("Gesture ambient-mode write is disabled for current profile")
            return
        }
        val function = gestureFunctionForModes(selected) ?: run {
            appendLog("No Sony gesture function represents ambient modes=$selected")
            return
        }
        val gesture = _state.value.gestureOperationsState
        // Sound Connect keeps the complete EXT_PARAM list and changes only the
        // ambient-control preset entry.  EXT_PARAM has no key byte, so rebuilding
        // the list by left/right UI key can shift or drop presets and is not safe.
        val ambientPresets = listOf(
            AssignableSettingsPreset.AMBIENT_SOUND_CONTROL,
            AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_QUICK_ACCESS,
            AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_QUICK_ACCESS_BT_CLASSIC_ONLY,
            AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_MIC,
            AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_LISTENING_MODE,
        )
        val ambientPreset = ambientPresets.firstOrNull { preset ->
            gesture.capabilities.any { capability -> preset in capability.presets } &&
                gesture.mappings.any { mapping -> mapping.preset == preset }
        } ?: ambientPresets.firstOrNull { preset -> gesture.mappings.any { it.preset == preset } }

        if (ambientPreset == null) {
            appendLog("Gesture ambient-mode write ignored: no ambient-control preset in current mapping set")
            return
        }
        val mappingIndex = gesture.mappings.indexOfFirst { it.preset == ambientPreset }
        val mapping = gesture.mappings.getOrNull(mappingIndex)
        if (mapping == null || mapping.mappings.isEmpty()) {
            appendLog("Gesture ambient-mode write ignored: ambient preset has no actions")
            return
        }
        // The official app identifies this action by its current ambient function.
        // Do the same instead of searching every physical key/action capability.
        val actionIndex = mapping.mappings.indexOfFirst { it.function.isGestureAmbientFunction() }
        if (actionIndex < 0) {
            appendLog("Gesture ambient-mode write ignored: no ambient action in preset=$ambientPreset")
            return
        }
        val currentAction = mapping.mappings[actionIndex]
        if (currentAction.function == function) {
            appendLog("Gesture ambient modes already use ${function.name}", writeLogcat = false)
            return
        }

        // Preserve every returned preset, its order, and every unrelated action.
        val mappings = gesture.mappings.toMutableList()
        mappings[mappingIndex] = mapping.copy(
            mappings = mapping.mappings.toMutableList().also {
                it[actionIndex] = currentAction.copy(function = function)
            },
        )
        val profile = ensureConnectedProfile()
        val commands = HeadphoneAdapterRegistry.buildSetGestureMappingsCommands(profile, mappings)
        if (commands.isEmpty()) {
            appendLog("Gesture ambient-mode write produced no command")
            return
        }
        appendLog("Writing ambient gesture preset=$ambientPreset action=${currentAction.action} function=${function.name}")
        commands.forEach(::sendCommand)
        scheduleGestureRefresh()
    }

    private fun AssignableSettingsFunction.isGestureAmbientFunction(): Boolean = this in setOf(
        AssignableSettingsFunction.NC_ASM_OFF,
        AssignableSettingsFunction.NC_ASM,
        AssignableSettingsFunction.NC_OFF,
        AssignableSettingsFunction.ASM_OFF,
        AssignableSettingsFunction.NC_NCSS_ASM_OFF,
        AssignableSettingsFunction.NC_NCSS_ASM,
        AssignableSettingsFunction.NC_NCSS_OFF,
        AssignableSettingsFunction.NCSS_ASM_OFF,
        AssignableSettingsFunction.NC_NCSS,
        AssignableSettingsFunction.NCSS_ASM,
        AssignableSettingsFunction.NCSS_OFF,
    )

    private fun gestureFunctionForModes(
        modes: Set<GestureNoiseControlMode>,
    ): AssignableSettingsFunction? = when (modes) {
        setOf(GestureNoiseControlMode.NOISE_CANCELLING, GestureNoiseControlMode.AMBIENT_SOUND) ->
            AssignableSettingsFunction.NC_ASM
        setOf(GestureNoiseControlMode.NOISE_CANCELLING, GestureNoiseControlMode.OFF) ->
            AssignableSettingsFunction.NC_OFF
        setOf(GestureNoiseControlMode.AMBIENT_SOUND, GestureNoiseControlMode.OFF) ->
            AssignableSettingsFunction.ASM_OFF
        setOf(
            GestureNoiseControlMode.NOISE_CANCELLING,
            GestureNoiseControlMode.AMBIENT_SOUND,
            GestureNoiseControlMode.OFF,
        ) -> AssignableSettingsFunction.NC_ASM_OFF
        setOf(
            GestureNoiseControlMode.NOISE_CANCELLING,
            GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
        ) -> AssignableSettingsFunction.NC_NCSS
        setOf(
            GestureNoiseControlMode.NOISE_CANCELLING,
            GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
            GestureNoiseControlMode.AMBIENT_SOUND,
        ) -> AssignableSettingsFunction.NC_NCSS_ASM
        setOf(
            GestureNoiseControlMode.NOISE_CANCELLING,
            GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
            GestureNoiseControlMode.OFF,
        ) -> AssignableSettingsFunction.NC_NCSS_OFF
        setOf(
            GestureNoiseControlMode.NOISE_CANCELLING,
            GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
            GestureNoiseControlMode.AMBIENT_SOUND,
            GestureNoiseControlMode.OFF,
        ) -> AssignableSettingsFunction.NC_NCSS_ASM_OFF
        setOf(
            GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
            GestureNoiseControlMode.AMBIENT_SOUND,
            GestureNoiseControlMode.OFF,
        ) -> AssignableSettingsFunction.NCSS_ASM_OFF
        setOf(
            GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
            GestureNoiseControlMode.AMBIENT_SOUND,
        ) -> AssignableSettingsFunction.NCSS_ASM
        setOf(
            GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
            GestureNoiseControlMode.OFF,
        ) -> AssignableSettingsFunction.NCSS_OFF
        else -> null
    }

    fun setGestureFunction(keyCode: Int, actionCode: Int, functionCode: Int) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change gesture action.")
            return
        }
        if (!canWrite(HeadphoneFeature.GESTURE_OPERATIONS)) {
            appendLog("Gesture action write is disabled for current profile")
            return
        }
        val gesture = _state.value.gestureOperationsState
        val keys = gesture.uiKeys()
        val keyIndex = keys.indexOfFirst { it.key.code.toInt() and 0xFF == keyCode }
        val targetKey = keys.getOrNull(keyIndex) ?: return
        val targetAction = targetKey.actions.firstOrNull {
            it.action.code.toInt() and 0xFF == actionCode &&
                it.availableFunctions.any { function -> function.code.toInt() and 0xFF == functionCode }
        } ?: return
        val function = targetAction.availableFunctions.first { it.code.toInt() and 0xFF == functionCode }
        val presets = currentGesturePresetsForWrite()
        val mappings = currentGestureMappingsForWrite(presets).toMutableList()
        if (mappings.isEmpty()) {
            appendLog("Gesture action write ignored: no complete current mapping set")
            return
        }
        val current = mappings.getOrNull(keyIndex) ?: return
        val actionIndex = current.mappings.indexOfFirst { it.action == targetAction.action }
        val updatedActions = current.mappings.toMutableList()
        val updated = AssignableSettingsActionFunction(targetAction.action, function)
        if (actionIndex < 0) {
            // A RET_EXT_PARAM can be partial on some firmware. Do not lose a
            // legal capability action merely because it was absent in that read.
            updatedActions += updated
        } else {
            updatedActions[actionIndex] = updated
        }
        mappings[keyIndex] = current.copy(mappings = updatedActions)
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetGestureMappingsCommands(profile, mappings).forEach(::sendCommand)
        scheduleGestureRefresh()
    }

    private fun currentGesturePresetsForWrite(): List<AssignableSettingsPreset> {
        val gesture = _state.value.gestureOperationsState
        val keys = gesture.uiKeys()
        return keys.mapIndexed { index, key ->
            gesture.presets.getOrNull(index) ?: key.currentPreset
        }
    }

    private fun currentGestureMappingsForWrite(
        presets: List<AssignableSettingsPreset>,
    ): List<AssignableSettingsMapping> {
        val gesture = _state.value.gestureOperationsState
        val keys = gesture.uiKeys()
        val usedMappingIndices = mutableSetOf<Int>()
        val result = keys.mapIndexed { index, key ->
            val preset = presets.getOrNull(index) ?: key.currentPreset
            val mappingIndex = gesture.mappings.indices.firstOrNull { mappingIndex ->
                mappingIndex == index &&
                    mappingIndex !in usedMappingIndices &&
                    gesture.mappings[mappingIndex].preset == preset
            } ?: gesture.mappings.indices.firstOrNull { mappingIndex ->
                mappingIndex !in usedMappingIndices && gesture.mappings[mappingIndex].preset == preset
            }
            mappingIndex?.let(usedMappingIndices::add)
            val current = mappingIndex?.let { gesture.mappings[it] }
            val actions = current?.mappings?.toMutableList()
                ?: key.actions.map { action ->
                    AssignableSettingsActionFunction(action.action, action.function)
                }.toMutableList()
            AssignableSettingsMapping(preset, actions)
        }
        // The wire format has no key byte; dropping an empty item would shift
        // every following key's mapping. Refuse the write instead of emitting a
        // corrupt key-to-mapping association.
        return result.takeIf { it.all { mapping -> mapping.mappings.isNotEmpty() } }.orEmpty()
    }

    private fun scheduleGestureRefresh() {
        mainHandler.postDelayed({
            if (_state.value.deviceInfo.protocolReady && _state.value.connectedDevice != null) {
                refreshGestureOperationsState()
            }
        }, GESTURE_REFRESH_AFTER_WRITE_MS)
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

    fun setPlaybackVolume(volume: Int) {
        if (!_state.value.deviceInfo.protocolReady || !canWrite(HeadphoneFeature.PLAYBACK_CONTROL)) return
        val step = _state.value.playbackState.musicVolumeStep
        if (step <= 0) return
        val clamped = volume.coerceIn(0, step - 1)
        HeadphoneAdapterRegistry.buildSetPlaybackVolumeCommands(ensureConnectedProfile(), clamped)
            .forEach(::sendCommand)
        // Optimistic; the device's follow-up RET/NTFY corrects if needed.
        _state.update { it.copy(playbackState = it.playbackState.copy(musicVolume = clamped)) }
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
                playbackState = PlaybackState(),
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
            pendingQuickAccessFunctionCodes = null
            mainHandler.removeCallbacks(quickAccessConfirmTimeoutRunnable)
            mainHandler.removeCallbacks(playbackRefreshRunnable)
            awaitingCapabilityInfo = false
            pendingCapabilityCounter = null
            pendingCapabilityIdentifier = ""
            mainHandler.removeCallbacks(capabilityInfoTimeoutRunnable)
            clearSupportFunctionProbeState()
        }
        val pendingForConnection = if (connected && device != null) {
            pendingMultipointToggle?.takeIf { it.address.equals(device.address, ignoreCase = true) }
                ?: run {
                    if (pendingMultipointToggle != null) {
                        pendingMultipointToggle = null
                        mainHandler.removeCallbacks(multipointToggleReconcileRunnable)
                    }
                    null
                }
        } else {
            pendingMultipointToggle
        }
        _state.update {
            val deviceInfo = if (connected) {
                it.deviceInfo.withResolvedModelImage(device)
            } else {
                DeviceInfoState()
            }
            // Keep a probe-derived profile when the same device re-fires a
            // connection-state event (GATT then SPP handshakes each call this).
            // Re-resolving to the neutral profile here would discard the
            // RET_SUPPORT_FUNCTION probe results (batteryQueries, writable NC
            // types), reverting refresh to single battery and disabling writes.
            val sameDevice = it.connectedDevice?.address == device?.address
            val profile = if (connected && device != null) {
                if (sameDevice && it.connectedProfile != null) {
                    it.connectedProfile
                } else {
                    // A connection-state event may arrive before the capability
                    // probe runs (GATT and SPP each fire one). Resolving to the
                    // neutral profile here would reset formFactor to UNKNOWN and
                    // leave the headset rendered as TWS until a fresh probe lands.
                    // If a probe result is cached for this device, restore it now.
                    val resolved = HeadphoneAdapterRegistry.resolve(device, deviceInfo.modelName)
                    resolveFromCache(resolved, device.address)
                }
            } else {
                null
            }
            val resolvedDeviceInfo = deviceInfo.withProfileFallback(profile)
            // Read the stack-level battery (AVRCP/A2DP) as a fallback for the Tandem
            // read. The Tandem battery is empty until the capability probe resolves
            // the model (formFactor is UNKNOWN before then), which left over-ear
            // devices without any reported battery — the stack still has one. Only
            // fall back when no directional (L/R) level is present, so TWS with a
            // valid Tandem left/right read keep their richer layout.
            val systemBattery = if (connected && device != null) {
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
                    val current = it.batteryState
                    val fallbackSingle = systemBattery
                        ?.takeIf { current.single == null && current.left == null && current.right == null }
                    if (fallbackSingle != null) {
                        BatteryState(single = fallbackSingle, raw = listOf(fallbackSingle))
                    } else {
                        current
                    }
                } else {
                    BatteryState()
                },
                noiseControlState = if (connected) it.noiseControlState else NoiseControlState(),
                eqState = if (connected) it.eqState else EqState(),
                leaState = if (connected) it.leaState else LeaState(),
                quickAccessState = if (connected) it.quickAccessState else QuickAccessState(),
                gestureOperationsState = if (connected) it.gestureOperationsState else GestureOperationsState(),
                multipointState = when {
                    !connected -> MultipointState()
                    sameDevice -> it.multipointState
                    else -> {
                        val cached = device?.address?.let(::readCapabilityCache)
                        val optimisticValue = pendingForConnection?.let { request ->
                            if (request.decision == MultipointToggleDecision.CANCELLED) request.original else request.target
                        }
                        MultipointState(
                            supported = profile?.supports(HeadphoneFeature.MULTIPOINT) == true,
                            inquiredType = cached?.multipointTypeCode,
                            maxPairedDevices = cached?.maxPairedDevices ?: 0,
                            maxConnectedDevices = cached?.maxConnectedDevices ?: 0,
                            supportsFileTransfer = cached?.supportsFileTransfer,
                            // Restore the last confirmed value immediately. A
                            // following GS GET still remains authoritative.
                            multipointEnabled = optimisticValue ?: cached?.multipointEnabled,
                            pendingMultipointToggle = pendingForConnection
                                ?.takeIf { it.decision != MultipointToggleDecision.CANCELLED }
                                ?.target,
                        )
                    }
                },
                wearingState = if (connected) it.wearingState else WearingState(),
                eqUiCapability = if (connected) profile?.eqUiCapability else null,
                playbackStatus = if (connected) it.playbackStatus else PlaybackStatus.UNKNOWN,
                playbackState = if (connected) it.playbackState else PlaybackState(),
                endpointDiagnostic = if (connected) it.endpointDiagnostic else null,
                table2Diagnostic = if (connected) it.table2Diagnostic else null,
                permissionIssue = if (connected) it.permissionIssue else null,
                scanState = if (connected) "Connected" else "Idle",
                supportedFeatures = featureStatusesFor(profile),
                probeComplete = if (connected) it.probeComplete else false,
            )
        }
    }

    override fun onReady(info: SonyBleConnectionInfo) {
        _state.update {
            val base = (it.connectedProfile ?: it.connectedDevice?.let { device ->
                HeadphoneAdapterRegistry.resolve(device, it.deviceInfo.modelName)
            })?.copy(transport = info.transport.toHeadphoneTransport())
            // Bind the neutral profile to the protocol generation the transport
            // endpoints actually expose (V1 MC endpoint → V1, V2 HPC/SPP → V2).
            val profile = base?.let { p ->
                SonyTandemHeadphoneAdapter.withEndpointChannels(p, info.channels, info.sppUuid)
            }
            it.copy(
                connectionInfo = info,
                connectedProfile = profile,
                eqUiCapability = profile?.eqUiCapability,
                deviceInfo = it.deviceInfo.copy(protocolReady = true, protocolVersion = null, protocolVersionAccepted = null),
                endpointDiagnostic = null,
                table2Diagnostic = null,
                permissionIssue = null,
                supportedFeatures = featureStatusesFor(profile),
            )
        }
        appendLog("Tandem channel ready: transport=${info.transport}, mtu=${info.mtu}, writable=${info.writableValueLength}, channels=${info.channels}")
        probeCapabilities()
    }

    override fun onMessage(channel: TandemChannel, raw: ByteArray) {
        appendLog("RX [$channel] ${raw.hexString()}")
        val profile = _state.value.connectedProfile
            ?: runCatching { ensureConnectedProfile() }.getOrNull()
            ?: run {
                appendLog("Drop RX [$channel] frame: no connected device yet")
                return
            }
        when (val parsed = HeadphoneAdapterRegistry.parse(profile, channel, raw)) {
            is ParsedTandemResponse.DeviceInfo -> applyDeviceInfo(parsed)
            is ParsedTandemResponse.CommonStatus -> applyCommonStatus(parsed)
            is ParsedTandemResponse.Battery -> applyBattery(parsed)
            is ParsedTandemResponse.EqEbb -> applyEqEbb(parsed)
            is ParsedTandemResponse.EqEbbExtendedInfo -> applyEqEbbExtendedInfo(parsed)
            is ParsedTandemResponse.NoiseControl -> applyNoise(parsed)
            is ParsedTandemResponse.PlaybackAck -> applyPlayback(parsed)
            is ParsedTandemResponse.PlaybackCapability -> applyPlaybackCapability(parsed)
            is ParsedTandemResponse.PlaybackMetadata -> applyPlaybackMetadata(parsed)
            is ParsedTandemResponse.PlaybackMetadataField -> applyPlaybackMetadataField(parsed)
            is ParsedTandemResponse.PlaybackMetadataInvalidated -> applyPlaybackMetadataInvalidated(parsed)
            is ParsedTandemResponse.PlaybackVolume -> applyPlaybackVolume(parsed)
            is ParsedTandemResponse.LeaStatus -> applyLeaStatus(parsed)
            is ParsedTandemResponse.LeaPairedHistoryStatus -> applyLeaPairedHistory(parsed)
            is ParsedTandemResponse.LeaCapability -> applyLeaCapability(parsed)
            is ParsedTandemResponse.LeaConnectionMode -> appendLog(
                "LEA Table2 connection mode type=0x%02X mode=%s result=%s".format(
                    parsed.inquiredTypeCode,
                    parsed.mode,
                    parsed.result,
                )
            )
            is ParsedTandemResponse.LeaSettingAvailability -> applyLeaSettingAvailability(parsed)
            is ParsedTandemResponse.LeaParameterNotification -> applyLeaParameterNotification(parsed)
            is ParsedTandemResponse.QuickAccess -> applyQuickAccess(parsed)
            is ParsedTandemResponse.QuickAccessCapability -> applyQuickAccessCapability(parsed)
            is ParsedTandemResponse.QuickAccessStatus -> applyQuickAccessStatus(parsed)
            is ParsedTandemResponse.AssignableSettingsCapability -> applyAssignableSettingsCapability(parsed)
            is ParsedTandemResponse.AssignableSettingsPresets -> applyAssignableSettingsPresets(parsed)
            is ParsedTandemResponse.AssignableSettingsStatus -> applyAssignableSettingsStatus(parsed)
            is ParsedTandemResponse.AssignableSettingsExtendedParam -> applyAssignableSettingsExtendedParam(parsed)
            is ParsedTandemResponse.WearingStatus -> applyWearingStatus(parsed)
            is ParsedTandemResponse.MultipointCapability -> applyMultipointCapability(parsed)
            is ParsedTandemResponse.MultipointStatus -> applyMultipointStatus(parsed)
            is ParsedTandemResponse.MultipointDevices -> applyMultipointDevices(parsed)
            is ParsedTandemResponse.MultipointActionResult -> applyMultipointActionResult(parsed)
            is ParsedTandemResponse.SourceSwitchStatus -> applySourceSwitchStatus(parsed)
            is ParsedTandemResponse.SourceSwitchResult -> applySourceSwitchResult(parsed)
            is ParsedTandemResponse.MusicHandOverStatus -> applyMusicHandOverStatus(parsed)
            is ParsedTandemResponse.GeneralSettingCapability -> applyGeneralSettingCapability(parsed)
            is ParsedTandemResponse.GeneralSettingStatus -> applyGeneralSettingStatus(parsed)
            is ParsedTandemResponse.GeneralSettingParam -> applyGeneralSettingParam(parsed)
            is ParsedTandemResponse.AlertFixedMessage -> applyAlertFixedMessage(parsed)
            is ParsedTandemResponse.AlertForegroundMessage -> applyAlertForegroundMessage(parsed)
            is ParsedTandemResponse.AlertFixedMessageWithLeftRightSelection -> applyAlertLeftRightMessage(parsed)
            is ParsedTandemResponse.AlertFlexibleMessage -> applyAlertFlexibleMessage(parsed)
            is ParsedTandemResponse.AlertLeAudioNotification -> applyAlertLeAudioNotification(parsed)
            is ParsedTandemResponse.Unknown -> applyKnownOrUnknown(parsed)
            is ParsedTandemResponse.Table2Common -> applyTable2Diagnostic(channel, parsed)
            is ParsedTandemResponse.Table2Generic -> applyTable2Diagnostic(channel, parsed)
            is ParsedTandemResponse.SupportFunction -> applySupportFunction(parsed)
            is ParsedTandemResponse.ProtocolInfo -> applyProtocolInfo(parsed)
            is ParsedTandemResponse.ConnectCapabilityInfo -> applyConnectCapabilityInfo(parsed)
            is ParsedTandemResponse.CapabilityInfo -> applyCapabilityInfo(parsed)
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
                        modelColorCode = response.colorCode ?: info.modelColorCode,
                    )
                }
                else -> info
            }.withResolvedModelImage(current.connectedDevice)
                .withProfileFallback(current.connectedProfile)
            // Preserve the probe-derived profile. Re-resolving here would return
            // the neutral static profile (pure-dynamic match is always null) and
            // discard the RET_SUPPORT_FUNCTION probe results (batteryQueries,
            // writable NC types). Device-info responses arrive on every refresh.
            val profile = current.connectedProfile
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

    private fun DeviceInfoState.withResolvedModelImage(device: DiscoveredSonyDevice?): DeviceInfoState {
        val preferredModelName = modelName ?: device?.name?.removePrefix("LE_")
        val match = modelImageCatalog.resolve(
            preferredModelName,
            modelColor ?: parseModelColor(seriesAndColor),
            modelColorCode,
        )
        return copy(
            modelImageUrl = match?.imageUrl,
            modelImageSourceColor = match?.sourceColor,
            modelColor = match?.modelColor ?: modelColor ?: parseModelColor(seriesAndColor),
            modelColorCode = modelColorCode,
        )
    }

    private fun applyBattery(response: ParsedTandemResponse.Battery) {
        _state.update { current ->
            val battery = current.batteryState
            // Which battery kinds this device actually queries. A plain BATTERY
            // reply only makes sense for single-battery devices (headsets); on TWS
            // it is a stray reply to the neutral-profile GET sent while the
            // capability gate is still running and must not populate `single`
            // (otherwise the UI renders a single battery next to L/R/Cradle).
            val supported = current.connectedProfile?.capabilities?.batteryQueries.orEmpty()
            current.copy(
                batteryState = when (response.kind) {
                    PowerInquiredType.BATTERY ->
                        if (PowerInquiredType.BATTERY in supported) battery.copy(
                            // A reported 0% for a bud means it is not on-link (disconnected);
                            // map it to null so consumers render "disconnected" instead of a
                            // misleading 0%. The charging case (CRADLE) keeps its raw value.
                            single = response.values.firstOrNull().takeIf { it != 0 },
                            left = null,
                            right = null,
                            cradle = null,
                            raw = response.values.filterNotNull(),
                        ) else battery
                    PowerInquiredType.LEFT_RIGHT_BATTERY -> battery.copy(
                        single = null,
                        left = response.values.getOrNull(0).takeIf { it != 0 },
                        right = response.values.getOrNull(1).takeIf { it != 0 },
                        raw = response.values.filterNotNull(),
                    )
                    PowerInquiredType.CRADLE_BATTERY -> battery.copy(
                        single = null,
                        cradle = response.values.firstOrNull(),
                        raw = response.values.filterNotNull(),
                    )
                    else -> battery.copy(raw = response.values.filterNotNull())
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
        // Band geometry is only discoverable at runtime; the extended-info bands
        // list is the authoritative band count (raw bands, Clear Bass included).
        if (response.bands.size > 0) {
            _state.update { current ->
                val profile = current.connectedProfile
                if (profile == null || profile.capabilities.eqConfig.bandCount == response.bands.size) {
                    current
                } else {
                    current.copy(
                        connectedProfile = profile.copy(
                            capabilities = profile.capabilities.copy(
                                eqConfig = profile.capabilities.eqConfig.copy(bandCount = response.bands.size),
                            )
                        ),
                        eqUiCapability = profile.eqUiCapability.copy(
                            visibleBandCount = (response.bands.size - 1).coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
        val address = _state.value.connectedDevice?.address
        if (!address.isNullOrBlank()) {
            updateCapabilityCache(address) { entry ->
                entry.copy(
                    eqBandInfo = response.bands.map { band ->
                        EqBandInfoCache(
                            typeCode = band.type?.code?.toInt()?.and(0xFF) ?: -1,
                            value = band.value,
                        )
                    },
                )
            }
        }
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

    private fun refreshGestureOperationsState() {
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildRefreshGestureOperationsCommands(profile)
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
    }

    private fun schedulePlaybackStateReconcile() {
        mainHandler.removeCallbacks(playbackReconcileRunnable)
        mainHandler.postDelayed(playbackReconcileRunnable, PLAYBACK_RECONCILE_AFTER_COMMAND_MS)
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
                    noiseAdaptiveEnabled = response.noiseAdaptiveEnabled
                        ?: current.noiseControlState.noiseAdaptiveEnabled,
                    noiseAdaptiveSensitivity = response.noiseAdaptiveSensitivity
                        ?: current.noiseControlState.noiseAdaptiveSensitivity,
                    raw = response.values,
                )
            )
        }
    }

    private fun applyPlayback(response: ParsedTandemResponse.PlaybackAck) {
        val sourceLabel = if (response.isUnsolicited) "NTFY" else "RET"
        appendLog("Playback notification [$sourceLabel] ${response.values} status=${response.status}")
        response.enabled?.let { enabled ->
            _state.update { it.copy(playbackState = it.playbackState.copy(enabled = enabled)) }
        }
        if (response.status != PlaybackStatus.UNKNOWN) {
            applyPlaybackStatus(response.status, source = "Tandem", isUnsolicited = response.isUnsolicited)
        } else {
            updatePlaybackStatusFromAudioManager()
        }
    }

    private fun applyPlaybackCapability(response: ParsedTandemResponse.PlaybackCapability) {
        appendLog(
            "Playback capability step=${response.musicVolumeStep} " +
                "buttons=${response.supportsPlaybackButtons} meta=${response.supportsMetadata}"
        )
        _state.update {
            it.copy(playbackState = it.playbackState.copy(musicVolumeStep = response.musicVolumeStep))
        }
        // Mirror into the capability cache (official keeps the step in its
        // capability DB) so a cache-hit reconnect restores the volume row
        // without waiting for this round-trip. Entry creation stays owned by
        // the probe path; only refresh an existing entry here.
        val address = _state.value.connectedDevice?.address ?: return
        val existing = capabilityCache[address] ?: return
        if (existing.playVolumeStep != response.musicVolumeStep) {
            capabilityCache[address] = existing.copy(playVolumeStep = response.musicVolumeStep)
            cacheSink?.invoke(CapabilityProbeCache.encode(capabilityCache))
        }
        updateCapabilityCache(address) { entry ->
            entry.copy(
                capabilityValues = entry.capabilityValues.replaceCapabilityValue(
                    CapabilityValueCache(
                        domain = "PLAY",
                        inquiredTypeCode = response.inquiredTypeCode,
                        values = response.raw.unsignedList(),
                    ),
                ),
                playbackSupportsButtons = response.supportsPlaybackButtons,
                playbackSupportsMetadata = response.supportsMetadata,
            )
        }
    }

    private fun PlaybackName.toUiValue(): String? = when (status) {
        PlaybackNameStatus.SETTLED -> text
        PlaybackNameStatus.NOTHING -> ""
        PlaybackNameStatus.UNSETTLED -> null
    }

    private fun applyPlaybackMetadata(response: ParsedTandemResponse.PlaybackMetadata) {
        _state.update {
            it.copy(playbackState = it.playbackState.copy(
                track = response.track.toUiValue(),
                album = response.album.toUiValue(),
                artist = response.artist.toUiValue(),
                genre = response.genre.toUiValue(),
            ))
        }
    }

    private fun applyPlaybackMetadataField(response: ParsedTandemResponse.PlaybackMetadataField) {
        val value = response.name.toUiValue()
        _state.update {
            val playback = it.playbackState
            it.copy(playbackState = when (response.dataType) {
                PlaybackDetailedDataType.TRACK_NAME -> playback.copy(track = value)
                PlaybackDetailedDataType.ALBUM_NAME -> playback.copy(album = value)
                PlaybackDetailedDataType.ARTIST_NAME -> playback.copy(artist = value)
                PlaybackDetailedDataType.GENRE_NAME -> playback.copy(genre = value)
                else -> playback
            })
        }
    }

    private fun applyPlaybackMetadataInvalidated(response: ParsedTandemResponse.PlaybackMetadataInvalidated) {
        // Never clear on this signal: v1 NTFYs carry no content, so clearing
        // would flash "unknown track" on every song change. Just refetch.
        appendLog("Playback metadata invalidated (${response.dataType}); refetching")
        mainHandler.removeCallbacks(playbackMetadataRefetchRunnable)
        mainHandler.postDelayed(playbackMetadataRefetchRunnable, PLAYBACK_METADATA_REFETCH_DELAY_MS)
    }

    private fun applyPlaybackVolume(response: ParsedTandemResponse.PlaybackVolume) {
        _state.update { it.copy(playbackState = it.playbackState.copy(musicVolume = response.volume)) }
    }

    private fun applyLeaStatus(response: ParsedTandemResponse.LeaStatus) {
        if (!isExpectedLeaResponse(response.table, response.inquiredTypeCode)) {
            appendLog(
                "Ignoring LEA status from unexpected table/type " +
                    "table=${response.table} type=${response.inquiredTypeCode?.let { "0x%02X".format(it) }}"
            )
            return
        }
        appendLog("LEA status ${response.type} enabled=${response.enabled} streamingL=${response.streamingStatusL} streamingR=${response.streamingStatusR}")
        val next = _state.value.leaState.withConnectionStatus(response)
        _state.update { it.copy(leaState = next) }
        leAudioCoordinator.onHeadsetStreaming(next.streamingStatusL, next.streamingStatusR)
    }

    private fun applyLeaCapability(response: ParsedTandemResponse.LeaCapability) {
        appendLog(
            "LEA Table2 capability type=0x%02X compatibility=%s modes=%s addresses=%s".format(
                response.inquiredTypeCode,
                response.compatibility,
                response.connectionModes,
                response.addresses,
            )
        )
        if (response.addresses.isEmpty()) return
        _state.update { state ->
            state.copy(leaState = state.leaState.copy(leAudioAddresses = response.addresses))
        }
    }

    private fun applyLeaPairedHistory(response: ParsedTandemResponse.LeaPairedHistoryStatus) {
        if (!isExpectedLeaResponse(response.table, response.inquiredTypeCode)) {
            appendLog(
                "Ignoring LEA paired history from unexpected table/type " +
                    "table=${response.table} type=${response.inquiredTypeCode?.let { "0x%02X".format(it) }}"
            )
            return
        }
        appendLog("LEA paired history ${response.type} pairedHistory=${response.pairedHistory}")
        val next = _state.value.leaState.let { current ->
            current.copy(
                pairedHistory = response.pairedHistory?.name ?: current.pairedHistory,
                raw = response.values,
            )
        }
        _state.update { it.copy(leaState = next) }
        leAudioCoordinator.onPairedHistory(next.pairedHistory)
    }

    private fun isExpectedLeaResponse(
        table: dev.sonypods.protocol.SonyTable,
        inquiredTypeCode: Int?,
    ): Boolean {
        val lea = _state.value.connectedProfile?.capabilities?.lea ?: return false
        val expectedTable = when (lea.historyVariant) {
            HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1 -> dev.sonypods.protocol.SonyTable.NO_1
            HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2 -> dev.sonypods.protocol.SonyTable.NO_2
            else -> return false
        }
        return table == expectedTable && inquiredTypeCode == lea.historyInquiredTypeCode
    }

    private fun applyLeaParameterNotification(
        response: ParsedTandemResponse.LeaParameterNotification,
    ) {
        appendLog(
            "LEA parameter notification setting=${response.setting} " +
                "value=${response.enabled} values=${response.values}"
        )
        if (response.setting != LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING || response.enabled == null) return
        val next = _state.value.leaState.withSettingNotification(response)
        _state.update { it.copy(leaState = next) }
        leAudioCoordinator.onHeadsetSetting(next.enabled)
    }

    private fun applyLeaSettingAvailability(
        response: ParsedTandemResponse.LeaSettingAvailability,
    ) {
        appendLog(
            "LE Audio setting availability=${response.available} notification=${response.isNotification}"
        )
    }

    private fun applyQuickAccess(response: ParsedTandemResponse.QuickAccess) {
        val expected = pendingQuickAccessFunctionCodes
        if (expected != null && response.functionCodes == expected) {
            pendingQuickAccessFunctionCodes = null
            mainHandler.removeCallbacks(quickAccessConfirmTimeoutRunnable)
            appendLog("Quick Access write confirmed functions=${response.functionCodes}")
        } else {
            appendLog("Quick Access key=${response.key} functions=${response.functionCodes}")
        }
        _state.update { current ->
            val quickAccess = current.quickAccessState
            val functionName = response.functions.firstOrNull()?.name
            quickAccess.copy(
                lrKeyFunction = if (quickAccess.key == QuickAccessKey.L_R_KEY) functionName else quickAccess.lrKeyFunction,
                ncAmbKeyFunction = if (quickAccess.key == QuickAccessKey.NC_AMB_KEY) functionName else quickAccess.ncAmbKeyFunction,
                functionCodes = response.functionCodes,
                actions = quickAccess.actions.mapIndexed { index, action ->
                    action.copy(currentFunctionCode = response.functionCodes.getOrNull(index) ?: action.currentFunctionCode)
                },
                raw = response.values,
            ).let { updated -> current.copy(quickAccessState = updated) }
        }
    }

    private fun applyQuickAccessCapability(response: ParsedTandemResponse.QuickAccessCapability) {
        appendLog(
            "Quick Access capability key=${response.key} actions=${response.actions.size}",
            writeLogcat = false,
        )
        _state.update { current ->
            val quickAccess = current.quickAccessState
            val actions = response.actions.mapIndexed { index, action ->
                QuickAccessActionState(
                    action = action.action,
                    currentFunctionCode = quickAccess.functionCodes.getOrNull(index),
                    defaultFunctionCode = action.defaultFunctionCode,
                    availableFunctionCodes = action.availableFunctionCodes,
                )
            }
            current.copy(
                quickAccessState = quickAccess.copy(
                    key = response.key,
                    type = response.type,
                    actions = actions,
                    raw = response.values,
                )
            )
        }
        val address = _state.value.connectedDevice?.address
        if (!address.isNullOrBlank()) {
            updateCapabilityCache(address) { entry ->
                entry.copy(
                    quickAccessCapability = QuickAccessCapabilityCache(
                        keyCode = response.key.code.toInt() and 0xFF,
                        typeCode = response.type.code.toInt() and 0xFF,
                        actions = response.actions.map { action ->
                            QuickAccessActionCapabilityCache(
                                actionCode = action.action.code.toInt() and 0xFF,
                                defaultFunctionCode = action.defaultFunctionCode,
                                availableFunctionCodes = action.availableFunctionCodes,
                            )
                        },
                    ),
                )
            }
        }
    }

    private fun applyQuickAccessStatus(response: ParsedTandemResponse.QuickAccessStatus) {
        appendLog("Quick Access enabled=${response.enabled}", writeLogcat = false)
        _state.update { current ->
            current.copy(
                quickAccessState = current.quickAccessState.copy(
                    enabled = response.enabled,
                    raw = response.values,
                )
            )
        }
    }

    private fun applyAssignableSettingsCapability(response: ParsedTandemResponse.AssignableSettingsCapability) {
        appendLog(
            "Gesture capability keys=${response.keys.size} " +
                response.keys.joinToString { "${it.key}/${it.type}/${it.defaultPreset}" },
            writeLogcat = false,
        )
        _state.update { current ->
            current.copy(
                gestureOperationsState = current.gestureOperationsState.copy(
                    capabilities = response.keys,
                    rawCapability = response.values,
                )
            )
        }
        val address = _state.value.connectedDevice?.address
        if (!address.isNullOrBlank()) {
            updateCapabilityCache(address) { entry ->
                entry.copy(
                    gestureCapabilities = response.keys.map { key ->
                        GestureKeyCapabilityCache(
                            keyCode = key.key.code.toInt() and 0xFF,
                            typeCode = key.type.code.toInt() and 0xFF,
                            defaultPresetCode = key.defaultPreset.code.toInt() and 0xFF,
                            presets = key.presets.map { it.code.toInt() and 0xFF },
                            actionsByPreset = key.actionsByPreset.map { (preset, actions) ->
                                GesturePresetCapabilityCache(
                                    presetCode = preset.code.toInt() and 0xFF,
                                    actions = actions.map { action ->
                                        GestureActionCapabilityCache(
                                            actionCode = action.action.code.toInt() and 0xFF,
                                            defaultFunctionCode = action.defaultFunction.code.toInt() and 0xFF,
                                            availableFunctionCodes = action.availableFunctions.map {
                                                it.code.toInt() and 0xFF
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }
    }

    private fun applyAssignableSettingsPresets(response: ParsedTandemResponse.AssignableSettingsPresets) {
        appendLog("Gesture presets=${response.presets}", writeLogcat = false)
        _state.update { current ->
            current.copy(
                gestureOperationsState = current.gestureOperationsState.copy(
                    presets = response.presets,
                    rawPresets = response.values,
                )
            )
        }
    }

    private fun applyAssignableSettingsStatus(response: ParsedTandemResponse.AssignableSettingsStatus) {
        appendLog("Gesture enabled=${response.enabled}", writeLogcat = false)
        _state.update { current ->
            current.copy(
                gestureOperationsState = current.gestureOperationsState.copy(
                    enabled = response.enabled,
                    rawStatus = response.values,
                )
            )
        }
    }

    private fun applyAssignableSettingsExtendedParam(response: ParsedTandemResponse.AssignableSettingsExtendedParam) {
        appendLog(
            "Gesture mappings=" + response.mappings.joinToString { mapping ->
                "${mapping.preset}:${mapping.mappings.joinToString { "${it.action}=${it.function}" }}"
            },
            writeLogcat = false,
        )
        _state.update { current ->
            current.copy(
                gestureOperationsState = current.gestureOperationsState.copy(
                    mappings = response.mappings,
                    rawMappings = response.values,
                )
            )
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

    private fun applyMultipointCapability(response: ParsedTandemResponse.MultipointCapability) {
        if (response.maxConnectedDevices <= 0 || response.maxPairedDevices <= 0) return
        _state.update {
            if (it.multipointState.inquiredType == 2 && response.inquiredType == 0) {
                return@update it
            }
            val profile = it.connectedProfile?.let { currentProfile ->
                val capabilities = currentProfile.capabilities.copy(
                    features = currentProfile.capabilities.features + HeadphoneFeature.MULTIPOINT,
                )
                currentProfile.copy(
                    multipointTypeCode = response.inquiredType,
                    capabilities = capabilities,
                    featureBindings = buildFeatureBindings(currentProfile.featureProtocolMap, capabilities),
                )
            }
            it.copy(
                connectedProfile = profile,
                multipointState = it.multipointState.copy(
                    supported = true,
                    inquiredType = response.inquiredType,
                    maxPairedDevices = response.maxPairedDevices,
                    maxConnectedDevices = response.maxConnectedDevices,
                    supportsFileTransfer = response.fileTransferInMultiConnection == 0,
                    raw = response.raw.unsignedList(),
                ),
            )
        }
        val address = _state.value.connectedDevice?.address
        if (!address.isNullOrBlank()) {
            updateCapabilityCache(address) { entry ->
                entry.copy(
                    multipointTypeCode = response.inquiredType,
                    maxPairedDevices = response.maxPairedDevices,
                    maxConnectedDevices = response.maxConnectedDevices,
                    supportsFileTransfer = response.fileTransferInMultiConnection == 0,
                )
            }
        }
    }

    private fun applyMultipointStatus(response: ParsedTandemResponse.MultipointStatus) {
        _state.update {
            val current = it.multipointState
            if (current.inquiredType != null &&
                current.inquiredType != response.inquiredType &&
                !(current.inquiredType == 0 && response.inquiredType == 2)
            ) it else it.copy(
                multipointState = current.copy(
                    // A well-formed RET/NTFY_STATUS for a pairing-management
                    // type is itself proof the function exists on this model.
                    supported = true,
                    inquiredType = response.inquiredType,
                    enabled = response.enabled,
                    pairingMode = response.bluetoothMode == 1,
                    raw = response.raw.unsignedList(),
                ),
            )
        }
    }

    private fun applyMultipointDevices(response: ParsedTandemResponse.MultipointDevices) {
        _state.update {
            val current = it.multipointState
            if (current.inquiredType != null &&
                current.inquiredType != response.inquiredType &&
                !(current.inquiredType == 0 && response.inquiredType == 2)
            ) it else {
                // SC `lg0.s`: e() = connectedStatus > 0 sorted ascending,
                // g() = connectedStatus == 0 (history). The playback right is
                // matched against connectedStatus, not a list index.
                val connected = response.devices
                    .filter { device -> device.connected }
                    .sortedBy { device -> device.connectedStatus }
                val history = response.devices.filterNot { device -> device.connected }
                val activeSourceAddress = connected
                    .firstOrNull { device -> response.playbackRight > 0 && device.connectedStatus == response.playbackRight }
                    ?.address
                val profile = it.connectedProfile?.let { currentProfile ->
                    val capabilities = currentProfile.capabilities.copy(
                        features = currentProfile.capabilities.features + HeadphoneFeature.MULTIPOINT,
                    )
                    currentProfile.copy(
                        multipointTypeCode = response.inquiredType,
                        capabilities = capabilities,
                        featureBindings = buildFeatureBindings(currentProfile.featureProtocolMap, capabilities),
                    )
                }
                it.copy(
                    connectedProfile = profile,
                    multipointState = current.copy(
                        supported = true,
                        inquiredType = response.inquiredType,
                        connectedDevices = connected,
                        historyDevices = history,
                        playbackRight = response.playbackRight,
                        activeSourceAddress = activeSourceAddress,
                        raw = response.raw.unsignedList(),
                    ),
                )
            }
        }
    }

    private fun applyMultipointActionResult(response: ParsedTandemResponse.MultipointActionResult) {
        _state.update {
            val current = it.multipointState
            if (current.inquiredType != null && current.inquiredType != response.inquiredType) it else it.copy(
                multipointState = current.copy(
                    result = multipointResultLabel(response.result),
                    resultAddress = response.address,
                    raw = response.raw.unsignedList(),
                ),
            )
        }
        scheduleMultipointRefresh()
    }

    private fun applySourceSwitchStatus(response: ParsedTandemResponse.SourceSwitchStatus) {
        _state.update { it.copy(multipointState = it.multipointState.copy(sourceSwitchEnabled = response.enabled, raw = response.raw.unsignedList())) }
    }

    private fun applySourceSwitchResult(response: ParsedTandemResponse.SourceSwitchResult) {
        _state.update {
            it.copy(multipointState = it.multipointState.copy(
                fixedSourceAddress = response.address,
                sourceSwitchResult = sourceSwitchResultLabel(response.result),
                raw = response.raw.unsignedList(),
            ))
        }
    }

    private fun applyMusicHandOverStatus(response: ParsedTandemResponse.MusicHandOverStatus) {
        // SC `x30/c.java` builds the UI value as the inverse of the wire OnOff:
        // `new v30.e(!rVar.c())`. Our parser reports isOn, so invert here.
        _state.update { it.copy(multipointState = it.multipointState.copy(musicHandOverEnabled = !response.enabled, raw = response.raw.unsignedList())) }
    }

    /**
     * GS RET_CAPABILITY: find the "同时连接2台设备" slot by title, mirroring SC
     * `DeviceCapabilityTableset2.E1()` (ENUM_NAME format + exact title match).
     * Once found, store the slot on the profile so the refresh probes the right
     * slot and the UI toggle can write it.
     */
    private fun applyGeneralSettingCapability(response: ParsedTandemResponse.GeneralSettingCapability) {
        val type = response.type ?: return
        if (response.stringFormat != SonyTandemV2Table1Protocol.GS_STRING_FORMAT_ENUM_NAME.unsigned) return
        if (response.title != SonyTandemV2Table1Protocol.GS_TITLE_MULTIPOINT_SETTING) return
        _state.update { current ->
            val profile = current.connectedProfile ?: return@update current
            current.copy(
                connectedProfile = if (profile.multipointGsSlot == type) profile else profile.copy(multipointGsSlot = type),
                multipointState = current.multipointState.copy(supported = true),
            )
        }
        appendLog("GS multipoint slot discovered: 0x%02X (%s)".format(type, response.title))
        val address = _state.value.connectedDevice?.address
        if (!address.isNullOrBlank()) {
            updateCapabilityCache(address) { entry ->
                entry.copy(
                    capabilityValues = entry.capabilityValues.replaceCapabilityValue(
                        CapabilityValueCache(
                            domain = "GENERAL_SETTING",
                            inquiredTypeCode = type,
                            values = response.raw.unsignedList(),
                        ),
                    ),
                    generalSettingCapability = GeneralSettingCapabilityCache(
                        settingType = response.settingType,
                        stringFormat = response.stringFormat,
                        title = response.title,
                        description = response.description,
                    ),
                )
            }
        }
        saveMultipointCache()
        scheduleMultipointRefresh()
    }

    /** GS RET/NTFY_STATUS (0xD3/0xD5): slot availability, kept for diagnostics. */
    private fun applyGeneralSettingStatus(response: ParsedTandemResponse.GeneralSettingStatus) {
        val type = response.type
        val slot = _state.value.connectedProfile?.multipointGsSlot
        if (type == null || slot != type) return
        appendLog("GS multipoint status slot=0x%02X enabled=${response.enabled}".format(type))
        if (response.enabled != null) {
            _state.update { it.copy(multipointState = it.multipointState.copy(raw = response.raw.unsignedList())) }
        }
    }

    /** GS RET/NTFY_PARAM (0xD7/0xD9): the actual on/off state of the toggle. */
    private fun applyGeneralSettingParam(response: ParsedTandemResponse.GeneralSettingParam) {
        val type = response.type
        val slot = _state.value.connectedProfile?.multipointGsSlot
        if (type == null || slot != type || response.on == null) return
        val pending = pendingMultipointToggle
        if (pending != null) {
            when (pending.decision) {
                MultipointToggleDecision.AWAITING_CONFIRMATION -> {
                    // The device commonly reports the old value between GS SET
                    // and the user's 0x98 reply. Keep the optimistic target for
                    // both old and early target reports; the timeout below is a
                    // fallback for models that do not emit an alert.
                    if (response.on != pending.target) {
                        appendLog("Ignore stale multipoint value=${response.on} while target=${pending.target} is pending")
                    }
                    _state.update {
                        it.copy(multipointState = it.multipointState.copy(
                            multipointEnabled = pending.target,
                            pendingMultipointToggle = pending.target,
                            raw = response.raw.unsignedList(),
                        ))
                    }
                    return
                }
                MultipointToggleDecision.CONFIRMED -> {
                    if (response.on != pending.target) {
                        appendLog("Ignore stale multipoint value=${response.on} after confirmation; target=${pending.target}")
                        return
                    }
                }
                MultipointToggleDecision.CANCELLED -> {
                    if (response.on != pending.original) {
                        appendLog("Ignore stale multipoint value=${response.on} after cancellation; restore=${pending.original}")
                        return
                    }
                }
            }
        }
        if (pending != null) {
            pendingMultipointToggle = null
            mainHandler.removeCallbacks(multipointToggleReconcileRunnable)
        }
        _state.update {
            it.copy(
                multipointState = it.multipointState.copy(
                    multipointEnabled = pending?.let { request ->
                        if (request.decision == MultipointToggleDecision.CANCELLED) request.original else request.target
                    } ?: response.on,
                    raw = response.raw.unsignedList(),
                    pendingMultipointToggle = null,
                ),
            )
        }
        saveMultipointCache()
    }

    private fun reconcileMultipointToggleTimeout() {
        val pending = pendingMultipointToggle ?: return
        // No response arrived in time. Keep the user's chosen value (or the
        // pre-tap value after cancellation) and release the stale-response lock.
        val settled = if (pending.decision == MultipointToggleDecision.CANCELLED) {
            pending.original
        } else {
            pending.target
        }
        pendingMultipointToggle = null
        _state.update {
            it.copy(multipointState = it.multipointState.copy(
                multipointEnabled = settled,
                pendingMultipointToggle = null,
            ))
        }
        saveMultipointCache()
    }

    /** V2 Table1 ALERT_NTFY_PARAM (0x99, FIXED_MESSAGE): surface the multipoint
     * reconnection alerts (7=reconnect, 6=LDAC disable) and the 2-devices-connection
     * alerts (112=enable with LDAC, 113=quality-prior switch, 114=bg connected LDAC,
     * 115=LDAC 990 warning). Other message types ignored. */
    private fun applyAlertFixedMessage(response: ParsedTandemResponse.AlertFixedMessage) {
        appendLog("V2 alert NTFY msgType=${response.messageType} action=${response.actionType}")
        when (response.messageType) {
            SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_MULTIPOINT,
            SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_MULTIPOINT_LDAC_DISABLE,
            SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_ENABLING_2_DEVICES_WITH_LDAC,
            SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_QUALITY_PRIOR_WITH_2_DEVICES,
            SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_CONNECTED_2_DEVICES_BG_WITH_LDAC,
            SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_LDAC_990_WITH_2_DEVICES -> {
                if (pendingMultipointToggle != null) {
                    mainHandler.removeCallbacks(multipointToggleReconcileRunnable)
                }
                _state.update {
                    it.copy(multipointState = it.multipointState.copy(pendingAlertMessageType = response.messageType))
                }
            }
            in SonyTandemV2Table1Protocol.LE_AUDIO_ALERT_MESSAGE_TYPES -> {
                _state.update {
                    it.copy(
                        leAudioSwitchPending = true,
                        leAudioPendingAlert = LeAudioPendingAlert(
                            targetEnabled = fixedLeAudioAlertTargetsLeAudio(response.messageType),
                            inquiredType = ALERT_INQUIRED_TYPE_FIXED,
                            messageType = response.messageType,
                            actionType = response.actionType,
                            raw = response.raw,
                        ),
                    )
                }
            }
            else -> {
                appendLog("Ignoring non-multipoint V2 fixed alert msgType=${response.messageType}")
            }
        }
    }

    private fun applyAlertForegroundMessage(response: ParsedTandemResponse.AlertForegroundMessage) {
        appendLog("V2 foreground alert NTFY msgType=${response.messageType} action=${response.actionType}")
        if (response.messageType in SonyTandemV2Table1Protocol.LE_AUDIO_ALERT_MESSAGE_TYPES) {
            _state.update {
                it.copy(
                    leAudioSwitchPending = true,
                        leAudioPendingAlert = LeAudioPendingAlert(
                            targetEnabled = fixedLeAudioAlertTargetsLeAudio(response.messageType),
                            inquiredType = ALERT_INQUIRED_TYPE_FOREGROUND,
                            messageType = response.messageType,
                            actionType = response.actionType,
                            raw = response.raw,
                    ),
                )
            }
        }
    }

    private fun applyAlertLeftRightMessage(response: ParsedTandemResponse.AlertFixedMessageWithLeftRightSelection) {
        appendLog("V2 left/right alert NTFY msgType=${response.messageType} selected=${response.defaultSelectedSide}")
        if (response.messageType in SonyTandemV2Table1Protocol.LE_AUDIO_ALERT_MESSAGE_TYPES) {
            _state.update {
                it.copy(
                    leAudioSwitchPending = true,
                    leAudioPendingAlert = LeAudioPendingAlert(
                        targetEnabled = fixedLeAudioAlertTargetsLeAudio(response.messageType),
                        inquiredType = ALERT_INQUIRED_TYPE_LEFT_RIGHT,
                        messageType = response.messageType,
                        actionType = response.defaultSelectedSide,
                        raw = response.raw,
                    ),
                )
            }
        }
    }

    private fun applyAlertFlexibleMessage(response: ParsedTandemResponse.AlertFlexibleMessage) {
        appendLog(
            "V2 flexible alert NTFY msgType=${response.messageType} items=${response.itemCodes} " +
                "action=${response.actionType}",
        )
        if (response.messageType == SonyTandemV2Table1Protocol.FLEXIBLE_CHANGE_CONNECTION_WITH_LE_AUDIO_LIMITATION) {
            // C12259c0.c sets needToSkipPairingGuideDialog for flexible type 13.
            skipLeAudioPairingGuide = true
        }
        if (response.messageType in SonyTandemV2Table1Protocol.LE_AUDIO_FLEXIBLE_MESSAGE_TYPES) {
            _state.update {
                it.copy(
                    leAudioSwitchPending = true,
                    leAudioPendingAlert = LeAudioPendingAlert(
                        targetEnabled = flexibleLeAudioAlertTargetsLeAudio(response.messageType),
                        inquiredType = ALERT_INQUIRED_TYPE_FLEXIBLE,
                        messageType = response.messageType,
                        itemCodes = response.itemCodes,
                        actionType = response.actionType,
                        raw = response.raw,
                    ),
                )
            }
        }
    }

    private fun applyAlertLeAudioNotification(response: ParsedTandemResponse.AlertLeAudioNotification) {
        appendLog(
            "LE Audio alert status confirmation=${response.confirmationType} " +
                "notification=${response.isNotification}",
        )
    }

    private fun applyTable2Diagnostic(channel: TandemChannel, response: ParsedTandemResponse) {
        appendLog("Table2 ${response::class.simpleName} channel=$channel raw=${response.raw.hexString()}")
        val diagnostic = table2DiagnosticStateFor(channel, response) ?: return
        _state.update { it.copy(table2Diagnostic = diagnostic) }
    }

    private fun applyProtocolInfo(response: ParsedTandemResponse.ProtocolInfo) {
        val version = response.protocolVersion
        // V1 (2-byte BE, C29903d.f85968b) and V2 (4-byte BE, C30916e.f88128b)
        // whitelists are disjoint, so a single membership test covers both.
        val accepted = SonyTandemConstants.PROTOCOL_VERSIONS.contains(version) ||
            SonyTandemConstants.PROTOCOL_VERSIONS_V2.contains(version)
        if (accepted) {
            appendLog("Protocol version 0x%08X accepted (SC whitelist V1/V2)".format(version))
        } else {
            appendLog("Protocol version 0x%08X rejected: not in SC whitelist".format(version))
        }
        _state.update { current ->
            current.copy(
                deviceInfo = current.deviceInfo.copy(
                    protocolVersion = version,
                    protocolVersionAccepted = accepted,
                ),
                connectedProfile = if (accepted) {
                    current.connectedProfile?.withProtocolVersion(version)
                } else {
                    current.connectedProfile
                },
            )
        }
    }

    private fun applySupportFunction(response: ParsedTandemResponse.SupportFunction) {
        appendLog("Support function list=${response.functions.size} functions=${response.functions.joinToString { it.toString() }}")
        // SC aborts initialization for out-of-whitelist protocol versions
        // (InitializationFailedCause); mirror that by skipping capability probing.
        if (_state.value.deviceInfo.protocolVersionAccepted == false) {
            appendLog("Protocol version rejected; capability probing aborted (SC C29903d/C30916e)")
            clearSupportFunctionProbeState()
            markProbeComplete()
            return
        }
        val table = response.table.takeIf { it != dev.sonypods.protocol.SonyTable.INVALID }
            ?: dev.sonypods.protocol.SonyTable.NO_1
        supportFunctionsByTable[table] = response.functions
        pendingSupportFunctionTables.remove(table)
        if (pendingSupportFunctionTables.isNotEmpty()) {
            appendLog("Support-function table $table received; waiting for $pendingSupportFunctionTables")
            return
        }
        finishSupportFunctionProbe()
    }

    private fun finishSupportFunctionProbe() {
        val functions = supportFunctionsByTable
            .toSortedMap(compareBy { it.ordinal })
            .values
            .flatten()
            .distinctBy { it.table to it.code }
        clearSupportFunctionProbeState()
        if (functions.isEmpty()) {
            appendLog("No support functions received; falling back to direct refresh")
            markProbeComplete()
            refreshBasics()
            return
        }
        val alreadyProbed = _state.value.connectedProfile?.protocolEvidence
            ?.any { it.startsWith("probe:ret-support-function") } == true
        val probeCommands = runCatching {
            _state.value.connectedProfile?.let { profile ->
                SonyCapabilityProbe.buildCapabilityProbeCommands(profile, functions)
            } ?: emptyList()
        }.getOrElse { emptyList() }
        _state.update { current ->
            val profile = current.connectedProfile?.let { profile ->
                SonyCapabilityProbe.applyToProfile(profile, functions, profile.transport)
            } ?: current.connectedProfile
            current.copy(
                connectedProfile = profile,
                eqUiCapability = profile?.eqUiCapability,
                supportedFeatures = featureStatusesFor(profile),
            )
        }
        // Create the cache entry before dispatching the per-domain probes so a
        // very fast device response cannot arrive before there is an entry to
        // merge its detailed capability into.
        saveCapabilityCache(functions)
        if (!alreadyProbed) {
            probeCommands.forEach(::sendCommand)
        }
        refreshBasics()
        markProbeComplete()
    }

    private fun clearSupportFunctionProbeState() {
        mainHandler.removeCallbacks(supportFunctionTimeoutRunnable)
        pendingSupportFunctionTables.clear()
        supportFunctionsByTable.clear()
    }

    private fun applyCapabilityInfo(response: ParsedTandemResponse.CapabilityInfo) {
        val typeHex = response.inquiredTypeCode?.let { "0x%02X".format(it) } ?: "?"
        appendLog(
            "Probe capability domain=${response.domain} type=$typeHex len=${response.raw.size} raw=${response.raw.hexString()}",
            writeLogcat = false,
        )
        _state.update { current ->
            current.copy(
                connectedProfile = current.connectedProfile?.copy(
                    protocolEvidence = current.connectedProfile.protocolEvidence +
                        listOf("probe:ret-capability(${response.domain},type=$typeHex,len=${response.raw.size})"),
                )
            )
        }
        val address = _state.value.connectedDevice?.address
        if (!address.isNullOrBlank()) {
            updateCapabilityCache(address) { entry ->
                entry.copy(
                    capabilityValues = entry.capabilityValues.replaceCapabilityValue(
                        CapabilityValueCache(
                            domain = response.domain,
                            inquiredTypeCode = response.inquiredTypeCode,
                            values = response.values,
                        ),
                    ),
                )
            }
        }
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
    }

    private fun ensureConnectedProfile(): ConnectedHeadphoneProfile {
        val device = _state.value.connectedDevice ?: error("No connected device")
        _state.value.connectedProfile?.let { return it }
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

    /** True only while the BLE/SPP client still owns a usable Tandem transport. */
    fun hasLiveTransport(): Boolean = client.availableChannels().isNotEmpty()

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
            if (!current.debugLogging) current else {
                debugLogForwarder?.invoke(message)
                current.copy(
                    debugLogs = (listOf(message) + current.debugLogs).take(80)
                )
            }
        }
    }

    fun ingestRemoteDebugLog(message: String) {
        appendLog(message, writeLogcat = false)
    }

    companion object {
        @Volatile
        private var instance: SonyHeadphoneRepository? = null

        fun getInstance(
            resourceContext: Context,
            systemContext: Context = resourceContext,
            remoteModelInfoReader: (() -> String?)? = null,
            debugLogForwarder: ((String) -> Unit)? = null,
        ): SonyHeadphoneRepository {
            return instance ?: synchronized(this) {
                instance ?: SonyHeadphoneRepository(
                    resourceContext,
                    systemContext,
                    remoteModelInfoReader,
                    debugLogForwarder,
                ).also { instance = it }
            }
        }

        const val LOG_TAG = "OpenBuds"
        const val PLAY_NTFY_PARAM = 0xA9
        const val LEA_NTFY_STATUS = 0x45
        private const val LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING = 0x0C
        private const val LE_AUDIO_REFRESH_AFTER_SWITCH_MS = 1_000L

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
    FeatureStatus("耳机关机", "Sony USER_POWER_OFF command", profile.supports(HeadphoneFeature.POWER_OFF)),
    FeatureStatus("降噪开关", "NC/ASM gated by current device profile", profile.supports(HeadphoneFeature.NOISE_CONTROL)),
    FeatureStatus("环境声等级", "ASM seamless level when confirmed writable", profile.supports(HeadphoneFeature.AMBIENT_LEVEL)),
    FeatureStatus("播放控制", "Play, pause, previous, next", profile.supports(HeadphoneFeature.PLAYBACK_CONTROL)),
    FeatureStatus("EQ / Clear Bass", "Preset EQ, custom EQ, and Clear Bass", profile.supports(HeadphoneFeature.EQ)),
    FeatureStatus("LE Audio 状态", "Connection type, streaming status, paired history", profile.supports(HeadphoneFeature.LEA_STATUS)),
    FeatureStatus("Quick Access", "Customizable button actions L/R and NC/AMB keys", profile.supports(HeadphoneFeature.QUICK_ACCESS)),
    FeatureStatus("手势操作", "Touch, button and face-tap action assignments", profile.supports(HeadphoneFeature.GESTURE_OPERATIONS)),
    FeatureStatus("双设备管理", "Connect, disconnect and unpair multipoint devices", profile.supports(HeadphoneFeature.MULTIPOINT)),
    FeatureStatus("佩戴检测", "Earpiece fitting and wearing detection status", profile.supports(HeadphoneFeature.WEARING_STATUS)),
    FeatureStatus("Sense / AutoPlay / FOTA", "Advanced modules reserved", false),
)
