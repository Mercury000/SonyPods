package dev.sonypods.protocol

/**
 * The codec a sound-quality badge draws, generation-agnostic: V1 and V2 carry
 * the same byte codes except that V1 has no LC3 (SC `AudioCodec` table1/table2).
 * Unknown bytes map to null upstream — official hides the badge for them.
 */
enum class SoundQualityCodec(val code: Int) {
    UNSETTLED(0x00),
    SBC(0x01),
    AAC(0x02),
    LDAC(0x10),
    APT_X(0x20),
    APT_X_HD(0x21),
    LC3(0x30),
    OTHER(0xFF);

    companion object {
        fun fromCode(code: Int): SoundQualityCodec? = entries.firstOrNull { it.code == code }
    }
}

/** Which DSEE generation an upscaling effect report refers to. */
enum class DseeGeneration(val code: Int) {
    DSEE_HX(0x00),
    DSEE(0x01),
    DSEE_HX_AI(0x02),
    DSEE_ULTIMATE(0x03);

    companion object {
        fun fromCode(code: Int): DseeGeneration? = entries.firstOrNull { it.code == code }
    }
}

/** Whether DSEE is actively processing the stream right now (V1 == V2 codes). */
enum class DseeEffectState(val code: Int) {
    OFF(0x00),
    VALID(0x01),
    INVALID(0x02);

    companion object {
        fun fromCode(code: Int): DseeEffectState? = entries.firstOrNull { it.code == code }
    }
}

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
    MUSIC_VOLUME(0x20),
    CALL_VOLUME(0x21),
    MUSIC_VOLUME_WITH_MUTE(0x30),
    CALL_VOLUME_WITH_MUTE(0x31),
    PLAY_MODE(0x40),
}

enum class PlaybackNameStatus(val code: Int) {
    UNSETTLED(0),
    NOTHING(1),
    SETTLED(2);

    companion object {
        fun fromCode(code: Int): PlaybackNameStatus = entries.firstOrNull { it.code == code } ?: UNSETTLED
    }
}

/** V1 Table1 PLAY_GET/RET/NTFY_PARAM field-selector byte. */
enum class PlaybackDetailedDataType(val code: Byte) {
    TRACK_NAME(0x00),
    ALBUM_NAME(0x01),
    ARTIST_NAME(0x02),
    GENRE_NAME(0x03),
    PLAYER_NAME(0x10),
    VOLUME(0x20),
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
    ASSIGNABLE_SETTINGS(0x03),
    WEARING_STATUS_DETECTOR(0x06),
    QUICK_ACCESS(0x0D),
    /** ASSIGNABLE_SETTINGS variant whose capability payload carries a LE Audio limitation. */
    ASSIGNABLE_SETTINGS_WITH_LIMITATION(0x0E),
}

