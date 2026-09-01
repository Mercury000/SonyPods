package dev.sonypods.protocol

/**
 * V2 (TABLE_SET_2) Tandem FunctionType byte codes, reverse-engineered from
 * Sound Connect 13.2.1 (`com.sony.songpal.tandemfamily.message.mdr.v2.FunctionType`,
 * dalvik enum restored from `$values()`). Each FunctionType carries a Table
 * (NO_1 = table1, NO_2 = table2) and a byte code. Sound Connect replies to
 * `CONNECT_GET_SUPPORT_FUNCTION` (0x06) with an ordered list of the
 * FunctionTypes this model supports; the engine maps each known type to its
 * per-domain GET_CAPABILITY probe.
 *
 * The table is the complete SC set so that any code a model reports is
 * recognised (mirrors SC's NO_USE handling: a recognised-but-unused code is
 * carried, only genuinely unknown codes fall to [OUT_OF_RANGE] and are
 * skipped). Code/table pairs are byte-for-byte SC 13.2.1, including values
 * represented by named BSON/OpenCV constants in the decompiled enum.
 */
enum class SonyV2FunctionType(val code: Byte, val table: SonyTable) {
    // ── table1: CONNECT / COMMON ──
    CONCIERGE_DATA(0x10, SonyTable.NO_1),
    CONNECTION_STATUS(0x11, SonyTable.NO_1),
    CODEC_INDICATOR(0x12, SonyTable.NO_1),
    UPSCALING_INDICATOR(0x13, SonyTable.NO_1),
    BLE_SETUP(0x14, SonyTable.NO_1),
    TUTORIAL_CONTENTS_SELECT_ON_CONCIERGE(0x15, SonyTable.NO_1),
    CONNECTION_ESTABLISHED_TIME(0x16, SonyTable.NO_1),
    UNNECESSARY_AUTO_RECONNECTION(0x17, SonyTable.NO_1),
    DEVICE_SPECIAL_MODE(0x18, SonyTable.NO_1),
    PHONE_AND_CONNECTED_DEVICE_INFOMATION_FOR_CLASSIC(0x19, SonyTable.NO_1),
    TANDEM_RECONNECTION_REQUEST(0x1A, SonyTable.NO_1),
    DISPLAY_FW_VERSION(0x1B, SonyTable.NO_1),

    // ── table1: POWER / battery ──
    BATTERY_LEVEL_INDICATOR(0x20, SonyTable.NO_1),
    LEFT_RIGHT_BATTERY_LEVEL_INDICATOR(0x21, SonyTable.NO_1),
    CRADLE_BATTERY_LEVEL_INDICATOR(0x22, SonyTable.NO_1),
    POWER_OFF(0x23, SonyTable.NO_1),
    AUTO_POWER_OFF(0x24, SonyTable.NO_1),
    AUTO_POWER_OFF_WITH_WEARING_DETECTION(0x25, SonyTable.NO_1),
    POWER_SAVING_MODE_ON_OFF(0x26, SonyTable.NO_1),
    TANDEM_KEEP_ALIVE(0x27, SonyTable.NO_1),
    // Threshold-based battery indicators, declared by newer TWS models instead of
    // (or alongside) the plain indicators (SC FunctionType.java: byte 40/41/42).
    BATTERY_LEVEL_WITH_THRESHOLD(0x28, SonyTable.NO_1),
    LR_BATTERY_LEVEL_WITH_THRESHOLD(0x29, SonyTable.NO_1),
    CRADLE_BATTERY_LEVEL_WITH_THRESHOLD(0x2A, SonyTable.NO_1),
    BATTERY_SAFE_MODE(0x2B, SonyTable.NO_1),
    CARING_CHARGE(0x2C, SonyTable.NO_1),
    BT_STANDBY(0x2D, SonyTable.NO_1),
    STAMINA(0x2E, SonyTable.NO_1),
    AUTOMATIC_TOUCH_PANEL_BACKLIGHT_TURN_OFF(0x2F, SonyTable.NO_1),

