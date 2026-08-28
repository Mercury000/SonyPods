package dev.sonypods.protocol

import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR

object SonyTandemV2Table1Protocol {
    private const val CONNECT_GET_PROTOCOL_INFO: Byte = 0x00
    private const val CONNECT_RET_PROTOCOL_INFO: Byte = 0x01
    private const val CONNECT_GET_SUPPORT_FUNCTION: Byte = 0x06
    private const val CONNECT_RET_SUPPORT_FUNCTION: Byte = 0x07
    private const val CONNECT_GET_CAPABILITY_INFO: Byte = 0x02
    private const val CONNECT_RET_CAPABILITY_INFO: Byte = 0x03
    // SC V2 InquiredType FIXED_VALUE = 0x00 (gate in `ff0.C16467a`:
    // bArr.length==2 && bArr[0]==0x02 && bArr[1]==FIXED_VALUE). 0x03 is the RET
    // command byte, NOT the fixed value.
    private const val CONNECT_GET_CAPABILITY_INFO_FIXED_VALUE: Byte = 0x00
    private const val CONNECT_RET_DEVICE_INFO: Byte = 0x05
    private const val CONNECT_GET_DEVICE_INFO: Byte = 0x04
    private const val COMMON_GET_STATUS: Byte = 0x12
    private const val COMMON_RET_STATUS: Byte = 0x13
    private const val COMMON_NTFY_STATUS: Byte = 0x15
    private const val POWER_GET_STATUS: Byte = 0x22
    private const val POWER_RET_STATUS: Byte = 0x23
    private const val POWER_NTFY_STATUS: Byte = 0x25
    private const val POWER_SET_STATUS: Byte = 0x24
    private const val POWER_OFF: Byte = 0x03
    private const val POWER_OFF_USER_REQUEST: Byte = 0x01
    private const val SYSTEM_GET_PARAM: Byte = 0xF6.toByte()
    private const val SYSTEM_RET_PARAM: Byte = 0xF7.toByte()
    private const val SYSTEM_SET_PARAM: Byte = 0xF8.toByte()
    private const val SYSTEM_NTFY_PARAM: Byte = 0xF9.toByte()
    private const val SYSTEM_GET_EXT_PARAM: Byte = 0xFA.toByte()
    private const val SYSTEM_RET_EXT_PARAM: Byte = 0xFB.toByte()
    private const val SYSTEM_SET_EXT_PARAM: Byte = 0xFC.toByte()
    private const val SYSTEM_NTFY_EXT_PARAM: Byte = 0xFD.toByte()
    private const val SYSTEM_GET_CAPABILITY: Byte = 0xF0.toByte()
    private const val SYSTEM_RET_CAPABILITY: Byte = 0xF1.toByte()
    private const val SYSTEM_GET_STATUS: Byte = 0xF2.toByte()
    private const val SYSTEM_RET_STATUS: Byte = 0xF3.toByte()
    private const val SYSTEM_NTFY_STATUS: Byte = 0xF5.toByte()
    // ── General Setting (GS) domain, SC `table1/generalsetting` (if0/*). The
    // multipoint ("同时连接2台设备") toggle lives here, NOT in SOURCE_SWITCH_CONTROL.
    // GsInquiredType slots: GENERAL_SETTING1..4 = 0xD1..0xD4 (byte codes from
    // `GsInquiredType`); each slot's title is resolved via GET_CAPABILITY and the
    // official app matches `GsTitleTitle.MULTIPOINT_SETTING` ("MULTIPOINT_SETTING").
    private const val GS_GET_CAPABILITY: Byte = 0xD0.toByte()
    private const val GS_RET_CAPABILITY: Byte = 0xD1.toByte()
    private const val GS_GET_STATUS: Byte = 0xD2.toByte()
    private const val GS_RET_STATUS: Byte = 0xD3.toByte()
    private const val GS_NTFY_STATUS: Byte = 0xD5.toByte()
    private const val GS_GET_PARAM: Byte = 0xD6.toByte()
    private const val GS_RET_PARAM: Byte = 0xD7.toByte()
    private const val GS_SET_PARAM: Byte = 0xD8.toByte()
    private const val GS_NTFY_PARAM: Byte = 0xD9.toByte()
    // GsSettingType: BOOLEAN_TYPE=0x00, LIST_TYPE=0x01.
    private const val GS_SETTING_TYPE_BOOLEAN: Byte = 0x00
    // GsSettingValue: ON=0x00, OFF=0x01.
    private const val GS_VALUE_ON: Byte = 0x00
    private const val GS_VALUE_OFF: Byte = 0x01
    // EnableDisable: ENABLE=0x00, DISABLE=0x01.
    private const val GS_ENABLE: Byte = 0x00
    // GsStringFormat: RAW_NAME=0x00, ENUM_NAME=0x01.
    const val GS_STRING_FORMAT_ENUM_NAME: Byte = 0x01
    // DisplayLanguage.ENGLISH (SC `le0/b` locale mapping table).
    private const val GS_DISPLAY_LANGUAGE_ENGLISH: Byte = 0x01
    // The official app's slot-title match (`GsTitleTitle.MULTIPOINT_SETTING`).
    const val GS_TITLE_MULTIPOINT_SETTING = "MULTIPOINT_SETTING"
    // ── Alert (bf0/*): device-driven FIXED_MESSAGE confirmation loop. When a
    // multipoint toggle needs a disconnect/reconnect, the device replies with
    // ALERT_NTFY_PARAM (0x99, FIXED_MESSAGE=0x00, AlertMessageType, AlertActionType)
    // and only executes the change after the app answers ALERT_SET_PARAM (0x98,
    // FIXED_MESSAGE, same AlertMessageType, AlertAction POSITIVE/NEGATIVE).
    private const val ALERT_NTFY_PARAM: Byte = 0x99.toByte()
    private const val ALERT_SET_PARAM: Byte = 0x98.toByte()
    private const val ALERT_SET_STATUS: Byte = 0x94.toByte()
    private const val ALERT_GET_STATUS: Byte = 0x92.toByte()
    private const val ALERT_RET_STATUS: Byte = 0x93.toByte()
    private const val ALERT_NTFY_STATUS: Byte = 0x95.toByte()
    private const val ALERT_INQUIRED_TYPE_FIXED_MESSAGE: Byte = 0x00
    // Official bf0.C5695s uses AlertInquiredType.APP_BECOMES_FOREGROUND=0x04.
    private const val ALERT_INQUIRED_TYPE_APP_BECOMES_FOREGROUND: Byte = 0x04
    private const val ALERT_INQUIRED_TYPE_LE_AUDIO: Byte = 0x05
    private const val ALERT_INQUIRED_TYPE_FIXED_MESSAGE_WITH_LEFT_RIGHT: Byte = 0x02
    private const val ALERT_INQUIRED_TYPE_FLEXIBLE_MESSAGE: Byte = 0x06
    // AlertEnable/Disable (SC `bf0.AbstractC5678b` EnableDisable): ENABLE=0x00, DISABLE=0x01.
    private const val ALERT_ENABLE: Byte = 0x00
    private const val ALERT_DISABLE: Byte = 0x01
    // AlertMessageType (SC `com.sony.songpal.tandemfamily.message.mdr.v2.table1.alert.param.AlertMessageType`).
    // SC `C14663l0` switch (case 71-76) maps these to dialog → POSITIVE reply (0x98).
    const val ALERT_MESSAGE_TYPE_MULTIPOINT_LDAC_DISABLE = 6
    const val ALERT_MESSAGE_TYPE_MULTIPOINT = 7
    // 2-devices connection alerts (SC case 73-76): all surface a dialog and reply
    // ALERT_SET_PARAM POSITIVE on user confirmation.
    const val ALERT_MESSAGE_TYPE_ENABLING_2_DEVICES_WITH_LDAC = 112
    const val ALERT_MESSAGE_TYPE_QUALITY_PRIOR_WITH_2_DEVICES = 113
    const val ALERT_MESSAGE_TYPE_CONNECTED_2_DEVICES_BG_WITH_LDAC = 114
    const val ALERT_MESSAGE_TYPE_LDAC_990_WITH_2_DEVICES = 115
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_ONLY_FROM_LE_AUDIO = 44
    const val ALERT_MESSAGE_TYPE_CHANGE_LE_AUDIO_AND_CLASSIC_FROM_LE_AUDIO = 45
    const val ALERT_MESSAGE_TYPE_ENTER_CLASSIC_PAIRING_FROM_LE_AUDIO = 46
    const val ALERT_MESSAGE_TYPE_ENTER_PAIRING_WITH_LE_AUDIO_LIMITATIONS = 47
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO = 48
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_LIMITATIONS = 49
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA = 52
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA_WAKE_WORD = 53
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_QUICK_ACCESS = 54
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA_AND_QUICK_ACCESS = 55
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_PDM = 56
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA_AND_PDM = 57
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_QUICK_ACCESS_AND_PDM = 64
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_VA_QUICK_ACCESS_AND_PDM = 65
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_FROM_LE_AUDIO = 116
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_WITH_LIMITATIONS_FROM_LE_AUDIO = 117
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_SOUND_QUALITY_PRIOR_FROM_LE_AUDIO = 118
    const val ALERT_MESSAGE_TYPE_CHANGE_CLASSIC_AUDIO_CONNECTION_QUALITY_PRIOR_FROM_LE_AUDIO = 119

    /** Fixed/foreground AlertMessageType values belonging to the LE Audio flow. */
    val LE_AUDIO_ALERT_MESSAGE_TYPES: Set<Int> = setOf(
        44, 45, 46, 47, 48, 49, 52, 53, 54, 55, 56, 57, 64, 65, 116, 117, 118, 119,
    )

    const val FLEXIBLE_ENTER_PAIRING_WITH_LE_AUDIO_LIMITATION = 12
    const val FLEXIBLE_CHANGE_CONNECTION_WITH_LE_AUDIO_LIMITATION = 13
    const val FLEXIBLE_CHANGE_STANDBY_TO_CLASSIC_ONLY = 14
    const val FLEXIBLE_CHANGE_STANDBY_TO_LE_AUDIO_CLASSIC = 15
    const val FLEXIBLE_ENTER_PAIRING_WITH_CONNECTION_MODE = 16
    const val LE_AUDIO_FLEXIBLE_MESSAGE_TYPE_TO_LE = 17

    /** Flexible types which can appear in the LE Audio connection flow. */
    val LE_AUDIO_FLEXIBLE_MESSAGE_TYPES: Set<Int> = setOf(
        FLEXIBLE_ENTER_PAIRING_WITH_LE_AUDIO_LIMITATION,
        FLEXIBLE_CHANGE_CONNECTION_WITH_LE_AUDIO_LIMITATION,
        FLEXIBLE_CHANGE_STANDBY_TO_CLASSIC_ONLY,
        FLEXIBLE_CHANGE_STANDBY_TO_LE_AUDIO_CLASSIC,
        FLEXIBLE_ENTER_PAIRING_WITH_CONNECTION_MODE,
        LE_AUDIO_FLEXIBLE_MESSAGE_TYPE_TO_LE,
    )
    // AlertActionType / AlertAction: NEGATIVE=0, POSITIVE=1.
    private const val ALERT_ACTION_NEGATIVE: Byte = 0x00
    private const val ALERT_ACTION_POSITIVE: Byte = 0x01
    private const val LEA_GET_STATUS: Byte = 0x42
    private const val LEA_RET_STATUS: Byte = 0x43
    private const val LEA_NTFY_STATUS: Byte = 0x45
    private const val LEA_GET_PARAM: Byte = 0x46
    private const val LEA_RET_PARAM: Byte = 0x47
    private const val LEA_SET_PARAM: Byte = 0x48
    private const val LEA_NTFY_PARAM: Byte = 0x49
    // LeaSetParam / ClassicOnlyLeClassicSetting (Sound Connect V2 Table1):
    // [0x0C][EnableDisable][ConnectionMethodChange].
    private const val LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING: Byte = 0x0C
    private const val LEA_ENABLE: Byte = 0x00
    private const val LEA_DISABLE: Byte = 0x01
    private const val LEA_SETTING_AND_CONNECTION_METHOD_CHANGE: Byte = 0x00
    private const val LEA_SETTING_CHANGE_ONLY: Byte = 0x01
    private const val EQEBB_GET_STATUS: Byte = 0x52
    private const val EQEBB_RET_STATUS: Byte = 0x53
    private const val EQEBB_NTFY_STATUS: Byte = 0x55
    private const val EQEBB_GET_PARAM: Byte = 0x56
    private const val EQEBB_RET_PARAM: Byte = 0x57
    private const val EQEBB_SET_PARAM: Byte = 0x58
    private const val EQEBB_NTFY_PARAM: Byte = 0x59
    private const val EQEBB_GET_EXTENDED_INFO: Byte = 0x5A
    private const val EQEBB_RET_EXTENDED_INFO: Byte = 0x5B
    private const val EQEBB_GET_CAPABILITY: Byte = 0x50
    private const val EQEBB_RET_CAPABILITY: Byte = 0x51
    private const val NCASM_GET_CAPABILITY: Byte = 0x60
    private const val NCASM_RET_CAPABILITY: Byte = 0x61
    private const val NCASM_GET_STATUS: Byte = 0x62
    private const val NCASM_RET_STATUS: Byte = 0x63
    private const val NCASM_NTFY_STATUS: Byte = 0x65
    private const val NCASM_GET_PARAM: Byte = 0x66
    private const val NCASM_RET_PARAM: Byte = 0x67
    private const val NCASM_SET_PARAM: Byte = 0x68
    private const val NCASM_NTFY_PARAM: Byte = 0x69
    private const val PLAY_GET_STATUS: Byte = 0xA2.toByte()
    private const val PLAY_RET_STATUS: Byte = 0xA3.toByte()
    private const val PLAY_SET_STATUS: Byte = 0xA4.toByte()
    private const val PLAY_NTFY_STATUS: Byte = 0xA5.toByte()
    private const val PLAY_GET_PARAM: Byte = 0xA6.toByte()
    private const val PLAY_RET_PARAM: Byte = 0xA7.toByte()
    private const val PLAY_SET_PARAM: Byte = 0xA8.toByte()
    private const val PLAY_NTFY_PARAM: Byte = 0xA9.toByte()
    private const val PLAY_GET_CAPABILITY: Byte = 0xA0.toByte()
    private const val PLAY_RET_CAPABILITY: Byte = 0xA1.toByte()
    // ── AUDIO domain (Sound Connect `cf0` package): DSEE / upscaling lives here ──
    // Payload after dataType is [Command][AudioInquiredType][value…]; the upscaling
    // GET is exactly 3 bytes, SET/RET/NTFY carry one UpscalingTypeAutoOff value byte.
    private const val AUDIO_GET_CAPABILITY: Byte = 0xE0.toByte()
    private const val AUDIO_RET_CAPABILITY: Byte = 0xE1.toByte()
    private const val AUDIO_GET_PARAM: Byte = 0xE6.toByte()
    private const val AUDIO_RET_PARAM: Byte = 0xE7.toByte()
    private const val AUDIO_SET_PARAM: Byte = 0xE8.toByte()
    private const val AUDIO_NTFY_PARAM: Byte = 0xE9.toByte()
    /** AudioInquiredType.UPSCALING — the first-generation DSEE HX / DSEE toggle. */
    private const val AUDIO_INQ_UPSCALING: Byte = 0x01
    /** AudioInquiredType.UPSCALING_AUTO_OFF_WITH_STATUS_DISABLE_REASON — the newer
     * generation that also covers DSEE Ultimate. `BSON.REGEX` (0x0B) in SC's enum. */
    private const val AUDIO_INQ_UPSCALING_WITH_REASON: Byte = 0x0B
    /** UpscalingTypeAutoOff: OFF=0, AUTO=1 — the switch's two states. */
    private const val UPSCALING_OFF: Byte = 0x00
    private const val UPSCALING_AUTO: Byte = 0x01
    /** UpscalingType (AUDIO_RET_CAPABILITY body): the DSEE generation the headset
     * reports — the value Sound Connect's `UpsclType` title/description picks from
     * (`cf0.e0`): DSEE_HX=0, DSEE=1, DSEE_HX_AI("Extreme")=2, DSEE_ULTIMATE=3. */
    private val UPSCALING_TYPES: List<Byte> = listOf(0x00, 0x01, 0x02, 0x03)
    private const val VALUE_ENABLE: Byte = 0x00
    private const val VALUE_CHANGED: Byte = 0x01

