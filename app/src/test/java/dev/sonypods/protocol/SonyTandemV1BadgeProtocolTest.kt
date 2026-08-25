package dev.sonypods.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Badge wire captures on the V1 table: SC `qe0.q1`(RET) / `qe0.p0`(NTFY) for
 * codec and `qe0.y2` / `qe0.l1` for the upscaling effect — each body carries a
 * leading FIXED_VALUE (0x00) inquired byte that V2 does not have. */
class SonyTandemV1BadgeProtocolTest {

    /** V1 codec RET `0E 19 00 02` — AAC active. */
    @Test
    fun audioCodec_ret_aac_parses() {
        val parsed = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0x19, 0x00, 0x02)
        ) as ParsedTandemResponse.AudioCodecStatus

        assertFalse(parsed.isUnsolicited)
        assertEquals(SoundQualityCodec.AAC, parsed.codec)
    }

    /** V1 codec NTFY `0E 1B 00 10` — LDAC push. */
    @Test
    fun audioCodec_ntfy_ldac_isUnsolicitedPush() {
        val parsed = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0x1B.toByte(), 0x00, 0x10)
        ) as ParsedTandemResponse.AudioCodecStatus

        assertTrue(parsed.isUnsolicited)
        assertEquals(SoundQualityCodec.LDAC, parsed.codec)
    }

    /** V1 has no LC3 entry; an LC3 byte decodes to UNSETTLED and hides upstream. */
    @Test
    fun audioCodec_lc3Byte_mapsToUnsettled() {
        val parsed = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0x19, 0x00, 0x30)
        ) as ParsedTandemResponse.AudioCodecStatus

        assertEquals(SoundQualityCodec.UNSETTLED, parsed.codec)
    }

    /** The FIXED_VALUE inquired byte is mandatory; anything else drops the frame. */
    @Test
    fun audioCodec_wrongInquiredByte_isUnknown() {
        assertTrue(
            SonyTandemV1Table1Protocol.parse(
                byteArrayOf(0x0E, 0x19, 0x01, 0x02)
            ) is ParsedTandemResponse.Unknown
        )
        assertTrue(
            SonyTandemV1Table1Protocol.parse(
                byteArrayOf(0x0E, 0x19, 0x00)
            ) is ParsedTandemResponse.Unknown
        )
    }

    /** V1 upscaling effect RET `0E 15 00 01 01` — DSEE generation, VALID. */
    @Test
    fun upscalingEffect_ret_dseeValid_parses() {
        val parsed = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0x15, 0x00, 0x01, 0x01)
        ) as ParsedTandemResponse.UpscalingEffect

        assertFalse(parsed.isUnsolicited)
        assertEquals(DseeGeneration.DSEE, parsed.generation)
        assertEquals(DseeEffectState.VALID, parsed.state)
    }

    /** V1 NTFY `0E 17 00 03 02` — Ultimate generation reported INVALID. */
    @Test
    fun upscalingEffect_ntfy_ultimateInvalid_isUnsolicitedPush() {
        val parsed = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0x17, 0x00, 0x03, 0x02)
        ) as ParsedTandemResponse.UpscalingEffect

        assertTrue(parsed.isUnsolicited)
        assertEquals(DseeGeneration.DSEE_ULTIMATE, parsed.generation)
        assertEquals(DseeEffectState.INVALID, parsed.state)
    }
}
