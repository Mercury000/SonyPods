package dev.sonypods.utils.miuiStrongToast.data

import kotlinx.serialization.Serializable

@Serializable
data class TextParams(
    var text: String? = null,
    var textColor: Int = 0
)