    // ── table1: FW update → arrives as UPDT_GET_PARAM ──
    FW_UPDATE_TANDEM(0x30, SonyTable.NO_1),
    FW_UPDATE_MTK_TRANSFER_WITHOUT_DISCONNECTION(0x32, SonyTable.NO_1),
    FW_UPDATE_MTK_TRANSFER_WITHOUT_DISCONNECTION_AUTO_UPDATE(0x34, SonyTable.NO_1),
    FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE(0x35, SonyTable.NO_1),
    FW_UPDATE_MTK_TRANSFER_WITH_AC_CONNECTION_CHECK(0x36, SonyTable.NO_1),
    FW_UPDATE_TANDEM_TRANSFER_USING_COMMON_TABLE(0x37, SonyTable.NO_1),
    FW_UPDATE_USING_MC_APP(0x38, SonyTable.NO_1),

    // ── table1: LEA ──
    TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD(0x40, SonyTable.NO_1),
    HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD(0x41, SonyTable.NO_1),
    CLASSIC_ONLY_LE_CLASSIC_SETTING(0x42, SonyTable.NO_1),
    TWS_SUPPORTS_LEA_UNI_LEA_BROAD(0x43, SonyTable.NO_1),
    CHANGE_TANDEM_CONNECTION_PROFILE_FOR_ANDROID(0x44, SonyTable.NO_1),
    BGM_MODE_CANT_BE_USED_WITH_LEA_CONNECTION(0x45, SonyTable.NO_1),
    HEAD_TRACKER_CANT_BE_USED_WITH_LEA_CONNECTION(0x46, SonyTable.NO_1),
    PAIRING_DEVICE_MANAGEMENT_CANT_BE_USED_WITH_LEA_CONNECTION(0x47, SonyTable.NO_1),
    SOUND_AR_CANT_BE_USED_WITH_LEA_CONNECTION(0x48, SonyTable.NO_1),
    AUTO_PLAY_CANT_BE_USED_WITH_LEA_CONNECTION(0x49, SonyTable.NO_1),
    GATT_CONNECTABLE_CANT_BE_USED_WITH_LEA_CONNECTION(0x4A, SonyTable.NO_1),
    SOUND_AR_OPTIMIZATION_CANT_BE_USED_WITH_LEA_CONNECTION(0x4B, SonyTable.NO_1),
    QUICK_ACCESS_CANT_BE_USED_WITH_LEA_CONNECTION(0x4C, SonyTable.NO_1),
    CONNECTION_MODE_CANT_BE_USED_WITH_LEA_CONNECTION(0x4D, SonyTable.NO_1),
    VOICE_ASSISTANT_SETTINGS_CANT_BE_USED_WITH_LEA_CONNECTION(0x4E, SonyTable.NO_1),
    VOICE_ASSISTANT_WAKE_WORD_CANT_BE_USED_WITH_LEA_CONNECTION(0x4F, SonyTable.NO_1),

    // ── table1: EQEBB ──
    PRESET_EQ(0x50, SonyTable.NO_1),
    EBB(0x51, SonyTable.NO_1),
    PRESET_EQ_NON_CUSTOMIZABLE(0x52, SonyTable.NO_1),
    PRESET_EQ_AND_ULT_MODE(0x53, SonyTable.NO_1),
    SOUND_EFFECT(0x54, SonyTable.NO_1),
    CUSTOM_EQ(0x55, SonyTable.NO_1),
    TURN_KEY_EQ(0x56, SonyTable.NO_1),
    PRESET_EQ_AND_ERRORCODE(0x57, SonyTable.NO_1),
    ULT_SOUND_EFFECT_ASSIGN(0x58, SonyTable.NO_1),
    CUSTOMIZABLE_SOUND_EFFECT(0x59, SonyTable.NO_1),

