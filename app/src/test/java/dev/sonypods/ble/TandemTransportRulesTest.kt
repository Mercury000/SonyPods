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
        assertTrue(tracker.shouldDispatch(0))
        assertFalse(tracker.shouldDispatch(0))
        assertTrue(tracker.shouldDispatch(1))
        assertTrue(tracker.shouldDispatch(0))
        tracker.reset()
        assertTrue(tracker.shouldDispatch(0))
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
