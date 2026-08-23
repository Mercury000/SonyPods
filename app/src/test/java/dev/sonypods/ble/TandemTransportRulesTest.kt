package dev.sonypods.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TandemTransportRulesTest {
    @Test
    fun retryPolicies_matchSoundConnectTiming() {
        assertEquals(SppRetryPolicy(750L, 10), SonySppFrameType.DATA_MDR.retryPolicy())
        assertEquals(SppRetryPolicy(750L, 10), SonySppFrameType.DATA_MDR_NO2.retryPolicy())
        assertEquals(SppRetryPolicy(5_000L, 2), SonySppFrameType.LARGE_DATA_MDR.retryPolicy())
        assertNull(SonySppFrameType.ACK.retryPolicy())
        assertNull(SonySppFrameType.SHOT_MDR.retryPolicy())
        assertNull(SonySppFrameType.SHOT_MDR_NO2.retryPolicy())
    }

    @Test
    fun rxSequenceTracker_filtersOnlyConsecutiveDuplicates() {
        val tracker = SppRxSequenceTracker()
        val type = SonySppFrameType.DATA_MDR_NO2
        assertTrue(tracker.shouldDispatch(type, 0))
        assertFalse(tracker.shouldDispatch(type, 0))
        assertTrue(tracker.shouldDispatch(type, 1))
        assertTrue(tracker.shouldDispatch(type, 0))
        tracker.reset()
        assertTrue(tracker.shouldDispatch(type, 0))
    }

    /**
     * Each frame type counts its sequence independently, so one type's frame is never the other's
     * retransmission. Sharing one counter dropped the second stream's frame — ACKed, never
     * dispatched — and Table1/Table2 traffic does share a transport.
     */
    @Test
    fun rxSequenceTracker_keepsFrameTypeSequenceSpacesApart() {
        val tracker = SppRxSequenceTracker()
        assertTrue(tracker.shouldDispatch(SonySppFrameType.DATA_MDR_NO2, 0))
        assertTrue(tracker.shouldDispatch(SonySppFrameType.DATA_MDR, 0))
        assertTrue(tracker.shouldDispatch(SonySppFrameType.LARGE_DATA_MDR, 0))
        assertFalse(tracker.shouldDispatch(SonySppFrameType.DATA_MDR_NO2, 0))
        assertTrue(tracker.shouldDispatch(SonySppFrameType.DATA_MDR_NO2, 1))
    }

    @Test
    fun writableValueLength_requiresValidTwoByteBigEndianProtocolRange() {
        assertEquals(17, TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x00, 0x11)))
        assertEquals(509, TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x01, 0xFD.toByte())))
        assertNull(TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x00)))
        assertNull(TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x00, 0x11, 0x00)))
        assertNull(TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x00, 0x10)))
        assertNull(TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x02, 0x00)))
    }

    @Test
    fun writableValueLength_rejectsOversizedPayloadWithoutGuessingFragmentation() {
        assertTrue(TandemGattProtocolRules.canWrite(17, 17))
        assertFalse(TandemGattProtocolRules.canWrite(18, 17))
        assertTrue(TandemGattProtocolRules.canWrite(600, null))
    }
}