    // ── table1: NCASM ──
    NOISE_CANCELLING_ONOFF(0x61, SonyTable.NO_1),
    NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_ONOFF(0x62, SonyTable.NO_1),
    NOISE_CANCELLING_DUAL_SINGLE_OFF_AND_AMBIENT_SOUND_MODE_ONOFF(0x63, SonyTable.NO_1),
    NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT(0x64, SonyTable.NO_1),
    NOISE_CANCELLING_DUAL_SINGLE_OFF_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT(0x65, SonyTable.NO_1),
    AMBIENT_SOUND_MODE_ONOFF(0x66, SonyTable.NO_1),
    AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT(0x67, SonyTable.NO_1),
    MODE_NC_ASM_NOISE_CANCELLING_DUAL_AUTO_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT(0x68, SonyTable.NO_1),
    AMBIENT_SOUND_CONTROL_MODE_SELECT(0x69, SonyTable.NO_1),
    MODE_NC_ASM_NOISE_CANCELLING_DUAL_SINGLE_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT(0x6A, SonyTable.NO_1),
    MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT(0x6B, SonyTable.NO_1),
    MODE_NC_NCSS_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_WITH_TEST_MODE(0x6C, SonyTable.NO_1),
    MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_NOISE_ADAPTATION(0x6D, SonyTable.NO_1),
    AUTO_NCASM(0x70, SonyTable.NO_1),
    ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION(0x71, SonyTable.NO_1),
    HEART_RATE_SENSOR_SETTING(0x72, SonyTable.NO_1),
    HEART_RATE_PROFILE_SETTING(0x73, SonyTable.NO_1),
    HEART_RATE_SENSOR_TEST(0x74, SonyTable.NO_1),
    HEART_RATE_SENSOR_GREEN_LIGHT(0x75, SonyTable.NO_1),

    // ── table1: NS/OPT (NC optimizer) ──
    NC_OPTIMIZER_PERSONAL_BAROMETRIC(0x80.toByte(), SonyTable.NO_1),
    NC_OPTIMIZER_PERSONAL(0x81.toByte(), SonyTable.NO_1),
    NC_OPTIMIZER_BAROMETRIC(0x82.toByte(), SonyTable.NO_1),
    SOUND_FIELD_OPTIMIZATION(0x83.toByte(), SonyTable.NO_1),
    TV_SOUND_BOOSTER(0x84.toByte(), SonyTable.NO_1),

    // ── table1: alert / fixed-message notifiers ──
    FIXED_MESSAGE(0x90.toByte(), SonyTable.NO_1),
    VIBRATOR_ALERT_NOTIFICATION(0x91.toByte(), SonyTable.NO_1),
    FIXED_MESSAGE_WITH_LR_SELECTION(0x92.toByte(), SonyTable.NO_1),
    VOICE_ASSISTANT_ALERT_NOTIFICATION(0x93.toByte(), SonyTable.NO_1),
    LE_AUDIO_ALERT_NOTIFICATION(0x94.toByte(), SonyTable.NO_1),

    // ── table1: PLAY ──
    PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT(0xA1.toByte(), SonyTable.NO_1),
    PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT_AND_MUTE(0xA2.toByte(), SonyTable.NO_1),
    PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT_AND_FUNCTION_CHANGE(0xA3.toByte(), SonyTable.NO_1),
    PLAYBACK_CONTROLLER_WITH_FUNCTION_CHANGE(0xA4.toByte(), SonyTable.NO_1),

    // ── table1: SAR / auto-play / head-tracker ──
    SAR(0xB0.toByte(), SonyTable.NO_1),
    AUTO_PLAY(0xB1.toByte(), SonyTable.NO_1),
    GATT_CONNECTABLE(0xB2.toByte(), SonyTable.NO_1),
    SAR_OPTIMIZATION_COMPASS_ACCEL_TYPE(0xB3.toByte(), SonyTable.NO_1),
    HEAD_TRACKER_COMPASS_ACCEL_TYPE(0xB5.toByte(), SonyTable.NO_1),
    SAR_OPTIMIZATION_ACCEL_TYPE(0xB6.toByte(), SonyTable.NO_1),
    HEAD_TRACKER_ACCEL_TYPE(0xB7.toByte(), SonyTable.NO_1),
    INTEGRATED_AUTO_PLAY(0xB8.toByte(), SonyTable.NO_1),

