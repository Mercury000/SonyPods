package dev.sonypods.protocol

enum class DeviceInfoType(val code: Byte) {
    MODEL_NAME(0x01),
    FW_VERSION(0x02),
    SERIES_AND_COLOR_INFO(0x03),
    INSTRUCTION_GUIDE(0x04),
}

enum class CommonInquiredType(val code: Byte) {
    CONCIERGE(0x00),
    CONNECTION_STATUS(0x01),
    AUDIO_CODEC(0x02),
    UPSCALING_EFFECT(0x03),
    BLE_SETUP(0x04),
    CONNECTION_ESTABLISHED_TIME(0x05),
    DEVICE_SPECIAL_MODE(0x06),
    SMART_PHONE_AND_CONNECTED_DEVICE_INFORMATION_FOR_CLASSIC(0x07),
    TANDEM_RECONNECTION_REQUEST(0x08),
    DISPLAY_FW_VERSION(0x09),
}

enum class PowerInquiredType(val code: Byte) {
    BATTERY(0x00),
    LEFT_RIGHT_BATTERY(0x01),
    CRADLE_BATTERY(0x02),
    AUTO_POWER_OFF(0x04),
    POWER_SAVE_MODE(0x06),
    STAMINA(0x0E),
}

enum class EqEbbInquiredType(val code: Byte) {
    PRESET_EQ(0x00),
    EBB(0x01),
    PRESET_EQ_NONCUSTOMIZABLE(0x02),
    PRESET_EQ_AND_ULT_MODE(0x03),
    PRESET_EQ_AND_ERRORCODE(0x04),
    SOUND_EFFECT(0x30),
    CUSTOM_EQ(0x31),
    TURN_KEY_EQ(0x32),
    CUSTOMIZABLE_SOUND_EFFECT_SELECT(0x33),
    CUSTOMIZABLE_SOUND_EFFECT_SELECT_RESET(0x34),
    CUSTOMIZABLE_SOUND_EFFECT_SELECT_CUSTOM(0x35),
    CUSTOMIZABLE_SOUND_EFFECT_SELECT_EXTERNAL_UPDATE(0x36),
    ULT_BTN_SOUND_EFFECT_ASSIGN(0x40),
}

enum class EqBandInformationType(val code: Byte) {
    NO_INFORMATION(0x00),
    HZ(0x01),
    KHZ(0x02),
    SPECIFIC_INFORMATION(0x10),
}

enum class EqPresetId(val code: Byte, val displayName: String) {
    OFF(0x00, "Off"),
    ROCK(0x01, "Rock"),
    POP(0x02, "Pop"),
    JAZZ(0x03, "Jazz"),
    DANCE(0x04, "Dance"),
    EDM(0x05, "EDM"),
    R_AND_B_HIP_HOP(0x06, "R&B / Hip-Hop"),
    ACOUSTIC(0x07, "Acoustic"),
    BRIGHT(0x10, "Bright"),
    EXCITED(0x11, "Excited"),
    MELLOW(0x12, "Mellow"),
    RELAXED(0x13, "Relaxed"),
    VOCAL(0x14, "Vocal"),
    TREBLE(0x15, "Treble"),
    BASS(0x16, "Bass"),
    SPEECH(0x17, "Speech"),
    HEAVY(0x30, "Heavy"),
    CLEAR(0x31, "Clear"),
    HARD(0x32, "Hard"),
    SOFT(0x33, "Soft"),
    CUSTOM(0xA0.toByte(), "Custom"),
    USER_SETTING1(0xA1.toByte(), "User Setting 1"),
    USER_SETTING2(0xA2.toByte(), "User Setting 2"),
    UNSPECIFIED(0xFF.toByte(), "Unspecified"),
}