/** V2 Table1 ASSIGNABLE_SETTINGS key identifiers from Sound Connect. */
enum class AssignableSettingsKey(val code: Byte) {
    LEFT_SIDE(0x00),
    RIGHT_SIDE(0x01),
    CUSTOM(0x02),
    C(0x03),
    NC_AMB_KEY(0x04),
    NC_AMBIENT_KEY(0x05),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class AssignableSettingsType(val code: Byte) {
    TOUCH_SENSOR(0x00),
    BUTTON(0x01),
    FACE_TAP(0x02),
    OUT_OF_RANGE(0xFF.toByte()),
}

/** Presets are the physical control groups exposed by Sony's assignable-settings API. */
enum class AssignableSettingsPreset(val code: Byte) {
    AMBIENT_SOUND_CONTROL(0x00),
    VOLUME_CONTROL(0x10),
    PLAYBACK_CONTROL(0x20),
    TRACK_CONTROL(0x21),
    PLAYBACK_CONTROL_VOICE_ASSISTANT_LIMITATION(0x22),
    VOICE_RECOGNITION(0x30),
    GOOGLE_ASSIST(0x31),
    AMAZON_ALEXA(0x32),
    TENCENT_XIAOWEI(0x33),
    MS(0x34),
    AMBIENT_SOUND_CONTROL_QUICK_ACCESS(0x35),
    QUICK_ACCESS(0x36),
    TENCENT_XIAOWEI_Q_MSC(0x37),
    TEAMS(0x38),
    GOOGLE_ASSISTANT_BT_CLASSIC_ONLY(0x39),
    AMAZON_ALEXA_BT_CLASSIC_ONLY(0x40),
    TENCENT_XIAOWEI_BT_CLASSIC_ONLY(0x41),
    QUICK_ACCESS_BT_CLASSIC_ONLY(0x42),
    AMBIENT_SOUND_CONTROL_QUICK_ACCESS_BT_CLASSIC_ONLY(0x43),
    TENCENT_XIAOWEI_Q_MSC_BT_CLASSIC_ONLY(0x44),
    AMBIENT_SOUND_CONTROL_MIC(0x45),
    LISTENING_MODE_QUICK_ACCESS(0x46),
    AMBIENT_SOUND_CONTROL_LISTENING_MODE(0x47),
    CHAT_MIX(0x70),
    CUSTOM1(0x71),
    CUSTOM2(0x72),
    NO_FUNCTION(0xFF.toByte()),
    OUT_OF_RANGE(0xFE.toByte()),
}

enum class AssignableSettingsAction(val code: Byte) {
    SINGLE_TAP(0x00),
    DOUBLE_TAP(0x01),
    TRIPLE_TAP(0x02),
    REPEAT_TAP(0x03),
    SINGLE_TAP_AND_HOLD(0x10),
    DOUBLE_TAP_AND_HOLD(0x11),
    LONG_PRESS_THEN_ACTIVATE(0x21),
    LONG_PRESS_DURING_ACTIVATE(0x22),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class AssignableSettingsFunction(val code: Byte) {
    NO_FUNCTION(0x00),
    NC_ASM_OFF(0x01),
    NC_ASM(0x02),
    NC_OFF(0x03),
    ASM_OFF(0x04),
    QUICK_ATTENTION(0x10),
    NC_OPTIMIZER(0x11),
    PLAY_PAUSE(0x20),
    NEXT_TRACK(0x21),
    PREV_TRACK(0x22),
    VOLUME_UP(0x23),
    VOLUME_DOWN(0x24),
    VOICE_RECOGNITION(0x30),
    GET_YOUR_NOTIFICATION(0x31),
    TALK_TO_GOOGLE_ASSISTANT(0x32),
    STOP_GOOGLE_ASSISTANT(0x33),
    VOICE_INPUT_CANCEL(0x34),
    TALK_TO_TENCENT_XIAOWEI(0x35),
    CANCEL_VOICE_RECOGNITION(0x36),
    VOICE_INPUT_AMAZON_ALEXA(0x37),
    CANCEL_AMAZON_ALEXA(0x38),
    CANCEL_TENCENT_XIAOWEI(0x39),
    NEXT_TRACK_STOP_GEMINI_LIVE(0x3A),
    PREV_TRACK_STOP_GEMINI_LIVE(0x3B),
    LAUNCH_MLP(0x40),
    TALK_TO_YOUR_MLP(0x41),
    SPTF_ONE_TOUCH(0x42),
    QUICK_ACCESS1(0x43),
    QUICK_ACCESS2(0x44),
    TALK_TO_TENCENT_XIAOWEI_CANCEL(0x45),
    Q_MSC_ONE_TOUCH(0x46),
    TEAMS(0x47),
    TEAMS_VOICE_SKILLS(0x48),
    NC_NCSS_ASM_OFF(0x50),
    NC_NCSS_ASM(0x51),
    NC_NCSS_OFF(0x52),
    NCSS_ASM_OFF(0x53),
    NC_NCSS(0x54),
    NCSS_ASM(0x55),
    NCSS_OFF(0x56),
    AMB_SETTING(0x57),
    STANDARD_VOICE_SOUND(0x58),
    LISTENING_MODE(0x59),
    MIC_MUTE(0x70),
    GAME_UP(0x71),
    CHAT_UP(0x72),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class AssignableSettingsEnableDisable(val code: Byte) {
    ENABLE(0x00),
    DISABLE(0x01),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class QuickAccessKey(val code: Byte) {
    L_R_KEY(0x00),
    NC_AMB_KEY(0x01),
    FIXED_QUICK_ACCESS_KEY(0x02),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class QuickAccessFunction(val code: Byte) {
    NO_FUNCTION(0x00),
    /** The following IDs are the SAR/Quick Access service IDs, not gesture IDs. */
    SPTF(0x01),
    ENDEL(0x02),
    AMAZON_MUSIC(0x03),
    XIAO(0x04),
    XIMALAYA(0x05),
    KUGOU_MUSIC(0x06),
    Q_MSC_DIRECT(0x07),
    EYE_NAVI(0x08),
    NETEASE_CLOUD_MUSIC(0x09),
    APPLE_MUSIC(0x0A),
    YOUTUBE_MUSIC(0x0C),
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

/**
 * Noise Detection Sensitivity for the noise-adaptive (Auto Ambient Sound)
 * feature carried by [NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA].
 * Wire order is STANDARD/HIGH/LOW — not monotonic, so UI ordering must map explicitly.
 */
enum class NoiseAdaptiveSensitivity(val code: Byte) {
    STANDARD(0x00),
    HIGH(0x01),
    LOW(0x02);

    companion object {
        fun fromCode(code: Byte): NoiseAdaptiveSensitivity? = entries.firstOrNull { it.code == code }
    }
}

enum class NoiseControlMode {
    OFF,
    NOISE_CANCELLING,
    AMBIENT_SOUND,
}

/**
 * Modes that Sony can combine into the function assigned to an ambient-sound
 * gesture.  NCSS is the device-specific speech/noise-suppression variant that
 * only appears in the capability table of newer models.
 */
enum class GestureNoiseControlMode {
    NOISE_CANCELLING,
    NOISE_CANCELLING_SPEECH,
    AMBIENT_SOUND,
    OFF,
}

enum class PlaybackStatus {
    UNKNOWN,
    PLAYING,
    PAUSED,
    STOPPED,
}