    // ── table1: log / operation-history notifiers ──
    ACTION_LOG_NOTIFIER(0xC1.toByte(), SonyTable.NO_1),
    TIME_SERIES_OPERATIONLOG_NOTIFIER(0xC2.toByte(), SonyTable.NO_1),
    SOUND_DROPOUT_NOTIFIER(0xC3.toByte(), SonyTable.NO_1),

    // ── table1: general settings ──
    GENERAL_SETTING_1(0xD1.toByte(), SonyTable.NO_1),
    GENERAL_SETTING_2(0xD2.toByte(), SonyTable.NO_1),
    GENERAL_SETTING_3(0xD3.toByte(), SonyTable.NO_1),
    GENERAL_SETTING_4(0xD4.toByte(), SonyTable.NO_1),

    // ── table1: AUDIO ──
    CONNECTION_MODE_SOUND_QUALITY_CONNECTION_QUALITY(0xE1.toByte(), SonyTable.NO_1),
    UPSCALING_AUTO_OFF(0xE2.toByte(), SonyTable.NO_1),
    CONNECTION_MODE_SOUND_QUALITY_SOUND_WITH_LDAC_STATUS_QUALITY_CONNECTION_QUALITY(0xE3.toByte(), SonyTable.NO_1),
    BGM_MODE_SMALL_MIDDLE_LARGE(0xE4.toByte(), SonyTable.NO_1),
    UPMIX_CINEMA(0xE5.toByte(), SonyTable.NO_1),
    LISTENING_OPTION(0xE6.toByte(), SonyTable.NO_1),
    CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO(0xE7.toByte(), SonyTable.NO_1),
    VOICE_CONTENTS(0xE8.toByte(), SonyTable.NO_1),
    SOUND_LEAKAGE_REDUCTION(0xE9.toByte(), SonyTable.NO_1),
    LISTENING_OPTION_ASSIGN_CUSTOMIZABLE(0xEA.toByte(), SonyTable.NO_1),
    BGM_MODE_SMALL_MIDDLE_LARGE_AND_ERRORCODE(0xEB.toByte(), SonyTable.NO_1),
    UPMIX_SERIES(0xEC.toByte(), SonyTable.NO_1),
    UPSCALING_AUTO_OFF_WITH_STATUS_DISABLE_REASON(0xED.toByte(), SonyTable.NO_1),

    // ── table1: SYSTEM ──
    VIBRATOR_ON_OFF(0xF0.toByte(), SonyTable.NO_1),
    PLAYBACK_CONTROL_BY_WEARING_REMOVING_HEADPHONE_ON_OFF(0xF1.toByte(), SonyTable.NO_1),
    SMART_TALKING_MODE_TYPE1(0xF2.toByte(), SonyTable.NO_1),
    ASSIGNABLE_SETTING(0xF3.toByte(), SonyTable.NO_1),
    VOICE_ASSISTANT_SETTINGS(0xF4.toByte(), SonyTable.NO_1),
    VOICE_ASSISTANT_WAKE_WORD_ON_OFF(0xF5.toByte(), SonyTable.NO_1),
    WEARING_STATUS_DETECTOR(0xF6.toByte(), SonyTable.NO_1),
    EARPIECE_SELECTION(0xF7.toByte(), SonyTable.NO_1),
    CALL_SETTINGS(0xF8.toByte(), SonyTable.NO_1),
    RESET_SETTINGS(0xF9.toByte(), SonyTable.NO_1),
    AUTO_VOLUME(0xFA.toByte(), SonyTable.NO_1),
    FACE_TAP_TEST_MODE(0xFB.toByte(), SonyTable.NO_1),
    SMART_TALKING_MODE_TYPE2(0xFC.toByte(), SonyTable.NO_1),
    QUICK_ACCESS(0xFD.toByte(), SonyTable.NO_1),
    ASSIGNABLE_SETTING_WITH_LIMITATION(0xFE.toByte(), SonyTable.NO_1),

