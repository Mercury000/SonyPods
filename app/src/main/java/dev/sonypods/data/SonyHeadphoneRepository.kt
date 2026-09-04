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
import dev.sonypods.hook.Log
import androidx.core.content.ContextCompat
import dev.sonypods.ble.DiscoveredSonyDevice
import dev.sonypods.ble.SonyBleClient
import dev.sonypods.ble.SonyBleClientListener
import dev.sonypods.ble.SonyBleConnectionInfo
import dev.sonypods.ble.UnsupportedEndpointDiagnostics
import dev.sonypods.config.ConfigManager
import dev.sonypods.headphones.ConnectedHeadphoneProfile
import dev.sonypods.headphones.EqBandStepScale
import dev.sonypods.headphones.EqUiCapability
import dev.sonypods.headphones.EqWriteContext
import dev.sonypods.headphones.eqUiCapability
import dev.sonypods.headphones.hasClearBassSlot
import dev.sonypods.headphones.HeadphoneAdapterRegistry
import dev.sonypods.headphones.HeadphoneCommand
import dev.sonypods.headphones.HeadphoneFeature
import dev.sonypods.headphones.HeadphoneFormFactor
import dev.sonypods.headphones.HeadphoneProtocolVariant
import dev.sonypods.headphones.HeadphoneTransport
import dev.sonypods.headphones.TandemCodecRegistry
import dev.sonypods.headphones.EqProtocolEngine
import dev.sonypods.headphones.PlaybackDispatchStrategy
import dev.sonypods.headphones.MultipointDeviceAction
import dev.sonypods.headphones.buildFeatureBindings
import dev.sonypods.headphones.SonyCapabilityProbe
import dev.sonypods.headphones.SonyTandemHeadphoneAdapter
import dev.sonypods.headphones.TandemChannel
import dev.sonypods.leaudio.LeAudioSwitchCoordinator
import dev.sonypods.leaudio.LeAudioBond
import dev.sonypods.leaudio.LeAudioProfileGateway
import dev.sonypods.media.MediaPlaybackController
import dev.sonypods.config.CapabilityCacheManager
import dev.sonypods.config.CapabilityStorage
import dev.sonypods.device.UnifiedDeviceIdentityService
import dev.sonypods.device.IdentityType
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.protocol.AmbientSoundMode
import dev.sonypods.protocol.ConnectionQualityMode
import dev.sonypods.protocol.DeviceInfoType
import dev.sonypods.protocol.DseeEffectState
import dev.sonypods.protocol.DseeGeneration
import dev.sonypods.protocol.SoundQualityCodec
import dev.sonypods.protocol.EqBandInformationType
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.protocol.LeaConnectionType
import dev.sonypods.protocol.LeaInquiredType
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
import dev.sonypods.protocol.SonyTandemV2Table2Protocol
import dev.sonypods.protocol.SafeListeningInquiredTypeTable2
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
private const val EQ_CLEAR_BASS_RAW_INDEX = 0
private const val EQ_FIRST_FREQUENCY_RAW_INDEX = 1
private const val PLAYBACK_STALE_RESPONSE_WINDOW_MS = 2_500L
private const val PLAYBACK_METADATA_REFETCH_DELAY_MS = 50L

/** Fallback poll interval (seconds) for the Safe Listening sound-pressure
 * readout when the device's own minimum interval (SL_RET_CAPABILITY) is unknown.
 * SC polls at the device-reported value — `1000 * minimumInterval`. */
private const val SAFE_LISTENING_POLL_DEFAULT_INTERVAL_S = 10

/** Re-issue cadence and cap for the Safe Listening activation handshake. SC's
 * state machine re-sends SET_PARAM every 2s until NTFY_PARAM reports the
 * combination it asked for (up to 30 times before giving up); the readout card
 * does not need to keep trying that long. */
private const val SAFE_LISTENING_ACTIVATION_RETRY_MS = 2_000L
private const val SAFE_LISTENING_ACTIVATION_MAX_ATTEMPTS = 8

/** 连接质量切换重连窗口的硬上限；官方连接进度框同为 30s 自动关闭。 */
private const val CONNECTION_QUALITY_SWITCH_TIMEOUT_MS = 30_000L

/** `exchanged_capabilities.store_group`: SC passes 0 for the V1 command
 * tableset and 1 for V2, so the two generations never share a row. */
private const val STORE_GROUP_V1 = 0
private const val STORE_GROUP_V2 = 1

/** `exchanged_capabilities.command_table_number`: SC's TandemfamilyTableNumber
 * MDR_NO1 / MDR_NO2. */
private const val TANDEM_TABLE_NUMBER_NO1 = 12
private const val TANDEM_TABLE_NUMBER_NO2 = 14

/** Headset-named Tandem migration: how long to wait before the first connect
 * attempt (SC connects straight after its own disconnect; the new link usually
 * forms well under a second, and a failed attempt now retries instead of
 * waiting out a fixed timer). */
private const val TANDEM_MIGRATION_FIRST_DELAY_MS = 600L

/** How long a pending migration may stay unresolved before ordinary
 * connection management resumes. */
private const val TANDEM_MIGRATION_TIMEOUT_MS = 15_000L
private const val GESTURE_REFRESH_AFTER_WRITE_MS = 450L
/** Keep the optimistic GS value alive while the device asks for reconnection
 * confirmation and while the link is being re-established. */
private const val MULTIPOINT_TOGGLE_RECONCILE_TIMEOUT_MS = 5_000L
private const val QUICK_ACCESS_CONFIRM_TIMEOUT_MS = 2_000L
/** How long to wait for CONNECT_RET_CAPABILITY_INFO before falling back to the
 * full RET_SUPPORT_FUNCTION probe (some models/FW may not reply). */
private const val CAPABILITY_INFO_TIMEOUT_MS = 2_500L

/**
 * How long one capability step may go unanswered before the exchange is abandoned.
 *
 * SC needs no such value: its step blocks on a `CountDownLatch` and the transport's own failure
 * raises `InterruptedException` / `IOException` out of the sequence. Our inbound path is a
 * callback, so a step that is simply ignored by the headset has nothing to raise — this bounds
 * it. Timing out throws exactly like SC's interrupt: the sequence ends and nothing is persisted.
 */
private const val CAPABILITY_STEP_TIMEOUT_MS = 3_000L

/**
 * How long the initial-value gate waits with no further progress before releasing the
 * UI anyway. Re-armed on every reply the headset sends, so a slow burst that is still
 * being answered is never cut short — only silence ends the wait.
 */
private const val INITIAL_VALUE_IDLE_TIMEOUT_MS = 3_000L

/**
 * How long the channel has to stay quiet after the last domain answered before the gate
 * opens.
 *
 * Answering a domain is not the same as finishing it: BATTERY is three queries
 * (left/right, cradle), EQ is status plus param plus band info, gestures are capability
 * plus presets plus mappings. Releasing on the first reply of each domain is what made
 * the page open with most controls still at their defaults. Waiting for a short quiet
 * window instead covers the rest of every domain, and the capability replies that fill
 * in available presets and functions along with them.
 */
private const val INITIAL_VALUE_SETTLE_MS = 1_200L

/**
 * The ceiling on that wait. Both other timers can be held open indefinitely — the quiet
 * window by unsolicited traffic (playback metadata while music is playing pushes
 * notifications of its own), and a stuck write queue by a transport that never reports its
 * last write complete — so the gate also opens once this much time has passed since arming,
 * however busy the channel looks. Three times what a full LE burst needs, which is the slow
 * case the gate exists for.
 */
private const val INITIAL_VALUE_MAX_WAIT_MS = 15_000L

/** What a surface outside the app renders: battery level and the noise-control mode. */
private val ESSENTIAL_INITIAL_VALUE_DOMAINS = setOf(
    HeadphoneFeature.BATTERY,
    HeadphoneFeature.NOISE_CONTROL,
)
private val MULTIPOINT_ADDRESS = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

/**
 * One Tandem session's capability initializer, mirroring Sound Connect's
 * `uv.d.c` (V1) / `wv.e.e` (V2).
 *
 * SC creates one of these per connection and registers it on that connection's
 * own dispatcher (`le0.C22925e.m89633c`), unregistering when the initializer
 * returns. Every value the initializer decides on — the identifier and tableset
 * counter from CONNECT_RET_CAPABILITY_INFO, the store group, the
 * support-function collector — is therefore instance state, and a frame from a
 * headset that has since been replaced can never reach a newer session's state.
 *
 * Ours is selected by the inbound frame's source address for the same reason,
 * and holds the same values so nothing is read off a global that a reconnect may
 * already have moved.
 */
private class CapabilityProbeSession(
    /** The headset this session belongs to; frames are matched against it. */
    var deviceAddress: String?,
    /** `exchanged_capabilities.store_group`, fixed at session start. */
    val storeGroup: Int,
) {
    /** CONNECT_RET_CAPABILITY_INFO is outstanding. */
    var awaitingCapabilityInfo = false

    /** Identifier and tableset revision the headset reported, verbatim. */
    var identifier: String? = null
    var capabilityCounter: Int? = null

    /** A live per-domain probe is running, so its replies are worth storing. */
    var probing = false

    /** Stored capabilities are being replayed; replies must not be re-recorded. */
    var replaying = false

    /**
     * One entry per GET_SUPPORT_FUNCTION that went out, removed as its reply
     * lands: the probe completes when this empties and nothing else ends the
     * wait. A list, not a set, so two requests mapping to the same table still
     * expect two replies.
     */
    val pendingSupportFunctionTables = mutableListOf<dev.sonypods.protocol.SonyTable>()

    /**
     * A GET_SUPPORT_FUNCTION burst is out and its replies are still being
     * collected. Without it a duplicate or unsolicited RET_SUPPORT_FUNCTION
     * arriving after the probe closed would rebuild the profile from that one
     * table alone, narrowing what the full set had produced.
     */
    var supportFunctionProbeRunning = false
    val supportFunctionsByTable =
        mutableMapOf<dev.sonypods.protocol.SonyTable, List<SonySupportedFunction>>()
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
    val windNoiseReduction: Boolean = false,
    val noiseAdaptiveEnabled: Boolean = false,
    val noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.STANDARD,
    val raw: List<Int> = emptyList(),
)

data class EqState(
    val enabled: Boolean? = null,
    val preset: EqPresetId? = null,
    /** Base EqPresetId the device pairs with the ULT mode byte on
     * PRESET_EQ_AND_ULT_MODE devices; while [preset] shows the ULT_1/ULT_2
     * display marker, writes must resend this preset (SC `hf0.d`). */
    val ultBasePreset: EqPresetId? = null,
    val presetType: EqEbbInquiredType = EqEbbInquiredType.PRESET_EQ,
    val clearBass: Int? = null,
    val bandSteps: List<Int> = emptyList(),
    val rawBandSteps: List<Int> = emptyList(),
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
    val stage: LeAudioBond.Stage = LeAudioBond.Stage.IDLE,
    val message: String = "",
    /** The LE identity this module bonded, so disabling LE Audio can remove it again. */
    val bondedAddress: String? = null,
) {
    /**
     * Back to resting, keeping the bonded address.
     *
     * A stage and its failure text describe one run. Carrying them forward is what showed a stale
     * "bonding was rejected" the next time the guide opened, before anything had started. The
     * address is a durable hint about this headset rather than a record of the run, so it stays.
     */
    fun resting(): LeAudioDevicePairState =
        if (stage == LeAudioBond.Stage.IDLE && message.isEmpty()) {
            this
        } else {
            copy(stage = LeAudioBond.Stage.IDLE, message = "")
        }
}

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
            // An OUT_OF_RANGE entry is a positional placeholder for a byte off the
            // shared preset table (V1 keeps it to preserve key alignment), never a
            // selectable value: fall back to the capability's default preset.
            val currentPreset = presets.getOrNull(index)
                ?.takeIf { it != AssignableSettingsPreset.OUT_OF_RANGE }
                ?: capability.defaultPreset
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
    /** Raw RET/NTFY result code from the multipoint action commands; the UI
     * layer owns the display strings — the engine never formats copy. */
    val resultCode: Int? = null,
    val resultAddress: String? = null,
    val sourceSwitchEnabled: Boolean? = null,
    val fixedSourceAddress: String? = null,
    /** Raw SourceSwitchControlResult code; rendered (if ever) by the UI layer. */
    val sourceSwitchResultCode: Int? = null,
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

