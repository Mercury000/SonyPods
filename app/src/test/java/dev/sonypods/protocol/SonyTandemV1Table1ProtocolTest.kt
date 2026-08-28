package dev.sonypods.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1 Table1 Smart Talking Mode wire format, verified against Sound Connect's
 * table-set-1 implementation (`ve0.a`–`ve0.e`, `qe0.r3`/`qe0.q3`):
 * - single SMART_TALKING_MODE type byte 0x05 (0x02 is POWER_SAVING_MODE on V1)
 * - SET_PARAM = [0x05][MODE_ON_OFF 0x01][ON 0x01 / OFF 0x00]
 * - SET_EXT_PARAM = [0x05][DETAIL_TYPE_1 0x00][sensitivity][voiceFocus ON 0x01][modeOutTime]
 * - ON/OFF polarity is the inverse of V2's OnOffSettingValue.
 */
class SonyTandemV1Table1ProtocolTest {

    @Test
    fun speakToChat_buildCommands_matchOfficialV1Layout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF8.toByte(), 0x05, 0x01, 0x01),
            SonyTandemV1Table1Protocol.buildSetSpeakToChatEnabled(true),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF8.toByte(), 0x05, 0x01, 0x00),
            SonyTandemV1Table1Protocol.buildSetSpeakToChatEnabled(false),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xFC.toByte(), 0x05, 0x00, 0x01, 0x01, 0x02),
            SonyTandemV1Table1Protocol.buildSetSpeakToChatExtParam(
                sensitivity = SmartTalkingDetectionSensitivity.HIGH,
                modeOutTime = SmartTalkingModeOutTime.SLOW,
                voiceFocus = true,
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF6.toByte(), 0x05),
            SonyTandemV1Table1Protocol.buildGetSpeakToChatParam(),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xFA.toByte(), 0x05),
            SonyTandemV1Table1Protocol.buildGetSpeakToChatExtParam(),
        )
    }

    @Test
    fun speakToChat_parseStatus_reportsEffectOnlyNotTheToggle() {
        // [SMART_TALKING_MODE][CommonStatus = availability][effectStatus ACTIVE=0x01]
        val active = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0xF3.toByte(), 0x05, 0x00, 0x01),
        ) as ParsedTandemResponse.SpeakToChatStatus
        assertEquals(SmartTalkingEffectStatus.ACTIVE, active.effectStatus)

        val idle = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0xF5.toByte(), 0x05, 0x00, 0x00),
        ) as ParsedTandemResponse.SpeakToChatStatus
        assertEquals(SmartTalkingEffectStatus.IDLE, idle.effectStatus)
    }

    @Test
    fun speakToChat_parseParam_retAndNtfy_useV1Polarity() {
        // RET_PARAM: [0x05][settingType ON_OFF 0x00][value ON=0x01]
        val ret = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0xF7.toByte(), 0x05, 0x00, 0x01),
        ) as ParsedTandemResponse.SpeakToChatParam
        assertEquals(true, ret.enabled)

        // NTFY_PARAM: [0x05][paramType MODE_ON_OFF 0x01][value OFF=0x00]
        val ntfy = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0xF9.toByte(), 0x05, 0x01, 0x00),
        ) as ParsedTandemResponse.SpeakToChatParam
        assertEquals(false, ntfy.enabled)
    }

    @Test
    fun speakToChat_parseExtendedParam_matchesOfficialV1Layout() {
        // [0x05][DETAIL_TYPE_1 0x00][HIGH][voiceFocus ON 0x01][SLOW]
        val parsed = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0xFB.toByte(), 0x05, 0x00, 0x01, 0x01, 0x02),
        ) as ParsedTandemResponse.SpeakToChatParam
        assertEquals(SmartTalkingDetectionSensitivity.HIGH, parsed.sensitivity)
        assertEquals(true, parsed.voiceFocus)
        assertEquals(SmartTalkingModeOutTime.SLOW, parsed.modeOutTime)
    }

    @Test
    fun speakToChat_v2TypeBytes_areNotSmartTalkingOnV1() {
        // 0x02 is POWER_SAVING_MODE on V1: its responses must not be mistaken
        // for Smart Talking state.
        val powerSaving = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0xF3.toByte(), 0x02, 0x00),
        )
        assertFalse(powerSaving is ParsedTandemResponse.SpeakToChatStatus)
    }
}
