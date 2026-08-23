package dev.sonypods.ble

import dev.sonypods.headphones.TandemChannel
import dev.sonypods.protocol.hexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [SonyGattTandemSession] against recorded byte sequences.
 *
 * The reference frame is a real capture from this project's SPP session,
 * `3E 0C 00 00 00 00 02 02 00 10 3C` (GET_CAPABILITY_INFO). Sound Connect frames GATT through the
 * same `ne0.C24171b`, so a correct GATT session must produce those exact bytes.
 */
class SonyGattTandemSessionTest {
    private val writes = mutableListOf<ByteArray>()
    private val payloads = mutableListOf<Pair<TandemChannel, ByteArray>>()
    private val failures = mutableListOf<String>()
    private val timeouts = mutableListOf<Pair<Long, () -> Unit>>()

    private fun session(writableValueLength: Int? = 509) = SonyGattTandemSession(
        channel = TandemChannel.GATT_V2_HPC,
        writableValueLength = writableValueLength,
        writeBytes = { bytes -> writes += bytes; true },
        onPayload = { channel, payload -> payloads += channel to payload },
        onFailure = { reason -> failures += reason },
        log = {},
        scheduleTimeout = { delayMs, action -> timeouts += delayMs to action },
    )

    @Test
    fun sendProducesTheSameFrameAsSpp() {
        // Internal marker 0x0E maps to DataType DATA_MDR (0x0C) with the marker stripped.
        session().send(bytes("0E 02 00"))

        assertEquals(1, writes.size)
        assertEquals("3E 0C 00 00 00 00 02 02 00 10 3C".noSpaces(), writes[0].hexString().noSpaces())
    }

    @Test
    fun table2PayloadUsesTheTable2DataType() {
        session().send(bytes("0F 30 00"))

        // 0x0F maps to DATA_MDR_NO2 (0x0E).
        assertEquals(0x0E.toByte(), unframe(writes[0])[0])
    }

    @Test
    fun anInboundFrameIsUnframedAndAcked() {
        val session = session()
        // RET_CAPABILITY_INFO-shaped reply as DATA_MDR, sequence 1.
        session.onNotification(SonyTandemFraming.encode(0x0C, 1, bytes("03 00 41")))

        assertEquals(1, payloads.size)
        assertEquals(TandemChannel.GATT_V2_HPC, payloads[0].first)
        // The internal marker is restored for the protocol layer.
        assertEquals("0E 03 00 41".noSpaces(), payloads[0].second.hexString().noSpaces())

        // Sequence 1 is acknowledged with its inverse, 0.
        val ack = unframe(writes.single())
        assertEquals(0x01.toByte(), ack[0])
        assertEquals(0x00.toByte(), ack[1])
    }

    @Test
    fun aFrameSplitAcrossNotificationsIsReassembled() {
        val session = session()
        val frame = SonyTandemFraming.encode(0x0C, 1, bytes("03 00 41"))

        // Deliver one byte at a time: the worst case a real MTU can produce.
        frame.forEach { session.onNotification(byteArrayOf(it)) }

        assertEquals(1, payloads.size)
        assertEquals("0E 03 00 41".noSpaces(), payloads[0].second.hexString().noSpaces())
    }

    @Test
    fun twoFramesInOneNotificationAreBothDispatched() {
        val session = session()
        val first = SonyTandemFraming.encode(0x0C, 1, bytes("03 00 41"))
        val second = SonyTandemFraming.encode(0x0C, 0, bytes("25 09"))

        session.onNotification(first + second)

        assertEquals(2, payloads.size)
        assertEquals("0E 03 00 41".noSpaces(), payloads[0].second.hexString().noSpaces())
        assertEquals("0E 25 09".noSpaces(), payloads[1].second.hexString().noSpaces())
    }

    @Test
    fun aRetransmittedFrameIsAckedButDispatchedOnce() {
        val session = session()
        val frame = SonyTandemFraming.encode(0x0C, 1, bytes("03 00 41"))

        session.onNotification(frame)
        session.onNotification(frame)

        assertEquals(1, payloads.size)
        // Both copies are acknowledged, or the headset keeps resending.
        assertEquals(2, writes.size)
    }

    @Test
    fun aCorruptedFrameIsNeitherDispatchedNorAcked() {
        val session = session()
        val frame = SonyTandemFraming.encode(0x0C, 1, bytes("03 00 41"))
        frame[frame.size - 2] = (frame[frame.size - 2] + 1).toByte()

        session.onNotification(frame)

        assertEquals(0, payloads.size)
        assertEquals(0, writes.size)
    }

    @Test
    fun theNextFrameWaitsForTheAck() {
        val session = session()
        session.send(bytes("0E 02 00"))
        session.onWriteComplete(true)
        session.send(bytes("0E 04 01"))

        // Still one write: a data frame holds the line until acknowledged.
        assertEquals(1, writes.size)

        session.onNotification(SonyTandemFraming.encode(0x01, 1, byteArrayOf()))

        assertEquals(2, writes.size)
        // The second frame continues the sequence at 1.
        assertEquals(0x01.toByte(), unframe(writes[1])[1])
    }

    @Test
    fun aFrameLongerThanTheWritableLengthIsSplit() {
        val session = session(writableValueLength = 20)
        session.send(bytes("0E") + ByteArray(100) { 0x41 })

        assertTrue("expected several fragments, got ${writes.size}", writes.size > 1)
        assertTrue(writes.all { it.size <= 20 })
        // Reassembled, the fragments are exactly one frame.
        val rejoined = writes.reduce { a, b -> a + b }
        assertEquals(SonyTandemFraming.FRAME_START, rejoined.first())
        assertEquals(SonyTandemFraming.FRAME_END, rejoined.last())
    }

    @Test
    fun everyFragmentMustLandBeforeTheNextFrameIsWritten() {
        val session = session(writableValueLength = 20)
        session.send(bytes("0E") + ByteArray(100) { 0x41 })
        val fragments = writes.size
        session.send(bytes("0E 02 00"))

        // Acknowledge all but the last fragment; the queued frame must stay put.
        repeat(fragments - 1) { session.onWriteComplete(true) }
        assertEquals(fragments, writes.size)
    }

    @Test
    fun aDataFrameSchedulesItsAckTimeout() {
        session().send(bytes("0E 02 00"))

        // Official policy for DATA_MDR: 750 ms, up to 10 retries.
        assertEquals(1, timeouts.size)
        assertEquals(750L, timeouts[0].first)
    }

    @Test
    fun anUnacknowledgedFrameIsResentThenReportedAsFailed() {
        val session = session()
        session.send(bytes("0E 02 00"))

        // Run the scheduled timeout 11 times: 10 retries, then it gives up.
        repeat(11) {
            val pending = timeouts.removeAt(timeouts.size - 1)
            pending.second()
        }

        assertEquals(11, writes.size)
        assertEquals(1, failures.size)
        assertTrue(failures[0].contains("did not ACK"))
    }

    @Test
    fun closeDropsPendingStateSoTheNextSessionStartsClean() {
        val session = session()
        session.send(bytes("0E 02 00"))
        session.close()
        writes.clear()

        // A late notification after close must not produce anything.
        session.onNotification(SonyTandemFraming.encode(0x0C, 1, bytes("03 00 41")))

        assertEquals(0, payloads.size)
        assertEquals(0, writes.size)
    }

    private fun unframe(frame: ByteArray): ByteArray =
        SonyTandemFraming.unescape(frame.copyOfRange(1, frame.size - 1))

    private fun bytes(hex: String): ByteArray =
        hex.split(" ").filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun String.noSpaces(): String = replace(" ", "").uppercase()
}