    // ── table2: POWER ──
    AUTO_STANDBY(0x20, SonyTable.NO_2),
    CHARGE_IN_USE(0x21, SonyTable.NO_2),
    CARING_CHARGE_WITH_THRESHOLD(0x22, SonyTable.NO_2),
    USB_SUBMERSION(0x23, SonyTable.NO_2),
    USB_OVERHEAT_DETECTION(0x24, SonyTable.NO_2),

    // ── table2: PERIPHERAL / pairing ──
    PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT(0x30, SonyTable.NO_2),
    SOURCE_SWITCH_CONTROL(0x31, SonyTable.NO_2),
    PAIRING_DEVICE_MANAGEMENT_WITH_BLUETOOTH_CLASS_OF_DEVICE(0x32, SonyTable.NO_2),
    PAIRING_DEVICE_MANAGEMENT_WITH_BLUETOOTH_CLASS_OF_DEVICE_CLASSIC_LE(0x33, SonyTable.NO_2),
    MUSIC_HAND_OVER_SETTING(0x34, SonyTable.NO_2),

    // ── table2: VOICE GUIDANCE (MTK-transfer variants) ──
    VOICE_GUIDANCE_SETTING_MTK_TRANSFER_WITHOUT_DISCONNECTION_NOT_SUPPORT_LANGUAGE_SWITCH(0x40, SonyTable.NO_2),
    VOICE_GUIDANCE_SETTING_MTK_TRANSFER_WITHOUT_DISCONNECTION_SUPPORT_LANGUAGE_SWITCH(0x41, SonyTable.NO_2),
    VOICE_GUIDANCE_SETTING_MTK_TRANSFER_WITHOUT_DISCONNECTION_SUPPORT_LANGUAGE_SWITCH_AND_VOLUME_ADJUSTMENT(0x42, SonyTable.NO_2),
    VOICE_GUIDANCE_VOLUME_SETTING_MTK_FIXED_TO_5_STEPS(0x43, SonyTable.NO_2),
    VOICE_GUIDANCE_SETTING_SUPPORT_LANGUAGE_SWITCH(0x44, SonyTable.NO_2),
    VOICE_GUIDANCE_SETTING_ONLY_ON_OFF_SWITCH(0x45, SonyTable.NO_2),
    VOICE_GUIDANCE_BATTERY_LEVEL_VOICE(0x46, SonyTable.NO_2),
    VOICE_GUIDANCE_POWER_ON_OFF_SOUND(0x47, SonyTable.NO_2),
    VOICE_GUIDANCE_SOUND_EFFECT_ULT_BEEP_ON_OFF(0x48, SonyTable.NO_2),

    // ── table2: SAFE_LISTENING ──
    SAFE_LISTENING_HBS_1(0x50, SonyTable.NO_2),
    SAFE_LISTENING_TWS_1(0x51, SonyTable.NO_2),
    SAFE_LISTENING_HBS_2(0x52, SonyTable.NO_2),
    SAFE_LISTENING_TWS_2(0x53, SonyTable.NO_2),
    SAFE_VOLUME_CONTROL(0x54, SonyTable.NO_2),
    MAX_VOLUME_LEVEL_LIMIT(0x55, SonyTable.NO_2),

    // ── table2: LEA ──
    LE_AUDIO_CONNECTION_STATE_NOTIFICATION(0x60, SonyTable.NO_2),
    LE_AUDIO_SWITCH_SUPPORTED_COMPATIBILITY(0x61, SonyTable.NO_2),
    LE_AUDIO_CONNECTION_MODE(0x62, SonyTable.NO_2),
    GET_IDENTITY_RESOLVING_KEY(0x63, SonyTable.NO_2),
    PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD(0x64, SonyTable.NO_2),

    // ── table2: DJ / karaoke / lighting ──
    LINK_AUTO_SWITCH_CANT_BE_USED_WITH_LEA_CONNECTION(0x6F, SonyTable.NO_2),
    DJ_CONTROL(0x70, SonyTable.NO_2),
    ILLUMINATION(0x71, SonyTable.NO_2),
    KARAOKE(0x72, SonyTable.NO_2),
    DJ_CONTROL_WITH_STATUS_DISABLE_REASON(0x73, SonyTable.NO_2),
    KARAOKE_WITH_STATUS_DISABLE_REASON(0x74, SonyTable.NO_2),
    LIVE_KARAOKE(0x75, SonyTable.NO_2),

