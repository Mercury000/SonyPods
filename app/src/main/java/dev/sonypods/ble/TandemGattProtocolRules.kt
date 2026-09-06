package dev.sonypods.ble

/** Wire-level constraints used by the Sound Connect Tandem GATT session. */
internal object TandemGattProtocolRules {
    /** DETERMINE_MTU carries a single-byte MtuStatus; 1 = MTU_IS_DETERMINED (`gh.C17018p`). */
    fun isDetermineReady(value: ByteArray): Boolean =
        value.size == 1 && value[0] == DETERMINE_MTU_DETERMINED

    private const val DETERMINE_MTU_DETERMINED: Byte = 0x01

    /** WRITABLE_VALUE_LENGTH is a 2-byte big-endian value; only a non-2-byte read is malformed. */
    fun parseWritableValueLength(value: ByteArray): Int? {
        if (value.size != 2) return null
        return ((value[0].toInt() and 0xFF) shl 8) or
            (value[1].toInt() and 0xFF)
    }

    /**
     * Whether a WRITABLE_VALUE_LENGTH sits outside the ATT window the framer can honour.
     *
     * Sound Connect treats an out-of-window value as advisory only — `C19228a.mo69459d` logs
     * "Too small / Too large WRITABLE VALUE LENGTH" and still adopts the raw value. Mirror that:
     * the range check is logged, never fatal.
     */
    fun isOutOfProtocolRange(writableValueLength: Int): Boolean =
        (writableValueLength + TANDEM_GATT_OVERHEAD) !in MIN_ATT_MTU..MAX_ATT_MTU

    fun canWrite(payloadSize: Int, writableValueLength: Int?): Boolean =
        writableValueLength == null || payloadSize <= writableValueLength

    private const val TANDEM_GATT_OVERHEAD = 3
    private const val MIN_ATT_MTU = 20
    private const val MAX_ATT_MTU = 512
}
