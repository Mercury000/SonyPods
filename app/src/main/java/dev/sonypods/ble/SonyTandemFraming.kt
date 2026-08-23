package dev.sonypods.ble

/**
 * Tandem frame codec, shared by every transport.
 *
 * Sound Connect frames the same way regardless of transport: `ie0.C18010c.m73194n` hands the
 * payload to one framer, `ne0.C24171b`, whose output target is the transport abstraction
 * `InterfaceC18013f` — implemented by the GATT session (`je0.C19229b`) and by the RFCOMM socket
 * alike. Both `CommandTableSet` branches of `le0.C22925e` construct that same framer, and the
 * receive side mirrors it in `ne0.AbstractC24170a`. There is no unframed variant: writing a bare
 * Tandem payload to the GATT characteristic is dropped by the headset, which is why LE Audio
 * sessions came up and then answered nothing.
 *
 * Layout, matching `C24171b.m94090e` and `AbstractC24170a.m94081c`:
 * `0x3E` + escape(dataType, sequence, length(4, big endian), payload, checksum) + `0x3C`,
 * where checksum is the low byte of the sum over everything before it.
 */
internal object SonyTandemFraming {
    const val FRAME_START: Byte = 0x3E
    const val FRAME_END: Byte = 0x3C
    const val ESCAPE: Byte = 0x3D
    const val HEADER_SIZE = 6
    const val CHECKSUM_SIZE = 1

    /** Smallest frame that can still carry a header and a checksum. */
    const val MIN_FRAME_SIZE = HEADER_SIZE + CHECKSUM_SIZE

    fun encode(type: Byte, sequence: Byte, payload: ByteArray): ByteArray {
        val body = ByteArray(HEADER_SIZE + payload.size + CHECKSUM_SIZE)
        body[0] = type
        body[1] = sequence
        body[2] = ((payload.size ushr 24) and 0xFF).toByte()
        body[3] = ((payload.size ushr 16) and 0xFF).toByte()
        body[4] = ((payload.size ushr 8) and 0xFF).toByte()
        body[5] = (payload.size and 0xFF).toByte()
        payload.copyInto(body, HEADER_SIZE)
        body[body.lastIndex] = checksum(body, body.size - CHECKSUM_SIZE).toByte()
        return byteArrayOf(FRAME_START) + escape(body) + byteArrayOf(FRAME_END)
    }

    /**
     * Decodes one frame body — the bytes between the start and end markers, still escaped.
     *
     * Returns null when the frame is unusable; the reason is reported through [onError] so a
     * caller can log it without this object knowing how.
     */
    fun decode(escapedBody: ByteArray, onError: (String) -> Unit): Frame? {
        val body = unescape(escapedBody)
        if (body.size < MIN_FRAME_SIZE) {
            onError("short frame size=${body.size}")
            return null
        }
        val expected = checksum(body, body.size - CHECKSUM_SIZE)
        val actual = body.last().u
        if (expected != actual) {
            onError("checksum mismatch expected=${expected.toString(16)} actual=${actual.toString(16)}")
            return null
        }
        val length = body.int32be(2)
        if (length < 0 || body.size != HEADER_SIZE + length + CHECKSUM_SIZE) {
            onError("invalid length=$length actual=${body.size - MIN_FRAME_SIZE}")
            return null
        }
        return Frame(
            type = body[0],
            sequence = body[1],
            payload = body.copyOfRange(HEADER_SIZE, HEADER_SIZE + length),
        )
    }

    fun escape(bytes: ByteArray): ByteArray {
        val escaped = ArrayList<Byte>(bytes.size)
        bytes.forEach { byte ->
            when (byte) {
                FRAME_END -> {
                    escaped += ESCAPE
                    escaped += 0x2C.toByte()
                }
                ESCAPE -> {
                    escaped += ESCAPE
                    escaped += 0x2D.toByte()
                }
                FRAME_START -> {
                    escaped += ESCAPE
                    escaped += 0x2E.toByte()
                }
                else -> escaped += byte
            }
        }
        return escaped.toByteArray()
    }

    fun unescape(bytes: ByteArray): ByteArray {
        val unescaped = ArrayList<Byte>(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index]
            if (byte == ESCAPE && index + 1 < bytes.size) {
                index++
                unescaped += (bytes[index].toInt() or 0x10).toByte()
            } else {
                unescaped += byte
            }
            index++
        }
        return unescaped.toByteArray()
    }

    fun checksum(bytes: ByteArray, length: Int): Int =
        (0 until length).fold(0) { acc, index -> (acc + bytes[index].u) and 0xFF }

    /** ACK carries the inverse of the sequence it acknowledges. */
    fun inverseSequence(sequence: Byte): Byte = (1 - sequence).toByte()

    data class Frame(val type: Byte, val sequence: Byte, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return type == other.type &&
                sequence == other.sequence &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int =
            (type * 31 + sequence) * 31 + payload.contentHashCode()
    }

    private fun ByteArray.int32be(offset: Int): Int =
        ((this[offset].u) shl 24) or
            ((this[offset + 1].u) shl 16) or
            ((this[offset + 2].u) shl 8) or
            this[offset + 3].u

    private val Byte.u: Int
        get() = toInt() and 0xFF
}
