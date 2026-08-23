package dev.sonypods.ble

import dev.sonypods.protocol.hexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reference frame is a real capture from this project's SPP session:
 * `3E 0C 00 00 00 00 02 02 00 10 3C` — GET_CAPABILITY_INFO (`02 00`) as DATA_MDR, sequence 0.
 * Sound Connect builds byte-for-byte the same frame for GATT, since `ie0.C18010c` hands both
 * transports to the one framer `ne0.C24171b`.
 */
class SonyTandemFramingTest {
    private val capturedFrame = bytes("3E 0C 00 00 00 00 02 02 00 10 3C")

    @Test
    fun encodesTheCapturedFrameByteForByte() {
        val encoded = SonyTandemFraming.encode(
            type = 0x0C,
            sequence = 0,
            payload = bytes("02 00"),
        )

        assertEquals(capturedFrame.hexString(), encoded.hexString())
    }

    @Test
    fun decodeRecoversTypeSequenceAndPayload() {
        // Strip the start/end markers the way a transport's reader does.
        val body = capturedFrame.copyOfRange(1, capturedFrame.size - 1)
        val frame = SonyTandemFraming.decode(body) { error("unexpected: $it") }!!

        assertEquals(0x0C.toByte(), frame.type)
        assertEquals(0.toByte(), frame.sequence)
        assertArrayEquals(bytes("02 00"), frame.payload)
    }

    @Test
    fun checksumCoversEverythingBeforeIt() {
        // 0x0C + 0x00 + 0x00+0x00+0x00+0x02 + 0x02 + 0x00 = 0x10, the captured checksum byte.
        assertEquals(0x10, SonyTandemFraming.checksum(bytes("0C 00 00 00 00 02 02 00"), 8))
    }

    @Test
    fun lengthIsFourBytesBigEndian() {
        val encoded = SonyTandemFraming.encode(0x0C, 0, ByteArray(0x1234) { 0 })
        val body = SonyTandemFraming.unescape(encoded.copyOfRange(1, encoded.size - 1))

        assertEquals(0x00.toByte(), body[2])
        assertEquals(0x00.toByte(), body[3])
        assertEquals(0x12.toByte(), body[4])
        assertEquals(0x34.toByte(), body[5])
    }

    @Test
    fun markerBytesInsidePayloadAreEscaped() {
        // A payload containing 3E/3C/3D must not be mistaken for frame boundaries.
        val payload = bytes("3E 3C 3D")
        val encoded = SonyTandemFraming.encode(0x0C, 0, payload)

        // Exactly one start and one end marker survive, at the edges.
        assertEquals(1, encoded.count { it == SonyTandemFraming.FRAME_START })
        assertEquals(1, encoded.count { it == SonyTandemFraming.FRAME_END })
        assertEquals(SonyTandemFraming.FRAME_START, encoded.first())
        assertEquals(SonyTandemFraming.FRAME_END, encoded.last())
    }

    @Test
    fun escapingRoundTrips() {
        val payload = bytes("3E 3C 3D 00 FF 3D 3D")
        val encoded = SonyTandemFraming.encode(0x0E, 1, payload)
        val frame = SonyTandemFraming.decode(
            encoded.copyOfRange(1, encoded.size - 1),
        ) { error("unexpected: $it") }!!

        assertEquals(0x0E.toByte(), frame.type)
        assertEquals(1.toByte(), frame.sequence)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun aCorruptedChecksumIsRejected() {
        val body = capturedFrame.copyOfRange(1, capturedFrame.size - 1)
        body[body.lastIndex] = (body.last() + 1).toByte()
        var reported: String? = null

        assertNull(SonyTandemFraming.decode(body) { reported = it })
        assertEquals(true, reported?.contains("checksum"))
    }

    @Test
    fun aTruncatedFrameIsRejected() {
        var reported: String? = null

        assertNull(SonyTandemFraming.decode(bytes("0C 00 00")) { reported = it })
        assertEquals(true, reported?.contains("short"))
    }

    @Test
    fun aLengthThatDisagreesWithThePayloadIsRejected() {
        // Header claims 8 bytes of payload, only 2 follow.
        val body = bytes("0C 00 00 00 00 08 02 00")
        val withChecksum = body + SonyTandemFraming.checksum(body, body.size).toByte()
        var reported: String? = null

        assertNull(SonyTandemFraming.decode(withChecksum) { reported = it })
        assertEquals(true, reported?.contains("length"))
    }

    @Test
    fun ackSequenceIsTheInverse() {
        assertEquals(1.toByte(), SonyTandemFraming.inverseSequence(0))
        assertEquals(0.toByte(), SonyTandemFraming.inverseSequence(1))
    }

    private fun bytes(hex: String): ByteArray =
        hex.split(" ").filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
