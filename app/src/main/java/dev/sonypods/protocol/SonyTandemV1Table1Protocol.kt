package dev.sonypods.protocol

import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR

object SonyTandemV1Table1Protocol {
    // ── Connection / device information (V1) ──
    private const val CONNECT_GET_PROTOCOL_INFO: Byte = 0x00
    private const val CONNECT_RET_PROTOCOL_INFO: Byte = 0x01
    private const val CONNECT_GET_CAPABILITY_INFO: Byte = 0x02
    private const val CONNECT_RET_CAPABILITY_INFO: Byte = 0x03
    // SC V1 InquiredType FIXED_VALUE = 0x00 (`qe0.C26552h.mo65499f` sends
    // [cmd 0x02][FIXED_VALUE]); RET layout identical to V2.
    private const val CONNECT_GET_CAPABILITY_INFO_FIXED_VALUE: Byte = 0x00
    private const val CONNECT_GET_SUPPORT_FUNCTION: Byte = 0x06
    private const val CONNECT_RET_SUPPORT_FUNCTION: Byte = 0x07
    private const val CONNECT_RET_DEVICE_INFO: Byte = 0x05
    private const val CONNECT_GET_DEVICE_INFO: Byte = 0x04

    // ── Common / Battery (V1) ──
    private const val COMMON_GET_BATTERY_LEVEL: Byte = 0x10
    private const val COMMON_RET_BATTERY_LEVEL: Byte = 0x11
    private const val COMMON_NTFY_BATTERY_LEVEL: Byte = 0x13
    private const val COMMON_SET_POWER_OFF: Byte = 0x22
    private const val POWER_OFF_FIXED_VALUE: Byte = 0x00
    private const val POWER_OFF_USER_REQUEST: Byte = 0x01

    // ── Playback (V1) ──
    private const val PLAY_GET_STATUS: Byte = 0xA2.toByte()
    private const val PLAY_RET_STATUS: Byte = 0xA3.toByte()
    private const val PLAY_SET_STATUS: Byte = 0xA4.toByte()
    private const val PLAY_NTFY_STATUS: Byte = 0xA5.toByte()
    private const val PLAY_GET_PARAM: Byte = 0xA6.toByte()
    private const val PLAY_RET_PARAM: Byte = 0xA7.toByte()
    private const val PLAY_SET_PARAM: Byte = 0xA8.toByte()
    private const val PLAY_NTFY_PARAM: Byte = 0xA9.toByte()
    private const val PLAYBACK_CONTROLLER: Byte = 0x01
    private const val VALUE_ENABLE: Byte = 0x00

    // ── NC/ASM (V1 / shared) ──
    private const val NCASM_GET_PARAM: Byte = 0x66
    private const val NCASM_RET_PARAM: Byte = 0x67
    private const val NCASM_SET_PARAM: Byte = 0x68
    private const val NCASM_NTFY_PARAM: Byte = 0x69
    private const val NCASM_EFFECT_OFF: Byte = 0x00
    private const val NCASM_EFFECT_ADJUSTMENT_COMPLETION: Byte = 0x11
    private const val NCASM_SETTING_DUAL_SINGLE_OFF: Byte = 0x02
    private const val NCASM_ASM_SETTING_LEVEL_ADJUSTMENT: Byte = 0x01
    private const val NC_VALUE_OFF: Byte = 0x00
    private const val NC_VALUE_ON_SINGLE: Byte = 0x01
    private const val NC_VALUE_ON_DUAL: Byte = 0x02

    // ── EQ/EBB (V1 command bytes are identical to V2; only inquired-type sub-codes differ) ──
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
    private const val PLAY_GET_CAPABILITY: Byte = 0xA0.toByte()
    private const val PLAY_RET_CAPABILITY: Byte = 0xA1.toByte()
    private const val SYSTEM_GET_CAPABILITY: Byte = 0xF0.toByte()
    private const val SYSTEM_RET_CAPABILITY: Byte = 0xF1.toByte()
    private const val SYSTEM_GET_STATUS: Byte = 0xF2.toByte()
    private const val SYSTEM_RET_STATUS: Byte = 0xF3.toByte()
    private const val SYSTEM_NTFY_STATUS: Byte = 0xF5.toByte()
    private const val SYSTEM_GET_PARAM: Byte = 0xF6.toByte()
    private const val SYSTEM_RET_PARAM: Byte = 0xF7.toByte()
    private const val SYSTEM_SET_PARAM: Byte = 0xF8.toByte()
    private const val SYSTEM_NTFY_PARAM: Byte = 0xF9.toByte()
    private const val SYSTEM_GET_EXT_PARAM: Byte = 0xFA.toByte()
    private const val SYSTEM_RET_EXT_PARAM: Byte = 0xFB.toByte()
    private const val SYSTEM_SET_EXT_PARAM: Byte = 0xFC.toByte()
    private const val SYSTEM_NTFY_EXT_PARAM: Byte = 0xFD.toByte()

    // V1 inquired-type sub-codes
    private const val V1_PRESET_EQ: Byte = 0x01
    private const val V1_EBB: Byte = 0x02
    private const val V1_PRESET_EQ_NONCUSTOMIZABLE: Byte = 0x03

