package dev.sonypods.ble

/** Wire-level constraints used by the Sound Connect Tandem GATT session. */
internal object TandemGattProtocolRules {
    const val DETERMINE_READY: Byte = 0x01

    fun isDetermineReady(value: ByteArray): Boolean =
        value.size == 1 && value[0] == DETERMINE_READY

    /** WRITABLE_VALUE_LENGTH is a 2-byte big-endian value. */
    fun parseWritableValueLength(value: ByteArray): Int? {
        if (value.size != 2) return null
        val parsed = ((value[0].toInt() and 0xFF) shl 8) or
            (value[1].toInt() and 0xFF)
        return parsed.takeIf { it + TANDEM_GATT_OVERHEAD in MIN_ATT_MTU..MAX_ATT_MTU }
    }

    fun canWrite(payloadSize: Int, writableValueLength: Int?): Boolean =
        writableValueLength == null || payloadSize <= writableValueLength

    private const val TANDEM_GATT_OVERHEAD = 3
    private const val MIN_ATT_MTU = 20
    private const val MAX_ATT_MTU = 512
}