    // ── table2: SYSTEM ──
    WEARING_STATUS_CHECKER(0xF0.toByte(), SonyTable.NO_2),
    REPEAT_TAP_TRAINING_MODE(0xF1.toByte(), SonyTable.NO_2),
    QUICK_ACCESS_EASY_SETTING(0xF2.toByte(), SonyTable.NO_2),
    AUTO_VOLUME_OPTIMIZER(0xF3.toByte(), SonyTable.NO_2),
    AUTO_VOLUME_WITH_LIMITATION(0xF4.toByte(), SonyTable.NO_2),
    SONY_VOICE_ASSISTANT(0xF5.toByte(), SonyTable.NO_2),
    WEARING_POSITION(0xF6.toByte(), SonyTable.NO_2),
    LINK_AUTO_SWITCH_FOR_SPEAKER(0xF7.toByte(), SonyTable.NO_2),
    LINK_AUTO_SWITCH_FOR_HEADSETS(0xF8.toByte(), SonyTable.NO_2),
    MIC_ON_OFF_BY_HEADPHONE_OPERATION(0xF9.toByte(), SonyTable.NO_2),
    FUNCTION_CHANGE(0xFA.toByte(), SonyTable.NO_2),
    USB_BROWSER(0xFB.toByte(), SonyTable.NO_2),
    LIGHTING_MODE(0xFC.toByte(), SonyTable.NO_2),
    VOICE_ASSISTANT_WITH_SPECIFIC_SETUP_LINK_SUPPORT(0xFD.toByte(), SonyTable.NO_2),
    LIGHTING_DEFAULT_COLOR(0xFE.toByte(), SonyTable.NO_2),
    WEARING_POSITION_WITHOUT_FITTING_SUPPORTER(0xFF.toByte(), SonyTable.NO_2),

    // A legal Table1 function despite sharing 0xFF with sentinels in other table contexts.
    HEAD_GESTURE_ON_OFF_TRAINING(0xFF.toByte(), SonyTable.NO_1),
    OUT_OF_RANGE(0xFF.toByte(), SonyTable.INVALID),
    ;

    companion object {
        fun fromByteCode(table: SonyTable, byteCode: Byte): SonyV2FunctionType =
            entries.firstOrNull { it.table == table && it.code == byteCode } ?: OUT_OF_RANGE

        /**
         * The FunctionTypes SC's `FunctionCantBeUsedWithLEAConnectionType` maps to
         * (one per entry, `getFunctionTypeTableSet2()`). Declaring one of these in
         * the support-function list is what gives a feature the "unusable while LE
         * Audio carries the audio" concept at all — SC's `mo58685x1()` is a plain
         * membership test against the declared list.
         *
         * `BGM_MODE_SMALL_MIDDLE_LARGE_AND_ERRORCODE` looks out of place but is
         * SC's own mapping for `BGM_MODE_ERROR_CODE_IN_LISTENING_OPTION`.
         * Codes are unique across the set, so a code alone identifies the entry.
         */
        val LEA_RESTRICTION_TYPES: Set<SonyV2FunctionType> = setOf(
            BGM_MODE_CANT_BE_USED_WITH_LEA_CONNECTION,
            HEAD_TRACKER_CANT_BE_USED_WITH_LEA_CONNECTION,
            PAIRING_DEVICE_MANAGEMENT_CANT_BE_USED_WITH_LEA_CONNECTION,
            SOUND_AR_CANT_BE_USED_WITH_LEA_CONNECTION,
            GATT_CONNECTABLE_CANT_BE_USED_WITH_LEA_CONNECTION,
            SOUND_AR_OPTIMIZATION_CANT_BE_USED_WITH_LEA_CONNECTION,
            QUICK_ACCESS_CANT_BE_USED_WITH_LEA_CONNECTION,
            CONNECTION_MODE_CANT_BE_USED_WITH_LEA_CONNECTION,
            VOICE_ASSISTANT_SETTINGS_CANT_BE_USED_WITH_LEA_CONNECTION,
            VOICE_ASSISTANT_WAKE_WORD_CANT_BE_USED_WITH_LEA_CONNECTION,
            BGM_MODE_SMALL_MIDDLE_LARGE_AND_ERRORCODE,
            LINK_AUTO_SWITCH_CANT_BE_USED_WITH_LEA_CONNECTION,
        )

        fun leaRestrictionFromCode(code: Int): SonyV2FunctionType? =
            LEA_RESTRICTION_TYPES.firstOrNull { (it.code.toInt() and 0xFF) == (code and 0xFF) }
    }
}