data class SpeakToChatState(
    val enabled: Boolean = false,
    val effectStatus: dev.sonypods.protocol.SmartTalkingEffectStatus = dev.sonypods.protocol.SmartTalkingEffectStatus.IDLE,
    val sensitivity: dev.sonypods.protocol.SmartTalkingDetectionSensitivity = dev.sonypods.protocol.SmartTalkingDetectionSensitivity.AUTO,
    val modeOutTime: dev.sonypods.protocol.SmartTalkingModeOutTime = dev.sonypods.protocol.SmartTalkingModeOutTime.MID,
    val voiceFocus: Boolean = false,
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
    /**
     * PLAY RET/NTFY_STATUS byte 1: whether the headset's own playback controller is operable.
     *
     * false is its steady state under LE Audio, where media control belongs to the LE Audio
     * media-control path rather than to Tandem — the headset then answers a Tandem playback SET
     * with nothing at all. null = not reported yet. This decides which way a tap is dispatched;
     * it is deliberately not surfaced to the UI, because the controls stay live either way.
     */
    val controllerEnabled: Boolean? = null,
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

/** Live sound-quality badge values — what the headset reports right now via the
 * COMMON domain (SC `n10.a` codec / `u60.a` upscaling effect). A null codec
 * hides that badge; the DSEE badge draws only while [dseeActive] (VALID). */
data class SoundQualityState(
    val codec: SoundQualityCodec? = null,
    val dseeGeneration: DseeGeneration? = null,
    val dseeActive: Boolean = false,
)

/** Current sound pressure readout from SAFE_LISTENING_RET_EXTENDED_PARAM. */
enum class SafeListeningStatus { UNKNOWN, VALID, NOT_PLAYING, IN_CALL, DETACHED, ROOT_REQUIRED }

data class SafeListeningState(
    /** Headset-reported sound pressure in dB; null until a VALID read. */
    val levelDb: Int? = null,
    val status: SafeListeningStatus = SafeListeningStatus.UNKNOWN,
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
    val speakToChatState: SpeakToChatState = SpeakToChatState(),
    val playbackStatus: PlaybackStatus = PlaybackStatus.UNKNOWN,
    val playbackState: PlaybackState = PlaybackState(),
    val soundQualityState: SoundQualityState = SoundQualityState(),
    val safeListeningState: SafeListeningState = SafeListeningState(),
    /** Device-reported minimum poll interval (seconds) for the sound-pressure
     * readout, from SL_RET_CAPABILITY; null until probed. */
    val safeListeningMinimumInterval: Int? = null,
    /** DSEE / DSEE Extreme (AUDIO-domain upscaling) toggle; null until answered
     * or unsupported — the UI only draws it when the profile advertises it. */
    val upscalingEnabled: Boolean? = null,
    /** Bluetooth 连接质量（AUDIO 域 CONNECTION_MODE 系）：当前 PriorMode；
     * null = 尚未应答或不支持。UI 只在能力表宣告时绘制选择器。 */
    val connectionQualityMode: ConnectionQualityMode? = null,
    /** 该设置的可用性（AUDIO_STATUS EnableDisable）；null = 未应答。
     * 官方在 DISABLE 时将选项整组置灰。 */
    val connectionQualityEnabled: Boolean? = null,
    /** SET 已发出、尚未收到 RET/NTFY：官方此窗口内用引导页/进度框接管界面，
     * 播放控制不可用；我们以等价方式置灰播放控制。 */
    val connectionQualitySwitching: Boolean = false,
    val endpointDiagnostic: EndpointDiagnosticState? = null,
    val table2Diagnostic: Table2DiagnosticState? = null,
    val supportedFeatures: List<FeatureStatus> = featureStatusesFor(null),
    val autoReconnect: Boolean = false,
    val strictSonyScanFilter: Boolean = false,
    val preferredProtocol: String = "Sony Tandem",
    /**
     * True once the connection-time initial-value burst is done: every domain in
     * [HeadphoneAdapter.initialValueDomains] has answered, nothing is left to transmit and
     * the channel has stayed quiet for [INITIAL_VALUE_SETTLE_MS] (or the wait timed out).
     *
     * [capabilitiesKnown] only says which features exist; it is true before a single value
     * has come back, which over LE is some seconds ahead of the first reply. The first reply
     * of each domain is not enough either — a domain is several queries, and commands leave
     * the phone one at a time behind their ACK. The app UI is gated on this so it never opens
     * on defaults that cannot be tapped, or on values that visibly jump moments later.
     */
    val initialValuesReady: Boolean = false,
    /**
     * The same gate reduced to what a surface outside the app needs: battery and
     * noise control. The island and the connection notification render only those, so
     * they are released as soon as those two are in rather than waiting for the whole
     * burst the detail page needs.
     */
    val essentialValuesReady: Boolean = false,
) {
    /**
     * The device answered with its own capability table (or the counter-matched cache
     * restored one). Everything model-shaped is derived from it: form factor, how many
     * batteries to ask about, which noise-control types are writable, EQ.
     *
     * Until it lands the profile is the neutral fallback — UNKNOWN form factor, a single
     * battery question, nothing writable — so this is what a surface must wait for before
     * rendering anything about the model. "The probe stopped" is deliberately not that fact:
     * it is also true when the probe stopped *without* a table, and rendering on it showed a
     * pair of buds as a single-battery headband.
     */
    val capabilitiesKnown: Boolean
        get() = connectedProfile?.capabilitiesKnown == true
}

/** Direction of a debug-log frame: what the module sent, what it received, or a state line. */
enum class DebugLogKind { TX, RX, INFO }

/** A single debug-page line; [text] is the human-readable line, [kind] drives its rendering. */
data class DebugLogEntry(val text: String, val kind: DebugLogKind)

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
    private val debugLogForwarder: ((String, DebugLogKind) -> Unit)? = null,
) : SonyBleClientListener {
    private val appContext = systemContext.applicationContext ?: systemContext
    private val client = SonyBleClient(appContext, this)
    private val mediaController = MediaPlaybackController(appContext)
    private val modelImageCatalog = SonyModelImageCatalog(remoteModelInfoReader)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val leAudioProfileGateway = LeAudioProfileGateway(appContext)
    private val _state = MutableStateFlow(SonyHeadphoneUiState())
    private val _debugLogs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
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
                capturePairingSeed()
                _state.update {
                    it.copy(
                        leAudioSwitchPending = false,
                        leAudioPendingAlert = LeAudioPendingAlert(targetEnabled = enabled),
                    )
                }
            }

            override fun onFinished(success: Boolean, message: String) {
                appendLog("LE Audio switch ${if (success) "finished" else "failed"}: $message")
                if (disableUnpairPending) {
                    // The headset has confirmed (or definitively refused) the switch; the
                    // module-created LE identity has served its purpose either way.
                    disableUnpairPending = false
                    appendLog("dropping the module-created LE Audio bond")
                    leAudioBond.cancel()
                    unpairLeAudioDevice()
                }
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
    private val leAudioBond = LeAudioBond(
        appContext,
        object : LeAudioBond.Listener {
            override fun onStageChanged(
                stage: LeAudioBond.Stage,
                message: String,
                bondedAddress: String?,
            ) {
                _state.update { current ->
                    current.copy(
                        leAudioDevicePairState = LeAudioDevicePairState(
                            stage = stage,
                            message = message,
                            // Which address the run bonded, so a later disable can remove it and so
                            // the bluetooth process can answer "is there an LE identity" with no
                            // session to resolve from. A hint only: whether that bond still exists
                            // is read from the stack, never from this having been written.
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
    private var disableUnpairPending = false
    /** Headset the LE Audio pairing flow targets, captured by [capturePairingSeed]. */
    private var pairingSeedAddress: String? = null
    private var pairingSeedName: String? = null
    /**
     * The last LE endpoint addresses the headset reported, kept outside [_state] on purpose.
     *
     * [LeaState] is cleared on disconnect, and the pairing flow runs after the guide has told the
     * user to reset the headset — so the live copy is always empty by the time it is needed. Only
     * devices declaring supported-function 0x64 ever report any, so this is usually empty and the
     * advertisement decides; when it is not empty it outranks everything.
     */
    private var pairingSeedLeAddresses: List<String> = emptyList()
    // Official behaviour: a v1 metadata NTFY carries no content, so re-GET the
    // whole playback block; 50ms debounce coalesces notification bursts.
    private val playbackMetadataRefetchRunnable = Runnable {
        if (_state.value.deviceInfo.protocolReady && _state.value.connectedDevice != null) {
            refreshPlaybackState()
        }
    }
    /** Safe Listening current-sound-pressure poll. Armed only while the module
     * UI actually shows the sound-pressure card (battery: SC polls at the
     * device's minimum interval, and only while the view reading it is
     * attached). Re-arms itself; self-stops on stop()/disconnect. */
    private var safeListeningPollArmed = false

    /** True once the module asked the headset to enter preview mode, and so owes
     * it a matching off. SC's readout presenter pairs setPreview(true) on attach
     * with setPreview(false) on detach. The other way to make the readout carry a
     * level is the persistent Safe Listening feature, which drives the headset's
     * listening-log collection and belongs to the user: when SC's mirrored
     * preference says it is on, the module only re-asserts it (as SC does on every
     * connect) and owes nothing on exit. */
    private var safeListeningPreviewRequested = false

    private var safeListeningActivationAttempts = 0

    private val safeListeningActivationRunnable: Runnable = Runnable {
        pumpSafeListeningActivation()
    }

    private val safeListeningPollRunnable: Runnable = Runnable {
        if (!safeListeningPollArmed) return@Runnable
        sendSafeListeningCommand("GET safe listening level") {
            SonyTandemV2Table2Protocol.buildGetSafeListeningExtendedParam(it)
        }
        val intervalSec = _state.value.safeListeningMinimumInterval?.takeIf { it > 0 }
            ?: SAFE_LISTENING_POLL_DEFAULT_INTERVAL_S
        mainHandler.postDelayed(safeListeningPollRunnable, 1000L * intervalSec)
    }

    private fun sendSafeListeningCommand(
        label: String,
        build: (SafeListeningInquiredTypeTable2) -> ByteArray,
    ) {
        val profile = _state.value.connectedProfile ?: return
        val type = profile.capabilities.safeListeningInquiredType ?: return
        if (!profile.supports(HeadphoneFeature.SAFE_LISTENING)) return
        val channel = profile.bindingFor(HeadphoneFeature.SAFE_LISTENING)?.channel
            ?: profile.defaultResponseChannel()
        sendCommandIfReady(
            HeadphoneCommand(label = "$label ${type.name}", bytes = build(type), channel = channel)
        )
    }

    /** Arm the Safe Listening sound-pressure poll. Called by the engine when the
     * sound-pressure card becomes visible. The readout only reports a live level
     * once one of the two switches is on, so the poll waits for the NTFY_PARAM that
     * confirms the write — SC likewise only runs its timer once its state machine
     * has settled in PREVIEW (readout card) or ON/PAUSE (playback card). */
    fun startSafeListeningPoll() {
        safeListeningPollArmed = true
        safeListeningActivationAttempts = 0
        mainHandler.removeCallbacks(safeListeningPollRunnable)
        mainHandler.removeCallbacks(safeListeningActivationRunnable)
        pumpSafeListeningActivation()
    }

    /** Assert the activation SC's mirrored preference calls for, re-issuing it on
     * SC's 2s cadence until NTFY_PARAM confirms. Timer-driven rather than
     * notify-driven because a dropped SET_PARAM produces no notify to retry from.
     * GET_PARAM has no part in this: its reply carries an unrelated flag, not the
     * two switches. */
    private fun pumpSafeListeningActivation() {
        if (!safeListeningPollArmed) return
        val scMode = ConfigManager.current().scSafeListeningMode
        if (scMode == ConfigManager.SC_SL_MODE_UNKNOWN) {
            // Without SC's switch there is no way to tell whether enabling preview
            // would turn the user's own listening-log collection off, and the headset
            // cannot be asked. Write nothing and let the card say why.
            _state.update {
                it.copy(safeListeningState = SafeListeningState(
                    status = SafeListeningStatus.ROOT_REQUIRED,
                ))
            }
            return
        }
        if (safeListeningActivationAttempts >= SAFE_LISTENING_ACTIVATION_MAX_ATTEMPTS) {
            appendLog("Safe listening activation unconfirmed; polling the readout anyway")
            startSafeListeningReadoutPoll()
            return
        }
        safeListeningActivationAttempts++
        if (scMode == ConfigManager.SC_SL_MODE_ON) {
            // Re-assert the value the user already chose, which is what SC sends on
            // every connect. It re-arms measurement after a reconnect and leaves
            // nothing to restore on exit.
            sendSafeListeningCommand("SET safe listening on") {
                SonyTandemV2Table2Protocol.buildSetSafeListeningParam(it, first = true, second = false)
            }
        } else {
            safeListeningPreviewRequested = true
            sendSafeListeningCommand("SET safe listening preview on") {
                SonyTandemV2Table2Protocol.buildSetSafeListeningParam(it, first = false, second = true)
            }
        }
        mainHandler.postDelayed(safeListeningActivationRunnable, SAFE_LISTENING_ACTIVATION_RETRY_MS)
    }

    private fun startSafeListeningReadoutPoll() {
        if (!safeListeningPollArmed) return
        mainHandler.removeCallbacks(safeListeningActivationRunnable)
        mainHandler.removeCallbacks(safeListeningPollRunnable)
        mainHandler.post(safeListeningPollRunnable)
    }

    /** Disarm the Safe Listening poll; called when the card hides or the link
     * drops. Leaves the headset's own Safe Listening setting alone — only a
     * preview mode the module itself asked for is turned back off. */
    fun stopSafeListeningPoll() {
        safeListeningPollArmed = false
        safeListeningActivationAttempts = 0
        mainHandler.removeCallbacks(safeListeningPollRunnable)
        mainHandler.removeCallbacks(safeListeningActivationRunnable)
        if (!safeListeningPreviewRequested) return
        safeListeningPreviewRequested = false
        sendSafeListeningCommand("SET safe listening off") {
            SonyTandemV2Table2Protocol.buildSetSafeListeningParam(it, first = false, second = false)
        }
    }
    /** 连接质量切换窗口的硬上限：官方连接进度框同为 30s 自动关闭。 */
    private val connectionQualitySwitchTimeoutRunnable = Runnable {
        if (_state.value.connectionQualitySwitching) {
            _state.update { it.copy(connectionQualitySwitching = false) }
            appendLog("Connection quality switch window closed (30s timeout)")
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

    /** SQLite capability storage + working set, the Sound Connect
     * `exchanged_capabilities` shape. Wired by [dev.sonypods.hook.SonyEngineHost]. */
    @Volatile
    private var capabilityStorage: CapabilityStorage? = null

    @Volatile
    private var capabilityCacheManager: CapabilityCacheManager? = null

    /** SC's initializer, structurally: see [startCapabilityExchange]. */
    private val capabilityInitializer = TandemCapabilityInitializer(
        send = { command -> sendCommand(command) },
        log = { message -> appendLog(message) },
    )

    /** Hook-host fallback invoked only when the current model has no catalog image. */
    @Volatile
    private var modelCatalogFallbackRequester: ((String?, String?, Int?) -> Unit)? = null

    /**
     * The live Tandem session's capability initializer. Recreated for every
     * session, exactly as SC constructs one `uv.d.c` / `wv.e.e` per connection;
     * frames are matched against its [CapabilityProbeSession.deviceAddress] so a
     * late reply from a replaced headset cannot touch it.
     */
    private var capabilitySession: CapabilityProbeSession? = null

    /**
     * Cached capability session from a completed probe, preserved across a Tandem
     * target migration (SC `je0.C19229b.m77698c0`: "Tandem target change case.
     * Keep mActiveMdr instance.").  When the target identity disconnects and the
     * holding identity is about to promote, the session is stashed here keyed by
     * the physical headset's control address.  The next `probeCapabilities()` call
     * for the same control address restores it instead of re-probing.
     */
    private var cachedTandemTargetSession: CapabilityProbeSession? = null

    /**
     * A headset-directed Tandem target migration in flight (LEA_NTFY_PARAM
     * 0x0D / 0x0E). Sound Connect keeps the same session across the move
     * ("Tandem target change case. Keep mActiveMdr instance."); ours is
     * transport-level, so the equivalent is stashing the completed capability
     * session ([cachedTandemTargetSession]) and restoring it on the promoted
     * identity instead of re-running the initializer.
     *
     * 0x0E names the address (and ConnectionType) Tandem moves to; 0x0D only
     * asks the current target to close, leaving the promotion to whatever
     * reconnects next.
     */
    private class PendingTandemMigration(
        val targetAddress: String?,
        val connectionType: LeaConnectionType?,
    )

    private var pendingTandemMigration: PendingTandemMigration? = null

    /** Whether this migration has already dialled its target. One instruction, one dial. */
    private var tandemMigrationConnectIssued = false

    /** Address a headset-directed migration is moving Tandem to, if one is in flight. */
    fun pendingTandemMigrationTarget(): String? = pendingTandemMigration?.targetAddress

    /**
     * The addresses the LE Audio pairing flow is working on, or empty when nothing is in flight.
     *
     * Nothing else in the module may connect to them while it runs: an SPP page to the control
     * identity in the middle of the LE pairing takes the controller and kills it. See
     * [LeAudioBond.involvedAddresses].
     */
    fun leAudioPairingTargets(): Set<String> =
        if (leAudioBond.isRunning()) leAudioBond.involvedAddresses() else emptySet()

    /**
     * Safety valve: a migration that never lands (the new link never comes up,
     * the connect fails) must not block ordinary connects forever.
     */
    private val tandemMigrationTimeoutRunnable = Runnable {
        if (pendingTandemMigration != null) {
            appendLog("Tandem migration timed out; clearing pending state")
            pendingTandemMigration = null
            tandemMigrationConnectIssued = false
        }
    }

    private fun scheduleTandemMigrationTimeout() {
        mainHandler.removeCallbacks(tandemMigrationTimeoutRunnable)
        mainHandler.postDelayed(tandemMigrationTimeoutRunnable, TANDEM_MIGRATION_TIMEOUT_MS)
    }

    /** Set only by the official device-originated flexible Alert type 13. */
    private var skipLeAudioPairingGuide = false
    /**
     * Domains from [HeadphoneAdapterRegistry.initialValueDomains] that the connection-time
     * refresh burst has not been answered on yet. Empty means either "not armed" or
     * "all in"; [initialValueGateArmed] tells the two apart.
     */
    private val pendingInitialValueDomains = mutableSetOf<HeadphoneFeature>()
    private var initialValueGateArmed = false

    /**
     * The channel has gone quiet. Either the burst is genuinely finished (every domain in,
     * nothing left to transmit) or the headset stopped answering a domain it advertised —
     * both release the UI, because spinning forever on a query that will never be answered
     * would keep every other control hidden.
     *
     * A still-busy transport does not count as quiet: commands leave the phone one at a
     * time behind their ACK, so the pause between two of them is not the end of the
     * exchange. In that case the timer is simply re-armed; [initialValueDeadlineRunnable]
     * is what bounds the wait.
     */
    private val initialValueTimeoutRunnable = Runnable {
        if (!initialValueGateArmed) return@Runnable
        if (runCatching { client.hasOutstandingWrites() }.getOrDefault(false)) {
            rearmInitialValueIdle()
            return@Runnable
        }
        markInitialValuesReady(
            if (pendingInitialValueDomains.isEmpty()) {
                "channel settled after the initial burst"
            } else {
                "timed out waiting for $pendingInitialValueDomains"
            },
        )
    }

    /** The absolute ceiling on that wait; see [INITIAL_VALUE_MAX_WAIT_MS]. */
    private val initialValueDeadlineRunnable = Runnable {
        if (initialValueGateArmed) {
            markInitialValuesReady("gave up waiting for $pendingInitialValueDomains")
        }
    }
    private val capabilityInfoTimeoutRunnable = Runnable {
        val session = capabilitySession
        if (session != null && session.awaitingCapabilityInfo) {
            session.awaitingCapabilityInfo = false
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

    /**
     * Tandem debug ring buffer for the in-app debug page. Separate from [state] on
     * purpose: appendLog fires on every TX/RX frame, which must not re-emit the UI
     * state and re-run its collectors. In the engine process, lines additionally
     * reach the app's copy of this buffer via the ACTION_DEBUG_LOG broadcast.
     */
    val debugLogs: StateFlow<List<DebugLogEntry>> = _debugLogs.asStateFlow()

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
        leAudioBond.cancel()
        leAudioProfileGateway.close()
        clearSupportFunctionProbeState()
        clearInitialValueGate()
        mainHandler.removeCallbacksAndMessages(null)
        client.close()
        pendingMultipointToggle = null
        // SonyBleClient.close() intentionally does not notify its listener because
        // it is used for generation teardown.  The singleton repository is reused by
        // the next libxposed generation, so explicitly clear every connection-scoped
        // value here; otherwise protocolReady remains true while availableChannels()
        // is empty and the next generation will never reconnect.
        pendingTandemMigration = null
        tandemMigrationConnectIssued = false
        cachedTandemTargetSession = null
        onConnectionStateChanged(connected = false, device = null)
        pendingPlaybackStatus = null
        pendingQuickAccessFunctionCodes = null
        capabilityCacheManager?.clear()
        modelCatalogFallbackRequester = null
    }

    /** Wire the SQLite capability storage (Sound Connect `exchanged_capabilities`
     * shape) and its in-memory working set. */
    fun attachCapabilityStorage(storage: CapabilityStorage) {
        capabilityStorage = storage
        capabilityCacheManager = CapabilityCacheManager(storage)
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

    /**
     * @param initial true for the connection-time burst. It arms the initial-value gate,
     *   so consumers can wait for the replies instead of rendering defaults; a later
     *   user-triggered refresh must not re-arm it and close an open detail page.
     */
    /** Wall clock of the last full refresh burst; see [fullRefreshAgeMs]. */
    private var lastFullRefreshAtMs = 0L

    /**
     * Rebuilds the capability tableset from the control identity's stored row.
     *
     * A session parked on the holding identity cannot probe — that identity answers with the
     * LEA-only support-function list — and [probeCapabilities] runs once per Tandem channel, so
     * there is no later attempt either. Left at that, `capabilitiesKnown` never becomes true: the
     * phase stays CONNECTING and every control surface is dead. Observed twice on 2026-09-04, once
     * right after a successful LE bond (the headset brings its GATT link up on the LE identity) and
     * once the moment the LE Audio switch moved the session there on purpose.
     *
     * The tableset is not missing, only elsewhere. Sound Connect keys its capability store by
     * identifier for exactly this reason, and the control identity's row was written by its own
     * session. Rebuilding from that row is the same funnel a counter hit uses
     * ([applyConnectCapabilityInfo]), so nothing here is a new code path — only a new reason to
     * enter it.
     *
     * Moving the session instead does not work: dialing the control identity while the holding
     * identity owns the link fails every attempt and the retries turn into a reconnect storm.
     */
    private fun restoreControlIdentityCapabilities(holdingAddress: String): Boolean {
        val control = UnifiedDeviceIdentityService.resolveControlAddress(holdingAddress)
        if (control.equals(holdingAddress, ignoreCase = true)) return false
        val profile = runCatching { ensureConnectedProfile() }.getOrNull() ?: return false
        val storeGroup = storeGroupFor(profile)
        val session = CapabilityProbeSession(deviceAddress = holdingAddress, storeGroup = storeGroup)
        session.identifier = control
        capabilitySession = session
        val restored = restoreCapabilitiesFromStorage(session, control, storeGroup)
        appendLog(
            if (restored) {
                "Rebuilt the capability tableset from control identity $control " +
                    "(storeGroup=$storeGroup)"
            } else {
                "No stored capability tableset for control identity $control; control stays " +
                    "unavailable until that identity runs its own session"
            }
        )
        if (!restored) capabilitySession = null
        return restored
    }

    fun refreshBasics(initial: Boolean = false) {
        lastFullRefreshAtMs = SystemClock.elapsedRealtime()
        if (!_state.value.deviceInfo.protocolReady) {
            if (_state.value.connectedDevice != null && _state.value.endpointDiagnostic != null) {
                appendLog("Refresh requested for unsupported endpoint; rerunning GATT diagnostics")
                client.refreshUnsupportedEndpointProbe()
            } else {
                onBluetoothUnavailable("Sony Tandem channel is not ready; cannot refresh device state.")
            }
            // The gate exists to wait for this burst's replies. Nothing is being sent,
            // so there is nothing to wait for — leaving it closed would hold the UI on
            // a session whose channel is already gone.
            if (initial) markInitialValuesReady("channel not ready for the initial refresh")
            return
        }
        if (client.availableChannels().isEmpty()) {
            onBluetoothUnavailable("Sony Tandem channel is no longer available; reconnect required.")
            if (initial) markInitialValuesReady("no channel for the initial refresh")
            return
        }
        val profile = ensureConnectedProfile()
        if (initial) armInitialValueGate(profile)
        HeadphoneAdapterRegistry.buildRefreshCommands(profile)
            .forEach(::sendCommand)
        updatePlaybackStatusFromAudioManager()
    }

    /**
     * Age of the last full refresh burst. The engine answers most status requests
     * from the cache — the headset pushes NTFYs on change — and uses this to tell
     * when a burst is due again as drift repair for a possibly missed push.
     */
    fun fullRefreshAgeMs(): Long = SystemClock.elapsedRealtime() - lastFullRefreshAtMs

    /**
     * Start waiting for the values the burst about to be sent will bring back.
     *
     * Only one arming per connection matters: once the gate has opened, every consumer
     * has already been told the session is operable and taking that back would flicker
     * the UI back to its connecting state.
     */
    private fun armInitialValueGate(profile: ConnectedHeadphoneProfile) {
        if (_state.value.initialValuesReady) return
        val domains = runCatching { HeadphoneAdapterRegistry.initialValueDomains(profile) }
            .getOrDefault(emptySet())
        pendingInitialValueDomains.clear()
        pendingInitialValueDomains += domains
        initialValueGateArmed = true
        mainHandler.removeCallbacks(initialValueTimeoutRunnable)
        mainHandler.removeCallbacks(initialValueDeadlineRunnable)
        if (pendingInitialValueDomains.isEmpty()) {
            markInitialValuesReady("no queryable domain for ${profile.protocolName}")
            return
        }
        appendLog("Awaiting initial values $pendingInitialValueDomains")
        publishEssentialValuesReady()
        rearmInitialValueIdle()
        mainHandler.postDelayed(initialValueDeadlineRunnable, INITIAL_VALUE_MAX_WAIT_MS)
    }

    /**
     * Cross off the domain a freshly parsed reply belongs to, then wait for the channel to
     * settle.
     *
     * The checklist alone is not a completion signal: one reply marks a domain as *started*,
     * while BATTERY, EQ, gestures and multipoint each answer with several, and the
     * capability replies that fill in available presets and functions carry no domain at
     * all. So the gate never opens here — every reply only re-arms the quiet-window timer,
     * and [initialValueTimeoutRunnable] is what releases the UI once the headset has stopped
     * talking and nothing is left to transmit. [initialValueDeadlineRunnable] bounds it.
     */
    private fun noteInitialValue(parsed: ParsedTandemResponse) {
        if (!initialValueGateArmed) return
        val domain = initialValueDomainOf(parsed)
        if (domain != null && pendingInitialValueDomains.remove(domain)) {
            appendLog(
                if (pendingInitialValueDomains.isEmpty()) {
                    "Initial value $domain received; every domain answered, waiting for the channel to settle"
                } else {
                    "Initial value $domain received; waiting for $pendingInitialValueDomains"
                },
                writeLogcat = false,
            )
            publishEssentialValuesReady()
        }
        rearmInitialValueIdle()
    }

    /**
     * Restart the quiet window. It is short once every domain has answered — only the rest
     * of each domain is still outstanding — and longer while a domain has not been heard
     * from at all, since that reply may simply be slow.
     */
    private fun rearmInitialValueIdle() {
        mainHandler.removeCallbacks(initialValueTimeoutRunnable)
        val delay = if (pendingInitialValueDomains.isEmpty()) {
            INITIAL_VALUE_SETTLE_MS
        } else {
            INITIAL_VALUE_IDLE_TIMEOUT_MS
        }
        mainHandler.postDelayed(initialValueTimeoutRunnable, delay)
    }

    /**
     * Which domain a reply carries a value for. Capability and protocol replies are
     * mapped to nothing: they describe the model, not its current state, and a session
     * that only got those still has every control sitting at its default.
     */
    private fun initialValueDomainOf(parsed: ParsedTandemResponse): HeadphoneFeature? = when (parsed) {
        is ParsedTandemResponse.DeviceInfo -> HeadphoneFeature.DEVICE_INFO
        is ParsedTandemResponse.Battery -> HeadphoneFeature.BATTERY
        is ParsedTandemResponse.NoiseControl -> HeadphoneFeature.NOISE_CONTROL
        is ParsedTandemResponse.EqEbb,
        is ParsedTandemResponse.EqEbbExtendedInfo -> HeadphoneFeature.EQ
        is ParsedTandemResponse.PlaybackAck,
        is ParsedTandemResponse.PlaybackVolume,
        is ParsedTandemResponse.PlaybackMetadata,
        is ParsedTandemResponse.PlaybackMetadataField -> HeadphoneFeature.PLAYBACK_CONTROL
        is ParsedTandemResponse.LeaStatus,
        is ParsedTandemResponse.LeaPairedHistoryStatus,
        is ParsedTandemResponse.LeaSettingAvailability,
        is ParsedTandemResponse.LeaParameterNotification -> HeadphoneFeature.LEA_STATUS
        is ParsedTandemResponse.QuickAccess,
        is ParsedTandemResponse.QuickAccessStatus -> HeadphoneFeature.QUICK_ACCESS
        is ParsedTandemResponse.AssignableSettingsPresets,
        is ParsedTandemResponse.AssignableSettingsStatus,
        is ParsedTandemResponse.AssignableSettingsExtendedParam -> HeadphoneFeature.GESTURE_OPERATIONS
        is ParsedTandemResponse.WearingStatus -> HeadphoneFeature.WEARING_STATUS
        is ParsedTandemResponse.SpeakToChatStatus,
        is ParsedTandemResponse.SpeakToChatParam -> HeadphoneFeature.SPEAK_TO_CHAT
        is ParsedTandemResponse.MultipointStatus,
        is ParsedTandemResponse.MultipointDevices,
        is ParsedTandemResponse.SourceSwitchStatus,
        is ParsedTandemResponse.MusicHandOverStatus -> HeadphoneFeature.MULTIPOINT
        else -> null
    }

    private fun markInitialValuesReady(reason: String) {
        mainHandler.removeCallbacks(initialValueTimeoutRunnable)
        mainHandler.removeCallbacks(initialValueDeadlineRunnable)
        pendingInitialValueDomains.clear()
        initialValueGateArmed = false
        if (_state.value.initialValuesReady && _state.value.essentialValuesReady) return
        _state.update { it.copy(initialValuesReady = true, essentialValuesReady = true) }
        appendLog("Initial values ready ($reason)")
    }

    /** Release the island/notification as soon as their own two domains are in. */
    private fun publishEssentialValuesReady() {
        if (_state.value.essentialValuesReady) return
        if (pendingInitialValueDomains.any { it in ESSENTIAL_INITIAL_VALUE_DOMAINS }) return
        _state.update { it.copy(essentialValuesReady = true) }
        appendLog("Essential values ready; waiting for $pendingInitialValueDomains", writeLogcat = false)
    }

    private fun clearInitialValueGate() {
        mainHandler.removeCallbacks(initialValueTimeoutRunnable)
        mainHandler.removeCallbacks(initialValueDeadlineRunnable)
        pendingInitialValueDomains.clear()
        initialValueGateArmed = false
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
        val connectedAddress = _state.value.connectedDevice?.address
        // SC Tandem target migration: "Keep mActiveMdr instance." When the target
        // identity disconnects and the holding identity promotes, the completed
        // capability session stashes in [cachedTandemTargetSession].  If the new
        // identity resolves to the same physical headset (same control address),
        // restore the session instead of re-probing — the capability tableset does
        // not change across a target migration. This check runs BEFORE the
        // holding-identity skip below: a migration that promotes the pure-LE half
        // makes that half the target, and skipping first would strand the session.
        val cached = cachedTandemTargetSession
        if (cached != null && connectedAddress != null) {
            val cachedControl = cached.deviceAddress?.let {
                UnifiedDeviceIdentityService.resolveControlAddress(it)
            }
            val newControl = UnifiedDeviceIdentityService.resolveControlAddress(connectedAddress)
            if (cachedControl.equals(newControl, ignoreCase = true)) {
                appendLog(
                    "Restoring cached capability session for tandem target migration: " +
                        "control=$newControl, identifier=${cached.identifier}"
                )
                capabilitySession = cached
                cached.deviceAddress = connectedAddress
                cachedTandemTargetSession = null
                pendingTandemMigration = null
                mainHandler.removeCallbacks(tandemMigrationTimeoutRunnable)
                // The session is complete — replay the capability table onto the
                // new profile so the UI stays populated while the initializer gate
                // opens.
                val functions = cached.supportFunctionsByTable
                    .toSortedMap(compareBy { it.ordinal })
                    .values
                    .flatten()
                    .distinctBy { it.table to it.code }
                if (functions.isNotEmpty()) {
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
                }
                refreshBasics(initial = true)
                return
            } else {
                // Different physical headset — drop the stale cache.
                appendLog(
                    "Discarding cached tandem session: " +
                        "cached=$cachedControl, new=$newControl"
                )
                cachedTandemTargetSession = null
                pendingTandemMigration = null
                mainHandler.removeCallbacks(tandemMigrationTimeoutRunnable)
            }
        }
        // Tandem target is the single identity that carries control and therefore owns the
        // capability tableset; the other bonded identity of a dual-mode headset is only a
        // holding connection (Sound Connect `je0.C19229b`: one target + one holding slot,
        // and only the target's session ever runs the initializer). A pure-LE holding
        // identity advertises a different, LEA-only support-function list, so letting it
        // probe would build the profile from the wrong identity.
        if (connectedAddress != null) {
            if (isLeOnlyHoldingIdentity(connectedAddress)) {
                appendLog(
                    "Skipping capability probe for LE holding identity $connectedAddress; " +
                        "control identity owns the tableset"
                )
                restoreControlIdentityCapabilities(connectedAddress)
                refreshBasics(initial = true)
                return
            }
        }
        // One initializer per physical headset at a time, whatever the transport.
        capabilitySession?.let { live ->
            if (live.awaitingCapabilityInfo || live.supportFunctionProbeRunning) return
        }
        val profile = ensureConnectedProfile()
        val session = CapabilityProbeSession(
            deviceAddress = _state.value.connectedDevice?.address,
            storeGroup = storeGroupFor(profile),
        )
        capabilitySession = session
        // SC C30916e opens every session with CONNECT_GET_PROTOCOL_INFO
        // (m112238v0 runs before the capability gate) — keep that order. Sending
        // this query directly AFTER a capability exchange deterministically wedges
        // the headset's HPC ACK state (four captures, 19:48:00 / 19:48:15 /
        // 20:26:40 / 20:26:43): the firmware keeps repeating its previous ACK while
        // still answering payloads. First-position protocol info never wedges.
        runCatching {
            val codec = TandemCodecRegistry.codecFor(profile.protocolFor(HeadphoneFeature.DEVICE_INFO))
            codec.buildGetProtocolInfo()?.let { bytes ->
                sendCommand(
                    HeadphoneCommand(
                        label = "GET protocol info",
                        bytes = bytes,
                        channel = profile.channelFor(HeadphoneFeature.DEVICE_INFO),
                    )
                )
            }
        }
        val address = _state.value.connectedDevice?.address
        if (address != null && address.isNotBlank()) {
            val capabilityInfoCommand = runCatching {
                SonyCapabilityProbe.buildGetCapabilityInfoCommand(profile)
            }.getOrNull()
            if (capabilityInfoCommand != null) {
                session.awaitingCapabilityInfo = true
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
        val session = capabilitySession ?: CapabilityProbeSession(
            deviceAddress = _state.value.connectedDevice?.address,
            storeGroup = storeGroupFor(profile),
        ).also { capabilitySession = it }
        clearSupportFunctionProbeState()
        val supportCommands = runCatching {
            SonyCapabilityProbe.buildGetSupportFunctionCommands(profile, client.availableChannels())
        }.getOrElse { emptyList() }
        if (supportCommands.isEmpty()) {
            appendLog("No support-function probe for ${profile.protocolName}; falling back to direct refresh")
            clearSupportFunctionProbeState()
            refreshBasics(initial = true)
            return
        }
        supportCommands.forEach { command ->
            // The Table2 query may ride the HPC channel (LE Audio has no MC at
            // probe time), so classify by the request's dataType, not the channel.
            session.pendingSupportFunctionTables += if (command.bytes.firstOrNull() == SonyTandemConstants.DATA_MDR_NO2) {
                dev.sonypods.protocol.SonyTable.NO_2
            } else {
                dev.sonypods.protocol.SonyTable.NO_1
            }
        }
        session.supportFunctionProbeRunning = true
        appendLog(
            "Probing support function (SC C29903d/C30916e capability sequence); " +
                "awaiting ${supportCommands.size} table(s) ${session.pendingSupportFunctionTables}"
        )
        supportCommands.forEach(::sendCommand)
    }

    /**
     * CONNECT_RET_CAPABILITY_INFO (0x03): the capability gate.
     *
     * Mirrors SC's `m109368F` (V1) / `m112161H` (V2): the reply carries the
     * device's tableset revision and its identifier. When the stored counter for
     * this (identifier, storeGroup, Table1) row matches, the whole per-domain
     * probe is omitted and the tableset is rebuilt from the stored raw capability
     * bytes; otherwise a full probe runs and repopulates the row.
     */
    private fun applyConnectCapabilityInfo(response: ParsedTandemResponse.ConnectCapabilityInfo) {
        val session = capabilitySession ?: return
        if (!session.awaitingCapabilityInfo) return
        session.awaitingCapabilityInfo = false
        mainHandler.removeCallbacks(capabilityInfoTimeoutRunnable)
        val identifier = response.identifier.takeIf { it.isNotBlank() }
        session.identifier = identifier
        session.capabilityCounter = response.capabilityCounter
        val storeGroup = session.storeGroup
        val storedCounter = identifier
            ?.let { capabilityStorage?.readCounter(it, storeGroup, TANDEM_TABLE_NUMBER_NO1) }
            ?: -1
        if (storedCounter != -1 && storedCounter == response.capabilityCounter &&
            identifier != null && restoreCapabilitiesFromStorage(session, identifier, storeGroup)
        ) {
            appendLog(
                "Capability counter ${response.capabilityCounter} matches stored " +
                    "(identifier=$identifier storeGroup=$storeGroup) → omit capability probe; " +
                    "rebuilt from stored capabilities"
            )
            refreshBasics(initial = true)
            return
        }
        appendLog(
            "Capability counter ${response.capabilityCounter} stored=" +
                "${storedCounter.takeIf { it != -1 } ?: "none"} " +
                "(identifier=$identifier storeGroup=$storeGroup) → start get capability"
        )
        session.probing = true
        runProbeFromSupportFunction(ensureConnectedProfile())
    }

    /** SC's `store_group`: 0 for the V1 command tableset, 1 for V2. Fixed when the
     * session's initializer is created, never re-read off a global that a
     * reconnect may already have moved. */
    private fun storeGroupFor(profile: ConnectedHeadphoneProfile): Int =
        when (profile.protocolFor(HeadphoneFeature.DEVICE_INFO)) {
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2 -> STORE_GROUP_V1
            else -> STORE_GROUP_V2
        }

    /**
     * Whether [address] is the pure-LE half of a dual-mode headset — Sound
     * Connect's "holding" identity. The control identity (whose
     * [UnifiedDeviceIdentityService.resolveControlAddress] resolves to itself)
     * is the Tandem target and owns the capability tableset; the LE half holds
     * the link but must never run the initializer, or it would overwrite the
     * tableset with its LEA-only support-function list.
     */
    private fun isLeOnlyHoldingIdentity(address: String): Boolean {
        val type = UnifiedDeviceIdentityService.getIdentityType(address)
        if (type != IdentityType.LE) return false
        // A dual-mode headset bonds one control identity and one pure-LE identity.
        // The control identity resolves to itself and is the Tandem target; the LE
        // half resolves to the control address (≠ itself), making it holding.
        // A genuinely LE-only device has no control alias — it resolves to itself —
        // so it is not holding; it is the target.
        val control = UnifiedDeviceIdentityService.resolveControlAddress(address)
        return !control.equals(address, ignoreCase = true)
    }

    /** SC's `command_table_number`, taken from the frame's dataType. */
    private fun tableNumberFor(raw: ByteArray): Int =
        if (raw.firstOrNull() == SonyTandemConstants.DATA_MDR_NO2) {
            TANDEM_TABLE_NUMBER_NO2
        } else {
            TANDEM_TABLE_NUMBER_NO1
        }

    /**
     * Rebuild the capability tableset from the stored raw capability replies,
     * re-parsing them exactly as a live probe would (SC `C29900a.m109330c` /
     * `wv.a`: the stored bytes go back through the same parsers into the same
     * builder). Nothing is derived from a summarized view, so no capability can
     * be lost by having gone uncaptured.
     *
     * The support-function replies rebuild the profile first; every other stored
     * reply is then applied against it.
     */
    private fun restoreCapabilitiesFromStorage(
        session: CapabilityProbeSession,
        identifier: String,
        storeGroup: Int,
    ): Boolean {
        val storage = capabilityStorage ?: return false
        val profile = _state.value.connectedProfile ?: return false
        // SC's rule, unchanged (`wv.a.m112114g`): the Table1 row must exist and carry
        // something; the Table2 row is optional (its read returns an empty list when the
        // row is absent, never null, so the `== null` bail-out beside it is dead code).
        // Nothing here needs to second-guess how complete the row is — the row is only
        // ever written by a finished exchange, see [saveCapabilitiesOnExchangeComplete].
        val storedTable1 = storage.readCapabilities(identifier, storeGroup, TANDEM_TABLE_NUMBER_NO1)
        val storedTable2 = storage.readCapabilities(identifier, storeGroup, TANDEM_TABLE_NUMBER_NO2)
        if (storedTable1.isNullOrEmpty()) return false
        val stored = storedTable1.map { rawForStoredPayload(TANDEM_TABLE_NUMBER_NO1, it) } +
            (storedTable2 ?: emptyList()).map { rawForStoredPayload(TANDEM_TABLE_NUMBER_NO2, it) }
        val parsedFrames = stored.mapNotNull { bytes ->
            runCatching { HeadphoneAdapterRegistry.parse(profile, bytes) }.getOrNull()
        }
        val supportFunctionFrames = parsedFrames.filterIsInstance<ParsedTandemResponse.SupportFunction>()
        val supportFunctions = supportFunctionFrames
            .flatMap { it.functions }
            .distinctBy { it.table to it.code }
        if (supportFunctions.isEmpty()) return false
        // The support-function and capability-info replies have already done their work
        // above; only the per-domain capability replies get replayed below.
        val capabilityFrames = parsedFrames.filterNot {
            it is ParsedTandemResponse.SupportFunction ||
                it is ParsedTandemResponse.ConnectCapabilityInfo
        }

        val restored = SonyCapabilityProbe.applyToProfile(
            profile,
            supportFunctions,
            profile.transport,
            markProbed = false,
        )
        _state.update { current ->
            current.copy(
                connectedProfile = restored,
                eqUiCapability = restored.eqUiCapability,
                supportedFeatures = featureStatusesFor(restored),
                initialValuesReady = false,
                essentialValuesReady = false,
            )
        }
        // Replay only capability replies. The support-function replies have
        // already built the profile above; routing them through the live probe
        // completion state would dispatch a new probe burst and violate SC's
        // cache-hit path.
        session.replaying = true
        try {
            capabilityFrames.forEach { applyParsed(it, profile.defaultResponseChannel()) }
        } finally {
            session.replaying = false
        }
        appendLog(
            "Rebuilt capabilities from storage: ${stored.size} stored command(s), " +
                "${supportFunctions.size} function(s)",
            writeLogcat = false,
        )
        return true
    }

    /** Stored payloads omit the outer data type, as SC's `C15171e` does. */
    private fun rawForStoredPayload(tableNumber: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            if (tableNumber == TANDEM_TABLE_NUMBER_NO2) {
                SonyTandemConstants.DATA_MDR_NO2
            } else {
                SonyTandemConstants.DATA_MDR
            },
        ) + payload

    /**
     * Whether a reply is one of SC's PersistableCapability commands — the
     * capability grammar a tableset is built from, never live state. These are
     * the frames stored verbatim so a counter hit can rebuild without probing.
     *
     * SC's `InterfaceC24172c`/`InterfaceC24173d` also include RET_DEVICE_INFO
     * (`wv.a.m112111c` → `m112117m` feeds model name / FW version / series-color
     * into the builder from stored bytes), so [ParsedTandemResponse.DeviceInfo]
     * is persisted and replayed the same way. RET_BLUETOOTH_DEVICE_INFO is in
     * SC's set too, but our engine never issues that query, so there is nothing
     * to capture.
     */
    private fun ParsedTandemResponse.isPersistableCapability(): Boolean = when (this) {
        is ParsedTandemResponse.SupportFunction,
        is ParsedTandemResponse.ConnectCapabilityInfo,
        is ParsedTandemResponse.DeviceInfo,
        is ParsedTandemResponse.CapabilityInfo,
        is ParsedTandemResponse.NcAsmCapabilityInfo,
        is ParsedTandemResponse.EqEbbExtendedInfo,
        is ParsedTandemResponse.PlaybackCapability,
        is ParsedTandemResponse.UpscalingCapability,
        is ParsedTandemResponse.QuickAccessCapability,
        is ParsedTandemResponse.AssignableSettingsCapability,
        is ParsedTandemResponse.AssignableSettingsPresets,
        is ParsedTandemResponse.MultipointCapability,
        is ParsedTandemResponse.GeneralSettingCapability,
        is ParsedTandemResponse.SafeListeningCapability,
        is ParsedTandemResponse.LeaCapability -> true
        else -> false
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
            current.windNoiseReduction,
        ).forEach(::sendCommand)
        refreshNoiseControlStateAfterWrite(profile)
    }

    fun setWindNoiseReduction(enabled: Boolean) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change wind noise reduction.")
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
            it.copy(
                noiseControlState = it.noiseControlState.forMode(NoiseControlMode.NOISE_CANCELLING).copy(
                    windNoiseReduction = enabled,
                    ambientLevel = level,
                )
            )
        }
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.NOISE_CANCELLING,
            level,
            ambientMode,
            current.noiseAdaptiveEnabled,
            current.noiseAdaptiveSensitivity,
            enabled,
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
            current.windNoiseReduction,
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
            current.windNoiseReduction,
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
            current.windNoiseReduction,
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
            current.windNoiseReduction,
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
    /**
     * The 2-device on/off switch, which is a General Setting and nothing else.
     *
     * Sound Connect keeps two unrelated mechanisms here, and so must we:
     *  - `PeripheralInquiredType.PAIRING_DEVICE_MANAGEMENT_*` carries the device list —
     *    connect, disconnect, unpair, playback-right, hand-over (`u70.C29444f` → `C31025a`);
     *  - a `GsInquiredType` slot whose capability title is `MULTIPOINT_SETTING` carries this
     *    switch (`u70.C29444f.m108135b` builds a plain GS sender per slot, with no reference to
     *    the peripheral domain at all). `so.C28522r.mo24713c` picks between them: `mo58509L0()`
     *    (peripheral declared) → the full card, else `mo58500J() != null` (a GS slot titled
     *    MULTIPOINT_SETTING) → `so.C28516l`, a title-plus-status row that opens the multipoint
     *    device-settings page rather than an inline toggle. Either way the write goes through the
     *    GS sender, so the peripheral capability is not part of the path.
     *
     * Gating this on [HeadphoneFeature.MULTIPOINT] conflated the two: that feature is only ever
     * granted from a peripheral RET_CAPABILITY ([applyMultipointCapability]), which a GS-driven
     * headset never sends — so the card rendered off the GS slot while every write was refused
     * before a command was built.
     */
    fun setMultipointEnabled(enabled: Boolean) {
        if (!_state.value.deviceInfo.protocolReady) {
            appendLog("Multipoint toggle unavailable: protocol not ready")
            return
        }
        val profile = ensureConnectedProfile()
        val address = _state.value.connectedDevice?.address
        val current = _state.value.multipointState
        val existing = pendingMultipointToggle?.takeIf { it.address.equals(address, ignoreCase = true) }
        if (existing != null) {
            appendLog("Ignoring multipoint toggle while the previous request is pending")
            return
        }
        val original = current.multipointEnabled ?: run {
            appendLog("Multipoint toggle unavailable: current value unknown")
            return
        }
        if (profile.multipointGsSlot == null || address.isNullOrBlank()) {
            appendLog("Cannot toggle 2-device multipoint: GS slot not discovered")
            return
        }
        val commands = HeadphoneAdapterRegistry.buildSetMultipointEnabledCommands(profile, enabled)
        if (commands.isEmpty()) {
            appendLog("Cannot toggle 2-device multipoint: profile produced no GS write")
            return
        }
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
     * execute the requested change; NEGATIVE cancels it. Clears the pending state.
     *
     * The alert lives in the ALERT domain and serves both multipoint mechanisms — the
     * GS toggle raises the reconnect confirmation just as the peripheral list does — so it
     * cannot be gated on the peripheral feature alone (see [setMultipointEnabled]). */
    fun replyMultipointAlert(positive: Boolean) {
        if (!_state.value.deviceInfo.protocolReady) return
        val profileForGate = _state.value.connectedProfile
        val multipointAvailable = canWrite(HeadphoneFeature.MULTIPOINT) ||
            profileForGate?.multipointGsSlot != null
        if (!multipointAvailable) {
            appendLog("Multipoint alert reply unavailable: neither peripheral nor GS multipoint")
            return
        }
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

    fun setSpeakToChatEnabled(enabled: Boolean) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change Speak-to-Chat.")
            return
        }
        val profile = ensureConnectedProfile()
        HeadphoneAdapterRegistry.buildSetSpeakToChatEnabledCommands(profile, enabled).forEach(::sendCommand)
    }

    fun setSpeakToChatSensitivity(sensitivity: dev.sonypods.protocol.SmartTalkingDetectionSensitivity) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change Speak-to-Chat sensitivity.")
            return
        }
        val profile = ensureConnectedProfile()
        val current = _state.value.speakToChatState
        HeadphoneAdapterRegistry.buildSetSpeakToChatParamsCommands(
            profile = profile,
            sensitivity = sensitivity,
            modeOutTime = current.modeOutTime,
            voiceFocus = current.voiceFocus,
        ).forEach(::sendCommand)
    }

    fun setSpeakToChatModeOutTime(modeOutTime: dev.sonypods.protocol.SmartTalkingModeOutTime) {
        if (!_state.value.deviceInfo.protocolReady) {
            onBluetoothUnavailable("Sony Tandem channel is not ready; cannot change Speak-to-Chat mode out time.")
            return
        }
        val profile = ensureConnectedProfile()
        val current = _state.value.speakToChatState
        HeadphoneAdapterRegistry.buildSetSpeakToChatParamsCommands(
            profile = profile,
            sensitivity = current.sensitivity,
            modeOutTime = modeOutTime,
            voiceFocus = current.voiceFocus,
        ).forEach(::sendCommand)
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
            disableUnpairPending = false
            leAudioCoordinator.start(current.leaState.pairedHistory)
        } else {
            // The official transaction must run first: it delivers the setting change over
            // the control channel and raises the reset guide. Dropping the LE bond before
            // that would cut the very link the transaction rides on — the Tandem session can
            // be on either identity — and the switch would finish silently with no guide and
            // no reconnect guidance. The bond is dropped after the headset confirms, or by
            // the fallback below when it never answers.
            disableUnpairPending = true
            leAudioCoordinator.disable()
            mainHandler.postDelayed({
                if (disableUnpairPending) {
                    disableUnpairPending = false
                    appendLog("LE Audio disable was not confirmed; dropping the LE bond anyway")
                    leAudioBond.cancel()
                    unpairLeAudioDevice()
                }
            }, DISABLE_UNPAIR_FALLBACK_MS)
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
            _state.update {
                it.copy(
                    leAudioPendingAlert = null,
                    leAudioSwitchPending = leAudioCoordinator.isRunning(),
                    leAudioDevicePairState = it.leAudioDevicePairState.restingUnlessRunning(),
                )
            }
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
            // The user aborted the switch at the guide; the LE bond must survive that.
            disableUnpairPending = false
            leAudioCoordinator.cancel()
        }
        _state.update {
            it.copy(
                leAudioPendingAlert = null,
                leAudioSwitchPending = leAudioCoordinator.isRunning(),
                leAudioDevicePairState = it.leAudioDevicePairState.restingUnlessRunning(),
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
        capturePairingSeed()
        _state.update {
            it.copy(
                leAudioPendingAlert = LeAudioPendingAlert(targetEnabled = true),
                leAudioDevicePairState = it.leAudioDevicePairState.resting(),
            )
        }
    }

    /**
     * Bonds the headset's LE Audio identity: the phone-side half of the LE Audio hand-over.
     *
     * The seed is read rather than live state because the guide's own instruction (reset the headset
     * into pairing mode) ends the session that produced it, and [LeaState] is cleared on disconnect.
     */
    fun startLeAudioDevicePairing() {
        val current = _state.value.connectedDevice
        val address = current?.address ?: pairingSeedAddress
        _state.update { it.copy(leAudioDevicePairState = it.leAudioDevicePairState.resting()) }
        leAudioBond.start(
            reportedLeAddresses = pairingSeedLeAddresses,
            targetName = current?.name ?: pairingSeedName,
            // The session address is not necessarily the classic identity: a re-run finds the Tandem
            // session already migrated to the LE identity, and dropping *that* bond would leave the
            // classic one in place. resolveControlAddress answers the address unchanged when nothing
            // is mapped yet, i.e. on the first run.
            controlAddress = address?.let { SonyDeviceService.resolveControlAddress(it) },
        )
    }

    /**
     * Remembers what the pairing flow needs, while the headset is still connected.
     *
     * Captured when the guide is raised rather than when pairing starts, because the guide's own
     * instruction — reset the headset into pairing mode — is what ends the session. The LE endpoint
     * query is re-issued here so the addresses are as fresh as the session allows; its answer lands
     * asynchronously in [applyLeaCapability], which keeps [pairingSeedLeAddresses] up to date.
     */
    private fun capturePairingSeed() {
        val device = _state.value.connectedDevice ?: return
        pairingSeedAddress = device.address
        pairingSeedName = device.name
        appendLog("LE Audio pairing target captured: ${device.address}")
        runCatching {
            HeadphoneAdapterRegistry
                .buildRefreshLeaPairedHistoryCommands(ensureConnectedProfile())
                .forEach(::sendCommand)
        }.onFailure { appendLog("LE endpoint address query could not be sent: ${it.message}") }
    }

    /**
     * Drops the LE identity bonded by [startLeAudioDevicePairing].
     *
     * The identity service answers which address that is, because it outlives this process — the
     * pairer's own record of the run does not, and a disable after a restart used to find nothing
     * to remove and leave the LE bond behind.
     */
    fun unpairLeAudioDevice(address: String? = null) {
        val control = SonyDeviceService.resolveControlAddress(
            _state.value.connectedDevice?.address ?: pairingSeedAddress,
        )
        val target = address
            ?: SonyDeviceService.leAudioIdentityFor(control)
            ?: _state.value.leAudioDevicePairState.bondedAddress
        if (target == null) {
            appendLog("no module-created LE Audio bond to remove")
            return
        }
        if (leAudioBond.unpair(target)) {
            _state.update { it.copy(leAudioDevicePairState = LeAudioDevicePairState()) }
        }
    }

    /**
     * Clears a finished run's stage and message so they cannot greet the next one.
     *
     * A live run is left alone: the guide's own instruction disconnects the headset and dismissing
     * the dialog does not abort the bonding, so both of those arrive while a stage is still real.
     */
    private fun LeAudioDevicePairState.restingUnlessRunning(): LeAudioDevicePairState =
        if (leAudioBond.isRunning()) this else resting()

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
        val eqConfig = _state.value.connectedProfile?.capabilities?.eqConfig
        val scale = eqConfig?.let(EqBandStepScale::forConfig) ?: EqBandStepScale.STANDARD
        val clearBassSlot = eqConfig?.let(::hasClearBassSlot) ?: true
        // Ten-band arrays (SC `EqBandSteps10band`) have no Clear Bass slot, so
        // frequency steps start at raw index 0.
        val rawIndex = index + if (clearBassSlot) EQ_FIRST_FREQUENCY_RAW_INDEX else 0
        if (index !in eq.bandSteps.indices || rawIndex !in eq.rawBandSteps.indices) {
            appendLog("CUSTOM EQ band change ignored index=$index bands=${eq.bandSteps.size} raw=${eq.rawBandSteps.size}")
            return
        }
        val targetPreset = eq.bandEditPreset()
        val rawSteps = eq.rawBandSteps.toMutableList()
        rawSteps[rawIndex] = displayEqStepToRaw(level, scale)
        updateEqBands(rawSteps, targetPreset)
        sendEqBandSteps(
            "SET CUSTOM EQ band ${index + 1}=${level.coerceIn(scale.displayRange.first, scale.displayRange.last)}",
            rawSteps,
            targetPreset,
        )
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
            // OUT_OF_RANGE is a positional placeholder, never writable — the
            // protocol builders reject it, so substitute the resolved current
            // preset (already default-backed by uiKeys()).
            gesture.presets.getOrNull(index)
                ?.takeIf { it != AssignableSettingsPreset.OUT_OF_RANGE }
                ?: key.currentPreset
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
    }

    fun playbackPlayPause() {
        if (!canWrite(HeadphoneFeature.PLAYBACK_CONTROL)) return
        val wasPlaying = _state.value.playbackStatus == PlaybackStatus.PLAYING
        val control = if (wasPlaying) PlaybackControl.PAUSE else PlaybackControl.PLAY
        beginPlaybackStatusTransition(
            if (control == PlaybackControl.PAUSE) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
        )
        dispatchPlayback(control, mediaFallback = { mediaController.playPause() })
    }

    fun playbackNext() {
        if (!canWrite(HeadphoneFeature.PLAYBACK_CONTROL)) return
        clearPendingPlaybackTransition()
        dispatchPlayback(PlaybackControl.TRACK_UP, mediaFallback = { mediaController.next() })
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

    fun setAutoReconnect(enabled: Boolean) {
        _state.update { it.copy(autoReconnect = enabled) }
    }

    fun setStrictSonyScanFilter(enabled: Boolean) {
        _state.update { it.copy(strictSonyScanFilter = enabled) }
    }

    private fun sendCommand(command: HeadphoneCommand) {
        appendLog("${command.label} [${command.channel}] -> ${command.bytes.hexString()}", kind = DebugLogKind.TX)
        client.sendToChannel(command.channel, command.bytes)
    }

    private fun sendCommandIfReady(command: HeadphoneCommand) {
        if (_state.value.deviceInfo.protocolReady) {
            sendCommand(command)
        }
    }

    private fun dispatchPlayback(control: PlaybackControl, mediaFallback: () -> Unit) {
        val profile = ensureConnectedProfile()
        val tandemOnly = profile.playbackDispatchStrategy == PlaybackDispatchStrategy.TANDEM_ONLY
        // A controller the headset reports as disabled swallows the SET silently, which is what
        // left the transport buttons doing nothing under LE Audio. TANDEM_FIRST then means what
        // its name says: hand the tap to the phone's media session instead. TANDEM_ONLY has
        // nowhere else to go, so it still writes and lets the headset decide.
        val controllerUsable = tandemOnly || _state.value.playbackState.controllerEnabled != false
        val commands = if (_state.value.deviceInfo.protocolReady && controllerUsable) {
            HeadphoneAdapterRegistry.buildPlaybackCommands(profile, control)
        } else {
            emptyList()
        }
        if (commands.isNotEmpty() && profile.playbackDispatchStrategy != PlaybackDispatchStrategy.ANDROID_MEDIA_FALLBACK) {
            appendLog("PLAYBACK ${control.name} via Tandem")
            commands.forEach(::sendCommand)
            return
        }
        if (!tandemOnly) {
            appendLog("PLAYBACK ${control.name} via Android media fallback")
            mediaFallback()
        }
    }

    private fun currentEqWriteContext(): EqWriteContext {
        val eqState = _state.value.eqState
        return EqWriteContext(
            rawBandSteps = eqState.rawBandSteps,
            preset = eqState.preset,
            basePreset = eqState.ultBasePreset,
        )
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
        if (connected) {
            // A session exists, so the migration instruction is satisfied and must be retired.
            // Leaving it pending is what made every later disconnect re-dial the target, and each
            // dial tore down the session that had just been established — a 400 ms loop that also
            // starved the capability probe. Which identity the session landed on is the target
            // selection's business, not a standing reconnect order's.
            if (pendingTandemMigration != null) {
                appendLog("Tandem session established; migration instruction retired")
                pendingTandemMigration = null
                mainHandler.removeCallbacks(tandemMigrationTimeoutRunnable)
            }
            tandemMigrationConnectIssued = false
        }
        if (!connected) {
            clearPendingPlaybackTransition()
            pendingQuickAccessFunctionCodes = null
            mainHandler.removeCallbacks(quickAccessConfirmTimeoutRunnable)
            stopSafeListeningPoll()
            // The initializer goes with the session, as SC's does when its Callable
            // returns and unregisters from the dispatcher. An exchange that did not
            // finish writes nothing — the stored row keeps whatever a completed one
            // last put there, exactly as SC leaves it.
            capabilityInitializer.cancel()
            capabilityCacheManager?.clear()
            mainHandler.removeCallbacks(capabilityInfoTimeoutRunnable)
            // SC's Tandem target migration ("Keep mActiveMdr instance"): when
            // the headset initiates a target change or the link drops while a
            // pending migration is recorded, stash the completed session so the
            // next probe for the same control address can reuse it.
            val session = capabilitySession
            if (session != null && pendingTandemMigration != null &&
                session.supportFunctionsByTable.isNotEmpty()
            ) {
                val controlAddress = _state.value.connectedDevice?.address?.let {
                    UnifiedDeviceIdentityService.resolveControlAddress(it)
                }
                appendLog(
                    "Caching capability session for target migration: " +
                        "control=$controlAddress, identifier=${session.identifier}"
                )
                cachedTandemTargetSession = session
            }
            capabilitySession = null
            // A 0x0E migration names the identity Tandem moves to. SC's
            // `changeTandemConnectionProfile` connects that address (with the
            // headset's ConnectionType) once its own disconnect completes — its
            // SC `changeTandemConnectionProfile` connects the named address once its own
            // disconnect completes — its holding link is already up, so the connect is instant.
            // Ours dials, so the connect is issued from here once, on the disconnect the
            // instruction caused. It is not re-issued: the instruction is satisfied by a session
            // existing at all (see onConnectionStateChanged) and bounded by the migration timeout
            // when no session ever appears. Re-arming it per disconnect is what turned a landed
            // migration into a 400 ms reconnect loop — each dial tore down the session the previous
            // dial had just established, and the attempt bound never fired because a transport-up
            // reset the counter.
            pendingTandemMigration?.let { migration ->
                val targetAddress = migration.targetAddress ?: return@let
                val connectionType = migration.connectionType ?: LeaConnectionType.BLE_GATT
                if (tandemMigrationConnectIssued) return@let
                tandemMigrationConnectIssued = true
                appendLog(
                    "Tandem migration: connecting to headset-named target " +
                        "$targetAddress over $connectionType"
                )
                mainHandler.postDelayed(
                    {
                        if (pendingTandemMigration?.targetAddress != targetAddress) return@postDelayed
                        client.connectTandemTarget(targetAddress, connectionType)
                    },
                    TANDEM_MIGRATION_FIRST_DELAY_MS,
                )
            }
            clearInitialValueGate()
            // The setting transaction rides the live transport: once the link is gone it can
            // never see its 0x49 notification, so keeping the gate armed wedged the UI on
            // "switching LE Audio" across reboots and reconnects. The pairing-guide phase
            // runs after the coordinator already completed, so this cannot cut a live one.
            leAudioCoordinator.cancel()
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
            //
            // A swap between the two bonded identities of one dual-mode headset is
            // the SAME device — SC's Tandem target migration keeps the existing
            // session and capabilities across the move ("Tandem target change
            // case. Keep mActiveMdr instance."), it never re-initializes. So the
            // profile survives an LE↔control address change too.
            val sameDevice = it.connectedDevice?.address.equals(device?.address, ignoreCase = true) ||
                it.connectedDevice?.address?.let { addr ->
                    device?.address?.let { other ->
                        UnifiedDeviceIdentityService.resolveControlAddress(addr)
                            .equals(UnifiedDeviceIdentityService.resolveControlAddress(other), ignoreCase = true)
                    }
                } == true
            val profile = if (connected && device != null) {
                if (sameDevice && it.connectedProfile != null) {
                    it.connectedProfile
                } else {
                    // A connection-state event may arrive before the capability
                    // probe runs (GATT and SPP each fire one), so this is the
                    // neutral profile. Nothing is restored here: like SC, the
                    // capability tableset only exists once the initializer has
                    // read CONNECT_RET_CAPABILITY_INFO and either replayed the
                    // stored capabilities or probed.
                    HeadphoneAdapterRegistry.resolve(device, deviceInfo.modelName)
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
                // A failure reason belongs to the run that produced it. A live run is untouched:
                // the guide tells the user to reset the headset, so a disconnect arrives in the
                // middle of every successful pairing.
                leAudioDevicePairState = when {
                    leAudioBond.isRunning() -> it.leAudioDevicePairState
                    sameDevice -> it.leAudioDevicePairState.resting()
                    else -> LeAudioDevicePairState()
                },
                quickAccessState = if (connected) it.quickAccessState else QuickAccessState(),
                gestureOperationsState = if (connected) it.gestureOperationsState else GestureOperationsState(),
                multipointState = when {
                    !connected -> MultipointState()
                    sameDevice -> it.multipointState
                    else -> {
                        val optimisticValue = pendingForConnection?.let { request ->
                            if (request.decision == MultipointToggleDecision.CANCELLED) request.original else request.target
                        }
                        MultipointState(
                            // The capability replay / probe establishes support;
                            // a peripheral type code and a GS MULTIPOINT_SETTING
                            // slot are the two things that can prove it, and
                            // GS-driven devices only ever report the latter.
                            supported = profile?.supports(HeadphoneFeature.MULTIPOINT) == true ||
                                profile?.multipointGsSlot != null,
                            multipointEnabled = optimisticValue,
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
                initialValuesReady = if (connected) it.initialValuesReady else false,
                essentialValuesReady = if (connected) it.essentialValuesReady else false,
                soundQualityState = if (connected) it.soundQualityState else SoundQualityState(),
                leAudioSwitchPending = if (connected) it.leAudioSwitchPending else false,
                upscalingEnabled = if (connected) it.upscalingEnabled else null,
                connectionQualityMode = if (connected) it.connectionQualityMode else null,
                connectionQualityEnabled = if (connected) it.connectionQualityEnabled else null,
                connectionQualitySwitching = false,
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
        // From this moment the card must never draw blank rows: seed every name
        // slot as unknown until real text arrives (headset RET/NTFY or nothing).
        _state.update { current ->
            current.copy(playbackState = current.playbackState.copy(
                track = current.playbackState.track ?: "",
                artist = current.playbackState.artist ?: "",
                album = current.playbackState.album ?: "",
            ))
        }
        probeCapabilities()
    }

    override fun onMessage(channel: TandemChannel, raw: ByteArray, sourceAddress: String?) {
        // A frame belongs to the transport session that produced it. SC gets this
        // structurally — its dispatcher is one instance per device and only feeds
        // that device's own handlers — so a reply that arrives after the headset
        // has been replaced simply has nobody left to deliver to. Dropping the
        // mismatch here is the same guarantee: without it a late reply would be
        // applied to, and cached under, whichever headset is connected now.
        val owner = capabilitySession?.deviceAddress ?: _state.value.connectedDevice?.address
        if (sourceAddress != null && owner != null && !sourceAddress.equals(owner, ignoreCase = true)) {
            appendLog(
                "Drop RX [$channel] frame from $sourceAddress: session belongs to $owner",
                writeLogcat = false,
            )
            return
        }
        val profile = _state.value.connectedProfile
            ?: runCatching { ensureConnectedProfile() }.getOrNull()
            ?: run {
                appendLog("Drop RX [$channel] frame: no connected device yet")
                return
            }
        val parsed = HeadphoneAdapterRegistry.parse(profile, channel, raw)
        appendLog("RX [$channel] ${raw.hexString()} · ${parsed::class.simpleName}", kind = DebugLogKind.RX)
        recordCapabilityIfPersistable(parsed, raw)
        // Release the exchange step waiting on this reply before the handlers run, the way SC's
        // dispatcher lets the predicate count the latch down independently of the feature holders.
        capabilityInitializer.offer(parsed)
        applyParsed(parsed, channel)
        // After the handler, so the value is already in the state when the gate opens.
        noteInitialValue(parsed)
    }

    /** SC persists every PersistableCapability reply (raw bytes) so a later hit
     * rebuilds the tableset from them; mirror that into the SQLite cache. */
    private fun recordCapabilityIfPersistable(parsed: ParsedTandemResponse, raw: ByteArray) {
        val manager = capabilityCacheManager ?: return
        val session = capabilitySession ?: return
        if (!session.probing || session.replaying || !parsed.isPersistableCapability()) return
        val identifier = session.identifier ?: return
        val counter = session.capabilityCounter ?: return
        manager.put(
            identifier,
            session.storeGroup,
            tableNumberFor(raw),
            counter,
            raw.drop(1).toByteArray(),
        )
    }

    private fun applyParsed(parsed: ParsedTandemResponse, channel: TandemChannel) {
        when (parsed) {
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
            is ParsedTandemResponse.Upscaling -> applyUpscaling(parsed)
            is ParsedTandemResponse.ConnectionQuality -> applyConnectionQuality(parsed)
            is ParsedTandemResponse.ConnectionQualityAvailability -> applyConnectionQualityAvailability(parsed)
            is ParsedTandemResponse.UpscalingCapability -> applyUpscalingCapability(parsed)
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
            is ParsedTandemResponse.LeaTandemTargetInstruction -> applyLeaTandemTargetInstruction(parsed)
            is ParsedTandemResponse.QuickAccess -> applyQuickAccess(parsed)
            is ParsedTandemResponse.QuickAccessCapability -> applyQuickAccessCapability(parsed)
            is ParsedTandemResponse.QuickAccessStatus -> applyQuickAccessStatus(parsed)
            is ParsedTandemResponse.AssignableSettingsCapability -> applyAssignableSettingsCapability(parsed)
            is ParsedTandemResponse.AssignableSettingsPresets -> applyAssignableSettingsPresets(parsed)
            is ParsedTandemResponse.AssignableSettingsStatus -> applyAssignableSettingsStatus(parsed)
            is ParsedTandemResponse.AssignableSettingsExtendedParam -> applyAssignableSettingsExtendedParam(parsed)
            is ParsedTandemResponse.WearingStatus -> applyWearingStatus(parsed)
            is ParsedTandemResponse.SpeakToChatStatus -> applySpeakToChatStatus(parsed)
            is ParsedTandemResponse.SpeakToChatParam -> applySpeakToChatParam(parsed)
            is ParsedTandemResponse.AudioCodecStatus -> applySoundCodec(parsed)
            is ParsedTandemResponse.UpscalingEffect -> applyUpscalingEffectState(parsed)
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
            is ParsedTandemResponse.SafeListeningExtendedParam -> applySafeListeningExtendedParam(parsed)
            is ParsedTandemResponse.SafeListeningCapability -> applySafeListeningCapability(parsed)
            is ParsedTandemResponse.SafeListeningParam -> applySafeListeningParam(parsed)
            is ParsedTandemResponse.ProtocolInfo -> applyProtocolInfo(parsed)
            is ParsedTandemResponse.ConnectCapabilityInfo -> applyConnectCapabilityInfo(parsed)
            is ParsedTandemResponse.NcAsmCapabilityInfo -> applyNcAsmCapabilityInfo(parsed)
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
        // The headset answers every battery query and also pushes unsolicited
        // battery NTFYs, so identical values arrive constantly. One summary line
        // per actual change instead of one per reply is what keeps the battery
        // refresh loop from flooding logcat and the debug ring.
        val previous = _state.value.batteryState
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
        val now = _state.value.batteryState
        if (now != previous) {
            appendLog(
                "Battery ${response.kind} L=${now.left ?: "-"} R=${now.right ?: "-"} " +
                    "C=${now.cradle ?: "-"} single=${now.single ?: "-"} " +
                    "(was L=${previous.left ?: "-"} R=${previous.right ?: "-"} " +
                    "C=${previous.cradle ?: "-"} single=${previous.single ?: "-"})"
            )
        }
    }

    private fun applyEqEbb(response: ParsedTandemResponse.EqEbb) {
        appendLog(
            "EQ/EBB notification type=${response.type} enabled=${response.enabled} " +
                "preset=${response.preset} clearBass=${response.clearBass} bands=${response.bandSteps} values=${response.values}"
        )
        _state.update { current ->
            val eqConfig = current.connectedProfile?.capabilities?.eqConfig
            val scale = eqConfig?.let(EqBandStepScale::forConfig) ?: EqBandStepScale.STANDARD
            val clearBassSlot = eqConfig?.let(::hasClearBassSlot) ?: true
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
                displayEqBands(response.bandSteps, clearBassSlot, scale)
            } else {
                current.eqState.bandSteps
            }
            // Only standard-geometry arrays carry Clear Bass at raw index 0; on
            // a 10-band device that slot is the 31 Hz step and must not leak
            // into the Clear Bass state.
            val clearBassFromEq = if (hasEqBands && clearBassSlot &&
                response.bandSteps.size > EQ_CLEAR_BASS_RAW_INDEX
            ) {
                displayEqStep(response.bandSteps[EQ_CLEAR_BASS_RAW_INDEX], scale)
            } else {
                null
            }
            current.copy(
                eqState = current.eqState.copy(
                    enabled = response.enabled ?: current.eqState.enabled,
                    // PRESET_EQ_AND_ULT_MODE folds the base preset + EqUltModeStatus
                    // into the display vocabulary: an active ULT mode shows the
                    // ULT_1/ULT_2 marker while ultBasePreset keeps the base preset.
                    preset = when {
                        response.type == EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE &&
                            response.ultMode == 0x01 -> EqPresetId.ULT_1
                        response.type == EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE &&
                            response.ultMode == 0x02 -> EqPresetId.ULT_2
                        else -> response.preset ?: current.eqState.preset
                    },
                    ultBasePreset = when (response.type) {
                        EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE ->
                            response.preset ?: current.eqState.ultBasePreset
                        else -> current.eqState.ultBasePreset
                    },
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
        if (response.bands.isNotEmpty()) {
            val normalBands = response.bands.filter { it.type != EqBandInformationType.SPECIFIC_INFORMATION }
            val hasClearBass = response.bands.any { it.type == EqBandInformationType.SPECIFIC_INFORMATION }
            // SC `EqBandSteps10band`: ten frequency bands and no Clear Bass slot
            // mean raw steps 0..12 centered at 6 — the preset-array Clear Bass of
            // the standard geometry does not exist on this device.
            val isTenBand = !hasClearBass && normalBands.size == 10
            val dynamicLabels = normalBands.map { band ->
                when (band.type) {
                    EqBandInformationType.HZ -> {
                        if (band.value < 1000) {
                            "${band.value} Hz"
                        } else if (band.value % 1000 == 0) {
                            "${band.value / 1000} kHz"
                        } else {
                            val dec = band.value / 1000.0
                            if (dec == dec.toLong().toDouble()) "${dec.toLong()} kHz" else "$dec kHz"
                        }
                    }
                    EqBandInformationType.KHZ -> "${band.value} kHz"
                    else -> if (band.value in 1..20000) "${band.value} Hz" else "${band.value}"
                }
            }
            _state.update { current ->
                val profile = current.connectedProfile
                if (profile == null) {
                    current
                } else {
                    val updatedEqConfig = profile.capabilities.eqConfig.copy(
                        bandCount = response.bands.size,
                        hasClearBass = hasClearBass,
                        bandLabels = dynamicLabels,
                        isTenBand = isTenBand,
                    )
                    val scale = EqBandStepScale.forConfig(updatedEqConfig)
                    val clearBassSlot = hasClearBassSlot(updatedEqConfig)
                    current.copy(
                        connectedProfile = profile.copy(
                            capabilities = profile.capabilities.copy(
                                eqConfig = updatedEqConfig,
                                // Devices without the EBB function still expose
                                // Clear Bass as the SPECIFIC_INFORMATION band of
                                // the PRESET_EQ array (LinkBuds S): the extended
                                // info is the authoritative capability signal,
                                // without which canWrite() blocked the slider.
                                // Ten-band devices are the opposite: no Clear
                                // Bass anywhere, so an EBB probe hit must not
                                // keep the slider alive.
                                features = when {
                                    hasClearBass -> profile.capabilities.features + HeadphoneFeature.CLEAR_BASS
                                    isTenBand -> profile.capabilities.features - HeadphoneFeature.CLEAR_BASS
                                    else -> profile.capabilities.features
                                },
                            )
                        ),
                        // The geometry can land after a param response already
                        // displayed bandSteps with the standard scale — re-derive
                        // the display values from the raw steps.
                        eqState = current.eqState.copy(
                            bandSteps = displayEqBands(current.eqState.rawBandSteps, clearBassSlot, scale),
                            clearBass = if (clearBassSlot && current.eqState.rawBandSteps.size > EQ_CLEAR_BASS_RAW_INDEX) {
                                displayEqStep(current.eqState.rawBandSteps[EQ_CLEAR_BASS_RAW_INDEX], scale)
                            } else {
                                current.eqState.clearBass
                            },
                        ),
                        eqUiCapability = EqProtocolEngine.uiCapability(updatedEqConfig),
                    )
                }
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
        // Sony official app (c40.C6114e / c40.C6119j) never sends GET_PARAM after SET_PARAM.
        // It maintains optimistic local state and relies entirely on asynchronous NCASM_NTFY_PARAM notifications.
        // Actively querying immediately after writing causes race conditions where headphones (such as WF-1000XM5
        // when out of ear or transitioning) return transient OFF / standby DSP states that clobber user selection.
        appendLog("NC/ASM write sent; current mode is kept from local selection, awaiting device notification")
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

    private fun clearPendingPlaybackTransition() {
        pendingPlaybackStatus = null
    }

    private fun beginPlaybackStatusTransition(expected: PlaybackStatus) {
        pendingPlaybackStatus = PendingPlaybackStatus(
            expected = expected,
            ignoreOppositeUntilMs = SystemClock.elapsedRealtime() + PLAYBACK_STALE_RESPONSE_WINDOW_MS,
        )
        _state.update { it.copy(playbackStatus = expected) }
    }

    private fun updateEqBands(rawSteps: List<Int>, preset: EqPresetId? = _state.value.eqState.preset) {
        _state.update {
            val eqConfig = it.connectedProfile?.capabilities?.eqConfig
            val scale = eqConfig?.let(EqBandStepScale::forConfig) ?: EqBandStepScale.STANDARD
            val clearBassSlot = eqConfig?.let(::hasClearBassSlot) ?: true
            it.copy(
                eqState = it.eqState.copy(
                    preset = preset ?: it.eqState.preset,
                    rawBandSteps = rawSteps,
                    bandSteps = displayEqBands(rawSteps, clearBassSlot, scale),
                    clearBass = if (clearBassSlot && rawSteps.size > EQ_CLEAR_BASS_RAW_INDEX) {
                        displayEqStep(rawSteps[EQ_CLEAR_BASS_RAW_INDEX], scale)
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
                    windNoiseReduction = response.windNoiseReduction
                        ?: current.noiseControlState.windNoiseReduction,
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
        appendLog(
            "Playback notification [$sourceLabel] ${response.values} " +
                "status=${response.status} controllerEnabled=${response.enabled}"
        )
        response.enabled?.let { enabled ->
            _state.update {
                it.copy(playbackState = it.playbackState.copy(controllerEnabled = enabled))
            }
        }
        if (response.status != PlaybackStatus.UNKNOWN) {
            applyPlaybackStatus(response.status, source = "Tandem")
        } else {
            updatePlaybackStatusFromAudioManager()
        }
        // 连接质量切换的重连窗口：耳机重新报出可用状态（音频已恢复）才解除
        // 播放控制置灰；30s 硬上限与官方连接进度框一致。
        if (_state.value.connectionQualitySwitching &&
            response.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED) &&
            response.enabled != false
        ) {
            mainHandler.removeCallbacks(connectionQualitySwitchTimeoutRunnable)
            _state.update { it.copy(connectionQualitySwitching = false) }
            appendLog("Connection quality switch window closed: audio restored")
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
    }

    /** Once a session exists, a name slot must never render blank: settled
     * non-blank text wins, and everything else (NOTHING, UNSETTLED, settled-but-
     * blank) degrades to the empty string that the detail card draws as "unknown". */
    private fun PlaybackName.toUiValue(): String = when {
        status == PlaybackNameStatus.SETTLED && text.isNotBlank() -> text
        else -> ""
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

    // UNSETTLED/OTHER keep the enum (the UI hides those badges) — storing the
    // raw value preserves what the headset actually said for diagnostics.
    private fun applySoundCodec(response: ParsedTandemResponse.AudioCodecStatus) {
        _state.update {
            it.copy(soundQualityState = it.soundQualityState.copy(codec = response.codec))
        }
    }

    private fun applySafeListeningExtendedParam(response: ParsedTandemResponse.SafeListeningExtendedParam) {
        // errorCause is the wire SafeListeningErrorCause: 0xFF (OUT_OF_RANGE) is
        // the valid-value case the UI displays; the level byte is a placeholder
        // (255) for every other cause, so only VALID reads carry a level.
        // SC's SafeListeningErrorCause.fromByteCode defaults ANY unknown byte to
        // OUT_OF_RANGE (this device reports 0x03), i.e. the valid-value branch.
        val status = when (response.errorCause) {
            0xFF -> SafeListeningStatus.VALID
            0x00 -> SafeListeningStatus.NOT_PLAYING
            0x01 -> SafeListeningStatus.IN_CALL
            0x02 -> SafeListeningStatus.DETACHED
            else -> SafeListeningStatus.VALID
        }
        _state.update {
            it.copy(safeListeningState = SafeListeningState(
                levelDb = if (status == SafeListeningStatus.VALID) response.level else null,
                status = status,
            ))
        }
    }

    private fun applySafeListeningCapability(response: ParsedTandemResponse.SafeListeningCapability) {
        if (response.minimumInterval <= 0) return
        _state.update { it.copy(safeListeningMinimumInterval = response.minimumInterval) }
        // The poll read the device interval now; restart it so the new cadence
        // applies immediately instead of at the next fallback tick.
        startSafeListeningReadoutPoll()
    }

    private fun applySafeListeningParam(response: ParsedTandemResponse.SafeListeningParam) {
        if (!safeListeningPollArmed) return
        // Only NTFY_PARAM answers for the two switches; a RET_PARAM reply carries an
        // unrelated flag that stays 0x00 whatever the switches are, so it is neither
        // requested nor trusted here.
        val previewOn = response.previewOn ?: return
        if (!response.featureOn && !previewOn) return
        // Activation confirmed. Only a headset in one of those two states answers the
        // capability query that names its minimum poll interval; read it event-driven
        // rather than on a fixed delay.
        startSafeListeningReadoutPoll()
        sendSafeListeningCommand("GET SAFE_LISTENING capability") {
            SonyTandemV2Table2Protocol.buildGetSafeListeningCapability(it)
        }
    }

    /** Only `VALID` means DSEE is actively processing; OFF/INVALID hide it. */
    private fun applyUpscalingEffectState(response: ParsedTandemResponse.UpscalingEffect) {
        _state.update {
            it.copy(soundQualityState = it.soundQualityState.copy(
                dseeGeneration = response.generation,
                dseeActive = response.state == DseeEffectState.VALID,
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

    private fun applyUpscaling(response: ParsedTandemResponse.Upscaling) {
        appendLog(
            "Upscaling ${if (response.isUnsolicited) "NTFY" else "RET"} " +
                "inq=0x%02X enabled=%s".format(response.inquiredTypeCode, response.enabled)
        )
        _state.update { it.copy(upscalingEnabled = response.enabled) }
    }

    /** AUDIO_RET/NTFY_PARAM (CONNECTION_MODE 系)：官方不做乐观更新——
     * 选中态只在耳机确认（RET）或主动通知（NTFY，含流迁移方向）后变化。 */
    private fun applyConnectionQuality(response: ParsedTandemResponse.ConnectionQuality) {
        appendLog(
            "Connection quality ${if (response.isUnsolicited) "NTFY" else "RET"} " +
                "inq=0x%02X mode=%s%s"
                    .format(
                        response.inquiredTypeCode,
                        response.mode,
                        response.switchingStreamCode?.let { " switching=0x%02X".format(it) }.orEmpty(),
                    )
        )
        // 模式确认（RET 几乎立即到达）不等于切换完成：重连窗口要等音频恢复，
        // 由 PLAY 状态帧或 30s 超时关闭（官方连接进度框同为 30s 上限）。
        _state.update { it.copy(connectionQualityMode = response.mode) }
    }

    /** AUDIO_RET/NTFY_STATUS：EnableDisable 可用性——DISABLE 时选项整组置灰。 */
    private fun applyConnectionQualityAvailability(
        response: ParsedTandemResponse.ConnectionQualityAvailability,
    ) {
        appendLog(
            "Connection quality availability ${if (response.isUnsolicited) "NTFY" else "RET"} " +
                "enabled=${response.enabled}"
        )
        _state.update { it.copy(connectionQualityEnabled = response.enabled) }
    }

    /** AUDIO_RET_CAPABILITY: records the DSEE generation (`cf0.e0`) the headset
     * reports; the detail row's official title/description derive from it. */
    private fun applyUpscalingCapability(response: ParsedTandemResponse.UpscalingCapability) {
        appendLog(
            "Upscaling capability inq=0x%02X type=%d"
                .format(response.inquiredTypeCode, response.upscalingTypeCode)
        )
        _state.update { state ->
            val profile = state.connectedProfile ?: return@update state
            state.copy(
                connectedProfile = profile.copy(
                    capabilities = profile.capabilities.copy(
                        upscalingTypeCode = response.upscalingTypeCode,
                    ),
                ),
            )
        }
    }

    /**
     * Toggles DSEE / DSEE Extreme (AUDIO_SET_PARAM). The inquired type is the one
     * the device's support-function list chose; optimistic like the other SETs —
     * the RET/NTFY corrects the state when the headset disagrees.
     */
    fun setUpscalingEnabled(enabled: Boolean) {
        val profile = _state.value.connectedProfile ?: return
        if (!profile.supports(HeadphoneFeature.UPSCALING)) return
        if (profile.protocolFor(HeadphoneFeature.DEVICE_INFO) != HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1) return
        val inquiredTypeCode = profile.capabilities.upscalingInquiredTypeCode ?: return
        _state.update { it.copy(upscalingEnabled = enabled) }
        sendCommandIfReady(
            HeadphoneCommand(
                label = "SET upscaling ${if (enabled) "AUTO" else "OFF"}",
                bytes = TandemCodecRegistry
                    .codecFor(profile.protocolFor(HeadphoneFeature.DEVICE_INFO))
                    .buildSetUpscaling(inquiredTypeCode.toByte(), enabled)
                    ?: return,
                channel = profile.channelFor(HeadphoneFeature.UPSCALING),
            )
        )
    }

    /**
     * 切换 Bluetooth 连接质量（AUDIO_SET_PARAM + PriorMode）。刻意**不做乐观
     * 更新**：官方在确认对话框通过后仅发送，选中态等 RET/NTFY 带回新值才变——
     * 失败（重试耗尽/被拒）时 UI 自然停在原值上。
     */
    fun setConnectionQuality(mode: ConnectionQualityMode) {
        val profile = _state.value.connectedProfile ?: return
        if (!profile.supports(HeadphoneFeature.CONNECTION_QUALITY)) return
        if (_state.value.connectionQualityEnabled == false) {
            appendLog("Connection quality change ignored: setting currently unavailable")
            return
        }
        if (profile.protocolFor(HeadphoneFeature.DEVICE_INFO) != HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1) return
        val inquiredTypeCode = profile.capabilities.connectionQualityInquiredTypeCode ?: return
        _state.update { it.copy(connectionQualitySwitching = true) }
        mainHandler.removeCallbacks(connectionQualitySwitchTimeoutRunnable)
        mainHandler.postDelayed(connectionQualitySwitchTimeoutRunnable, CONNECTION_QUALITY_SWITCH_TIMEOUT_MS)
        sendCommandIfReady(
            HeadphoneCommand(
                label = "SET connection quality ${mode.name}",
                bytes = TandemCodecRegistry
                    .codecFor(profile.protocolFor(HeadphoneFeature.DEVICE_INFO))
                    .buildSetConnectionQuality(inquiredTypeCode.toByte(), mode)
                    ?: return,
                channel = profile.channelFor(HeadphoneFeature.CONNECTION_QUALITY),
            )
        )
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
        // Kept outside _state as well: this is the pairing flow's only criterion and it has to
        // survive the disconnect the guide causes. See [pairingSeedLeAddresses].
        pairingSeedLeAddresses = response.addresses
        appendLog("LE endpoint addresses reported: ${response.addresses}")
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

    /**
     * The headset's Tandem-target instructions (LEA_NTFY_PARAM 0x0D / 0x0E / 0x0F).
     *
     * A dual-identity Sony headset decides itself which bonded identity carries
     * Tandem and says so on the live session. SC reacts by disconnecting the
     * current target so its holding identity is promoted, and — crucially —
     * keeps the same session object and the same capability tableset across the
     * move ("Tandem target change case. Keep mActiveMdr instance."), never
     * re-running its initializer against the new identity.
     *
     * Our transport reconnects rather than swapping a socket underneath, so the
     * equivalent is [cachedTandemTargetSession]: the table stays keyed to the
     * physical headset and is restored on the new identity instead of re-probed.
     *
     * Gating mirrors SC's registration: the 0x0D handler only exists for devices
     * declaring `TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD` (0x40,
     * `u70.C29444f`), the 0x0E observer only for devices declaring
     * `CHANGE_TANDEM_CONNECTION_PROFILE_FOR_ANDROID` (0x44, `C14319c.mo61835c`).
     */
    private fun applyLeaTandemTargetInstruction(
        response: ParsedTandemResponse.LeaTandemTargetInstruction,
    ) {
        val capabilities = _state.value.connectedProfile?.capabilities
        when (response.type) {
            LeaInquiredType.CHANGE_TANDEM_CONNECTION_PROFILE_FOR_ANDROID -> {
                if (capabilities?.declaresChangeTandemConnectionProfile != true) {
                    appendLog("Ignoring 0x0E tandem profile change: device does not declare 0x44")
                    return
                }
                val address = response.targetAddress
                if (address.isNullOrBlank()) {
                    appendLog("0x0E tandem profile change carries no target address; ignored")
                    return
                }
                appendLog(
                    "Headset moves Tandem to $address over ${response.connectionType}; " +
                        "closing current target for migration"
                )
                // The headset just named its other identity. Recorded so the 0x0D path — which
                // carries no address and has to derive the promotion target — has something to
                // find, and so the device list can fold the two. CSIS group id supersedes this
                // whenever the stack can answer; the direction is settled the same way either way.
                _state.value.connectedDevice?.address?.let { current ->
                    runCatching {
                        UnifiedDeviceIdentityService.recordGroup(
                            members = listOf(current, address),
                            source = dev.sonypods.device.IdentitySource.PAIRING,
                        )
                    }
                }
                pendingTandemMigration = PendingTandemMigration(address, response.connectionType)
                scheduleTandemMigrationTimeout()
                // SC `C14356p0.m62041c0` (changeTandemConnectionProfile): disconnect
                // now, connect the named target when it lands — the reconnect is
                // scheduled in onConnectionStateChanged.
                mainHandler.post { client.disconnect() }
            }

            LeaInquiredType.EXECUTE_TANDEM_TARGET_CHANGE -> {
                if (capabilities?.declaresTandemTargetChange != true) {
                    appendLog("Ignoring 0x0D tandem target change: device does not declare 0x43")
                    return
                }
                // SC `d30.C15456c` disconnects the current target so that its *holding*
                // device is promoted to target (`C22925e.m89635e`). 0x0D names no address,
                // so the promotion has to be derived: the other bonded identity of this
                // headset. Recording it as the migration target is what makes the promotion
                // happen — [SonyBleClient.connectTandemTarget] dials it verbatim, while an
                // ordinary reconnect would fold straight back onto the control identity the
                // headset just asked us to leave, and the headset would answer with another
                // 0x0D. That loop is what kept the initial-value gate from ever opening.
                val current = _state.value.connectedDevice?.address
                val promoted = current?.let { address ->
                    val control = UnifiedDeviceIdentityService.resolveControlAddress(address)
                    if (control.equals(address, ignoreCase = true)) {
                        UnifiedDeviceIdentityService.leAudioAddressFor(address)
                    } else {
                        control
                    }
                }?.takeIf { !it.equals(current, ignoreCase = true) }
                appendLog(
                    if (promoted != null) {
                        "Headset asks to move Tandem off $current; promoting its holding " +
                            "identity $promoted"
                    } else {
                        "Headset asks to move Tandem off the current target; closing it " +
                            "(no second identity known, so promotion is left to the reconnect)"
                    }
                )
                pendingTandemMigration = pendingTandemMigration
                    ?: PendingTandemMigration(promoted, null)
                scheduleTandemMigrationTimeout()
                mainHandler.post { client.disconnect() }
            }

            LeaInquiredType.NOTIFY_DISCONNECTING_TANDEM -> {
                if (capabilities?.declaresTandemDisconnectingNotification != true) {
                    appendLog("Ignoring 0x0F tandem disconnect notice: device declares no LEA-CTKD type")
                    return
                }
                appendLog("Headset warns the Tandem link is going down")
            }

            else -> Unit
        }
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

    private fun applySpeakToChatStatus(response: ParsedTandemResponse.SpeakToChatStatus) {
        appendLog("Speak-to-Chat status effect=${response.effectStatus}")
        _state.update { current ->
            current.copy(
                speakToChatState = current.speakToChatState.copy(
                    effectStatus = response.effectStatus ?: current.speakToChatState.effectStatus,
                )
            )
        }
    }

    private fun applySpeakToChatParam(response: ParsedTandemResponse.SpeakToChatParam) {
        appendLog("Speak-to-Chat param enabled=${response.enabled} sensitivity=${response.sensitivity} modeOutTime=${response.modeOutTime} voiceFocus=${response.voiceFocus}")
        _state.update { current ->
            current.copy(
                speakToChatState = current.speakToChatState.copy(
                    enabled = response.enabled ?: current.speakToChatState.enabled,
                    sensitivity = response.sensitivity ?: current.speakToChatState.sensitivity,
                    modeOutTime = response.modeOutTime ?: current.speakToChatState.modeOutTime,
                    voiceFocus = response.voiceFocus ?: current.speakToChatState.voiceFocus,
                )
            )
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
                    resultCode = response.result,
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
                sourceSwitchResultCode = response.result,
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
            // No refresh burst follows this abort, so nothing would ever answer the
            // initial-value gate. Release it instead of leaving every consumer waiting
            // for values this session is never going to ask for.
            markInitialValuesReady("capability probing aborted")
            return
        }
        val table = response.table.takeIf { it != dev.sonypods.protocol.SonyTable.INVALID }
            ?: dev.sonypods.protocol.SonyTable.NO_1
        val session = capabilitySession
        if (session == null || !session.supportFunctionProbeRunning) {
            // Every table we asked for has already been accounted for. Feeding this one
            // through would rebuild the profile from it alone, dropping the functions the
            // other tables contributed.
            appendLog("Support-function table $table arrived outside a probe; ignored")
            return
        }
        session.supportFunctionsByTable[table] = response.functions
        session.pendingSupportFunctionTables.remove(table)
        if (session.pendingSupportFunctionTables.isNotEmpty()) {
            appendLog(
                "Support-function table $table received; " +
                    "waiting for ${session.pendingSupportFunctionTables}"
            )
            return
        }
        finishSupportFunctionProbe(session)
    }

    private fun finishSupportFunctionProbe(session: CapabilityProbeSession) {
        val functions = session.supportFunctionsByTable
            .toSortedMap(compareBy { it.ordinal })
            .values
            .flatten()
            .distinctBy { it.table to it.code }
        clearSupportFunctionProbeState()
        if (functions.isEmpty()) {
            appendLog("No support functions received; falling back to direct refresh")
            refreshBasics(initial = true)
            return
        }
        val alreadyProbed = _state.value.connectedProfile?.protocolEvidence
            ?.any { it.startsWith("probe:ret-support-function") } == true
        val probeCommands = runCatching {
            _state.value.connectedProfile?.let { profile ->
                SonyCapabilityProbe.buildCapabilityProbeCommands(profile, functions)
            } ?: emptyList()
        }.getOrElse { emptyList() }
        // The table can land after an earlier give-up already ran a burst against the neutral
        // profile and opened the value gate. That burst asked one BATTERY question and nothing
        // else, while the real table asks a different set — so the gate is re-closed here and
        // the burst at the end of this function is awaited properly. Nothing was rendered off
        // the open gate: every surface also requires capabilitiesKnown, which the update below
        // is what sets.
        clearInitialValueGate()
        _state.update { current ->
            val profile = current.connectedProfile?.let { profile ->
                SonyCapabilityProbe.applyToProfile(profile, functions, profile.transport)
            } ?: current.connectedProfile
            current.copy(
                connectedProfile = profile,
                eqUiCapability = profile?.eqUiCapability,
                supportedFeatures = featureStatusesFor(profile),
                initialValuesReady = false,
                essentialValuesReady = false,
            )
        }
        if (!alreadyProbed) {
            startCapabilityExchange(probeCommands)
        }
        appendLog("Capability table applied: ${functions.size} functions", writeLogcat = false)
        refreshBasics(initial = true)
    }

    /**
     * Run the per-domain capability requests the way SC's initializer does: one at a time, each
     * blocking until its own reply lands, the whole run inside a single try, and the row written
     * only after that run returns.
     *
     * SC's `wv.e$e` sends every step through `P(msg, replyClass, predicate)` → `wv.e$c.f(...)`,
     * which blocks on a `CountDownLatch`; the sequence sits between `:try_start_1e` and
     * `:try_end_6b8`, and `capabilitystore.d.h()` (saveIntoStorage) is emitted *after* the four
     * catches, so a step that never answers jumps to `:catch_6fc` ("Initialization interrupted")
     * and the row is left exactly as it was. Firing the burst and letting the initial-value gate
     * decide the exchange was done is what persisted short rows: that gate's wait set is derived
     * from the capability table itself, so a table missing a domain never waited for it.
     */
    private fun startCapabilityExchange(commands: List<HeadphoneCommand>) {
        if (commands.isEmpty()) return
        capabilityInitializer.start(
            name = "sonypods-capability-exchange",
            sequence = {
                commands.forEach { command ->
                    capabilityInitializer.sendAndAwait(command, CAPABILITY_STEP_TIMEOUT_MS) { parsed ->
                        capabilityReplyMatches(command.bytes, parsed)
                    }
                }
            },
            onComplete = { capabilityCacheManager?.saveAll() },
        )
    }

    /**
     * Whether [parsed] is the reply to the request [request] — SC's per-step predicate, expressed
     * on the wire bytes we have instead of on a reply class. MDR pairs GET/RET as base+0/base+1
     * within a dataType (0x60 GET_CAPABILITY ↔ 0x61 RET_CAPABILITY), and the inquired type rides
     * in the next byte, so masking the low bit off the command byte matches a reply to its own
     * request and to nothing else.
     */
    private fun capabilityReplyMatches(request: ByteArray, parsed: ParsedTandemResponse): Boolean {
        val reply = parsed.raw
        if (request.size < 2 || reply.size < 2) return false
        if (reply[0] != request[0]) return false
        if ((reply[1].toInt() and 0xFE) != (request[1].toInt() and 0xFE)) return false
        val requestedInquiredType = request.getOrNull(2) ?: return true
        return requestedInquiredType == reply.getOrNull(2)
    }

    private fun clearSupportFunctionProbeState() {
        capabilitySession?.let { session ->
            session.supportFunctionProbeRunning = false
            session.pendingSupportFunctionTables.clear()
            session.supportFunctionsByTable.clear()
        }
    }

    /**
     * V1 NCASM capability: the NcAsmSettingType byte decides whether the device
     * has the three-state (dual/single-mic) NC setting — the single-mic state is
     * what the UI exposes as wind-noise reduction (SC `qe0.d2$c` →
     * NoiseCancellingType.DUAL_SINGLE).
     */
    private fun applyNcAsmCapabilityInfo(response: ParsedTandemResponse.NcAsmCapabilityInfo) {
        val settingTypeHex = response.ncAsmSettingType?.let { "0x%02X".format(it) } ?: "?"
        appendLog(
            "NCASM capability type=0x02 settingType=$settingTypeHex " +
                "windNoise=${response.supportsSingleMicWindNoise}",
            writeLogcat = false,
        )
        _state.update { current ->
            val profile = current.connectedProfile ?: return@update current
            if (profile.capabilities.supportsWindNoiseReduction == response.supportsSingleMicWindNoise) {
                current
            } else {
                current.copy(
                    connectedProfile = profile.copy(
                        capabilities = profile.capabilities.copy(
                            supportsWindNoiseReduction = response.supportsSingleMicWindNoise,
                        ),
                    ),
                )
            }
        }
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

    private fun applyPlaybackStatus(status: PlaybackStatus, source: String) {
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

        // An unsolicited NTFY is accepted as-is: the headset is the party
        // executing playback, and its notification arrives over GATT before
        // AudioManager.isMusicActive can reflect a headset-initiated pause —
        // "verifying" against the slower source could only reject the truth
        // and burn a re-query round trip.
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

    private fun appendLog(message: String, kind: DebugLogKind = DebugLogKind.INFO, writeLogcat: Boolean = true) {
        if (writeLogcat) {
            // BASIC logcat carries only warnings and errors; every repository
            // event line is routine and goes to logcat at DEBUG. The debug page
            // ring buffer receives it regardless of level.
            Log.d(LOG_TAG, message)
        }
        // Deliberately not part of _state: a log line must not re-emit the whole UI
        // state (and re-run every collector) — with the old field, one appendLog
        // produced two logcat lines and a full state cascade.
        // Appended at the tail so the debug page reads as a stable log tail (the page
        // follows the newest entry) instead of a prepend-shifted wall that jitters.
        _debugLogs.value = (_debugLogs.value + DebugLogEntry(message, kind)).takeLast(DEBUG_LOG_CAPACITY)
        debugLogForwarder?.invoke(message, kind)
    }

    fun ingestRemoteDebugLog(message: String, kind: DebugLogKind = DebugLogKind.INFO) {
        appendLog(message, kind = kind, writeLogcat = false)
    }

    companion object {
        @Volatile
        private var instance: SonyHeadphoneRepository? = null

        fun getInstance(
            resourceContext: Context,
            systemContext: Context = resourceContext,
            remoteModelInfoReader: (() -> String?)? = null,
            debugLogForwarder: ((String, DebugLogKind) -> Unit)? = null,
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

        const val LOG_TAG = "SonyPods-Engine"

        /** Ring-buffer depth for [debugLogs]. Sized so a whole battery-refresh burst
         * (TX + RX + parse lines) cannot evict the connection-time history: at ~30
         * lines per burst, 500 keeps roughly the last 15 bursts. */
        private const val DEBUG_LOG_CAPACITY = 500
        const val PLAY_NTFY_PARAM = 0xA9
        const val LEA_NTFY_STATUS = 0x45
        private const val LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING = 0x0C
        private const val LE_AUDIO_REFRESH_AFTER_SWITCH_MS = 1_000L

        /** How long a disable may wait for the headset's own confirmation before the
         * module-created LE bond is dropped without one. */
        private const val DISABLE_UNPAIR_FALLBACK_MS = 12_000L

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
    // Clear Bass exists only on the standard geometry (SC `EqBandStepsStandard`).
    val clamped = level.coerceIn(
        EqBandStepScale.STANDARD.displayRange.first,
        EqBandStepScale.STANDARD.displayRange.last,
    )
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

internal fun displayEqStep(
    rawStep: Int,
    scale: EqBandStepScale = EqBandStepScale.STANDARD,
): Int = scale.displayOf(rawStep)

internal fun displayEqBands(
    rawSteps: List<Int>,
    clearBassSlot: Boolean = true,
    scale: EqBandStepScale = EqBandStepScale.STANDARD,
): List<Int> {
    val displaySteps = rawSteps.map { scale.displayOf(it) }
    // Only the standard geometry (SC `EqBandStepsStandard`) carries Clear Bass
    // in the raw array at index 0; 10-band arrays are pure frequency steps.
    return if (clearBassSlot && displaySteps.size > EQ_FIRST_FREQUENCY_RAW_INDEX) {
        displaySteps.drop(EQ_FIRST_FREQUENCY_RAW_INDEX)
    } else {
        displaySteps
    }
}

internal fun displayEqStepToRaw(
    displayStep: Int,
    scale: EqBandStepScale = EqBandStepScale.STANDARD,
): Int = scale.rawOf(displayStep)

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