    // ── Smart Talking Mode (V1) ──
    // Official V1 layout (SC `ve0.d`/`qe0.r3` SET_PARAM, `ve0.a`+`ve0.b`/`qe0.q3`
    // SET_EXT_PARAM, `ve0.c`/`ve0.e` RET payloads). V1 differs from V2 in three
    // ways: a single SMART_TALKING_MODE type byte 0x05 (0x02 is POWER_SAVING_MODE
    // on V1), a parameter-type / detail-type byte precedes the values, and the
    // ON/OFF polarity (ON=0x01) is the inverse of V2's OnOffSettingValue.
    private const val V1_SYSTEM_SMART_TALKING_MODE: Byte = 0x05
    private const val V1_SMART_TALKING_SETTING_TYPE_ON_OFF: Byte = 0x00
    private const val V1_SMART_TALKING_DETAIL_TYPE_1: Byte = 0x00
    private const val V1_SMART_TALKING_PARAM_MODE_ON_OFF: Byte = 0x01
    private const val V1_ON: Byte = 0x01
    private const val V1_OFF: Byte = 0x00

    // ── Device information ──

    fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_DEVICE_INFO, byteArrayOf(type.code))

    /**
     * CONNECT_GET_CAPABILITY_INFO (0x02) with the fixed
     * `[type 0x00 FIXED_VALUE]` payload, identical to V2 (SC `qe0.C26552h`).
     * Response (RET_CAPABILITY_INFO 0x03) carries the capability counter +
     * identifier used to decide whether the per-domain capability probe may be
     * omitted on reconnection.
     */
    fun buildGetCapabilityInfo(): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_CAPABILITY_INFO, byteArrayOf(CONNECT_GET_CAPABILITY_INFO_FIXED_VALUE))

    /**
     * Parse a V1 CONNECT_RET_CAPABILITY_INFO payload (0x03); layout shared
     * with V2, see [parseConnectRetCapabilityInfoPayload].
     */
    fun parseConnectRetCapabilityInfo(payload: ByteArray): ParsedTandemResponse.ConnectCapabilityInfo? =
        parseConnectRetCapabilityInfoPayload(payload)

    /**
     * CONNECT_GET_PROTOCOL_INFO (0x00) with the fixed
     * `[type 0x00 FIXED_VALUE]` payload, identical to V2 (SC `qe0.C26634y`
     * / `ff0.C16469c`, body `[cmd 0x00][type 0x00]`). Sent unconditionally as
     * the first exchange after transport ready.
     */
    fun buildGetProtocolInfo(): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_PROTOCOL_INFO, byteArrayOf(0x00))

    // ── Sound-quality badges (V1 dedicated COMMON commands; SC `qe0.C26527c` /
    // `C26568k0` builders, bodies `[cmd][FIXED_VALUE 0x00]`) ──
    private const val COMMON_GET_AUDIO_CODEC: Byte = 0x18
    private const val COMMON_RET_AUDIO_CODEC: Byte = 0x19
    private const val COMMON_NTFY_AUDIO_CODEC: Byte = 0x1B
    private const val COMMON_GET_UPSCALING_EFFECT: Byte = 0x14
    private const val COMMON_RET_UPSCALING_EFFECT: Byte = 0x15
    private const val COMMON_NTFY_UPSCALING_EFFECT: Byte = 0x17
    /** V1 carries a single FIXED_VALUE (0x00) inquired type for both domains. */
    private const val V1_COMMON_FIXED_VALUE: Byte = 0x00

    /** COMMON_GET_AUDIO_CODEC — codec badge source on V1 models. */
    fun buildGetAudioCodecStatus(): ByteArray =
        SonyTandemFrame.message(COMMON_GET_AUDIO_CODEC, byteArrayOf(V1_COMMON_FIXED_VALUE))

    /** COMMON_GET_UPSCALING_EFFECT — live DSEE badge source on V1 models. */
    fun buildGetUpscalingEffectStatus(): ByteArray =
        SonyTandemFrame.message(COMMON_GET_UPSCALING_EFFECT, byteArrayOf(V1_COMMON_FIXED_VALUE))

    /**
     * Parse a V1 CONNECT_RET_PROTOCOL_INFO payload (0x01); see the shared
     * [parseProtocolInfoPayload] for the byte layout.
     */
    fun parseProtocolInfo(payload: ByteArray): ParsedTandemResponse.ProtocolInfo? =
        parseProtocolInfoPayload(payload)

    /**
     * CONNECT_GET_SUPPORT_FUNCTION (0x06) with a single FIXED_VALUE (0x00)
     * byte, identical to V2 (SC `qe0.C26538e0`).
     */
    fun buildGetSupportFunction(): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_SUPPORT_FUNCTION, byteArrayOf(0x00))

    /**
     * Parse a V1 CONNECT_RET_SUPPORT_FUNCTION payload (0x07). V1 carries a
     * flat single-byte FunctionType list (no order field); SC `qe0.C26610s2`
     * message body is
     *   [0]=0x07 command, [1]=FIXED_VALUE, [2]=count, [3..]=FunctionType bytes,
     * so the engine payload (dataType+command stripped) is
     *   [0]=0x00, [1]=count, [2+i]=code.
     * Unknown FunctionTypes are dropped (SC `FunctionType.fromByteCode` → NO_USE).
     */
    fun parseSupportFunction(payload: ByteArray): List<SonySupportedFunction> {
        if (payload.size < 2) return emptyList()
        val count = payload.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
        return buildList {
            for (i in 0 until count) {
                val code = payload.getOrNull(2 + i) ?: break
                if (SonyV1FunctionType.fromByteCode(code) != SonyV1FunctionType.OUT_OF_RANGE) {
                    add(SonySupportedFunction(code, order = i))
                }
            }
        }
    }

    // ── Battery ──

    fun buildGetBatteryStatus(type: PowerInquiredType = PowerInquiredType.BATTERY): ByteArray =
        SonyTandemFrame.message(COMMON_GET_BATTERY_LEVEL, byteArrayOf(type.code))

    /** Sound Connect V1 Table1 USER_POWER_OFF: 0E 22 00 01. */
    fun buildPowerOff(): ByteArray =
        SonyTandemFrame.message(
            COMMON_SET_POWER_OFF,
            byteArrayOf(POWER_OFF_FIXED_VALUE, POWER_OFF_USER_REQUEST),
        )

    // ── NC/ASM ──

    fun buildGetNcAsmParam(): ByteArray =
        SonyTandemFrame.message(
            NCASM_GET_PARAM,
            byteArrayOf(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM.code),
        )

    fun buildSetNoiseControlMode(
        controlMode: NoiseControlMode,
        ambientLevel: Int = 10,
        ambientMode: AmbientSoundMode = AmbientSoundMode.NORMAL,
        windNoiseReduction: Boolean = false,
    ): ByteArray {
        val effect = if (controlMode == NoiseControlMode.OFF) {
            NCASM_EFFECT_OFF
        } else {
            NCASM_EFFECT_ADJUSTMENT_COMPLETION
        }
        val ncValue = when (controlMode) {
            NoiseControlMode.NOISE_CANCELLING -> if (windNoiseReduction) NC_VALUE_ON_SINGLE else NC_VALUE_ON_DUAL
            NoiseControlMode.AMBIENT_SOUND,
            NoiseControlMode.OFF -> NC_VALUE_OFF
        }
        val asmLevel = if (controlMode == NoiseControlMode.AMBIENT_SOUND) {
            ambientLevel.coerceIn(1, 20).toByte()
        } else {
            0x00
        }
        return SonyTandemFrame.message(
            NCASM_SET_PARAM,
            byteArrayOf(
                NcAsmInquiredType.V1_TABLE_SET1_NC_ASM.code,
                effect,
                NCASM_SETTING_DUAL_SINGLE_OFF,
                ncValue,
                NCASM_ASM_SETTING_LEVEL_ADJUSTMENT,
                ambientMode.code,
                asmLevel,
            ),
        )
    }

    // ── EQ/EBB (V1 type codes) ──

    /** Convert a V2 EqEbbInquiredType to its V1 byte code. */
    fun v1TypeCode(v2: EqEbbInquiredType): Byte = when (v2) {
        EqEbbInquiredType.PRESET_EQ -> V1_PRESET_EQ
        EqEbbInquiredType.EBB -> V1_EBB
        EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE -> V1_PRESET_EQ_NONCUSTOMIZABLE
        else -> throw IllegalArgumentException("Unsupported V1 EQ/EBB type: $v2")
    }

    fun buildGetEqEbbStatus(type: EqEbbInquiredType): ByteArray =
        SonyTandemFrame.message(EQEBB_GET_STATUS, byteArrayOf(v1TypeCode(type)))

    fun buildGetEqEbbParam(type: EqEbbInquiredType): ByteArray =
        SonyTandemFrame.message(EQEBB_GET_PARAM, byteArrayOf(v1TypeCode(type)))

    fun buildGetEqEbbExtendedInfo(type: EqEbbInquiredType): ByteArray =
        SonyTandemFrame.message(EQEBB_GET_EXTENDED_INFO, byteArrayOf(v1TypeCode(type)))

    // ── Capability-probe GET_CAPABILITY builders (V1 SC `qe0.C26592p` NCASM,
    //    `qe0.C26572l` EQEBB, `qe0.C26622v` PLAY) ──

    fun buildGetNcAsmCapability(type: NcAsmInquiredType): ByteArray =
        SonyTandemFrame.message(NCASM_GET_CAPABILITY, byteArrayOf(type.code))

    fun buildGetEqEbbCapability(type: EqEbbInquiredType): ByteArray =
        SonyTandemFrame.message(EQEBB_GET_CAPABILITY, byteArrayOf(v1TypeCode(type), 0x00))

    fun buildGetPlayCapability(type: PlayInquiredType): ByteArray =
        SonyTandemFrame.message(PLAY_GET_CAPABILITY, byteArrayOf(type.code))

    fun buildGetSystemCapability(type: SystemInquiredType): ByteArray {
        // The shared enum carries the V2 type codes; on the V1 wire smart
        // talking is a single SMART_TALKING_MODE byte (0x05) and assignable
        // settings is 0x06 — where V2's 0x02/0x03 are POWER_SAVING_MODE and
        // CONTROL_BY_WEARING respectively.
        val typeCode = when (type) {
            SystemInquiredType.SMART_TALKING_MODE_TYPE1,
            SystemInquiredType.SMART_TALKING_MODE_TYPE2 -> V1_SYSTEM_SMART_TALKING_MODE
            SystemInquiredType.ASSIGNABLE_SETTINGS,
            SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION -> V1_SYSTEM_ASSIGNABLE_SETTINGS
            else -> type.code
        }
        return SonyTandemFrame.message(SYSTEM_GET_CAPABILITY, byteArrayOf(typeCode))
    }

    // ── Assignable Settings (V1) ──
    // Official V1 (`n00.b` sender, `te0.c`/`se0.d` payloads): a preset-only API.
    // The wire type is 0x06 (0x03 on V1 is CONTROL_BY_WEARING). Capability and
    // preset get/set share the shape `[0x06][count][value...]`; there is no
    // EXT_PARAM path — per-action assignment does not exist on V1 (official
    // `te0.b`/`te0.d` serialization throws UnsupportedOperationException and the
    // setter's action read/write are empty stubs).

    private const val V1_SYSTEM_ASSIGNABLE_SETTINGS: Byte = 0x06

    fun buildGetAssignableSettingsCapability(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_CAPABILITY, byteArrayOf(V1_SYSTEM_ASSIGNABLE_SETTINGS))

    fun buildGetAssignableSettingsStatus(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_STATUS, byteArrayOf(V1_SYSTEM_ASSIGNABLE_SETTINGS))

    fun buildGetAssignableSettingsPresets(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_PARAM, byteArrayOf(V1_SYSTEM_ASSIGNABLE_SETTINGS))

    /** SET_PARAM = `[0x06][count][preset...]` in capability key order (SC `se0.d`). */
    fun buildSetAssignableSettingsPresets(presets: List<AssignableSettingsPreset>): ByteArray {
        require(presets.isNotEmpty()) { "Assignable preset list is empty" }
        require(presets.none { it == AssignableSettingsPreset.OUT_OF_RANGE }) {
            "Assignable preset is invalid"
        }
        return SonyTandemFrame.message(
            SYSTEM_SET_PARAM,
            byteArrayOf(V1_SYSTEM_ASSIGNABLE_SETTINGS, presets.size.toByte()) + presets.map { it.code },
        )
    }

    // ── Smart Talking Mode (V1): GET bodies are just [SMART_TALKING_MODE 0x05],
    // the V1 type byte — never V2's TYPE1/TYPE2 codes (SC `qe0.C26558i0` & co). ──

    fun buildGetSpeakToChatStatus(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_STATUS, byteArrayOf(V1_SYSTEM_SMART_TALKING_MODE))

    fun buildGetSpeakToChatParam(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_PARAM, byteArrayOf(V1_SYSTEM_SMART_TALKING_MODE))

    fun buildGetSpeakToChatExtParam(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_EXT_PARAM, byteArrayOf(V1_SYSTEM_SMART_TALKING_MODE))

    /** SET_PARAM = `[0x05][MODE_ON_OFF 0x01][ON 0x01 / OFF 0x00]` (SC `ve0.d`). */
    fun buildSetSpeakToChatEnabled(enabled: Boolean): ByteArray =
        SonyTandemFrame.message(
            SYSTEM_SET_PARAM,
            byteArrayOf(
                V1_SYSTEM_SMART_TALKING_MODE,
                V1_SMART_TALKING_PARAM_MODE_ON_OFF,
                if (enabled) V1_ON else V1_OFF,
            ),
        )

    /** SET_EXT_PARAM = `[0x05][DETAIL_TYPE_1 0x00][sensitivity][voiceFocus ON 0x01/OFF 0x00][modeOutTime]` (SC `ve0.a`+`ve0.b`). */
    fun buildSetSpeakToChatExtParam(
        sensitivity: SmartTalkingDetectionSensitivity,
        modeOutTime: SmartTalkingModeOutTime,
        voiceFocus: Boolean = false,
    ): ByteArray =
        SonyTandemFrame.message(
            SYSTEM_SET_EXT_PARAM,
            byteArrayOf(
                V1_SYSTEM_SMART_TALKING_MODE,
                V1_SMART_TALKING_DETAIL_TYPE_1,
                sensitivity.code,
                if (voiceFocus) V1_ON else V1_OFF,
                modeOutTime.code,
            ),
        )

    fun buildSetEqPreset(
        preset: EqPresetId,
        type: EqEbbInquiredType,
        bandSteps: List<Int> = emptyList(),
    ): ByteArray =
        SonyTandemFrame.message(
            EQEBB_SET_PARAM,
            byteArrayOf(v1TypeCode(type), preset.code, bandSteps.size.toByte()) +
                bandSteps.map { it.coerceIn(0, 255).toByte() }.toByteArray(),
        )

    fun buildSetClearBass(level: Int): ByteArray =
        SonyTandemFrame.message(
            EQEBB_SET_PARAM,
            byteArrayOf(V1_EBB, level.coerceIn(-127, 127).toByte()),
        )

    // ── Playback ──

    fun buildGetPlaybackStatus(): ByteArray =
        SonyTandemFrame.message(PLAY_GET_STATUS, byteArrayOf(PLAYBACK_CONTROLLER))

    fun buildPlayback(control: PlaybackControl): ByteArray =
        SonyTandemFrame.message(
            PLAY_SET_STATUS,
            byteArrayOf(PLAYBACK_CONTROLLER, VALUE_ENABLE, control.code),
        )

    fun buildGetPlaybackParam(dataType: PlaybackDetailedDataType): ByteArray =
        SonyTandemFrame.message(PLAY_GET_PARAM, byteArrayOf(PLAYBACK_CONTROLLER, dataType.code))

    fun buildSetPlaybackVolume(volume: Int): ByteArray =
        SonyTandemFrame.message(
            PLAY_SET_PARAM,
            byteArrayOf(PLAYBACK_CONTROLLER, PlaybackDetailedDataType.VOLUME.code, volume.coerceIn(0, 255).toByte()),
        )

    // ── Parse ──

    fun parse(raw: ByteArray): ParsedTandemResponse {
        val normalized = if (raw.firstOrNull() == DATA_MDR) raw else byteArrayOf(DATA_MDR) + raw
        val command = normalized.getOrNull(1)
        val payload = if (normalized.size > 2) normalized.copyOfRange(2, normalized.size) else byteArrayOf()
        return when (command) {
            CONNECT_RET_PROTOCOL_INFO -> parseProtocolInfo(payload)
                ?: unknown(command, payload, raw)
            CONNECT_RET_SUPPORT_FUNCTION -> ParsedTandemResponse.SupportFunction(
                functions = parseSupportFunction(payload),
                raw = raw,
            )
            CONNECT_RET_CAPABILITY_INFO -> parseConnectRetCapabilityInfo(payload)
                ?: unknown(command, payload, raw)
            NCASM_RET_CAPABILITY -> ParsedTandemResponse.NcAsmCapabilityInfo(
                inquiredTypeCode = payload.firstOrNull()?.unsigned,
                // SC `qe0.d2$c` body for the V1 NC/ASM type 0x02:
                // [type][NcAsmSettingType][ncValue][AsmSettingType][count][ids...];
                // the setting type decides whether single-mic wind-noise NC exists.
                ncAsmSettingType = payload.getOrNull(1)?.unsigned,
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
            SYSTEM_RET_CAPABILITY -> if (payload.firstOrNull() == V1_SYSTEM_ASSIGNABLE_SETTINGS) {
                parseAssignableSettingsCapability(payload, raw)
            } else {
                ParsedTandemResponse.CapabilityInfo(
                    domain = "SYSTEM",
                    inquiredTypeCode = payload.firstOrNull()?.unsigned,
                    values = payload.unsignedList(),
                    raw = raw,
                )
            }
            CONNECT_RET_DEVICE_INFO -> parseDeviceInfo(payload, raw)
            COMMON_RET_BATTERY_LEVEL -> parseBattery(payload, raw)
            COMMON_NTFY_BATTERY_LEVEL -> if (looksLikeV1BatteryPayload(payload)) {
                parseBattery(payload, raw)
            } else {
                unknown(command, payload, raw)
            }
            NCASM_RET_PARAM,
            NCASM_NTFY_PARAM -> parseNoiseControl(command, payload, raw)
            EQEBB_RET_STATUS, EQEBB_NTFY_STATUS,
            EQEBB_RET_PARAM, EQEBB_NTFY_PARAM ->
                SonyEqEbbPayloadParser.parse(EqEbbPayloadVersion.V1, command, payload, raw)
            EQEBB_RET_EXTENDED_INFO ->
                SonyEqEbbPayloadParser.parseExtendedInfo(EqEbbPayloadVersion.V1, payload, raw)
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
            PLAY_RET_PARAM -> parsePlayRetParam(payload, raw)
            PLAY_NTFY_PARAM -> parsePlayNtfyParam(payload, raw)
            SYSTEM_RET_STATUS, SYSTEM_NTFY_STATUS -> parseSystemStatus(payload, raw)
            SYSTEM_RET_PARAM, SYSTEM_NTFY_PARAM -> parseSystemRetParam(payload, raw)
            SYSTEM_RET_EXT_PARAM, SYSTEM_NTFY_EXT_PARAM -> parseSystemRetExtendedParam(payload, raw)
            COMMON_RET_AUDIO_CODEC, COMMON_NTFY_AUDIO_CODEC -> parseAudioCodecStatus(command, payload, raw)
            COMMON_RET_UPSCALING_EFFECT, COMMON_NTFY_UPSCALING_EFFECT ->
                parseUpscalingEffectStatus(command, payload, raw)
            else -> unknown(command, payload, raw)
        }
    }

    /**
     * RET/NTFY_STATUS = `[0x05][CommonStatus][effectStatus NOT_ACTIVE 0x00 / ACTIVE 0x01]`
     * (SC `ve0.e`). The CommonStatus byte is the control's *availability*, not the
     * on/off toggle: official `l50.a`/`k50.a` feed it to `setEnabled()` (graying)
     * while the switch itself follows RET_PARAM's value.
     */
    private fun parseSystemStatus(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        return when (payload.firstOrNull()) {
            V1_SYSTEM_SMART_TALKING_MODE -> {
                val effect = payload.getOrNull(2)?.let { e ->
                    SmartTalkingEffectStatus.entries.firstOrNull { it.code == e }
                }
                ParsedTandemResponse.SpeakToChatStatus(
                    effectStatus = effect,
                    values = payload.unsignedList(),
                    raw = raw,
                )
            }
            // `[0x06][count][CommonStatus...]` — per-key availability (SC `se0.e`).
            V1_SYSTEM_ASSIGNABLE_SETTINGS -> parseAssignableSettingsStatus(payload, raw)
            else -> unknown(SYSTEM_RET_STATUS, payload, raw)
        }
    }

    /**
     * RET_PARAM = `[0x05][settingType ON_OFF 0x00][value]` (SC `ve0.c`);
     * NTFY_PARAM = `[0x05][paramType][value]` (SC `ve0.d`). Both put the mode
     * value at index 2 with V1 polarity (ON=0x01); preview-only notifies are
     * not tracked.
     */
    private fun parseSystemRetParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        return when {
            payload.firstOrNull() == V1_SYSTEM_SMART_TALKING_MODE &&
                (payload.getOrNull(1) == V1_SMART_TALKING_SETTING_TYPE_ON_OFF ||
                    payload.getOrNull(1) == V1_SMART_TALKING_PARAM_MODE_ON_OFF) -> {
                val enabled = payload.getOrNull(2)?.unsigned == 0x01
                ParsedTandemResponse.SpeakToChatParam(
                    enabled = enabled,
                    values = payload.unsignedList(),
                    raw = raw,
                )
            }
            payload.firstOrNull() == V1_SYSTEM_ASSIGNABLE_SETTINGS -> {
                parseAssignableSettingsPresets(payload, raw)
            }
            else -> unknown(SYSTEM_RET_PARAM, payload, raw)
        }
    }

    /** RET/NTFY_PARAM for assignable = `[0x06][count][preset...]` in capability
     * key order (SC `se0.d`). Off-table preset bytes keep the position (as
     * OUT_OF_RANGE) so index alignment with the capability keys survives. */
    private fun parseAssignableSettingsPresets(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val count = payload.getOrNull(1)?.unsigned ?: 0
        val presets = payload.drop(2).take(count).map { byte ->
            AssignableSettingsPreset.entries.firstOrNull { it.code == byte }
                ?: AssignableSettingsPreset.OUT_OF_RANGE
        }
        return ParsedTandemResponse.AssignableSettingsPresets(
            presets = presets,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    /** RET/NTFY_STATUS for assignable = `[0x06][count][CommonStatus...]`, one
     * availability bit per key in capability order (SC `se0.e`; CommonStatus
     * ENABLE=0x00 / DISABLE=0x01). */
    private fun parseAssignableSettingsStatus(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val count = payload.getOrNull(1)?.unsigned ?: 0
        val enabled = payload.drop(2).take(count).map { it == V1_OFF }
        return ParsedTandemResponse.AssignableSettingsStatus(
            enabled = enabled,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    /**
     * RET_CAPABILITY for assignable (SC `qe0.t2$b` → `te0.c` nested grammar),
     * after dataType+command are stripped:
     * `[0x06][keyCount]{ [key][keyType][defaultPreset][presetCount]{ [preset][actionCount]{ [action][function] } } }`.
     *
     * Unlike V2 there is no single/multiple action split: every action entry is
     * exactly one `[action][function]` pair, and the default preset travels in
     * the key header. Entries with a non-positive count are skipped entirely
     * (`te0.b`/`te0.d` drop them too).
     */
    private fun parseAssignableSettingsCapability(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val keyCount = payload.getOrNull(1)?.unsigned ?: 0
        var offset = 2
        val keys = mutableListOf<ParsedTandemResponse.AssignableSettingsKeyCapability>()
        repeat(keyCount) {
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
                if (offset + 2 > payload.size) return@repeat
                val preset = AssignableSettingsPreset.entries.firstOrNull { it.code == payload[offset] }
                val actionCount = payload[offset + 1].unsigned
                offset += 2
                if (preset == null || preset == AssignableSettingsPreset.OUT_OF_RANGE || actionCount < 1) return@repeat
                val actions = mutableListOf<ParsedTandemResponse.AssignableSettingsActionCapability>()
                repeat(actionCount) {
                    if (offset + 2 > payload.size) return@repeat
                    val action = AssignableSettingsAction.entries.firstOrNull { it.code == payload[offset] }
                    val function = AssignableSettingsFunction.entries.firstOrNull { it.code == payload[offset + 1] }
                    offset += 2
                    if (action != null && function != null &&
                        action != AssignableSettingsAction.OUT_OF_RANGE &&
                        function != AssignableSettingsFunction.OUT_OF_RANGE
                    ) {
                        actions += ParsedTandemResponse.AssignableSettingsActionCapability(
                            action = action,
                            defaultFunction = function,
                            availableFunctions = listOf(function),
                        )
                    }
                }
                if (actions.isNotEmpty()) {
                    presets += preset
                    actionsByPreset[preset] = actions.sortedBy { it.action.code.unsigned }
                }
            }
            // Official `te0.b` drops a key whose preset list ended up empty
            // (`m106487h` requires at least one valid preset block).
            if (presets.isEmpty()) return@repeat
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

    /** RET/NTFY_EXT_PARAM = `[0x05][detailType TYPE_1 0x00][sensitivity][voiceFocus ON 0x01][modeOutTime]` (SC `ve0.a`+`ve0.b`). */
    private fun parseSystemRetExtendedParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        if (payload.firstOrNull() == V1_SYSTEM_SMART_TALKING_MODE &&
            payload.getOrNull(1) == V1_SMART_TALKING_DETAIL_TYPE_1
        ) {
            val sensitivity = payload.getOrNull(2)?.let { s ->
                SmartTalkingDetectionSensitivity.entries.firstOrNull { it.code == s }
            }
            val voiceFocus = payload.getOrNull(3)?.unsigned == 0x01
            val modeOutTime = payload.getOrNull(4)?.let { m ->
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
        return unknown(SYSTEM_RET_EXT_PARAM, payload, raw)
    }

    /** V1 codec body `[FIXED_VALUE 0x00][codecByte]`, exactly 2 bytes — SC
     * `qe0.p0` (`COMMON_NTFY_AUDIO_CODEC`). Unknown codec bytes degrade to a
     * null [ParsedTandemResponse.AudioCodecStatus.codec] (badge hidden). */
    private fun parseAudioCodecStatus(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val inquired = payload.getOrNull(0)?.unsigned
        val codecByte = payload.getOrNull(1)?.unsigned
        if (payload.size != 2 || inquired != 0x00 || codecByte == null) {
            return unknown(command, payload, raw)
        }
        // The V1 AudioCodec table has no LC3 entry; its lookup degrades any
        // off-table byte to UNSETTLED, which hides the badge upstream.
        val codec = when (SoundQualityCodec.fromCode(codecByte)) {
            SoundQualityCodec.LC3, null -> SoundQualityCodec.UNSETTLED
            else -> SoundQualityCodec.fromCode(codecByte)
        }
        return ParsedTandemResponse.AudioCodecStatus(
            codec = codec,
            isUnsolicited = command == COMMON_NTFY_AUDIO_CODEC,
            raw = raw,
        )
    }

    /** V1 effect body `[FIXED_VALUE 0x00][effectType][effectStatus]`, exactly
     * 3 bytes — SC `qe0.y2` (`COMMON_RET_UPSCALING_EFFECT`). */
    private fun parseUpscalingEffectStatus(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val inquired = payload.getOrNull(0)?.unsigned
        val typeByte = payload.getOrNull(1)?.unsigned
        val stateByte = payload.getOrNull(2)?.unsigned
        if (payload.size != 3 || inquired != 0x00 || typeByte == null || stateByte == null) {
            return unknown(command, payload, raw)
        }
        // The V1 table has no DSEE Ultimate (SC `UpscalingEffectType` v1), but the
        // shared enum keeps the byte mapping so a firmware that sends it still renders.
        return ParsedTandemResponse.UpscalingEffect(
            generation = DseeGeneration.fromCode(typeByte),
            state = DseeEffectState.fromCode(stateByte),
            isUnsolicited = command == COMMON_NTFY_UPSCALING_EFFECT,
            raw = raw,
        )
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

    private fun parseBattery(payload: ByteArray, raw: ByteArray): ParsedTandemResponse.Battery {
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

    private fun parseNoiseControl(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let(NcAsmInquiredType::fromV1Table1Code)
        if (type != NcAsmInquiredType.V1_TABLE_SET1_NC_ASM) {
            return ParsedTandemResponse.Unknown(
                dataType = DATA_MDR.unsigned,
                command = command.unsigned,
                payload = payload,
                raw = raw,
            )
        }
        val controlMode = when {
            payload.getOrNull(1) == NCASM_EFFECT_OFF -> NoiseControlMode.OFF
            payload.getOrNull(3) == NC_VALUE_ON_SINGLE ||
                payload.getOrNull(3) == NC_VALUE_ON_DUAL -> NoiseControlMode.NOISE_CANCELLING
            payload.getOrNull(3) == NC_VALUE_OFF &&
                payload.getOrNull(1) != NCASM_EFFECT_OFF -> NoiseControlMode.AMBIENT_SOUND
            else -> null
        }
        val ambientMode = payload.getOrNull(5)?.let { byte ->
            AmbientSoundMode.entries.firstOrNull { it.code == byte }
        }
        // SC `se0.b0` layout: [type][NcAsmEffect][NcAsmSettingType][ncValue]…
        // payload[3] is a three-state NcDualSingleValue only when the setting
        // type is DUAL_SINGLE_OFF; on ON_OFF devices 0x01 there merely means
        // "NC on" and must not read as wind-noise.
        val windNoiseReduction = if (payload.getOrNull(2) == NCASM_SETTING_DUAL_SINGLE_OFF) {
            when (payload.getOrNull(3)) {
                NC_VALUE_ON_SINGLE -> true
                NC_VALUE_ON_DUAL -> false
                else -> null
            }
        } else {
            null
        }
        return ParsedTandemResponse.NoiseControl(
            type = type,
            values = payload.drop(1).map { it.unsigned },
            enabled = controlMode == NoiseControlMode.NOISE_CANCELLING,
            ambientSoundEnabled = controlMode == NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = payload.getOrNull(6)?.unsigned,
            ambientMode = ambientMode,
            controlMode = controlMode,
            windNoiseReduction = windNoiseReduction,
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

    /** STATUS enable bit; guarded the same way, so a PLAY_MODE reply cannot grey out the
     * playback card. */
    private fun playStatusEnabled(payload: ByteArray): Boolean? =
        payload.getOrNull(1)
            ?.takeIf { payload.firstOrNull()?.unsigned in 1..3 }
            ?.let { it.unsigned == 0 }

    /** [type, volumeStep, PlaybackControlType, MetaDataDisplayType]; falls back to
     * the generic capability dump so the debug page still shows odd replies. */
    private fun parsePlayCapability(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        if (payload.size >= 4 && payload[0].unsigned == 0x01) {
            return ParsedTandemResponse.PlaybackCapability(
                inquiredTypeCode = payload[0].unsigned,
                musicVolumeStep = payload[1].unsigned,
                supportsPlaybackButtons = payload[2].unsigned == 1,
                supportsMetadata = payload[3].unsigned == 1,
                raw = raw,
            )
        }
        return ParsedTandemResponse.CapabilityInfo(
            domain = "PLAY",
            inquiredTypeCode = payload.firstOrNull()?.unsigned,
            values = payload.unsignedList(),
            raw = raw,
        )
    }

    /** Names: [type, dataType, nameStatus, len, utf8…]; volume: [type, 0x20, value]. */
    private fun parsePlayRetParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val dataType = payload.getOrNull(1)?.let { code ->
            PlaybackDetailedDataType.entries.firstOrNull { it.code == code }
        } ?: return unknown(PLAY_RET_PARAM, payload, raw)
        if (dataType == PlaybackDetailedDataType.VOLUME) {
            val volume = payload.getOrNull(2)?.unsigned ?: return unknown(PLAY_RET_PARAM, payload, raw)
            return ParsedTandemResponse.PlaybackVolume(volume, isUnsolicited = false, raw = raw)
        }
        val statusCode = payload.getOrNull(2)?.unsigned ?: return unknown(PLAY_RET_PARAM, payload, raw)
        val length = payload.getOrNull(3)?.unsigned ?: return unknown(PLAY_RET_PARAM, payload, raw)
        val end = (4 + length).coerceAtMost(payload.size)
        val text = if (length == 0) "" else payload.copyOfRange(4, end).decodeToString()
        val status = if (length == 0) PlaybackNameStatus.NOTHING else PlaybackNameStatus.fromCode(statusCode)
        return ParsedTandemResponse.PlaybackMetadataField(dataType, PlaybackName(text, status), raw)
    }

    /** SC's v1 name NTFYs carry no content; they only announce "metadata changed". */
    private fun parsePlayNtfyParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val dataType = payload.getOrNull(1)?.let { code ->
            PlaybackDetailedDataType.entries.firstOrNull { it.code == code }
        } ?: return unknown(PLAY_NTFY_PARAM, payload, raw)
        return when {
            dataType == PlaybackDetailedDataType.VOLUME && payload.size >= 3 ->
                ParsedTandemResponse.PlaybackVolume(payload[2].unsigned, isUnsolicited = true, raw = raw)
            else -> ParsedTandemResponse.PlaybackMetadataInvalidated(dataType, raw)
        }
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
            0x20 -> "HEAR"
            0x30 -> "PREMIUM"
            0x40 -> "SPORTS"
            0x50 -> "CASUAL"
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
            0x06 -> "Gold"
            0x07 -> "Pink"
            0x08 -> "Gray"
            0x09 -> "Yellow"
            0x0A -> "Green"
            0x0B -> "Violet"
            0x0C -> "Orange"
            else -> "UNKNOWN_COLOR_0x%02X".format(code)
        }

    private fun looksLikeV1BatteryPayload(payload: ByteArray): Boolean {
        val type = payload.firstOrNull()?.unsigned ?: return false
        return when (type) {
            0x00, 0x02 -> payload.size >= 2 && payload[1].percentageOrNull() != null
            0x01 -> payload.size >= 4 &&
                payload[1].percentageOrNull() != null &&
                payload[2].unsigned == 0x00 &&
                payload[3].percentageOrNull() != null
            else -> false
        }
    }

    private fun unknown(command: Byte?, payload: ByteArray, raw: ByteArray): ParsedTandemResponse.Unknown =
        ParsedTandemResponse.Unknown(
            dataType = DATA_MDR.unsigned,
            command = command?.unsigned,
            payload = payload,
            raw = raw,
        )
}
