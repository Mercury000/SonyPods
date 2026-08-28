package dev.sonypods.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerProtocolTest {

    @Test
    fun testUltModePackAndParse() {
        // Pack ULT 1 with no base preset known → base falls back to OFF
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

        // Pack ULT 1 while BASS is the device's current preset: official
        // C17560d pairs the *current* EqPresetId with the EqUltModeStatus, so
        // the base preset byte must be preserved, not forced to OFF.
        val ult1WithBaseFrame = SonyTandemV2Table1Protocol.buildSetEqPreset(
            preset = EqPresetId.ULT_1,
            typeCode = EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE.code,
            bandSteps = listOf(10, 10, 10),
            basePreset = EqPresetId.BASS,
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x03, 0x16, 0x01, 0x03, 10, 10, 10),
            ult1WithBaseFrame,
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

        // Parse ULT 1 from RET_PARAM / NTFY_PARAM payload:
        // [type 0x03, preset 0x00, ultMode 0x01, bandCount 3, ...]
        // The parser reports the base preset and the ult mode separately —
        // folding into a display marker is the caller's job.
        val parsedUlt1 = SonyEqEbbPayloadParser.parse(
            version = EqEbbPayloadVersion.V2,
            command = 0x57.toByte(),
            payload = byteArrayOf(0x03, 0x00, 0x01, 0x03, 10, 10, 10),
            raw = byteArrayOf(),
        ) as ParsedTandemResponse.EqEbb
        assertEquals(EqPresetId.OFF, parsedUlt1.preset)
        assertEquals(0x01, parsedUlt1.ultMode)
        assertEquals(listOf(10, 10, 10), parsedUlt1.bandSteps)

        // Parse ULT 2 from RET_PARAM payload
        val parsedUlt2 = SonyEqEbbPayloadParser.parse(
            version = EqEbbPayloadVersion.V2,
            command = 0x57.toByte(),
            payload = byteArrayOf(0x03, 0x00, 0x02, 0x00),
            raw = byteArrayOf(),
        ) as ParsedTandemResponse.EqEbb
        assertEquals(EqPresetId.OFF, parsedUlt2.preset)
        assertEquals(0x02, parsedUlt2.ultMode)

        // ULT off: the base preset byte is the preset again, ultMode 0
        val parsedUltOff = SonyEqEbbPayloadParser.parse(
            version = EqEbbPayloadVersion.V2,
            command = 0x57.toByte(),
            payload = byteArrayOf(0x03, 0x16, 0x00, 0x00),
            raw = byteArrayOf(),
        ) as ParsedTandemResponse.EqEbb
        assertEquals(EqPresetId.BASS, parsedUltOff.preset)
        assertEquals(0x00, parsedUltOff.ultMode)
    }

    @Test
    fun testSoundEffectPackAndParse() {
        // Pack FLAT (0x05) — SC gf0.z0 builds [type][effect], frame length 3.
        val flatFrame = SonyTandemV2Table1Protocol.buildSetEqPreset(
            preset = EqPresetId.FLAT,
            typeCode = EqEbbInquiredType.SOUND_EFFECT.code,
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x30, 0x05),
            flatFrame,
        )

        // Pack LIVE_SOUND (0x06) via CUSTOMIZABLE_SOUND_EFFECT_SELECT — SC
        // gf0.v0 also validates length == 3: no trailing byte.
        val liveFrame = SonyTandemV2Table1Protocol.buildSetEqPreset(
            preset = EqPresetId.LIVE_SOUND,
            typeCode = EqEbbInquiredType.CUSTOMIZABLE_SOUND_EFFECT_SELECT.code,
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x33, 0x06),
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

    /** The sound-effect vocabulary must never leak into a preset-space write:
     * 0x34/0x35/0x40-0x42 are not EqPresetId codes in SC's table. */
    @Test
    fun testSoundEffectSpacePreset_isRejectedByPresetWrite() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SonyTandemV2Table1Protocol.buildSetEqPreset(
                preset = EqPresetId.ULT_1,
                typeCode = EqEbbInquiredType.PRESET_EQ.code,
            )
        }
    }

    /** Undefined preset bytes must not resolve to the sound-effect vocabulary. */
    @Test
    fun testUndefinedPresetCode_parsesToNull() {
        val parsed = SonyEqEbbPayloadParser.parse(
            version = EqEbbPayloadVersion.V2,
            command = 0x57.toByte(),
            payload = byteArrayOf(0x00, 0x40),
            raw = byteArrayOf(),
        ) as ParsedTandemResponse.EqEbb
        assertNull(parsed.preset)
    }

    /** V1 DUAL_SINGLE_OFF frames keep the wind-noise reading; ON_OFF frames
     * (payload[3] = NcOnOffValue, not NcDualSingleValue) must not report one. */
    @Test
    fun testV1WindNoiseSettingTypeGate() {
        // DUAL_SINGLE_OFF setting type (payload[2]=0x02), single-mic NC on
        val dualSingle = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0x67, 0x02, 0x11, 0x02, 0x01, 0x01, 0x00, 0x00)
        ) as ParsedTandemResponse.NoiseControl
        assertEquals(true, dualSingle.windNoiseReduction)

        // ON_OFF setting type (payload[2]=0x00): payload[3]=0x01 just means
        // "NC on" — the wind-noise flag must stay null.
        val onOff = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0x67, 0x02, 0x11, 0x00, 0x01, 0x00, 0x00, 0x00)
        ) as ParsedTandemResponse.NoiseControl
        assertEquals(NoiseControlMode.NOISE_CANCELLING, onOff.controlMode)
        assertNull(onOff.windNoiseReduction)
    }

    /** The AUTO device (0x15) resolves the AUTO write into the actual mic
     * configuration: AUTO_SINGLE (0x04) / AUTO_DUAL (0x05) readbacks still
     * mean auto wind-noise reduction is active. */
    @Test
    fun testAutoNcWindNoiseAutoSingleAndDualReadback() {
        val autoSingle = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x15, 0x01, 0x01, 0x00, 0x04, 0x00, 0x0A)
        ) as ParsedTandemResponse.NoiseControl
        assertEquals(NoiseControlMode.NOISE_CANCELLING, autoSingle.controlMode)
        assertEquals(true, autoSingle.windNoiseReduction)

        val autoDual = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x15, 0x01, 0x01, 0x00, 0x05, 0x00, 0x0A)
        ) as ParsedTandemResponse.NoiseControl
        assertEquals(NoiseControlMode.NOISE_CANCELLING, autoDual.controlMode)
        assertEquals(true, autoDual.windNoiseReduction)
    }
}