/**
 * V1 (TABLE_SET_1) Tandem FunctionType byte codes, from
 * `com.sony.songpal.tandemfamily.message.mdr.v1.table1.param.FunctionType`.
 * V1's RET_SUPPORT_FUNCTION carries a flat single-byte FunctionType list (no
 * order field). Codes differ from V2 for EQ (V1 PRESET_EQ=0x51 vs V2 0x50)
 * and its NC/ASM types are a squarer subset (NOISE_CANCELLING / NC_AND_ASM /
 * AMBIENT_SOUND_MODE).
 */
enum class SonyV1FunctionType(val code: Byte) {
    BATTERY_LEVEL(0x11),
    UPSCALING_INDICATOR(0x12),
    CODEC_INDICATOR(0x13),
    BLE_SETUP(0x14),
    LEFT_RIGHT_BATTERY_LEVEL(0x15),
    LEFT_RIGHT_CONNECTION_STATUS(0x17),
    CRADLE_BATTERY_LEVEL(0x18),
    POWER_OFF(0x21),
    CONCIERGE_DATA(0x22),
    TANDEM_KEEP_ALIVE(0x23),
    FW_UPDATE(0x30),
    PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT(0x38),
    VOICE_GUIDANCE(0x39),
    VPT(0x41),
    SOUND_POSITION(0x42),
    PRESET_EQ(0x51),
    EBB(0x52),
    PRESET_EQ_NONCUSTOMIZABLE(0x53),
    NOISE_CANCELLING(0x61),
    NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE(0x62),
    AMBIENT_SOUND_MODE(0x63),
    AUTO_NC_ASM(0x71),
    NC_OPTIMIZER(0x81.toByte()),
    VIBRATOR_ALERT_NOTIFICATION(0x92.toByte()),
    PLAYBACK_CONTROLLER(0xA1.toByte()),
    TRAINING_MODE(0xB1.toByte()),
    ACTION_LOG_NOTIFIER(0xC1.toByte()),
    GENERAL_SETTING1(0xD1.toByte()),
    GENERAL_SETTING2(0xD2.toByte()),
    GENERAL_SETTING3(0xD3.toByte()),
    CONNECTION_MODE(0xE1.toByte()),
    UPSCALING(0xE2.toByte()),
    VIBRATOR(0xF1.toByte()),
    POWER_SAVING_MODE(0xF2.toByte()),
    CONTROL_BY_WEARING(0xF3.toByte()),
    AUTO_POWER_OFF(0xF4.toByte()),
    SMART_TALKING_MODE(0xF5.toByte()),
    ASSIGNABLE_SETTINGS(0xF6.toByte()),
    OUT_OF_RANGE(0xFF.toByte()),
    ;

    companion object {
        fun fromByteCode(byteCode: Byte): SonyV1FunctionType =
            entries.firstOrNull { it.code == byteCode } ?: OUT_OF_RANGE
    }
}

enum class SonyTable {
    NO_1,
    NO_2,
    INVALID,
}

/**
 * A single entry from RET_SUPPORT_FUNCTION. V2 provides an explicit [order]
 * (used by SC to sequence the capability probes); V1 has no order field and
 * uses list position instead.
 */
data class SonySupportedFunction(
    val code: Byte,
    val order: Int,
    val table: SonyTable = SonyTable.INVALID,
)