    // ── AUDIO domain status sub-commands (SC `cf0.q0/s0/t0/m` family) ──
    // Availability rides GET/RET/NTFY_STATUS with [inq][EnableDisable]; it gates
    // whether the connection-quality options are currently usable.
    private const val AUDIO_GET_STATUS: Byte = 0xE2.toByte()
    private const val AUDIO_RET_STATUS: Byte = 0xE3.toByte()
    private const val AUDIO_NTFY_STATUS: Byte = 0xE5.toByte()

    // ── AudioInquiredType entries carrying the Bluetooth 连接质量 setting ──
    // CONNECTION_MODE=0x00 (classic), _WITH_LDAC_STATUS=0x02 (LDAC models),
    // CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO=0x05 (the LE-era dual-mode variant).
    private const val AUDIO_INQ_CONNECTION_MODE: Byte = 0x00
    private const val AUDIO_INQ_CONNECTION_MODE_WITH_LDAC_STATUS: Byte = 0x02
    private const val AUDIO_INQ_CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO: Byte = 0x05

    /** EnableDisable for the STATUS frames: ENABLE=0, DISABLE=1. */
    private const val ENABLE_DISABLE_ENABLE: Byte = 0x00

    // ── NCASM V2 enums (Sound Connect 13.2.1, `ncasm/param` package) ──
    // NcAsmOnOffValue: OFF=0x00, ON=0x01 (NOT the inverted generic V2
    // `OnOffSettingValue` convention — SC uses NcAsmOnOffValue everywhere in the
    // NCASM SET/RET frames, and its own NcAsmSendStatus maps OFF/ON onto it).
    private const val NCASM_ON: Byte = 0x01
    private const val NCASM_OFF: Byte = 0x00
    // NcAsmMode / NcNcssAsmMode: NC=0x00, ASM=0x01 (NcSS=0x02).
    private const val NCASM_MODE_NC: Byte = 0x00
    private const val NCASM_MODE_ASM: Byte = 0x01
    // NcValue: OFF=0, ON_SINGLE=1, ON_DUAL=2, AUTO=3, AUTO_SINGLE=4, AUTO_DUAL=5.
    private const val NC_VALUE_OFF: Byte = 0x00
    private const val NC_VALUE_ON_SINGLE: Byte = 0x01
    private const val NC_VALUE_ON_DUAL: Byte = 0x02
    private const val NC_VALUE_AUTO: Byte = 0x03
    private const val NC_VALUE_AUTO_SINGLE: Byte = 0x04
    private const val NC_VALUE_AUTO_DUAL: Byte = 0x05

    // ── NC_AMB_TOGGLE Function byte codes (rf0/j, `system/param/Function`) ──
    // Mapping is semantic: NC_ASM_OFF turns everything off, NC_OFF turns noise
    // cancelling off (leaving ambient), ASM_OFF turns ambient off (leaving NC).
    private const val TOGGLE_FUNCTION_NC_ASM_OFF: Byte = 0x01
    private const val TOGGLE_FUNCTION_NC_OFF: Byte = 0x03
    private const val TOGGLE_FUNCTION_ASM_OFF: Byte = 0x04

    // EqUltModeStatus: OFF=0x00 (SC `eqebb/param/EqUltModeStatus`); the engine
    // never sets ULT, so the PRESET_EQ_AND_ULT_MODE body always carries OFF.
    private const val EQ_ULT_MODE_OFF: Byte = 0x00