enum class NcAsmInquiredType(val code: Byte) {
    V1_TABLE_SET1_NC_ASM(0x02),
    NC_ON_OFF(0x01),
    NC_ON_OFF_AND_ASM_ON_OFF(0x02),
    NC_MODE_SWITCH_AND_ASM_ON_OFF(0x03),
    NC_ON_OFF_AND_ASM_SEAMLESS(0x13),
    NC_MODE_SWITCH_AND_ASM_SEAMLESS(0x14),
    MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS(0x15),
    MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS(0x16),
    MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS(0x17),
    MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS(0x18),
    MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA(0x19),
    ASM_ON_OFF(0x21),
    ASM_SEAMLESS(0x22),
    NC_AMB_TOGGLE(0x30),
    NC_TEST_MODE(0x40),
    ;

    companion object {
        /** Code 0x02 has different meanings in the V1 and V2 NC/ASM tables. */
        fun fromV1Table1Code(code: Byte): NcAsmInquiredType? =
            if (code == V1_TABLE_SET1_NC_ASM.code) V1_TABLE_SET1_NC_ASM
            else entries.firstOrNull { it != V1_TABLE_SET1_NC_ASM && it.code == code }

        fun fromV2Table1Code(code: Byte): NcAsmInquiredType? =
            entries.firstOrNull { it != V1_TABLE_SET1_NC_ASM && it.code == code }
    }
}

enum class PlaybackControl(val code: Byte) {
    PAUSE(0x01),
    TRACK_UP(0x02),
    TRACK_DOWN(0x03),
    STOP(0x06),
    PLAY(0x07),
}

enum class PlayInquiredType(val code: Byte) {
    PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT(0x01),
    PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT_AND_FUNCTION_CHANGE(0x02),
    PLAYBACK_CONTROL_WITH_FUNCTION_CHANGE(0x03),
    PLAY_MODE(0x40),
}

enum class LeaInquiredType(val code: Byte) {
    TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD(0x00),
    HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD(0x01),
    TWS_SUPPORTS_LEA_UNI_LEA_BROAD(0x02),
}

enum class LeaEnableDisable(val code: Byte) {
    ENABLE(0x00),
    DISABLE(0x01),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class LeaStreamingStatus(val code: Byte) {
    POWER_OFF(0x00),
    NONE(0x01),
    VIA_A2DP(0x02),
    VIA_LE_AUDIO_UNICAST(0x03),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class LeaPairedHistory(val code: Byte) {
    BOTH_CLASSIC_BT_BLE(0x00),
    ONLY_CLASSIC_BT(0x01),
    ONLY_BLE(0x02),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class SystemInquiredType(val code: Byte) {
    WEARING_STATUS_DETECTOR(0x06),
    QUICK_ACCESS(0x0D),
}

enum class QuickAccessKey(val code: Byte) {
    L_R_KEY(0x00),
    NC_AMB_KEY(0x01),
    FIXED_QUICK_ACCESS_KEY(0x02),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class QuickAccessFunction(val code: Byte) {
    NO_FUNCTION(0x00),
    NC_ASM_OFF(0x01),
    NC_ASM(0x02),
    NC_OFF(0x03),
    ASM_OFF(0x04),
    PLAY_PAUSE(0x20),
    NEXT_TRACK(0x21),
    PREV_TRACK(0x22),
    VOLUME_UP(0x23),
    VOLUME_DOWN(0x24),
    VOICE_RECOGNITION(0x30),
    QUICK_ACCESS1(0x43),
    QUICK_ACCESS2(0x44),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class WearingDetectionStatus(val code: Byte) {
    NOT_STARTED(0x00),
    STARTED(0x01),
    COMPLETED_SUCCESSFULLY(0x02),
    COMPLETED_UNSUCCESSFULLY(0x03),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class WearingDetectionResult(val code: Byte) {
    GOOD(0x00),
    POOR(0x01),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class AmbientSoundMode(val code: Byte) {
    NORMAL(0x00),
    VOICE(0x01),
}

enum class NoiseControlMode {
    OFF,
    NOISE_CANCELLING,
    AMBIENT_SOUND,
}

enum class PlaybackStatus {
    UNKNOWN,
    PLAYING,
    PAUSED,
    STOPPED,
}
