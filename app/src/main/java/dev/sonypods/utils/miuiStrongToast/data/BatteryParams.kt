package dev.sonypods.utils.miuiStrongToast.data

import kotlinx.serialization.Serializable

@Serializable
data class PodParams(
    var battery: Int = 0,
    var isCharging: Boolean = false,
    var isConnected: Boolean = false,
    var rawStatus: Int = 0,
)

@Serializable
data class BatteryParams(
    var left: PodParams? = null,
    var right: PodParams? = null,
    var case: PodParams? = null,
) {
    val hasAnyLevel: Boolean
        get() = (left?.battery ?: 0) > 0 ||
            (right?.battery ?: 0) > 0 ||
            (case?.battery ?: 0) > 0
}