    fun buildGetProtocolInfo(): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_PROTOCOL_INFO, byteArrayOf(0x00))

    fun buildGetCapabilityInfo(): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_CAPABILITY_INFO, byteArrayOf(CONNECT_GET_CAPABILITY_INFO_FIXED_VALUE))

    fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_DEVICE_INFO, byteArrayOf(type.code))

    /**
     * CONNECT_GET_SUPPORT_FUNCTION (0x06). Payload is a single
     * `ConnectInquiredType.FIXED_VALUE` byte (0x00), identical in V1 and V2
     * (SC `ff0.C16470d.b.m69414f()` / `qe0.C26538e0`). The response
     * (RET_SUPPORT_FUNCTION 0x07) carries the authoritative per-model
     * (FunctionType, order) list that drives dynamic capability probing.
     */
    fun buildGetSupportFunction(): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_SUPPORT_FUNCTION, byteArrayOf(0x00))

    /** AUDIO_GET_PARAM for one of the upscaling inquired types (0x01 / 0x0B). */
    fun buildGetUpscaling(inquiredTypeCode: Byte): ByteArray =
        SonyTandemFrame.message(AUDIO_GET_PARAM, byteArrayOf(inquiredTypeCode))

    /** AUDIO_GET_PARAM for one of the connection-quality inquired types (0x00/02/05). */
    fun buildGetConnectionQuality(inquiredTypeCode: Byte): ByteArray =
        SonyTandemFrame.message(AUDIO_GET_PARAM, byteArrayOf(inquiredTypeCode))

    /** AUDIO_GET_STATUS: the EnableDisable availability for a connection-quality type. */
    fun buildGetConnectionQualityAvailability(inquiredTypeCode: Byte): ByteArray =
        SonyTandemFrame.message(AUDIO_GET_STATUS, byteArrayOf(inquiredTypeCode))

    /**
     * AUDIO_SET_PARAM switching 声音质量优先 / 稳定连接优先 — [mode] maps onto
     * PriorMode exactly as SC's `r60.b.mo98226b` does (LOW_LATENCY included so
     * the wire format stays complete even though the UI never offers it).
     */
    fun buildSetConnectionQuality(
        inquiredTypeCode: Byte,
        mode: ConnectionQualityMode,
    ): ByteArray = SonyTandemFrame.message(AUDIO_SET_PARAM, byteArrayOf(inquiredTypeCode, mode.code))

    /**
     * AUDIO_GET_CAPABILITY (0xE0) for one of the upscaling inquired types. Its
     * RET carries the DSEE generation byte (`cf0.e0`) that decides whether the
     * row reads DSEE / DSEE HX / DSEE Extreme / DSEE Ultimate.
     */
    fun buildGetUpscalingCapability(inquiredTypeCode: Byte): ByteArray =
        SonyTandemFrame.message(AUDIO_GET_CAPABILITY, byteArrayOf(inquiredTypeCode))

    /**
     * AUDIO_SET_PARAM toggling DSEE / DSEE Extreme: [inquiredTypeCode] selects the
     * generation the device advertised, [on] maps onto UpscalingTypeAutoOff
     * AUTO/OFF exactly as Sound Connect's UpsclValue does.
     */
    fun buildSetUpscaling(inquiredTypeCode: Byte, on: Boolean): ByteArray =
        SonyTandemFrame.message(
            AUDIO_SET_PARAM,
            byteArrayOf(inquiredTypeCode, if (on) UPSCALING_AUTO else UPSCALING_OFF),
        )

    /**
     * Parse a V2 CONNECT_RET_SUPPORT_FUNCTION payload (0x07).
     *
     * Wire layout (SC `ff0.C16478l`): message body after dataType is
     *   [0]=0x07 command, [1]=0x00 FIXED_VALUE, [2]=count,
     *   [3..]=(FunctionType.byteCode, order) 2-byte pairs, length == count*2+3.
     * Engine payload (dataType+command stripped) therefore is
     *   [0]=0x00 FIXED_VALUE, [1]=count, [2+2i]=code, [3+2i]=order.
     * Returns the list ordered by the `order` field (SC sorts via
     * `ze0.C32196c.m115754g` before consuming). Unknown FunctionTypes are
     * skipped, mirroring SC's NO_USE handling.
     */
    fun parseSupportFunction(payload: ByteArray): List<SonySupportedFunction> =
        if (payload.size < 2) {
            emptyList()
        } else {
            val count = payload.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
            buildList {
                for (i in 0 until count) {
                    val code = payload.getOrNull(2 + i * 2) ?: break
                    val order = payload.getOrNull(3 + i * 2)?.toInt()?.and(0xFF) ?: break
                    val resolved = SonyV2FunctionType.fromByteCode(SonyTable.NO_1, code)
                    if (resolved != SonyV2FunctionType.OUT_OF_RANGE) {
                        add(SonySupportedFunction(code, order, SonyTable.NO_1))
                    }
                }
            }.sortedBy { it.order }
        }

    fun buildGetDisplayFirmwareVersion(): ByteArray =
        SonyTandemFrame.message(COMMON_GET_STATUS, byteArrayOf(CommonInquiredType.DISPLAY_FW_VERSION.code))

    /** COMMON_GET_STATUS for AUDIO_CODEC — the sound-quality codec badge source
     * (SC `ef0.b.m68094g(AUDIO_CODEC)`; body `[cmd 0x12][inqType 0x02]`). */
    fun buildGetAudioCodecStatus(): ByteArray =
        SonyTandemFrame.message(COMMON_GET_STATUS, byteArrayOf(CommonInquiredType.AUDIO_CODEC.code))

    /** COMMON_GET_STATUS for UPSCALING_EFFECT — the live DSEE badge source
     * (SC `ef0.b.m68094g(UPSCALING_EFFECT)`; body `[cmd 0x12][inqType 0x03]`). */
    fun buildGetUpscalingEffectStatus(): ByteArray =
        SonyTandemFrame.message(COMMON_GET_STATUS, byteArrayOf(CommonInquiredType.UPSCALING_EFFECT.code))

    fun buildGetBatteryStatus(type: PowerInquiredType): ByteArray =
        SonyTandemFrame.message(POWER_GET_STATUS, byteArrayOf(type.code))

    /** Sound Connect V2 Table1 USER_POWER_OFF: 0E 24 03 01. */
    fun buildPowerOff(): ByteArray =
        SonyTandemFrame.message(
            POWER_SET_STATUS,
            byteArrayOf(POWER_OFF, POWER_OFF_USER_REQUEST),
        )

    // ── Capability-probe GET_CAPABILITY builders (SC `pf0.C25895a` NCASM 0x60,
    //    `gf0.C16901b` EQEBB 0x50, `tf0.C28926a` PLAY 0xA0) ────────────────

    fun buildGetNcAsmCapability(type: NcAsmInquiredType): ByteArray =
        SonyTandemFrame.message(NCASM_GET_CAPABILITY, byteArrayOf(type.code))

    fun buildGetNcAsmCapability(typeCode: Byte): ByteArray =
        SonyTandemFrame.message(NCASM_GET_CAPABILITY, byteArrayOf(typeCode))

    /** EQEBB GET_CAPABILITY carries the InquiredType plus a DisplayLanguage byte
     * (SC `gf0.C16901b`). DisplayLanguage is a module-internal constant; the
     * engine passes it through for payload-shape parity. */
    fun buildGetEqEbbCapability(type: EqEbbInquiredType): ByteArray =
        SonyTandemFrame.message(EQEBB_GET_CAPABILITY, byteArrayOf(type.code, 0x00))

    fun buildGetEqEbbCapability(typeCode: Byte): ByteArray =
        SonyTandemFrame.message(EQEBB_GET_CAPABILITY, byteArrayOf(typeCode, 0x00))

    fun buildGetPlayCapability(type: PlayInquiredType): ByteArray =
        SonyTandemFrame.message(PLAY_GET_CAPABILITY, byteArrayOf(type.code))

    fun buildGetPlayCapability(typeCode: Byte): ByteArray =
        SonyTandemFrame.message(PLAY_GET_CAPABILITY, byteArrayOf(typeCode))

    fun buildGetEqEbbStatus(type: EqEbbInquiredType): ByteArray =
        buildGetEqEbbStatus(type.code)

    fun buildGetEqEbbStatus(typeCode: Byte): ByteArray =
        SonyTandemFrame.message(EQEBB_GET_STATUS, byteArrayOf(typeCode))

    fun buildGetEqEbbParam(type: EqEbbInquiredType): ByteArray =
        buildGetEqEbbParam(type.code)

    fun buildGetEqEbbParam(typeCode: Byte): ByteArray =
        SonyTandemFrame.message(EQEBB_GET_PARAM, byteArrayOf(typeCode))

    fun buildGetEqEbbExtendedInfo(type: EqEbbInquiredType): ByteArray =
        SonyTandemFrame.message(EQEBB_GET_EXTENDED_INFO, byteArrayOf(type.code))

    fun buildSetEqPreset(
        preset: EqPresetId,
        type: EqEbbInquiredType = EqEbbInquiredType.PRESET_EQ,
        bandSteps: List<Int> = emptyList(),
        basePreset: EqPresetId? = null,
    ): ByteArray =
        buildSetEqPreset(preset, type.code, bandSteps, basePreset)

    fun buildSetEqPreset(
        preset: EqPresetId,
        typeCode: Byte,
        bandSteps: List<Int> = emptyList(),
        basePreset: EqPresetId? = null,
    ): ByteArray {
        val bands = bandSteps.map { it.coerceIn(0, 255).toByte() }.toByteArray()
        // EQEBB SET_PARAM body (SC `hf0/c` PRESET_EQ, `hf0/d`
        // PRESET_EQ_AND_ULT_MODE): standard presets are
        //   [preset][bandCount][bandSteps...]
        // while the ULT variant inserts the EqUltModeStatus byte:
        //   [preset][ultModeStatus][bandCount][bandSteps...]
        val body = when (typeCode) {
            EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE.code -> {
                // Official C17560d pairs the *current* EqPresetId with the
                // EqUltModeStatus; selecting an ULT mode must keep the base
                // preset instead of forcing OFF.
                val (base, ultMode) = when (preset) {
                    EqPresetId.ULT, EqPresetId.ULT_1 -> (basePreset ?: EqPresetId.OFF) to 0x01.toByte()
                    EqPresetId.ULT_2 -> (basePreset ?: EqPresetId.OFF) to 0x02.toByte()
                    else -> preset to 0x00.toByte()
                }
                byteArrayOf(typeCode, base.code, ultMode, bandSteps.size.toByte()) + bands
            }
            EqEbbInquiredType.SOUND_EFFECT.code,
            EqEbbInquiredType.CUSTOMIZABLE_SOUND_EFFECT_SELECT.code -> {
                // SC `gf0.z0`/`gf0.v0` build exactly [type][SoundEffectType] and
                // validate the whole frame at length 3 — no trailing byte.
                byteArrayOf(typeCode, soundEffectByteFor(preset))
            }
            else -> {
                require(!preset.isSoundEffectSpace) {
                    "$preset is a sound-effect/ULT marker with no EqPresetId wire code"
                }
                byteArrayOf(typeCode, preset.code, bandSteps.size.toByte()) + bands
            }
        }
        return SonyTandemFrame.message(EQEBB_SET_PARAM, body)
    }

    /** Maps the app-level sound-effect vocabulary onto SC `SoundEffectType`
     * bytes (0x00-0x06) — a different namespace from EqPresetId codes. */
    private fun soundEffectByteFor(preset: EqPresetId): Byte = when (preset) {
        EqPresetId.ULT -> 0x01.toByte()
        EqPresetId.ULT_1 -> 0x02.toByte()
        EqPresetId.ULT_2 -> 0x03.toByte()
        EqPresetId.CUSTOM,
        EqPresetId.USER_SETTING1,
        EqPresetId.USER_SETTING2,
        EqPresetId.USER_SETTING3,
        EqPresetId.USER_SETTING4,
        EqPresetId.USER_SETTING5 -> 0x04.toByte()
        EqPresetId.FLAT -> 0x05.toByte()
        EqPresetId.LIVE_SOUND -> 0x06.toByte()
        else -> 0x00.toByte()
    }

    fun buildSetClearBass(level: Int): ByteArray =
        buildSetClearBass(level, EqEbbInquiredType.EBB.code)

    fun buildSetClearBass(level: Int, ebbTypeCode: Byte): ByteArray =
        SonyTandemFrame.message(
            EQEBB_SET_PARAM,
            byteArrayOf(ebbTypeCode, level.coerceIn(-127, 127).toByte()),
        )

    fun buildGetNcAsmStatus(type: NcAsmInquiredType): ByteArray =
        SonyTandemFrame.message(NCASM_GET_STATUS, byteArrayOf(type.code))

    fun buildGetNcAsmParam(type: NcAsmInquiredType): ByteArray =
        SonyTandemFrame.message(NCASM_GET_PARAM, byteArrayOf(type.code))

    /**
     * SET_PARAM for the mode the caller actually wants, dispatched per
     * NcAsmInquiredType so every frame is byte-exact with Sound Connect 13.2.1.
     * SC builds each type with a dedicated writer (`rf0/{d,e,f,g,h,i,j,k,l,m,n,o,p}`
     * for ASM_ON_OFF, ASM_SEAMLESS, MODE_NC_ASM_DUAL, MODE_NC_ASM_DUAL_NA,
     * MODE_NC_ASM_AUTO, MODE_NC_ASM_DUAL_SINGLE, NC_AMB_TOGGLE, NC_MODE_SWITCH_
     * AND_ASM_ON_OFF, NC_MODE_SWITCH_AND_ASM_SEAMLESS, NC_ON_OFF,
     * NC_ON_OFF_AND_ASM_ON_OFF, NC_ON_OFF_AND_ASM_SEAMLESS). The shared base
     * writes `[ValueChangeStatus][NcAsmOnOffValue totalEffect]` before the type's
     * own params; NC_AMB_TOGGLE is the single exception (3-byte frame, no status).
     */
    fun buildSetNoiseControlMode(
        controlMode: NoiseControlMode,
        ambientLevel: Int = 10,
        ambientMode: AmbientSoundMode = AmbientSoundMode.NORMAL,
        type: NcAsmInquiredType = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        noiseAdaptive: Boolean = false,
        noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.STANDARD,
        windNoiseReduction: Boolean = false,
    ): ByteArray {
        val effect = if (controlMode == NoiseControlMode.OFF) NCASM_OFF else NCASM_ON
        val mode = if (controlMode == NoiseControlMode.AMBIENT_SOUND) NCASM_MODE_ASM else NCASM_MODE_NC
        val level = ambientLevel.coerceIn(1, 20).toByte()
        val ncOn = if (controlMode == NoiseControlMode.NOISE_CANCELLING) NCASM_ON else NCASM_OFF
        val ncValue = if (controlMode == NoiseControlMode.NOISE_CANCELLING) {
            if (windNoiseReduction) NC_VALUE_ON_SINGLE else NC_VALUE_ON_DUAL
        } else {
            NC_VALUE_OFF
        }
        val autoNcValue = if (controlMode == NoiseControlMode.NOISE_CANCELLING) {
            if (windNoiseReduction) NC_VALUE_AUTO else NC_VALUE_ON_DUAL
        } else {
            NC_VALUE_ON_DUAL
        }
        val asmOn = if (controlMode == NoiseControlMode.AMBIENT_SOUND) NCASM_ON else NCASM_OFF

        if (type == NcAsmInquiredType.NC_AMB_TOGGLE) {
            // rf0/j: [function] only — no ValueChangeStatus / totalEffect.
            val function = when (controlMode) {
                NoiseControlMode.OFF -> TOGGLE_FUNCTION_NC_ASM_OFF
                NoiseControlMode.NOISE_CANCELLING -> TOGGLE_FUNCTION_ASM_OFF
                NoiseControlMode.AMBIENT_SOUND -> TOGGLE_FUNCTION_NC_OFF
            }
            return SonyTandemFrame.message(NCASM_SET_PARAM, byteArrayOf(type.code, function))
        }

        val body = when (type) {
            // rf0/n: [NcAsmOnOffValue]
            NcAsmInquiredType.NC_ON_OFF -> byteArrayOf(ncOn)
            // rf0/o: [NcAsmOnOffValue][AmbientSoundMode][NcAsmOnOffValue]
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_ON_OFF -> byteArrayOf(ncOn, ambientMode.code, asmOn)
            // rf0/k: [NcValue][AmbientSoundMode][NcAsmOnOffValue]
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_ON_OFF -> byteArrayOf(ncValue, ambientMode.code, asmOn)
            // rf0/p: [NcAsmOnOffValue][AmbientSoundMode][level]
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS -> byteArrayOf(ncOn, ambientMode.code, level)
            // rf0/l: [NcValue][AmbientSoundMode][level]
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> byteArrayOf(ncValue, ambientMode.code, level)
            // rf0/i: [NcAsmMode][NcValue][AmbientSoundMode][level].
            // NcValue is ON_DUAL for every mode on the AUTO device (btsnoop
            // `68 15 01 01 01 02 01 10` ambient / `68 15 01 01 00 02 01 10` NC /
            // `68 15 01 00 00 02 01 0a` off — WF-1000XM4); when wind noise reduction
            // is enabled, NcValue is AUTO (0x03) in NC mode.
            NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                byteArrayOf(mode, autoNcValue, ambientMode.code, level)
            // rf0/h: [NcAsmMode][NcValue][AmbientSoundMode][level]
            NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                byteArrayOf(mode, ncValue, ambientMode.code, level)
            // rf0/f: [NcAsmMode][AmbientSoundMode][level]
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                byteArrayOf(mode, ambientMode.code, level)
            // rf0/m: [NcNcssAsmMode][AmbientSoundMode][level]
            NcAsmInquiredType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                byteArrayOf(mode, ambientMode.code, level)
            // rf0/g: [NcAsmMode][AmbientSoundMode][level][NcAsmOnOffValue][NoiseAdaptiveSensitivity].
            // The trailing pair is the noise-adaptive (Auto Ambient Sound) toggle
            // and its sensitivity; the firmware owns the ambient level while the
            // toggle is ON and reports adjustments via NCASM_NTFY_PARAM.
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA ->
                byteArrayOf(
                    mode,
                    ambientMode.code,
                    level,
                    if (noiseAdaptive) NCASM_ON else NCASM_OFF,
                    noiseAdaptiveSensitivity.code,
                )
            // rf0/d: [AmbientSoundMode][NcAsmOnOffValue]
            NcAsmInquiredType.ASM_ON_OFF -> byteArrayOf(ambientMode.code, asmOn)
            // rf0/e: [AmbientSoundMode][level]
            NcAsmInquiredType.ASM_SEAMLESS -> byteArrayOf(ambientMode.code, level)
            NcAsmInquiredType.V1_TABLE_SET1_NC_ASM,
            NcAsmInquiredType.NC_TEST_MODE -> throw IllegalArgumentException(
                "NCASM type $type has no V2 SET_PARAM layout"
            )
        }
        return SonyTandemFrame.message(
            NCASM_SET_PARAM,
            byteArrayOf(type.code, VALUE_CHANGED, effect) + body,
        )
    }

    fun buildSetNcModeSwitchAndAmbientLevel(
        controlMode: NoiseControlMode,
        ambientLevel: Int = 10,
        ambientMode: AmbientSoundMode = AmbientSoundMode.NORMAL,
        type: NcAsmInquiredType = NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        noiseAdaptive: Boolean = false,
        noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.STANDARD,
        windNoiseReduction: Boolean = false,
    ): ByteArray =
        buildSetNoiseControlMode(
            controlMode, ambientLevel, ambientMode, type, noiseAdaptive, noiseAdaptiveSensitivity, windNoiseReduction,
        )

    fun buildGetPlaybackStatus(
        type: PlayInquiredType = PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT,
    ): ByteArray =
        SonyTandemFrame.message(PLAY_GET_STATUS, byteArrayOf(type.code))

    fun buildGetLeaStatus(type: LeaInquiredType): ByteArray =
        SonyTandemFrame.message(LEA_GET_STATUS, byteArrayOf(type.code))

    fun buildGetLeaPairedHistory(type: LeaInquiredType): ByteArray =
        SonyTandemFrame.message(LEA_GET_PARAM, byteArrayOf(type.code))

    /** Sound Connect C15454a initialization for the persistent LE Audio setting. */
    fun buildGetLeAudioSettingAvailability(): ByteArray =
        SonyTandemFrame.message(LEA_GET_STATUS, byteArrayOf(LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING))

    fun buildGetLeAudioSetting(): ByteArray =
        SonyTandemFrame.message(LEA_GET_PARAM, byteArrayOf(LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING))

    /**
     * Sets the Sony LE Audio capability. With [changeConnectionMethod] enabled,
     * the headset immediately changes its Bluetooth connection method, which can
     * briefly disconnect and reconnect the active audio link.
     */
    fun buildSetLeAudioEnabled(
        enabled: Boolean,
        changeConnectionMethod: Boolean = true,
    ): ByteArray =
        SonyTandemFrame.message(
            LEA_SET_PARAM,
            byteArrayOf(
                LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING,
                if (enabled) LEA_ENABLE else LEA_DISABLE,
                if (changeConnectionMethod) {
                    LEA_SETTING_AND_CONNECTION_METHOD_CHANGE
                } else {
                    LEA_SETTING_CHANGE_ONLY
                },
            ),
        )

    fun buildGetQuickAccess(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_PARAM, byteArrayOf(SystemInquiredType.QUICK_ACCESS.code))

    fun buildGetQuickAccessCapability(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_CAPABILITY, byteArrayOf(SystemInquiredType.QUICK_ACCESS.code))

    fun buildGetQuickAccessStatus(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_STATUS, byteArrayOf(SystemInquiredType.QUICK_ACCESS.code))

    /** SYSTEM_SET_PARAM for QUICK_ACCESS: [systemType][count][function...]. */
    fun buildSetQuickAccess(functions: List<QuickAccessFunction>): ByteArray {
        return buildSetQuickAccessCodes(functions.map { it.code.unsigned })
    }

    /**
     * Raw-ID variant of [buildSetQuickAccess].  Quick Access services are
     * supplied by Sound Connect/SAR and newer services are not necessarily
     * present in this module's enum.  Do not discard those IDs while writing.
     */
    fun buildSetQuickAccessCodes(functionCodes: List<Int>): ByteArray {
        require(functionCodes.isNotEmpty() && functionCodes.size <= 255) {
            "Quick Access function list is invalid"
        }
        require(functionCodes.all { it in 0..255 }) {
            "Quick Access function ID is invalid"
        }
        return SonyTandemFrame.message(
            SYSTEM_SET_PARAM,
            byteArrayOf(SystemInquiredType.QUICK_ACCESS.code, functionCodes.size.toByte()) +
                functionCodes.map { it.toByte() }.toByteArray(),
        )
    }

    /**
     * ASSIGNABLE_SETTINGS is the complete Sony touch/button gesture API. The
     * three reads are deliberately kept separate: capability describes the
     * legal actions/functions, SET_PARAM state describes the selected preset
     * for each key, and EXT_PARAM contains the actual action mappings.
     */
    fun buildGetAssignableSettingsCapability(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_CAPABILITY, byteArrayOf(SystemInquiredType.ASSIGNABLE_SETTINGS.code))

    fun buildGetAssignableSettingsCapability(type: SystemInquiredType): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_CAPABILITY, byteArrayOf(type.code))

    fun buildGetSystemCapability(type: SystemInquiredType): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_CAPABILITY, byteArrayOf(type.code))

    fun buildGetAssignableSettingsStatus(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_STATUS, byteArrayOf(SystemInquiredType.ASSIGNABLE_SETTINGS.code))

    fun buildGetAssignableSettingsStatus(type: SystemInquiredType): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_STATUS, byteArrayOf(type.code))

    fun buildGetAssignableSettingsPresets(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_PARAM, byteArrayOf(SystemInquiredType.ASSIGNABLE_SETTINGS.code))

    fun buildGetAssignableSettingsPresets(type: SystemInquiredType): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_PARAM, byteArrayOf(type.code))

    fun buildGetAssignableSettingsExtendedParam(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_EXT_PARAM, byteArrayOf(SystemInquiredType.ASSIGNABLE_SETTINGS.code))

    fun buildGetAssignableSettingsExtendedParam(type: SystemInquiredType): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_EXT_PARAM, byteArrayOf(type.code))

    fun buildSetAssignableSettingsPresets(
        presets: List<AssignableSettingsPreset>,
    ): ByteArray {
        return buildSetAssignableSettingsPresets(SystemInquiredType.ASSIGNABLE_SETTINGS, presets)
    }

    fun buildSetAssignableSettingsPresets(
        type: SystemInquiredType,
        presets: List<AssignableSettingsPreset>,
    ): ByteArray {
        require(presets.isNotEmpty() && presets.size <= 255) { "Assignable preset list is invalid" }
        require(presets.none { it == AssignableSettingsPreset.OUT_OF_RANGE }) { "Assignable preset is invalid" }
        return SonyTandemFrame.message(
            SYSTEM_SET_PARAM,
            byteArrayOf(type.code, presets.size.toByte()) +
                presets.map { it.code }.toByteArray(),
        )
    }

    fun buildSetAssignableSettingsExtendedParam(
        mappings: List<AssignableSettingsMapping>,
    ): ByteArray {
        return buildSetAssignableSettingsExtendedParam(SystemInquiredType.ASSIGNABLE_SETTINGS, mappings)
    }

    fun buildSetAssignableSettingsExtendedParam(
        type: SystemInquiredType,
        mappings: List<AssignableSettingsMapping>,
    ): ByteArray {
        require(mappings.isNotEmpty() && mappings.size <= 255) { "Assignable mapping list is invalid" }
        val body = buildList<Byte> {
            add(type.code)
            add(mappings.size.toByte())
            mappings.forEach { mapping ->
                require(mapping.preset != AssignableSettingsPreset.OUT_OF_RANGE)
                require(mapping.mappings.isNotEmpty() && mapping.mappings.size <= 255)
                add(mapping.preset.code)
                add(mapping.mappings.size.toByte())
                mapping.mappings.forEach { pair ->
                    require(pair.action != AssignableSettingsAction.OUT_OF_RANGE)
                    require(pair.function != AssignableSettingsFunction.OUT_OF_RANGE)
                    add(pair.action.code)
                    add(pair.function.code)
                }
            }
        }.toByteArray()
        return SonyTandemFrame.message(SYSTEM_SET_EXT_PARAM, body)
    }

    fun buildGetSpeakToChatStatus(type: SystemInquiredType = SystemInquiredType.SMART_TALKING_MODE_TYPE1): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_STATUS, byteArrayOf(type.code))

    fun buildGetSpeakToChatParam(type: SystemInquiredType = SystemInquiredType.SMART_TALKING_MODE_TYPE1): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_PARAM, byteArrayOf(type.code))

    fun buildGetSpeakToChatExtParam(type: SystemInquiredType = SystemInquiredType.SMART_TALKING_MODE_TYPE1): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_EXT_PARAM, byteArrayOf(type.code))

    fun buildGetSpeakToChatCapability(type: SystemInquiredType = SystemInquiredType.SMART_TALKING_MODE_TYPE1): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_CAPABILITY, byteArrayOf(type.code))

    fun buildSetSpeakToChatEnabled(
        enabled: Boolean,
        type: SystemInquiredType = SystemInquiredType.SMART_TALKING_MODE_TYPE1,
    ): ByteArray {
        val onOff = if (enabled) 0x00.toByte() else 0x01.toByte()
        return SonyTandemFrame.message(
            SYSTEM_SET_PARAM,
            byteArrayOf(type.code, onOff, 0x01.toByte()),
        )
    }

    fun buildSetSpeakToChatExtParam(
        sensitivity: SmartTalkingDetectionSensitivity,
        modeOutTime: SmartTalkingModeOutTime,
        voiceFocus: Boolean = false,
        type: SystemInquiredType = SystemInquiredType.SMART_TALKING_MODE_TYPE1,
    ): ByteArray {
        val body = if (type == SystemInquiredType.SMART_TALKING_MODE_TYPE2) {
            byteArrayOf(type.code, sensitivity.code, modeOutTime.code)
        } else {
            val focusByte = if (voiceFocus) 0x00.toByte() else 0x01.toByte()
            byteArrayOf(type.code, sensitivity.code, focusByte, modeOutTime.code)
        }
        return SonyTandemFrame.message(SYSTEM_SET_EXT_PARAM, body)
    }

    fun buildGetWearingStatus(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_PARAM, byteArrayOf(SystemInquiredType.WEARING_STATUS_DETECTOR.code))

    // ── General Setting (GS) builders ────────────────────────────────────────
    // Layouts verified against SC 13.2.1 `if0/{a,b,h,i,j,o,p}` + `jf0/a`:
    // GET_CAPABILITY (0xD0) body = [type][DisplayLanguage];
    // RET_CAPABILITY (0xD1) body = [type][settingType][stringFormat][titleLen][title..][descLen][desc..];
    // GET_STATUS (0xD2) body = [type]; RET_STATUS (0xD3) = [type][EnableDisable];
    // GET_PARAM (0xD6) body = [type]; RET_PARAM (0xD7) = [type][settingType][value];
    // SET_PARAM (0xD8) body = [type][settingType][value] (boolean: ON=0x00/OFF=0x01).

    fun buildGetGeneralSettingCapability(type: Byte): ByteArray =
        SonyTandemFrame.message(GS_GET_CAPABILITY, byteArrayOf(type, GS_DISPLAY_LANGUAGE_ENGLISH))

    fun buildGetGeneralSettingStatus(type: Byte): ByteArray =
        SonyTandemFrame.message(GS_GET_STATUS, byteArrayOf(type))

    fun buildGetGeneralSettingParam(type: Byte): ByteArray =
        SonyTandemFrame.message(GS_GET_PARAM, byteArrayOf(type))

    fun buildSetGeneralSetting(type: Byte, on: Boolean): ByteArray =
        SonyTandemFrame.message(
            GS_SET_PARAM,
            byteArrayOf(type, GS_SETTING_TYPE_BOOLEAN, if (on) GS_VALUE_ON else GS_VALUE_OFF),
        )

    /** 0xD1-0xD4 GENERAL_SETTING1..4 slot codes (SC `GsInquiredType`). */
    fun generalSettingSlots(): List<Byte> = (0xD1..0xD4).map { it.toByte() }

    /** Reply to a device FIXED_MESSAGE alert: [0x98, 0x00(FIXED_MESSAGE), msgType, action].
     * POSITIVE lets the device execute the requested change (e.g. multipoint reconnection),
     * NEGATIVE cancels it. Same channel as GS. */
    fun buildReplyAlertFixingMessage(messageType: Int, positive: Boolean): ByteArray =
        SonyTandemFrame.message(
            ALERT_SET_PARAM,
            byteArrayOf(
                ALERT_INQUIRED_TYPE_FIXED_MESSAGE,
                messageType.toByte(),
                if (positive) ALERT_ACTION_POSITIVE else ALERT_ACTION_NEGATIVE,
            ),
        )

    /** ALERT_SET_STATUS (0x94, SC `bf0.AbstractC5694r`): arm the device alert domain.
     * The device only pushes ALERT_NTFY_PARAM (0x99) confirmations (e.g. multipoint
     * reconnect) after the app has sent ENABLE — FIXED_MESSAGE on connect
     * (SC `C15100d0.m65206b` → `i00.C17828a.mo70105d`) and APP_BECOMES_FOREGROUND
     * when the remote UI is shown (SC `MdrRemoteBaseActivity.onRemoteShown` → `mo70103b`). */
    fun buildSetAlertStatus(inquiredType: Byte, enable: Boolean): ByteArray =
        SonyTandemFrame.message(
            ALERT_SET_STATUS,
            byteArrayOf(inquiredType, if (enable) ALERT_ENABLE else ALERT_DISABLE),
        )

    /** 0x94 [0x02][0x00]: arm the device alert domain for UI-shown notifications. */
    fun buildSetAlertAppBecomesForeground(enable: Boolean): ByteArray =
        buildSetAlertStatus(ALERT_INQUIRED_TYPE_APP_BECOMES_FOREGROUND, enable)

    /** 0x94 [0x00][0x00]: arm the device alert domain for fixed-message confirmations. */
    fun buildSetAlertFixedMessage(enable: Boolean): ByteArray =
        buildSetAlertStatus(ALERT_INQUIRED_TYPE_FIXED_MESSAGE, enable)

    fun buildGetAlertStatus(): ByteArray =
        SonyTandemFrame.message(ALERT_GET_STATUS, byteArrayOf(ALERT_INQUIRED_TYPE_LE_AUDIO))

    fun buildSetAlertLeAudioNotification(enable: Boolean): ByteArray =
        buildSetAlertStatus(ALERT_INQUIRED_TYPE_LE_AUDIO, enable)

    fun buildSetNcOnOff(enabled: Boolean): ByteArray =
        SonyTandemFrame.message(
            NCASM_SET_PARAM,
            byteArrayOf(
                NcAsmInquiredType.NC_ON_OFF.code,
                VALUE_CHANGED,
                if (enabled) NCASM_ON else NCASM_OFF,
                if (enabled) NCASM_ON else NCASM_OFF,
            ),
        )

    fun buildSetAmbientSound(
        enabled: Boolean,
        mode: AmbientSoundMode = AmbientSoundMode.NORMAL,
    ): ByteArray =
        SonyTandemFrame.message(
            NCASM_SET_PARAM,
            byteArrayOf(
                NcAsmInquiredType.ASM_ON_OFF.code,
                VALUE_CHANGED,
                if (enabled) NCASM_ON else NCASM_OFF,
                mode.code,
                if (enabled) NCASM_ON else NCASM_OFF,
            ),
        )

    fun buildSetAmbientLevel(
        level: Int,
        enabled: Boolean = true,
        mode: AmbientSoundMode = AmbientSoundMode.NORMAL,
    ): ByteArray =
        SonyTandemFrame.message(
            NCASM_SET_PARAM,
            byteArrayOf(
                NcAsmInquiredType.ASM_SEAMLESS.code,
                VALUE_CHANGED,
                if (enabled) NCASM_ON else NCASM_OFF,
                mode.code,
                level.coerceIn(1, 20).toByte(),
            ),
        )

    fun buildPlayback(
        control: PlaybackControl,
        type: PlayInquiredType = PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT,
    ): ByteArray =
        SonyTandemFrame.message(
            PLAY_SET_STATUS,
            byteArrayOf(type.code, VALUE_ENABLE, control.code),
        )

    fun buildGetPlaybackParam(type: PlayInquiredType): ByteArray =
        SonyTandemFrame.message(PLAY_GET_PARAM, byteArrayOf(type.code))

    fun buildSetPlaybackVolume(
        volume: Int,
        type: PlayInquiredType = PlayInquiredType.MUSIC_VOLUME,
    ): ByteArray =
        SonyTandemFrame.message(PLAY_SET_PARAM, byteArrayOf(type.code, volume.coerceIn(0, 255).toByte()))

    fun parse(raw: ByteArray): ParsedTandemResponse {
        val normalized = if (raw.firstOrNull() == DATA_MDR) raw else byteArrayOf(DATA_MDR) + raw
        if (normalized.size < 2) {
            return ParsedTandemResponse.Unknown(null, null, byteArrayOf(), raw)
        }
        val dataType = normalized[0]
        val command = normalized[1]
        val payload = normalized.drop(2).map { it }.toByteArray()
        if (dataType != DATA_MDR) {
            return ParsedTandemResponse.Unknown(dataType.unsigned, command.unsigned, payload, raw)
        }

        return when (command) {
            CONNECT_RET_PROTOCOL_INFO -> parseProtocolInfoPayload(payload, v2 = true)
                ?: ParsedTandemResponse.Unknown(dataType.unsigned, command.unsigned, payload, raw)
            CONNECT_RET_SUPPORT_FUNCTION -> ParsedTandemResponse.SupportFunction(
                functions = parseSupportFunction(payload),
                table = SonyTable.NO_1,
                raw = raw,
            )
            CONNECT_RET_CAPABILITY_INFO -> parseConnectRetCapabilityInfoPayload(payload)
                ?: ParsedTandemResponse.Unknown(dataType.unsigned, command.unsigned, payload, raw)
            NCASM_RET_CAPABILITY -> ParsedTandemResponse.CapabilityInfo(
                domain = "NCASM",
                inquiredTypeCode = payload.firstOrNull()?.unsigned,
                values = payload.unsignedList(),
                raw = raw,
            )
            EQEBB_RET_CAPABILITY -> ParsedTandemResponse.CapabilityInfo(
                domain = "EQEBB",
                inquiredTypeCode = payload.firstOrNull()?.unsigned,
                values = payload.unsignedList(),
                raw = raw,
            )
            PLAY_RET_CAPABILITY -> parsePlayCapability(payload, raw)
            CONNECT_RET_DEVICE_INFO -> parseDeviceInfo(payload, raw)
            SYSTEM_RET_CAPABILITY -> parseSystemRetCapability(payload, raw)
            SYSTEM_RET_STATUS, SYSTEM_NTFY_STATUS -> parseSystemStatus(payload, raw)
            COMMON_RET_STATUS, COMMON_NTFY_STATUS -> parseCommonStatus(command, payload, raw)
            POWER_RET_STATUS, POWER_NTFY_STATUS -> parseBattery(payload, raw)
            EQEBB_RET_STATUS, EQEBB_NTFY_STATUS,
            EQEBB_RET_PARAM, EQEBB_NTFY_PARAM -> parseEqEbb(command, payload, raw)
            EQEBB_RET_EXTENDED_INFO -> SonyEqEbbPayloadParser.parseExtendedInfo(EqEbbPayloadVersion.V2, payload, raw)
            NCASM_RET_STATUS, NCASM_NTFY_STATUS -> parseNoiseControl(command, payload, raw)
            NCASM_RET_PARAM, NCASM_NTFY_PARAM -> parseNoiseControl(command, payload, raw)
            PLAY_RET_STATUS -> ParsedTandemResponse.PlaybackAck(
                values = payload.unsignedList(),
                status = parsePlaybackStatus(payload),
                enabled = playStatusEnabled(payload),
                isUnsolicited = false,
                raw = raw,
            )
            PLAY_NTFY_STATUS -> ParsedTandemResponse.PlaybackAck(
                values = payload.unsignedList(),
                status = parsePlaybackStatus(payload),
                enabled = playStatusEnabled(payload),
                isUnsolicited = true,
                raw = raw,
            )
            PLAY_RET_PARAM -> parsePlayParam(payload, raw, isUnsolicited = false)
            PLAY_NTFY_PARAM -> parsePlayParam(payload, raw, isUnsolicited = true)
            AUDIO_RET_PARAM -> parseAudioParam(payload, raw, isUnsolicited = false)
            AUDIO_NTFY_PARAM -> parseAudioParam(payload, raw, isUnsolicited = true)
            AUDIO_RET_CAPABILITY -> parseUpscalingCapability(payload, raw)
            AUDIO_GET_STATUS -> parseConnectionQualityAvailability(
                payload, raw, isUnsolicited = false,
            ) { it.size == 2 }
            AUDIO_RET_STATUS -> parseConnectionQualityAvailability(
                payload, raw, isUnsolicited = false,
            ) { it.size == 2 }
            AUDIO_NTFY_STATUS -> parseConnectionQualityAvailability(
                payload, raw, isUnsolicited = true,
            ) { it.size == 2 }
            LEA_RET_STATUS, LEA_NTFY_STATUS -> if (
                payload.firstOrNull() == LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING
            ) {
                parseLeaSettingAvailability(payload, raw, command == LEA_NTFY_STATUS)
            } else {
                parseLeaStatus(payload, raw)
            }
            LEA_RET_PARAM, LEA_NTFY_PARAM -> if (
                payload.firstOrNull() == LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING
            ) {
                parseLeaParameterNotification(payload, raw)
            } else {
                parseLeaParam(payload, raw)
            }
            SYSTEM_RET_PARAM, SYSTEM_NTFY_PARAM -> parseSystemRetParam(payload, raw)
            SYSTEM_RET_EXT_PARAM, SYSTEM_NTFY_EXT_PARAM -> parseSystemRetExtendedParam(payload, raw)
            GS_RET_CAPABILITY -> parseGeneralSettingCapability(payload, raw)
            GS_RET_STATUS, GS_NTFY_STATUS -> parseGeneralSettingStatus(payload, raw)
            GS_RET_PARAM, GS_NTFY_PARAM -> parseGeneralSettingParam(payload, raw)
            ALERT_RET_STATUS -> parseAlertStatus(payload, raw, false)
            ALERT_NTFY_STATUS -> parseAlertStatus(payload, raw, true)
            ALERT_NTFY_PARAM -> parseAlertParam(payload, raw)
            else -> ParsedTandemResponse.Unknown(dataType.unsigned, command.unsigned, payload, raw)
        }
    }

    private fun parseDeviceInfo(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { code ->
            DeviceInfoType.entries.firstOrNull { it.code == code }
        }
        val text = when (type) {
            DeviceInfoType.MODEL_NAME,
            DeviceInfoType.FW_VERSION,
            DeviceInfoType.INSTRUCTION_GUIDE -> parseLengthPrefixedString(payload, offset = 1)
            DeviceInfoType.SERIES_AND_COLOR_INFO -> parseSeriesAndColor(payload)
            null -> null
        }
        val colorCode = if (type == DeviceInfoType.SERIES_AND_COLOR_INFO) payload.getOrNull(2)?.unsigned else null
        return ParsedTandemResponse.DeviceInfo(type, text, raw, colorCode)
    }

    private fun parseCommonStatus(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { code ->
            CommonInquiredType.entries.firstOrNull { it.code == code }
        }
        // SC `ef0.n$b`/`ef0.i$b` dispatch AUDIO_CODEC and UPSCALING_EFFECT to
        // their own message classes with strict length+value validation; a frame
        // failing it is dropped whole, never partially applied.
        when (type) {
            CommonInquiredType.AUDIO_CODEC -> return parseAudioCodecStatus(command, payload, raw)
            CommonInquiredType.UPSCALING_EFFECT -> return parseUpscalingEffectStatus(command, payload, raw)
            else -> Unit
        }
        val text = when (type) {
            CommonInquiredType.DISPLAY_FW_VERSION -> parseLengthPrefixedString(payload, offset = 1)
            null -> null
            else -> null
        }
        return ParsedTandemResponse.CommonStatus(
            type = type,
            text = text,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }

    /** `[inqType 0x02][codecByte]`, exactly 2 bytes; a byte outside the codec
     * table rejects the frame whole (SC `ef0.o$b` validates the same way). */
    private fun parseAudioCodecStatus(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val codecByte = payload.getOrNull(1)?.unsigned
        val codec = codecByte?.let(SoundQualityCodec::fromCode)
        if (payload.size != 2 || codec == null) {
            return ParsedTandemResponse.Unknown(DATA_MDR.unsigned, command.unsigned, payload, raw)
        }
        return ParsedTandemResponse.AudioCodecStatus(
            codec = codec,
            isUnsolicited = command == COMMON_NTFY_STATUS,
            raw = raw,
        )
    }

    /** `[inqType 0x03][effectType][effectStatus]`, exactly 3 bytes. */
    private fun parseUpscalingEffectStatus(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val generation = payload.getOrNull(1)?.unsigned?.let(DseeGeneration::fromCode)
        val state = payload.getOrNull(2)?.unsigned?.let(DseeEffectState::fromCode)
        if (payload.size != 3 || generation == null || state == null) {
            return ParsedTandemResponse.Unknown(DATA_MDR.unsigned, command.unsigned, payload, raw)
        }
        return ParsedTandemResponse.UpscalingEffect(
            generation = generation,
            state = state,
            isUnsolicited = command == COMMON_NTFY_STATUS,
            raw = raw,
        )
    }

    private fun parseLengthPrefixedString(payload: ByteArray, offset: Int): String? {
        val length = payload.getOrNull(offset)?.unsigned ?: return fallbackDeviceInfoString(payload)
        val start = offset + 1
        if (length <= 0 || payload.size < start + length) {
            return fallbackDeviceInfoString(payload)
        }
        return payload.copyOfRange(start, start + length)
            .decodeToString()
            .trimEnd('\u0000')
            .takeIf { it.isNotBlank() }
    }

    private fun fallbackDeviceInfoString(payload: ByteArray): String? =
        payload.drop(1)
            .takeIf { it.isNotEmpty() }
            ?.toByteArray()
            ?.decodeToString()
            ?.trimEnd('\u0000')
            ?.takeIf { it.isNotBlank() }

    private fun parseSeriesAndColor(payload: ByteArray): String? {
        val series = payload.getOrNull(1)?.unsigned ?: return null
        val color = payload.getOrNull(2)?.unsigned ?: return null
        return "${modelSeriesLabel(series)} / ${modelColorLabel(color)}"
    }

    private fun modelSeriesLabel(code: Int): String =
        when (code) {
            0x00 -> "NO_SERIES"
            0x10 -> "EXTRA_BASS"
            0x11 -> "ULT_POWER_SOUND"
            0x20 -> "HEAR"
            0x30 -> "PREMIUM"
            0x40 -> "SPORTS"
            0x50 -> "CASUAL"
            0x60 -> "LINK_BUDS"
            0x70 -> "NECKBAND"
            0x80 -> "LINKPOD"
            0x90 -> "GAMING"
            else -> "UNKNOWN_SERIES_0x%02X".format(code)
        }

    private fun modelColorLabel(code: Int): String =
        when (code) {
            0x00 -> "Default"
            0x01 -> "Black"
            0x02 -> "White"
            0x03 -> "Silver"
            0x04 -> "Red"
            0x05 -> "Blue"
            0x06 -> "Pink"
            0x07 -> "Yellow"
            0x08 -> "Green"
            0x09 -> "Gray"
            0x0A -> "Gold"
            0x0B -> "Cream"
            0x0C -> "Orange"
            0x0D -> "Brown"
            0x0E -> "Violet"
            0x11 -> "Black-I"
            0x12 -> "White-I"
            0x13 -> "Silver-I"
            0x14 -> "Red-I"
            0x15 -> "Blue-I"
            0x16 -> "Pink-I"
            0x17 -> "Yellow-I"
            0x18 -> "Green-I"
            0x19 -> "Gray-I"
            0x1A -> "Gold-I"
            0x1B -> "Cream-I"
            0x1C -> "Orange-I"
            0x1D -> "Brown-I"
            0x1E -> "Violet-I"
            else -> "Unknown color 0x%02X".format(code)
        }

    private fun parseBattery(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val kind = payload.firstOrNull()?.let { code ->
            // 0x09 is an extended battery NTFY (LEFT_RIGHT layout, 2-byte values) that
            // some devices push unsolicited on per-bud connect/disconnect. It is not in
            // the PowerInquiredType enum, so map it to LEFT_RIGHT_BATTERY so the engine
            // updates left/right (and sees a disconnected bud as 0 -> null).
            if (code == 0x09.toByte()) PowerInquiredType.LEFT_RIGHT_BATTERY
            else PowerInquiredType.entries.firstOrNull { it.code == code }
        }
        // Keep position: a null (sentinel or absent slot) stays in its place so the
        // engine can tell which bud is disconnected, instead of listOfNotNull silently
        // dropping it and shifting the other bud's level into the disconnected slot.
        val values = when (kind) {
            PowerInquiredType.BATTERY,
            PowerInquiredType.CRADLE_BATTERY -> listOf(payload.getOrNull(1)?.percentageOrNull())
            PowerInquiredType.LEFT_RIGHT_BATTERY -> listOf(
                payload.getOrNull(1)?.percentageOrNull(),
                payload.getOrNull(3)?.percentageOrNull(),
            )
            else -> payload.drop(1).map { it.unsigned }
        }
        return ParsedTandemResponse.Battery(kind, values, raw)
    }

    private fun parseEqEbb(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        return SonyEqEbbPayloadParser.parse(EqEbbPayloadVersion.V2, command, payload, raw)
    }

    private fun parseNoiseControl(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let(NcAsmInquiredType::fromV2Table1Code)
        val values = payload.drop(1).map { it.unsigned }
        val isParamResponse = command == NCASM_RET_PARAM || command == NCASM_NTFY_PARAM
        if (!isParamResponse) {
            return ParsedTandemResponse.NoiseControl(
                type = type,
                values = values,
                raw = raw,
            )
        }
        // Payload layout (type byte at idx[0], then the base header, then the
        // type-specific params — all mirroring the SC rf0 writers / zf0 parsers
        // 1:1, so idx = rf0 idx - 1 because rf0 counts the command byte):
        //   [0]=type, [1]=ValueChangeStatus, [2]=NcAsmOnOffValue totalEffect,
        //   [3..]=type params.
        val ambientMode = when (type) {
            NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> payload.getOrNull(5)
            NcAsmInquiredType.NC_ON_OFF -> null
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_ON_OFF,
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_ON_OFF -> payload.getOrNull(4)
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS,
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS -> payload.getOrNull(4)
            NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS -> payload.getOrNull(5)
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA -> payload.getOrNull(4)
            else -> payload.getOrNull(3)
        }?.let { byte ->
            AmbientSoundMode.entries.firstOrNull { it.code == byte }
        }
        // totalEffect at idx[2] (NcAsmOnOffValue: ON=0x01 / OFF=0x00).
        val combinedEnabled = payload.getOrNull(2)?.let { it == NCASM_ON }
        val combinedMode = payload.getOrNull(3)
        val combinedControlMode = when (type) {
            NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> when {
                payload.getOrNull(1) == NCASM_OFF -> NoiseControlMode.OFF
                payload.getOrNull(3) == NC_VALUE_ON_SINGLE ||
                    payload.getOrNull(3) == NC_VALUE_ON_DUAL -> NoiseControlMode.NOISE_CANCELLING
                payload.getOrNull(3) == NC_VALUE_OFF &&
                    payload.getOrNull(1) != NCASM_OFF -> NoiseControlMode.AMBIENT_SOUND
                else -> null
            }
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS -> when {
                combinedEnabled == false -> NoiseControlMode.OFF
                combinedMode == NCASM_MODE_ASM -> NoiseControlMode.AMBIENT_SOUND
                combinedMode == NCASM_MODE_NC -> NoiseControlMode.NOISE_CANCELLING
                else -> null
            }
            NcAsmInquiredType.NC_ON_OFF -> when (payload.getOrNull(3) ?: payload.getOrNull(1)) {
                NCASM_ON -> NoiseControlMode.NOISE_CANCELLING
                NCASM_OFF -> NoiseControlMode.OFF
                else -> null
            }
            NcAsmInquiredType.ASM_ON_OFF -> when (payload.getOrNull(4) ?: payload.getOrNull(1)) {
                NCASM_ON -> NoiseControlMode.AMBIENT_SOUND
                NCASM_OFF -> NoiseControlMode.OFF
                else -> null
            }
            NcAsmInquiredType.ASM_SEAMLESS -> when (payload.getOrNull(2)) {
                NCASM_ON -> NoiseControlMode.AMBIENT_SOUND
                NCASM_OFF -> NoiseControlMode.OFF
                else -> null
            }
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_ON_OFF -> when {
                payload.getOrNull(3) == NCASM_ON -> NoiseControlMode.NOISE_CANCELLING
                payload.getOrNull(5) == NCASM_ON -> NoiseControlMode.AMBIENT_SOUND
                else -> NoiseControlMode.OFF
            }
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_ON_OFF -> when {
                payload.getOrNull(3) == NC_VALUE_ON_SINGLE ||
                    payload.getOrNull(3) == NC_VALUE_ON_DUAL -> NoiseControlMode.NOISE_CANCELLING
                payload.getOrNull(5) == NCASM_ON -> NoiseControlMode.AMBIENT_SOUND
                else -> NoiseControlMode.OFF
            }
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS -> when {
                payload.getOrNull(2) == NCASM_OFF -> NoiseControlMode.OFF
                payload.getOrNull(3) == NCASM_ON -> NoiseControlMode.NOISE_CANCELLING
                else -> NoiseControlMode.AMBIENT_SOUND
            }
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> when {
                payload.getOrNull(2) == NCASM_OFF -> NoiseControlMode.OFF
                payload.getOrNull(3) == NC_VALUE_ON_SINGLE ||
                    payload.getOrNull(3) == NC_VALUE_ON_DUAL -> NoiseControlMode.NOISE_CANCELLING
                else -> NoiseControlMode.AMBIENT_SOUND
            }
            else -> null
        }
        val modeBasedTypes = setOf(
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS,
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_ON_OFF,
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_ON_OFF,
        )
        return ParsedTandemResponse.NoiseControl(
            type = type,
            values = values,
            enabled = when (type) {
                NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> combinedControlMode == NoiseControlMode.NOISE_CANCELLING
                NcAsmInquiredType.NC_ON_OFF -> payload.getOrNull(3)?.let { it == NCASM_ON }
                    ?: payload.getOrNull(1)?.let { it == VALUE_ENABLE }
                in modeBasedTypes -> combinedControlMode == NoiseControlMode.NOISE_CANCELLING
                else -> null
            },
            ambientSoundEnabled = when (type) {
                NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> combinedControlMode == NoiseControlMode.AMBIENT_SOUND
                NcAsmInquiredType.ASM_ON_OFF -> payload.getOrNull(4)?.let { it == NCASM_ON }
                    ?: payload.getOrNull(1)?.let { it == VALUE_ENABLE }
                NcAsmInquiredType.ASM_SEAMLESS -> payload.getOrNull(2)?.let { it == NCASM_ON }
                in modeBasedTypes -> combinedControlMode == NoiseControlMode.AMBIENT_SOUND
                else -> null
            },
            ambientLevel = when (type) {
                NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> payload.getOrNull(6)?.unsigned
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
                NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS,
                NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS,
                NcAsmInquiredType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA -> payload.getOrNull(5)?.unsigned
                NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
                NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS -> payload.getOrNull(6)?.unsigned
                NcAsmInquiredType.ASM_SEAMLESS -> payload.getOrNull(4)?.unsigned
                else -> null
            },
            ambientMode = ambientMode,
            controlMode = combinedControlMode,
            windNoiseReduction = when (type) {
                NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                    // The device resolves the AUTO write into the actual mic
                    // configuration (SC `NcValue`): AUTO/AUTO_SINGLE/AUTO_DUAL all
                    // mean auto wind-noise reduction is active.
                    when (payload.getOrNull(4)) {
                        NC_VALUE_AUTO, NC_VALUE_AUTO_SINGLE, NC_VALUE_AUTO_DUAL -> true
                        NC_VALUE_ON_DUAL, NC_VALUE_ON_SINGLE -> false
                        else -> null
                    }
                NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                    when (payload.getOrNull(4)) {
                        NC_VALUE_ON_SINGLE -> true
                        NC_VALUE_ON_DUAL -> false
                        else -> null
                    }
                NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS,
                NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_ON_OFF ->
                    when (payload.getOrNull(3)) {
                        NC_VALUE_ON_SINGLE -> true
                        NC_VALUE_ON_DUAL -> false
                        else -> null
                    }
                NcAsmInquiredType.V1_TABLE_SET1_NC_ASM ->
                    when (payload.getOrNull(3)) {
                        NC_VALUE_ON_SINGLE -> true
                        NC_VALUE_ON_DUAL -> false
                        else -> null
                    }
                else -> null
            },
            // rf0/g trailing pair: [6]=NcAsmOnOffValue NA toggle, [7]=sensitivity.
            noiseAdaptiveEnabled = if (type == NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA) {
                payload.getOrNull(6)?.let { it == NCASM_ON }
            } else {
                null
            },
            noiseAdaptiveSensitivity = if (type == NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA) {
                payload.getOrNull(7)?.let(NoiseAdaptiveSensitivity::fromCode)
            } else {
                null
            },
            raw = raw,
        )
    }

    /** Transport state, and only for the playback types: 0x40 PLAY_MODE carries its play mode at
     * this same offset, whose codes overlap PLAYING/PAUSED/STOPPED. */
    private fun parsePlaybackStatus(payload: ByteArray): PlaybackStatus =
        if (payload.firstOrNull()?.unsigned !in 1..3) {
            PlaybackStatus.UNKNOWN
        } else {
            when (payload.getOrNull(2)?.unsigned) {
                1 -> PlaybackStatus.PLAYING
                2 -> PlaybackStatus.PAUSED
                3 -> PlaybackStatus.STOPPED
                else -> PlaybackStatus.UNKNOWN
            }
        }

    /** STATUS enable bit; only for the playback types — 0x40 PLAY_MODE has its own
     * enable at the same offset and must not leak into the playback card state. */
    private fun playStatusEnabled(payload: ByteArray): Boolean? =
        payload.getOrNull(1)
            ?.takeIf { payload.firstOrNull()?.unsigned in 1..3 }
            ?.let { it.unsigned == 0 }

    /** Type 0x01/0x02 → [type, musicStep, callStep]; 0x03 → [type, musicStep].
     * The v2 wire format has no button/metadata support bits: SC hardcodes both
     * as supported for v2 devices, and so do we. */
    private fun parsePlayCapability(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.unsigned
        val musicStep = when (type) {
            0x01, 0x02 -> if (payload.size >= 3) payload[1].unsigned else null
            0x03 -> if (payload.size >= 2) payload[1].unsigned else null
            else -> null
        }
        if (type != null && musicStep != null) {
            return ParsedTandemResponse.PlaybackCapability(
                inquiredTypeCode = type,
                musicVolumeStep = musicStep,
                supportsPlaybackButtons = true,
                supportsMetadata = true,
                raw = raw,
            )
        }
        return ParsedTandemResponse.CapabilityInfo(
            domain = "PLAY",
            inquiredTypeCode = type,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    private fun parsePlayParam(payload: ByteArray, raw: ByteArray, isUnsolicited: Boolean): ParsedTandemResponse =
        when (payload.firstOrNull()?.unsigned) {
            0x01, 0x02, 0x03 -> parsePlayMetadata(payload, raw, isUnsolicited)
            // 0x20/0x21 = music/call volume; 0x30/0x31 append a mute byte we ignore.
            0x20, 0x21, 0x30, 0x31 -> payload.getOrNull(1)?.let {
                ParsedTandemResponse.PlaybackVolume(it.unsigned, isUnsolicited, raw)
            } ?: unknownPlayParam(payload, raw)
            else -> unknownPlayParam(payload, raw)
        }

    /**
     * AUDIO_RET_PARAM / AUDIO_NTFY_PARAM dispatch by inquired type: the
     * connection-quality types (0x00/02/05) carry a PriorMode value, the
     * upscaling types (0x01/0x0B) an UpscalingTypeAutoOff — anything else is
     * rejected whole like SC's per-type factories do.
     */
    private fun parseAudioParam(payload: ByteArray, raw: ByteArray, isUnsolicited: Boolean): ParsedTandemResponse {
        val inquiredType = payload.firstOrNull()?.unsigned
        return when (inquiredType) {
            AUDIO_INQ_CONNECTION_MODE.unsigned,
            AUDIO_INQ_CONNECTION_MODE_WITH_LDAC_STATUS.unsigned,
            AUDIO_INQ_CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO.unsigned,
            -> parseConnectionQualityParam(payload, raw, isUnsolicited)

            AUDIO_INQ_UPSCALING.unsigned,
            AUDIO_INQ_UPSCALING_WITH_REASON.unsigned,
            -> parseUpscalingParam(payload, raw, isUnsolicited)

            else -> ParsedTandemResponse.Unknown(DATA_MDR.unsigned, null, payload, raw)
        }
    }

    /**
     * Connection-quality PARAM frame (`cf0.i0` / `cf0.u`): `[inq][PriorMode]`,
     * three bytes on the wire for RET and the classic NTFY. The LE-era 0x05 NTFY
     * appends SwitchingStream as a fourth byte announcing which audio stream is
     * migrating — accepted and surfaced, never used to reject the frame.
     */
    private fun parseConnectionQualityParam(
        payload: ByteArray,
        raw: ByteArray,
        isUnsolicited: Boolean,
    ): ParsedTandemResponse {
        val mode = payload.getOrNull(1)?.let { ConnectionQualityMode.fromCode(it.unsigned) }
            ?: return ParsedTandemResponse.Unknown(DATA_MDR.unsigned, null, payload, raw)
        val switchingStream = payload.getOrNull(2)?.unsigned
        if (payload.size > 3) {
            return ParsedTandemResponse.Unknown(DATA_MDR.unsigned, null, payload, raw)
        }
        return ParsedTandemResponse.ConnectionQuality(
            mode = mode,
            switchingStreamCode = switchingStream,
            inquiredTypeCode = payload.firstOrNull()!!.unsigned,
            isUnsolicited = isUnsolicited,
            raw = raw,
        )
    }

    /** Upscaling PARAM frame (`cf0.o0`): `[inq][UpscalingTypeAutoOff]`, exactly two bytes. */
    private fun parseUpscalingParam(payload: ByteArray, raw: ByteArray, isUnsolicited: Boolean): ParsedTandemResponse {
        if (payload.size != 2) {
            return ParsedTandemResponse.Unknown(DATA_MDR.unsigned, null, payload, raw)
        }
        return when (payload[1]) {
            UPSCALING_OFF -> ParsedTandemResponse.Upscaling(false, payload[0].unsigned, isUnsolicited, raw)
            UPSCALING_AUTO -> ParsedTandemResponse.Upscaling(true, payload[0].unsigned, isUnsolicited, raw)
            else -> ParsedTandemResponse.Unknown(DATA_MDR.unsigned, null, payload, raw)
        }
    }

    /**
     * AUDIO status frame for a connection-quality inquired type (`cf0.t0` /
     * `cf0.m`): `[inq][EnableDisable]`. Only frames whose inquired type carries
     * this feature are consumed; other AUDIO statuses fall through as Unknown.
     * [lengthCheck] enforces each base class's exact wire length.
     */
    private fun parseConnectionQualityAvailability(
        payload: ByteArray,
        raw: ByteArray,
        isUnsolicited: Boolean,
        lengthCheck: (ByteArray) -> Boolean,
    ): ParsedTandemResponse {
        val inquiredType = payload.firstOrNull()?.unsigned
        val knownConnectionQualityType = inquiredType == AUDIO_INQ_CONNECTION_MODE.unsigned ||
            inquiredType == AUDIO_INQ_CONNECTION_MODE_WITH_LDAC_STATUS.unsigned ||
            inquiredType == AUDIO_INQ_CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO.unsigned ||
            // 0x0B rides the same STATUS sub-domain for the newer DSEE generation;
            // its availability is not consumed today but must not parse as Unknown.
            inquiredType == AUDIO_INQ_UPSCALING_WITH_REASON.unsigned
        if (!knownConnectionQualityType || !lengthCheck(payload)) {
            return ParsedTandemResponse.Unknown(DATA_MDR.unsigned, null, payload, raw)
        }
        val enabled = payload[1] == ENABLE_DISABLE_ENABLE
        return ParsedTandemResponse.ConnectionQualityAvailability(enabled, isUnsolicited, raw)
    }

    /**
     * AUDIO_RET_CAPABILITY (0xE1) for the upscaling inquired types: `[inq][type]`,
     * three bytes on the wire like SC's `cf0.e0` — length violations, foreign
     * inquired types and out-of-range UpscalingType values reject the whole frame.
     * [type] is the DSEE generation byte the UI titles/describes from.
     */
    private fun parseUpscalingCapability(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val inquiredType = payload.firstOrNull()?.unsigned
        if (payload.size != 2 || (inquiredType != AUDIO_INQ_UPSCALING.unsigned &&
                    inquiredType != AUDIO_INQ_UPSCALING_WITH_REASON.unsigned)
        ) {
            return ParsedTandemResponse.Unknown(DATA_MDR.unsigned, null, payload, raw)
        }
        val type = payload[1]
        if (type !in UPSCALING_TYPES) {
            return ParsedTandemResponse.Unknown(DATA_MDR.unsigned, null, payload, raw)
        }
        return ParsedTandemResponse.UpscalingCapability(inquiredType, type.unsigned, raw)
    }

    /** payload[0]=type, then exactly four [nameStatus, len, utf8…] elements in the
     * fixed order track/album/artist/genre. Partial payloads are rejected whole —
     * SC's parser does the same, and partial application would tear the card. */
    private fun parsePlayMetadata(payload: ByteArray, raw: ByteArray, isUnsolicited: Boolean): ParsedTandemResponse {
        val names = ArrayList<PlaybackName>(4)
        var index = 1
        repeat(4) {
            val statusCode = payload.getOrNull(index)?.unsigned ?: return unknownPlayParam(payload, raw)
            val length = payload.getOrNull(index + 1)?.unsigned ?: return unknownPlayParam(payload, raw)
            val start = index + 2
            if (start + length > payload.size) return unknownPlayParam(payload, raw)
            val text = if (length == 0) "" else payload.copyOfRange(start, start + length).decodeToString()
            val status = if (length == 0 && statusCode == PlaybackNameStatus.SETTLED.code) {
                PlaybackNameStatus.NOTHING
            } else {
                PlaybackNameStatus.fromCode(statusCode)
            }
            names += PlaybackName(text, status)
            index = start + length
        }
        return ParsedTandemResponse.PlaybackMetadata(
            track = names[0],
            album = names[1],
            artist = names[2],
            genre = names[3],
            isUnsolicited = isUnsolicited,
            raw = raw,
        )
    }

    private fun unknownPlayParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse =
        ParsedTandemResponse.Unknown(DATA_MDR.unsigned, PLAY_RET_PARAM.unsigned, payload, raw)

    private fun parseLeaStatus(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val typeCode = payload.firstOrNull()
        val type = LeaInquiredType.entries.firstOrNull { it.code == typeCode }
        val values = payload.unsignedList()
        val enabled = payload.getOrNull(1)?.let { code ->
            LeaEnableDisable.entries.firstOrNull { it.code == code }
        }
        val (streamingL, streamingR) = when (type) {
            LeaInquiredType.HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD ->
                payload.getOrNull(2)?.toLeaStreamingStatus() to null
            LeaInquiredType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
            LeaInquiredType.TWS_SUPPORTS_LEA_UNI_LEA_BROAD ->
                payload.getOrNull(2)?.toLeaStreamingStatus() to payload.getOrNull(3)?.toLeaStreamingStatus()
            null -> null to null
        }
        return ParsedTandemResponse.LeaStatus(
            type = type,
            values = values,
            enabled = enabled,
            streamingStatusL = streamingL,
            streamingStatusR = streamingR,
            inquiredTypeCode = typeCode?.unsigned,
            table = SonyTable.NO_1,
            raw = raw,
        )
    }

    /** ALERT_SET_PARAM FLEXIBLE_MESSAGE: [0x06][type][action]. */
    fun buildReplyAlertFlexibleMessage(messageType: Int, positive: Boolean): ByteArray =
        SonyTandemFrame.message(
            ALERT_SET_PARAM,
            byteArrayOf(
                ALERT_INQUIRED_TYPE_FLEXIBLE_MESSAGE,
                messageType.toByte(),
                if (positive) ALERT_ACTION_POSITIVE else ALERT_ACTION_NEGATIVE,
            ),
        )

    /** ALERT_SET_PARAM fixed-message-with-left/right-selection reply. */
    fun buildReplyAlertFixedMessageWithLeftRightSelection(messageType: Int, positive: Boolean): ByteArray =
        buildReplyAlertFixedMessageWithLeftRightSelection(messageType, if (positive) 1 else 0)

    /**
     * The left/right alert domain does not use a generic POSITIVE byte:
     * 0=NEGATIVE, 1=LEFT, 2=RIGHT. The official app echoes the selected side.
     */
    fun buildReplyAlertFixedMessageWithLeftRightSelection(messageType: Int, action: Int): ByteArray =
        SonyTandemFrame.message(
            ALERT_SET_PARAM,
            byteArrayOf(
                ALERT_INQUIRED_TYPE_FIXED_MESSAGE_WITH_LEFT_RIGHT,
                messageType.toByte(),
                action.coerceIn(0, 2).toByte(),
            ),
        )

    /** ALERT_SET_PARAM foreground fixed message: [0x04][type][action]. */
    fun buildReplyAlertForegroundMessage(messageType: Int, positive: Boolean): ByteArray =
        SonyTandemFrame.message(
            ALERT_SET_PARAM,
            byteArrayOf(
                ALERT_INQUIRED_TYPE_APP_BECOMES_FOREGROUND,
                messageType.toByte(),
                if (positive) ALERT_ACTION_POSITIVE else ALERT_ACTION_NEGATIVE,
            ),
        )
    private fun parseLeaParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val typeCode = payload.firstOrNull()
        val type = LeaInquiredType.entries.firstOrNull { it.code == typeCode }
        val pairedHistory = payload.getOrNull(1)?.let { code ->
            LeaPairedHistory.entries.firstOrNull { it.code == code }
        }
        return ParsedTandemResponse.LeaPairedHistoryStatus(
            type = type,
            values = payload.unsignedList(),
            pairedHistory = pairedHistory,
            inquiredTypeCode = typeCode?.unsigned,
            table = SonyTable.NO_1,
            raw = raw,
        )
    }

    private fun parseLeaParameterNotification(
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse {
        val setting = payload.firstOrNull()?.unsigned
        val enabled = payload.getOrNull(1)?.let { code ->
            LeaEnableDisable.entries.firstOrNull { it.code == code }
        }
        return ParsedTandemResponse.LeaParameterNotification(
            setting = setting,
            enabled = enabled,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    private fun parseLeaSettingAvailability(
        payload: ByteArray,
        raw: ByteArray,
        isNotification: Boolean,
    ): ParsedTandemResponse {
        val available = payload.getOrNull(1)?.let { it == LEA_ENABLE }
            ?: return ParsedTandemResponse.Unknown(
                LEA_CLASSIC_ONLY_LE_CLASSIC_SETTING.unsigned,
                if (isNotification) LEA_NTFY_STATUS.unsigned else LEA_RET_STATUS.unsigned,
                payload,
                raw,
            )
        return ParsedTandemResponse.LeaSettingAvailability(available, isNotification, raw)
    }

    private fun Byte.toLeaStreamingStatus(): LeaStreamingStatus? =
        LeaStreamingStatus.entries.firstOrNull { it.code == this }

    /** Parse the SC cg0.c capability format after dataType+command are stripped.
     * ASSIGNABLE_SETTINGS_WITH_LIMITATION inserts a LimitationType byte between
     * the system type and the key count; the key grammar is otherwise identical.
     */
    private fun parseAssignableSettingsCapability(
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse {
        val systemType = payload.firstOrNull()?.let { code ->
            SystemInquiredType.entries.firstOrNull { it.code == code }
        }
        val headerSize = when (systemType) {
            SystemInquiredType.ASSIGNABLE_SETTINGS -> 2
            SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION -> 3
            else -> 0
        }
        if (headerSize == 0) return ParsedTandemResponse.Unknown(
            null,
            SYSTEM_RET_CAPABILITY.unsigned,
            payload,
            raw,
        )
        val count = payload.getOrNull(headerSize - 1)?.unsigned ?: return ParsedTandemResponse.Unknown(
            null,
            SYSTEM_RET_CAPABILITY.unsigned,
            payload,
            raw,
        )
        var offset = headerSize
        val keys = mutableListOf<ParsedTandemResponse.AssignableSettingsKeyCapability>()
        repeat(count) {
            if (offset + 4 > payload.size) return@repeat
            val key = AssignableSettingsKey.entries.firstOrNull { it.code == payload[offset] }
            val type = AssignableSettingsType.entries.firstOrNull { it.code == payload[offset + 1] }
            val defaultPreset = AssignableSettingsPreset.entries.firstOrNull { it.code == payload[offset + 2] }
            val presetCount = payload[offset + 3].unsigned
            offset += 4
            if (key == null || type == null || defaultPreset == null ||
                key == AssignableSettingsKey.OUT_OF_RANGE ||
                type == AssignableSettingsType.OUT_OF_RANGE ||
                defaultPreset == AssignableSettingsPreset.OUT_OF_RANGE
            ) return@repeat

            val presets = mutableListOf<AssignableSettingsPreset>()
            val actionsByPreset = linkedMapOf<AssignableSettingsPreset, List<ParsedTandemResponse.AssignableSettingsActionCapability>>()
            repeat(presetCount) {
                if (offset + 3 > payload.size) return@repeat
                val preset = AssignableSettingsPreset.entries.firstOrNull { it.code == payload[offset] }
                val singleCount = payload[offset + 1].unsigned
                val multipleCount = payload[offset + 2].unsigned
                offset += 3
                if (preset == null || preset == AssignableSettingsPreset.OUT_OF_RANGE) return@repeat
                val actions = linkedMapOf<AssignableSettingsAction, ParsedTandemResponse.AssignableSettingsActionCapability>()
                repeat(singleCount) {
                    if (offset + 2 > payload.size) return@repeat
                    val action = AssignableSettingsAction.entries.firstOrNull { it.code == payload[offset] }
                    val function = AssignableSettingsFunction.entries.firstOrNull { it.code == payload[offset + 1] }
                    offset += 2
                    if (action != null && function != null &&
                        action != AssignableSettingsAction.OUT_OF_RANGE &&
                        function != AssignableSettingsFunction.OUT_OF_RANGE
                    ) {
                        actions[action] = ParsedTandemResponse.AssignableSettingsActionCapability(
                            action = action,
                            defaultFunction = function,
                            availableFunctions = listOf(function),
                        )
                    }
                }
                repeat(multipleCount) {
                    if (offset + 3 > payload.size) return@repeat
                    val action = AssignableSettingsAction.entries.firstOrNull { it.code == payload[offset] }
                    val defaultFunction = AssignableSettingsFunction.entries.firstOrNull { it.code == payload[offset + 1] }
                    val functionCount = payload[offset + 2].unsigned
                    offset += 3
                    val functions = buildList {
                        repeat(functionCount) {
                            if (offset < payload.size) {
                                AssignableSettingsFunction.entries.firstOrNull { it.code == payload[offset] }
                                    ?.takeIf { it != AssignableSettingsFunction.OUT_OF_RANGE }
                                    ?.let(::add)
                            }
                            offset++
                        }
                    }
                    if (action != null && defaultFunction != null &&
                        action != AssignableSettingsAction.OUT_OF_RANGE &&
                        defaultFunction != AssignableSettingsFunction.OUT_OF_RANGE &&
                        functions.isNotEmpty()
                    ) {
                        actions[action] = ParsedTandemResponse.AssignableSettingsActionCapability(
                            action = action,
                            defaultFunction = defaultFunction,
                            availableFunctions = functions.distinct(),
                        )
                    }
                }
                presets += preset
                actionsByPreset[preset] = actions.values.sortedBy { it.action.code.unsigned }
            }
            keys += ParsedTandemResponse.AssignableSettingsKeyCapability(
                key = key,
                type = type,
                defaultPreset = defaultPreset,
                presets = presets,
                actionsByPreset = actionsByPreset,
            )
        }
        return ParsedTandemResponse.AssignableSettingsCapability(
            keys = keys,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    private fun parseSystemRetCapability(
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse {
        return when (payload.firstOrNull()) {
            SystemInquiredType.QUICK_ACCESS.code -> parseQuickAccessCapability(payload, raw)
            SystemInquiredType.ASSIGNABLE_SETTINGS.code,
            SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION.code ->
                parseAssignableSettingsCapability(payload, raw)
            else -> ParsedTandemResponse.Unknown(
                null,
                SYSTEM_RET_CAPABILITY.unsigned,
                payload,
                raw,
            )
        }
    }

    /**
     * Parse the official QUICK_ACCESS capability grammar (`ag0.n0`):
     * [systemType][quickAccessKey][type][actionCount]
     * [(action)(defaultFunction)(functionCount)(functions...)]*.
     */
    private fun parseQuickAccessCapability(
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse {
        if (payload.size < 4) {
            return ParsedTandemResponse.Unknown(null, SYSTEM_RET_CAPABILITY.unsigned, payload, raw)
        }
        val key = QuickAccessKey.entries.firstOrNull { it.code == payload[1] }
        val type = AssignableSettingsType.entries.firstOrNull { it.code == payload[2] }
        val actionCount = payload[3].unsigned
        if (key == null || type == null ||
            key == QuickAccessKey.OUT_OF_RANGE || type == AssignableSettingsType.OUT_OF_RANGE
        ) {
            return ParsedTandemResponse.Unknown(null, SYSTEM_RET_CAPABILITY.unsigned, payload, raw)
        }
        var offset = 4
        val actions = buildList {
            repeat(actionCount) {
                if (offset + 3 > payload.size) return@repeat
                val action = AssignableSettingsAction.entries.firstOrNull { it.code == payload[offset] }
                val defaultFunction = QuickAccessFunction.entries.firstOrNull { it.code == payload[offset + 1] }
                val defaultFunctionCode = payload[offset + 1].unsigned
                val functionCount = payload[offset + 2].unsigned
                offset += 3
                val functionCodes = buildList {
                    repeat(functionCount) {
                        if (offset < payload.size) add(payload[offset].unsigned)
                        offset++
                    }
                }.distinct()
                val functions = buildList {
                    functionCodes.mapNotNullTo(this) { code ->
                        QuickAccessFunction.entries.firstOrNull { it.code.unsigned == code }
                            ?.takeIf { it != QuickAccessFunction.OUT_OF_RANGE }
                    }
                }.distinct()
                if (action != null &&
                    action != AssignableSettingsAction.OUT_OF_RANGE &&
                    functionCodes.isNotEmpty()
                ) {
                    add(
                        ParsedTandemResponse.QuickAccessActionCapability(
                            action = action,
                            defaultFunction = defaultFunction,
                            defaultFunctionCode = defaultFunctionCode,
                            availableFunctions = functions,
                            availableFunctionCodes = functionCodes,
                        )
                    )
                }
            }
        }
        if (offset != payload.size) {
            return ParsedTandemResponse.Unknown(null, SYSTEM_RET_CAPABILITY.unsigned, payload, raw)
        }
        return ParsedTandemResponse.QuickAccessCapability(
            key = key,
            type = type,
            actions = actions,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    /**
     * Parse the cg0.g current-preset list: [type][count][preset...]. Official
     * semantics: an unknown preset byte or a count that disagrees with the
     * remaining length rejects the whole frame (TandemException) — the previous
     * state is kept. Dropping only the unknown entry would shift every
     * subsequent key's preset, misaligning both the display and the next write.
     */
    private fun parseAssignableSettingsPresets(
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse {
        fun reject() = ParsedTandemResponse.Unknown(null, SYSTEM_RET_PARAM.unsigned, payload, raw)
        val count = payload.getOrNull(1)?.unsigned ?: 0
        if (count < 1 || payload.size != 2 + count) {
            return reject()
        }
        val presets = payload.drop(2).take(count).map { byte ->
            AssignableSettingsPreset.entries.firstOrNull { it.code == byte }
                ?.takeIf { it != AssignableSettingsPreset.OUT_OF_RANGE }
                ?: return reject()
        }
        return ParsedTandemResponse.AssignableSettingsPresets(
            presets = presets,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    /** Parse the cg0.h enable/disable list: [type][count][status...] */
    private fun parseAssignableSettingsStatus(
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse {
        val count = payload.getOrNull(1)?.unsigned ?: 0
        val enabled = payload.drop(2).take(count).map { it == AssignableSettingsEnableDisable.ENABLE.code }
        return ParsedTandemResponse.AssignableSettingsStatus(
            enabled = enabled,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    /** Parse the cg0.e extended mapping list or Speak-to-Chat extended parameters. */
    private fun parseSystemRetExtendedParam(
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse {
        val typeCode = payload.firstOrNull()
        if (typeCode == SystemInquiredType.SMART_TALKING_MODE_TYPE1.code) {
            val sensitivity = payload.getOrNull(1)?.let { s ->
                SmartTalkingDetectionSensitivity.entries.firstOrNull { it.code == s }
            }
            val voiceFocus = payload.getOrNull(2)?.unsigned == 0x00
            val modeOutTime = payload.getOrNull(3)?.let { m ->
                SmartTalkingModeOutTime.entries.firstOrNull { it.code == m }
            }
            return ParsedTandemResponse.SpeakToChatParam(
                sensitivity = sensitivity,
                voiceFocus = voiceFocus,
                modeOutTime = modeOutTime,
                values = payload.unsignedList(),
                raw = raw,
            )
        }
        if (typeCode == SystemInquiredType.SMART_TALKING_MODE_TYPE2.code) {
            val sensitivity = payload.getOrNull(1)?.let { s ->
                SmartTalkingDetectionSensitivity.entries.firstOrNull { it.code == s }
            }
            val modeOutTime = payload.getOrNull(2)?.let { m ->
                SmartTalkingModeOutTime.entries.firstOrNull { it.code == m }
            }
            return ParsedTandemResponse.SpeakToChatParam(
                sensitivity = sensitivity,
                modeOutTime = modeOutTime,
                values = payload.unsignedList(),
                raw = raw,
            )
        }
        val count = payload.getOrNull(1)?.unsigned ?: 0
        var offset = 2
        val mappings = mutableListOf<AssignableSettingsMapping>()
        repeat(count) {
            if (offset + 2 > payload.size) return@repeat
            val preset = AssignableSettingsPreset.entries.firstOrNull { it.code == payload[offset] }
            val mappingCount = payload[offset + 1].unsigned
            offset += 2
            if (preset == null || preset == AssignableSettingsPreset.OUT_OF_RANGE) return@repeat
            val actions = mutableListOf<AssignableSettingsActionFunction>()
            repeat(mappingCount) {
                if (offset + 2 > payload.size) return@repeat
                val action = AssignableSettingsAction.entries.firstOrNull { it.code == payload[offset] }
                val function = AssignableSettingsFunction.entries.firstOrNull { it.code == payload[offset + 1] }
                offset += 2
                if (action != null && function != null &&
                    action != AssignableSettingsAction.OUT_OF_RANGE &&
                    function != AssignableSettingsFunction.OUT_OF_RANGE
                ) {
                    actions += AssignableSettingsActionFunction(action, function)
                }
            }
            if (actions.isNotEmpty()) mappings += AssignableSettingsMapping(preset, actions)
        }
        return ParsedTandemResponse.AssignableSettingsExtendedParam(
            mappings = mappings,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    private fun parseSystemRetParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse =
        when (payload.firstOrNull()) {
            SystemInquiredType.SMART_TALKING_MODE_TYPE1.code,
            SystemInquiredType.SMART_TALKING_MODE_TYPE2.code -> {
                val enabled = payload.getOrNull(1)?.unsigned == 0x00
                ParsedTandemResponse.SpeakToChatParam(
                    enabled = enabled,
                    values = payload.unsignedList(),
                    raw = raw,
                )
            }
            SystemInquiredType.ASSIGNABLE_SETTINGS.code,
            SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION.code ->
                parseAssignableSettingsPresets(payload, raw)
            SystemInquiredType.QUICK_ACCESS.code -> parseQuickAccess(payload, raw)
            SystemInquiredType.WEARING_STATUS_DETECTOR.code -> parseWearingStatus(payload, raw)
            else -> ParsedTandemResponse.Unknown(null, SYSTEM_RET_PARAM.unsigned, payload, raw)
        }

    private fun parseQuickAccess(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val count = payload.getOrNull(1)?.unsigned ?: 0
        val functionCodes = payload.drop(2).take(count).map { it.unsigned }
        val functions = functionCodes.mapNotNull { code ->
            QuickAccessFunction.entries.firstOrNull { it.code.unsigned == code }
                ?.takeIf { it != QuickAccessFunction.OUT_OF_RANGE }
        }
        return ParsedTandemResponse.QuickAccess(
            functions = functions,
            functionCodes = functionCodes,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    private fun parseSystemStatus(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        return when (payload.firstOrNull()) {
            SystemInquiredType.SMART_TALKING_MODE_TYPE1.code,
            SystemInquiredType.SMART_TALKING_MODE_TYPE2.code -> {
                // Byte 1 is EnableDisable = control availability, not the toggle
                // (official UI binds the switch to RET_PARAM's value); the only
                // toggle-relevant field here is the effect status.
                val effect = payload.getOrNull(2)?.let { e ->
                    SmartTalkingEffectStatus.entries.firstOrNull { it.code == e }
                }
                ParsedTandemResponse.SpeakToChatStatus(
                    effectStatus = effect,
                    values = payload.unsignedList(),
                    raw = raw,
                )
            }
            SystemInquiredType.QUICK_ACCESS.code ->
                ParsedTandemResponse.QuickAccessStatus(
                    enabled = payload.getOrNull(1)?.unsigned == AssignableSettingsEnableDisable.ENABLE.code.unsigned,
                    values = payload.unsignedList(),
                    raw = raw,
                )
            SystemInquiredType.ASSIGNABLE_SETTINGS.code,
            SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION.code ->
                parseAssignableSettingsStatus(payload, raw)
            else -> ParsedTandemResponse.Unknown(null, SYSTEM_RET_STATUS.unsigned, payload, raw)
        }
    }

    private fun parseWearingStatus(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val status = payload.getOrNull(1)?.let { s ->
            WearingDetectionStatus.entries.firstOrNull { it.code == s }
        }
        val result = payload.getOrNull(2)?.let { r ->
            WearingDetectionResult.entries.firstOrNull { it.code == r }
        }
        return ParsedTandemResponse.WearingStatus(
            status = status,
            result = result,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    /**
     * RET_CAPABILITY (0xD1). Engine payload (dataType+command stripped):
     * `[0]=type, [1]=GsSettingType, [2]=GsStringFormat, [3]=titleLen,
     * [4..4+len)=title, [4+len]=descLen, [5+len..]=desc`.
     * Mirrors SC `if0/h` (title/desc length-prefixed strings, `jf0/a`).
     */
    private fun parseGeneralSettingCapability(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.getOrNull(0)?.unsigned ?: return ParsedTandemResponse.Unknown(
            null, GS_RET_CAPABILITY.unsigned, payload, raw,
        )
        val settingType = payload.getOrNull(1)?.unsigned
        val stringFormat = payload.getOrNull(2)?.unsigned
        val titleLen = payload.getOrNull(3)?.unsigned ?: return ParsedTandemResponse.Unknown(
            null, GS_RET_CAPABILITY.unsigned, payload, raw,
        )
        val titleStart = 4
        val titleEnd = titleStart + titleLen
        if (payload.size < titleEnd) {
            return ParsedTandemResponse.Unknown(null, GS_RET_CAPABILITY.unsigned, payload, raw)
        }
        val title = payload.copyOfRange(titleStart, titleEnd).decodeToString()
        val descStart = titleEnd + 1
        val descLen = payload.getOrNull(titleEnd)?.unsigned ?: 0
        val desc = if (payload.size >= descStart + descLen) {
            payload.copyOfRange(descStart, descStart + descLen).decodeToString()
        } else {
            ""
        }
        return ParsedTandemResponse.GeneralSettingCapability(
            type = type,
            settingType = settingType,
            stringFormat = stringFormat,
            title = title,
            description = desc,
            raw = raw,
        )
    }

    /** RET/NTFY_STATUS (0xD3/0xD5): `[type][EnableDisable]` (ENABLE=0x00). */
    private fun parseGeneralSettingStatus(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.getOrNull(0)?.unsigned
        val enabled = payload.getOrNull(1)?.let { it == GS_ENABLE }
        return ParsedTandemResponse.GeneralSettingStatus(
            type = type,
            enabled = enabled,
            raw = raw,
        )
    }

    /** RET/NTFY_PARAM (0xD7/0xD9): `[type][settingType][value]` (boolean ON=0x00). */
    private fun parseGeneralSettingParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.getOrNull(0)?.unsigned
        val settingType = payload.getOrNull(1)?.unsigned
        val value = payload.getOrNull(2)?.unsigned
        val on = when (settingType) {
            GS_SETTING_TYPE_BOOLEAN.unsigned -> value?.let { it == GS_VALUE_ON.unsigned }
            else -> null
        }
        return ParsedTandemResponse.GeneralSettingParam(
            type = type,
            settingType = settingType,
            on = on,
            raw = raw,
        )
    }

    /** ALERT_NTFY_PARAM (0x99). Payload shapes mirror bf0.C5682f/C5689m/C5690n. */
    private fun parseAlertParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val inquiredType = payload.getOrNull(0)
        val messageType = payload.getOrNull(1)?.unsigned
        if (messageType == null) {
            return ParsedTandemResponse.Unknown(inquiredType?.unsigned, ALERT_NTFY_PARAM.unsigned, payload, raw)
        }
        return when (inquiredType) {
            ALERT_INQUIRED_TYPE_FIXED_MESSAGE,
            ALERT_INQUIRED_TYPE_APP_BECOMES_FOREGROUND -> {
                val action = payload.getOrNull(2)?.unsigned
                    ?: return ParsedTandemResponse.Unknown(inquiredType.unsigned, ALERT_NTFY_PARAM.unsigned, payload, raw)
                if (inquiredType == ALERT_INQUIRED_TYPE_FIXED_MESSAGE) {
                    ParsedTandemResponse.AlertFixedMessage(messageType, action, raw)
                } else {
                    ParsedTandemResponse.AlertForegroundMessage(messageType, action, raw)
                }
            }
            ALERT_INQUIRED_TYPE_FIXED_MESSAGE_WITH_LEFT_RIGHT -> {
                val selected = payload.getOrNull(2)?.unsigned
                    ?: return ParsedTandemResponse.Unknown(inquiredType.unsigned, ALERT_NTFY_PARAM.unsigned, payload, raw)
                ParsedTandemResponse.AlertFixedMessageWithLeftRightSelection(messageType, selected, raw)
            }
            ALERT_INQUIRED_TYPE_FLEXIBLE_MESSAGE -> {
                val count = payload.getOrNull(2)?.unsigned ?: return ParsedTandemResponse.Unknown(inquiredType.unsigned, ALERT_NTFY_PARAM.unsigned, payload, raw)
                val end = 3 + count
                if (payload.size <= end) return ParsedTandemResponse.Unknown(inquiredType.unsigned, ALERT_NTFY_PARAM.unsigned, payload, raw)
                ParsedTandemResponse.AlertFlexibleMessage(
                    messageType = messageType,
                    itemCodes = payload.copyOfRange(3, end).map { it.unsigned },
                    actionType = payload[end].unsigned,
                    raw = raw,
                )
            }
            else -> ParsedTandemResponse.Unknown(inquiredType?.unsigned, ALERT_NTFY_PARAM.unsigned, payload, raw)
        }
    }

    private fun parseAlertStatus(
        payload: ByteArray,
        raw: ByteArray,
        isNotification: Boolean,
    ): ParsedTandemResponse {
        if (payload.getOrNull(0)?.unsigned != ALERT_INQUIRED_TYPE_LE_AUDIO.unsigned) {
            return ParsedTandemResponse.Unknown(null, ALERT_RET_STATUS.unsigned, payload, raw)
        }
        val confirmation = payload.getOrNull(1)?.unsigned
            ?: return ParsedTandemResponse.Unknown(ALERT_INQUIRED_TYPE_LE_AUDIO.unsigned, ALERT_RET_STATUS.unsigned, payload, raw)
        return ParsedTandemResponse.AlertLeAudioNotification(confirmation, isNotification, raw)
    }
}
