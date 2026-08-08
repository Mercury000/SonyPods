package dev.sonypods.ui

import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.PodParams

/** Callbacks from the earphone detail UI; each one sends a command to the engine. */
data class SonyDetailActions(
    val onAncModeChange: (NoiseControlMode) -> Unit = {},
    val onAmbientLevelChange: (Int) -> Unit = {},
    val onAmbientVoiceModeChange: (Boolean) -> Unit = {},
    val onEqPresetChange: (EqPresetId) -> Unit = {},
    val onClearBassChange: (Int) -> Unit = {},
    val onCustomEqBandChange: (Int, Int) -> Unit = { _, _ -> },
    val onPlaybackPrevious: () -> Unit = {},
    val onPlaybackPlayPause: () -> Unit = {},
    val onPlaybackNext: () -> Unit = {},
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

/** Mirrors SonyBleClient.isSonyCandidate: name-based Sony audio device detection. */
fun isLikelySonyAudioDevice(name: String?): Boolean {
    val normalized = name?.trim()?.lowercase().orEmpty()
    return normalized.contains("sony") ||
        normalized.contains("linkbuds") ||
        normalized.startsWith("wf-") ||
        normalized.startsWith("wh-") ||
        normalized.startsWith("wi-") ||
        normalized.startsWith("xba-") ||
        normalized.startsWith("mdr-")
}
