package dev.sonypods.utils.miuiStrongToast.data

import kotlinx.serialization.Serializable

/**
 * Field set mirrors what HyperOS emits for its own headset toasts: the strong-toast
 * half carries [viewFlags], the island half carries [turnAnim].
 */
@Serializable
data class TextParams(
    var text: String? = null,
    var textColor: Int = 0,
    var viewFlags: Int? = null,
    var turnAnim: Boolean? = null,
)
