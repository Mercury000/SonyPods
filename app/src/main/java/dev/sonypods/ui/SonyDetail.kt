package dev.sonypods.ui

import dev.sonypods.data.BatteryState
import dev.sonypods.data.SonyHeadphoneUiState
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.PodParams

/** Callbacks from the earphone detail UI into [dev.sonypods.data.SonyHeadphoneRepository]. */
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
    val onRefresh: () -> Unit = {},
)

/** Maps Sony battery levels to the [BatteryParams] container used by the battery card and hooks. */
fun BatteryState.toBatteryParams(): BatteryParams = BatteryParams(
    left = left?.let { PodParams(battery = it, isConnected = true) },
    right = right?.let { PodParams(battery = it, isConnected = true) },
    case = cradle?.let { PodParams(battery = it, isConnected = true) },
)

/** Single-battery pod (headband form factor) or null when the device reports L/R levels. */
fun BatteryState.toSinglePodParams(): PodParams? =
    single?.let { PodParams(battery = it, isConnected = true) }

val SonyHeadphoneUiState.isConnected: Boolean
    get() = connectedDevice != null

val SonyHeadphoneUiState.displayName: String
    get() = deviceInfo.modelName ?: connectedDevice?.name ?: ""

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
