package dev.sonypods.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerProtocolTest {

    @Test
    fun testUltModePackAndParse() {
        // Pack ULT 1
        val ult1Frame = SonyTandemV2Table1Protocol.buildSetEqPreset(
            preset = EqPresetId.ULT_1,
            typeCode = EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE.code,
            bandSteps = listOf(10, 10, 10),
        )
        // Command 0x58 (EQEBB_SET_PARAM), type 0x03, base preset 0x00, ultMode 0x01, bandCount 3, bands
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x03, 0x00, 0x01, 0x03, 10, 10, 10),
            ult1Frame,
        )

        // Pack ULT 2
        val ult2Frame = SonyTandemV2Table1Protocol.buildSetEqPreset(
            preset = EqPresetId.ULT_2,
            typeCode = EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE.code,
            bandSteps = emptyList<Int>(),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x03, 0x00, 0x02, 0x00),
            ult2Frame,
        )

        // Parse ULT 1 from RET_PARAM / NTFY_PARAM payload: [type 0x03, preset 0x00, ultMode 0x01, bandCount 3, ...]
        val parsedUlt1 = SonyEqEbbPayloadParser.parse(
            version = EqEbbPayloadVersion.V2,
            command = 0x57.toByte(),
            payload = byteArrayOf(0x03, 0x00, 0x01, 0x03, 10, 10, 10),
            raw = byteArrayOf(),
        ) as ParsedTandemResponse.EqEbb
        assertEquals(EqPresetId.ULT_1, parsedUlt1.preset)

        // Parse ULT 2 from RET_PARAM payload
        val parsedUlt2 = SonyEqEbbPayloadParser.parse(
            version = EqEbbPayloadVersion.V2,
            command = 0x57.toByte(),
            payload = byteArrayOf(0x03, 0x00, 0x02, 0x00),
            raw = byteArrayOf(),
        ) as ParsedTandemResponse.EqEbb
        assertEquals(EqPresetId.ULT_2, parsedUlt2.preset)
    }

    @Test
    fun testSoundEffectPackAndParse() {
        // Pack FLAT (0x05)
        val flatFrame = SonyTandemV2Table1Protocol.buildSetEqPreset(
            preset = EqPresetId.FLAT,
            typeCode = EqEbbInquiredType.SOUND_EFFECT.code,
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x30, 0x05),
            flatFrame,
        )

        // Pack LIVE_SOUND (0x06)
        val liveFrame = SonyTandemV2Table1Protocol.buildSetEqPreset(
            preset = EqPresetId.LIVE_SOUND,
            typeCode = EqEbbInquiredType.CUSTOMIZABLE_SOUND_EFFECT_SELECT.code,
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x33, 0x06, 0x00),
            liveFrame,
        )

        // Parse FLAT from payload: [type 0x30, soundEffect 0x05]
        val parsedFlat = SonyEqEbbPayloadParser.parse(
            version = EqEbbPayloadVersion.V2,
            command = 0x57.toByte(),
            payload = byteArrayOf(0x30, 0x05),
            raw = byteArrayOf(),
        ) as ParsedTandemResponse.EqEbb
        assertEquals(EqPresetId.FLAT, parsedFlat.preset)

        // Parse LIVE_SOUND from payload: [type 0x33, soundEffect 0x06]
        val parsedLive = SonyEqEbbPayloadParser.parse(
            version = EqEbbPayloadVersion.V2,
            command = 0x57.toByte(),
            payload = byteArrayOf(0x33, 0x06),
            raw = byteArrayOf(),
        ) as ParsedTandemResponse.EqEbb
        assertEquals(EqPresetId.LIVE_SOUND, parsedLive.preset)
    }
}
