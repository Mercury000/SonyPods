package dev.sonypods.utils

object MiuiHeadsetSupport {
    const val CAPABILITIES = "000000000000000010000000"

    fun encode(address: String): String = "${address.trim()},$CAPABILITIES"

    fun addressOf(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(',', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1] != CAPABILITIES) return null
        return parts[0]
    }
}
