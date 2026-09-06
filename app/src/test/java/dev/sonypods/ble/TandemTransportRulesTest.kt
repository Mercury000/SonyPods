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
    fun writableValueLength_parsesOnlyTwoByteBigEndianValues() {
        assertEquals(17, TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x00, 0x11)))
        assertEquals(509, TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x01, 0xFD.toByte())))
        assertNull(TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x00)))
        assertNull(TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x00, 0x11, 0x00)))
    }

    @Test
    fun writableValueLength_outOfWindowIsAdvisoryNotFatal() {
        // Sound Connect logs "Too small / Too large" and still adopts the raw value
        // (`C19228a.mo69459d`), so the range is a warning, never a parse failure.
        assertEquals(16, TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x00, 0x10)))
        assertEquals(512, TandemGattProtocolRules.parseWritableValueLength(byteArrayOf(0x02, 0x00)))
        assertTrue(TandemGattProtocolRules.isOutOfProtocolRange(16))
        assertTrue(TandemGattProtocolRules.isOutOfProtocolRange(512))
        assertFalse(TandemGattProtocolRules.isOutOfProtocolRange(17))
        assertFalse(TandemGattProtocolRules.isOutOfProtocolRange(509))
    }

    @Test
    fun determineMtuReady_onlyOneByteMtuIsDetermined() {
        // gh.C17018p: DETERMINE_MTU is a single-byte MtuStatus; 0x01 = MTU_IS_DETERMINED.
        assertTrue(TandemGattProtocolRules.isDetermineReady(byteArrayOf(0x01)))
        assertFalse(TandemGattProtocolRules.isDetermineReady(byteArrayOf(0x00)))
        assertFalse(TandemGattProtocolRules.isDetermineReady(byteArrayOf(0xFF.toByte())))
        assertFalse(TandemGattProtocolRules.isDetermineReady(byteArrayOf(0x01, 0x00)))
        assertFalse(TandemGattProtocolRules.isDetermineReady(byteArrayOf()))
    }

    @Test
    fun writableValueLength_rejectsOversizedPayloadWithoutGuessingFragmentation() {
        assertTrue(TandemGattProtocolRules.canWrite(17, 17))
        assertFalse(TandemGattProtocolRules.canWrite(18, 17))
        assertTrue(TandemGattProtocolRules.canWrite(600, null))
    }
}
