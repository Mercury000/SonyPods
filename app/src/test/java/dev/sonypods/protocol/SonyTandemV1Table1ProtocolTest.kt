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

    // ── Assignable settings (V1 wire type 0x06) ──────────────────────────────

    @Test
    fun assignableSettings_buildCommands_useV1TypeByte() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF0.toByte(), 0x06),
            SonyTandemV1Table1Protocol.buildGetAssignableSettingsCapability(),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF2.toByte(), 0x06),
            SonyTandemV1Table1Protocol.buildGetAssignableSettingsStatus(),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF6.toByte(), 0x06),
            SonyTandemV1Table1Protocol.buildGetAssignableSettingsPresets(),
        )
        // SET_PARAM = [0x06][count][preset...] in capability key order (SC se0.d).
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF8.toByte(), 0x06, 0x02, 0x20, 0x00),
            SonyTandemV1Table1Protocol.buildSetAssignableSettingsPresets(
                listOf(AssignableSettingsPreset.PLAYBACK_CONTROL, AssignableSettingsPreset.AMBIENT_SOUND_CONTROL),
            ),
        )
    }

    @Test
    fun assignableSettings_getSystemCapability_translatesSharedEnumToV1Type() {
        // Both shared-enum spellings must land on the V1 wire type 0x06; the
        // V2 code 0x03 is CONTROL_BY_WEARING on V1.
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF0.toByte(), 0x06),
            SonyTandemV1Table1Protocol.buildGetSystemCapability(SystemInquiredType.ASSIGNABLE_SETTINGS),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF0.toByte(), 0x06),
            SonyTandemV1Table1Protocol.buildGetSystemCapability(SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION),
        )
    }

    @Test
    fun assignableSettings_parseCapability_matchesNestedV1Layout() {
        // [0x06][1 key][LEFT][TOUCH_SENSOR][default ASC][1 preset][PLAYBACK_CONTROL][2 actions]
        //   actions: [SINGLE_TAP][PLAY_PAUSE], [DOUBLE_TAP][NEXT_TRACK]
        val parsed = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(
                0x0E, 0xF1.toByte(), 0x06, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x20, 0x02,
                0x00, 0x20,
                0x01, 0x21,
            ),
        ) as ParsedTandemResponse.AssignableSettingsCapability
        assertEquals(1, parsed.keys.size)
        val key = parsed.keys.first()
        assertEquals(AssignableSettingsKey.LEFT_SIDE, key.key)
        assertEquals(AssignableSettingsType.TOUCH_SENSOR, key.type)
        assertEquals(AssignableSettingsPreset.AMBIENT_SOUND_CONTROL, key.defaultPreset)
        assertEquals(listOf(AssignableSettingsPreset.PLAYBACK_CONTROL), key.presets)
        val actions = key.actionsByPreset.getValue(AssignableSettingsPreset.PLAYBACK_CONTROL)
        assertEquals(2, actions.size)
        assertEquals(AssignableSettingsAction.SINGLE_TAP, actions[0].action)
        assertEquals(AssignableSettingsFunction.PLAY_PAUSE, actions[0].defaultFunction)
        assertEquals(AssignableSettingsAction.DOUBLE_TAP, actions[1].action)
        assertEquals(AssignableSettingsFunction.NEXT_TRACK, actions[1].defaultFunction)
    }

    @Test
    fun assignableSettings_parsePresetsAndStatus_matchV1Layout() {
        // RET_PARAM = [0x06][count][preset...]
        val presets = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0xF7.toByte(), 0x06, 0x02, 0x00, 0x20),
        ) as ParsedTandemResponse.AssignableSettingsPresets
        assertEquals(
            listOf(AssignableSettingsPreset.AMBIENT_SOUND_CONTROL, AssignableSettingsPreset.PLAYBACK_CONTROL),
            presets.presets,
        )

        // RET_STATUS = [0x06][count][CommonStatus...] with ENABLE=0x00/DISABLE=0x01.
        val status = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0xF3.toByte(), 0x06, 0x02, 0x00, 0x01),
        ) as ParsedTandemResponse.AssignableSettingsStatus
        assertEquals(listOf(true, false), status.enabled)
    }
}
