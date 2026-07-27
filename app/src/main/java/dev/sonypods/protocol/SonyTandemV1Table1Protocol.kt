package dev.sonypods.protocol

import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR

object SonyTandemV1Table1Protocol {
    // ── Connection / device information (V1) ──
    private const val CONNECT_RET_DEVICE_INFO: Byte = 0x05
    private const val CONNECT_GET_DEVICE_INFO: Byte = 0x04

    // ── Common / Battery (V1) ──
    private const val COMMON_GET_BATTERY_LEVEL: Byte = 0x10
    private const val COMMON_RET_BATTERY_LEVEL: Byte = 0x11
    private const val COMMON_NTFY_BATTERY_LEVEL: Byte = 0x13

    // ── Playback (V1) ──
    private const val PLAY_GET_STATUS: Byte = 0xA2.toByte()
    private const val PLAY_RET_STATUS: Byte = 0xA3.toByte()
    private const val PLAY_SET_STATUS: Byte = 0xA4.toByte()
    private const val PLAY_NTFY_STATUS: Byte = 0xA5.toByte()
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

    // V1 inquired-type sub-codes
    private const val V1_PRESET_EQ: Byte = 0x01
    private const val V1_EBB: Byte = 0x02
    private const val V1_PRESET_EQ_NONCUSTOMIZABLE: Byte = 0x03

    // ── Device information ──

    fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_DEVICE_INFO, byteArrayOf(type.code))

    // ── Battery ──

    fun buildGetBatteryStatus(type: PowerInquiredType = PowerInquiredType.BATTERY): ByteArray =
        SonyTandemFrame.message(COMMON_GET_BATTERY_LEVEL, byteArrayOf(type.code))

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
    ): ByteArray {
        val effect = if (controlMode == NoiseControlMode.OFF) {
            NCASM_EFFECT_OFF
        } else {
            NCASM_EFFECT_ADJUSTMENT_COMPLETION
        }
        val ncValue = when (controlMode) {
            NoiseControlMode.NOISE_CANCELLING -> NC_VALUE_ON_DUAL
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

    // ── Parse ──

    fun parse(raw: ByteArray): ParsedTandemResponse {
        val normalized = if (raw.firstOrNull() == DATA_MDR) raw else byteArrayOf(DATA_MDR) + raw
        val command = normalized.getOrNull(1)
        val payload = if (normalized.size > 2) normalized.copyOfRange(2, normalized.size) else byteArrayOf()
        return when (command) {
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
                isUnsolicited = false,
                raw = raw,
            )
            PLAY_NTFY_STATUS -> ParsedTandemResponse.PlaybackAck(
                values = payload.unsignedList(),
                status = parsePlaybackStatus(payload),
                isUnsolicited = true,
                raw = raw,
            )
            else -> unknown(command, payload, raw)
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

    private fun parseBattery(payload: ByteArray, raw: ByteArray): ParsedTandemResponse.Battery {
        val kind = payload.firstOrNull()?.let { code ->
            PowerInquiredType.entries.firstOrNull { it.code == code }
        }
        val values = when (kind) {
            PowerInquiredType.BATTERY,
            PowerInquiredType.CRADLE_BATTERY -> listOfNotNull(payload.getOrNull(1)?.percentageOrNull())
            PowerInquiredType.LEFT_RIGHT_BATTERY -> listOfNotNull(
                payload.getOrNull(1)?.percentageOrNull(),
                payload.getOrNull(3)?.percentageOrNull(),
            )
            else -> payload.drop(1).map { it.unsigned }
        }
        return ParsedTandemResponse.Battery(kind, values, raw)
    }

    private fun parseNoiseControl(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { code ->
            NcAsmInquiredType.entries.firstOrNull { it.code == code }
        }
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
        return ParsedTandemResponse.NoiseControl(
            type = type,
            values = payload.drop(1).map { it.unsigned },
            enabled = controlMode == NoiseControlMode.NOISE_CANCELLING,
            ambientSoundEnabled = controlMode == NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = payload.getOrNull(6)?.unsigned,
            ambientMode = ambientMode,
            controlMode = controlMode,
            raw = raw,
        )
    }

    private fun parsePlaybackStatus(payload: ByteArray): PlaybackStatus =
        when (payload.getOrNull(2)?.unsigned) {
            1 -> PlaybackStatus.PLAYING
            2 -> PlaybackStatus.PAUSED
            3 -> PlaybackStatus.STOPPED
            else -> PlaybackStatus.UNKNOWN
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
