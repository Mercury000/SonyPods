package dev.sonypods.ui

import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.protocol.ConnectionQualityMode
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NoiseAdaptiveSensitivity
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.AssignableSettingsAction
import dev.sonypods.protocol.AssignableSettingsFunction
import dev.sonypods.protocol.AssignableSettingsKey
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.PodParams

/** Callbacks from the earphone detail UI; each one sends a command to the engine. */
data class SonyDetailActions(
    val onAncModeChange: (NoiseControlMode) -> Unit = {},
    val onWindNoiseReductionChange: (Boolean) -> Unit = {},
    val onAmbientLevelChange: (Int) -> Unit = {},
    val onAmbientVoiceModeChange: (Boolean) -> Unit = {},
    val onSpeakToChatEnabledChange: (Boolean) -> Unit = {},
    val onSpeakToChatSensitivityChange: (dev.sonypods.protocol.SmartTalkingDetectionSensitivity) -> Unit = {},
    val onSpeakToChatModeOutTimeChange: (dev.sonypods.protocol.SmartTalkingModeOutTime) -> Unit = {},
    val onNoiseAdaptiveChange: (Boolean) -> Unit = {},
    val onNoiseAdaptiveSensitivityChange: (NoiseAdaptiveSensitivity) -> Unit = {},
    val onEqPresetChange: (EqPresetId) -> Unit = {},
    val onClearBassChange: (Int) -> Unit = {},
    val onCustomEqBandChange: (Int, Int) -> Unit = { _, _ -> },
    val onGesturePresetChange: (AssignableSettingsKey, AssignableSettingsPreset) -> Unit = { _, _ -> },
    val onGestureFunctionChange: (AssignableSettingsKey, AssignableSettingsAction, AssignableSettingsFunction) -> Unit = { _, _, _ -> },
    val onQuickAccessFunctionChange: (Int, Int) -> Unit = { _, _ -> },
    val onGestureAmbientModesChange: (Set<GestureNoiseControlMode>) -> Unit = {},
    val onMultipointPairingModeChange: (Boolean) -> Unit = {},
    val onMultipointConnect: (String) -> Unit = {},
    val onMultipointDisconnect: (String) -> Unit = {},
    val onMultipointUnpair: (String) -> Unit = {},
    val onSourceSwitchEnabledChange: (Boolean) -> Unit = {},
    val onMultipointEnabledChange: (Boolean) -> Unit = {},
    val onLeAudioEnabledChange: (Boolean) -> Unit = {},
    val onLeAudioAlertReply: (Boolean) -> Unit = {},
    /** Toggles DSEE / DSEE Extreme (AUDIO-domain upscaling). */
    val onUpscalingEnabledChange: (Boolean) -> Unit = {},
    /** Picks 声音质量优先 / 稳定连接优先（AUDIO_SET_PARAM + PriorMode）。 */
    val onConnectionQualityChange: (ConnectionQualityMode) -> Unit = {},
    /** Bonds the headset's LE-only identity, the phone-side half of the LE Audio switch. */
    val onLeAudioDevicePair: () -> Unit = {},
    val onLeAudioPairingGuide: () -> Unit = {},
    /** Flips the system's per-device LE Audio permission; never touches the bond. */
    val onLeAudioPolicyChange: (Boolean) -> Unit = {},
    /** Flips this device's LDAC through the A2DP profile service, as the system's switch does. */
    val onLdacEnabledChange: (Boolean) -> Unit = {},
    val onMultipointAlertReply: (Boolean) -> Unit = {},
    val onFixedSourceChange: (String) -> Unit = {},
    val onMusicHandOverChange: (Boolean) -> Unit = {},
    val onOpenGestureOperations: () -> Unit = {},
    val onOpenMoreSettings: () -> Unit = {},
    val onOpenMultipointSettings: () -> Unit = {},
    val onPlaybackPrevious: () -> Unit = {},
    val onPlaybackPlayPause: () -> Unit = {},
    val onPlaybackNext: () -> Unit = {},
    val onPlaybackVolumeChange: (Int) -> Unit = {},
    val onPowerOff: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

/** Maps Sony battery levels to the [BatteryParams] container used by the battery card. */
fun SonyStateSnapshot.toBatteryParams(): BatteryParams = BatteryParams(
    left = batteryLeft?.let { PodParams(battery = it, isConnected = true) },
    right = batteryRight?.let { PodParams(battery = it, isConnected = true) },
    case = batteryCradle?.let { PodParams(battery = it, isConnected = true) },
)

/** Single-battery pod (headband form factor), or null when the device reports L/R
 * levels. Preferring L/R over a stray single reply keeps TWS devices off the
 * single-battery card even if a stale BATTERY response slipped through. */
fun SonyStateSnapshot.toSinglePodParams(): PodParams? =
    if (batteryLeft != null || batteryRight != null) null
    else batterySingle?.let { PodParams(battery = it, isConnected = true) }

val SonyStateSnapshot.displayName: String
    get() = deviceName.orEmpty()

/** Parsed noise-adaptive sensitivity carried in the snapshot, defaulting to STANDARD. */
fun SonyStateSnapshot.noiseAdaptiveSensitivityValue(): NoiseAdaptiveSensitivity =
    noiseAdaptiveSensitivity
        ?.let { name -> NoiseAdaptiveSensitivity.entries.firstOrNull { it.name == name } }
        ?: NoiseAdaptiveSensitivity.STANDARD

/** Name-only UI variant of the shared Sony identity service. */
fun isLikelySonyAudioDevice(name: String?): Boolean {
    return SonyDeviceService.isSonyName(name)
}
