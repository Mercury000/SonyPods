package dev.sonypods.protocol

import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR

object SonyTandemV2Table1Protocol {
    private const val CONNECT_GET_PROTOCOL_INFO: Byte = 0x00
    private const val CONNECT_RET_DEVICE_INFO: Byte = 0x05
    private const val CONNECT_GET_DEVICE_INFO: Byte = 0x04
    private const val COMMON_GET_STATUS: Byte = 0x12
    private const val COMMON_RET_STATUS: Byte = 0x13
    private const val COMMON_NTFY_STATUS: Byte = 0x15
    private const val POWER_GET_STATUS: Byte = 0x22
    private const val POWER_RET_STATUS: Byte = 0x23
    private const val POWER_NTFY_STATUS: Byte = 0x25
    private const val SYSTEM_GET_PARAM: Byte = 0x36
    private const val SYSTEM_RET_PARAM: Byte = 0x37
    private const val LEA_GET_STATUS: Byte = 0x42
    private const val LEA_RET_STATUS: Byte = 0x43
    private const val LEA_NTFY_STATUS: Byte = 0x45
    private const val LEA_GET_PARAM: Byte = 0x46
    private const val LEA_RET_PARAM: Byte = 0x47
    private const val LEA_NTFY_PARAM: Byte = 0x49
    private const val EQEBB_GET_STATUS: Byte = 0x52
    private const val EQEBB_RET_STATUS: Byte = 0x53
    private const val EQEBB_NTFY_STATUS: Byte = 0x55
    private const val EQEBB_GET_PARAM: Byte = 0x56
    private const val EQEBB_RET_PARAM: Byte = 0x57
    private const val EQEBB_SET_PARAM: Byte = 0x58
    private const val EQEBB_NTFY_PARAM: Byte = 0x59
    private const val EQEBB_GET_EXTENDED_INFO: Byte = 0x5A
    private const val EQEBB_RET_EXTENDED_INFO: Byte = 0x5B
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
    private const val VALUE_ENABLE: Byte = 0x00
    private const val VALUE_CHANGED: Byte = 0x01
    private const val NCASM_EFFECT_OFF: Byte = 0x00
    private const val NCASM_ON: Byte = 0x00
    private const val NCASM_OFF: Byte = 0x01
    private const val NCASM_MODE_NC: Byte = 0x00
    private const val NCASM_MODE_ASM: Byte = 0x01
    private const val NC_VALUE_OFF: Byte = 0x00
    private const val NC_VALUE_ON_SINGLE: Byte = 0x01
    private const val NC_VALUE_ON_DUAL: Byte = 0x02

    fun buildGetProtocolInfo(): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_PROTOCOL_INFO)

    fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray =
        SonyTandemFrame.message(CONNECT_GET_DEVICE_INFO, byteArrayOf(type.code))

    fun buildGetDisplayFirmwareVersion(): ByteArray =
        SonyTandemFrame.message(COMMON_GET_STATUS, byteArrayOf(CommonInquiredType.DISPLAY_FW_VERSION.code))

    fun buildGetBatteryStatus(type: PowerInquiredType): ByteArray =
        SonyTandemFrame.message(POWER_GET_STATUS, byteArrayOf(type.code))

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
    ): ByteArray =
        buildSetEqPreset(preset, type.code, bandSteps)

    fun buildSetEqPreset(
        preset: EqPresetId,
        typeCode: Byte,
        bandSteps: List<Int> = emptyList(),
    ): ByteArray =
        SonyTandemFrame.message(
            EQEBB_SET_PARAM,
            byteArrayOf(typeCode, preset.code, bandSteps.size.toByte()) +
                bandSteps.map { it.coerceIn(0, 255).toByte() }.toByteArray(),
        )

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

    fun buildSetNoiseControlMode(
        controlMode: NoiseControlMode,
        ambientLevel: Int = 10,
        ambientMode: AmbientSoundMode = AmbientSoundMode.NORMAL,
    ): ByteArray {
        val enabled = controlMode != NoiseControlMode.OFF
        val ncAsmMode = if (controlMode == NoiseControlMode.AMBIENT_SOUND) NCASM_MODE_ASM else NCASM_MODE_NC
        return SonyTandemFrame.message(
            NCASM_SET_PARAM,
            byteArrayOf(
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS.code,
                VALUE_CHANGED,
                if (enabled) NCASM_ON else NCASM_OFF,
                ncAsmMode,
                ambientMode.code,
                ambientLevel.coerceIn(1, 20).toByte(),
            ),
        )
    }

    fun buildSetNcModeSwitchAndAmbientLevel(
        controlMode: NoiseControlMode,
        ambientLevel: Int = 10,
        ambientMode: AmbientSoundMode = AmbientSoundMode.NORMAL,
    ): ByteArray {
        val totalEffect = if (controlMode == NoiseControlMode.OFF) NCASM_OFF else NCASM_ON
        val ncValue = when (controlMode) {
            NoiseControlMode.NOISE_CANCELLING -> NC_VALUE_ON_DUAL
            NoiseControlMode.AMBIENT_SOUND,
            NoiseControlMode.OFF -> NC_VALUE_OFF
        }
        val rawAmbientLevel = if (controlMode == NoiseControlMode.AMBIENT_SOUND) {
            (ambientLevel.coerceIn(1, 20) - 1).toByte()
        } else {
            0x00
        }
        return SonyTandemFrame.message(
            NCASM_SET_PARAM,
            byteArrayOf(
                NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS.code,
                VALUE_CHANGED,
                totalEffect,
                ncValue,
                ambientMode.code,
                rawAmbientLevel,
            ),
        )
    }

    fun buildGetPlaybackStatus(
        type: PlayInquiredType = PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT,
    ): ByteArray =
        SonyTandemFrame.message(PLAY_GET_STATUS, byteArrayOf(type.code))

    fun buildGetLeaStatus(type: LeaInquiredType): ByteArray =
        SonyTandemFrame.message(LEA_GET_STATUS, byteArrayOf(type.code))

    fun buildGetLeaPairedHistory(type: LeaInquiredType): ByteArray =
        SonyTandemFrame.message(LEA_GET_PARAM, byteArrayOf(type.code))

    fun buildGetQuickAccess(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_PARAM, byteArrayOf(SystemInquiredType.QUICK_ACCESS.code))

    fun buildGetWearingStatus(): ByteArray =
        SonyTandemFrame.message(SYSTEM_GET_PARAM, byteArrayOf(SystemInquiredType.WEARING_STATUS_DETECTOR.code))

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
                level.coerceIn(0, 255).toByte(),
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
            CONNECT_RET_DEVICE_INFO -> parseDeviceInfo(payload, raw)
            COMMON_RET_STATUS, COMMON_NTFY_STATUS -> parseCommonStatus(payload, raw)
            POWER_RET_STATUS, POWER_NTFY_STATUS -> parseBattery(payload, raw)
            EQEBB_RET_STATUS, EQEBB_NTFY_STATUS,
            EQEBB_RET_PARAM, EQEBB_NTFY_PARAM -> parseEqEbb(command, payload, raw)
            EQEBB_RET_EXTENDED_INFO -> SonyEqEbbPayloadParser.parseExtendedInfo(EqEbbPayloadVersion.V2, payload, raw)
            NCASM_RET_STATUS, NCASM_NTFY_STATUS -> parseNoiseControl(command, payload, raw)
            NCASM_RET_PARAM, NCASM_NTFY_PARAM -> parseNoiseControl(command, payload, raw)
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
            LEA_RET_STATUS, LEA_NTFY_STATUS -> parseLeaStatus(payload, raw)
            LEA_RET_PARAM, LEA_NTFY_PARAM -> parseLeaParam(payload, raw)
            SYSTEM_RET_PARAM -> parseSystemRetParam(payload, raw)
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
        return ParsedTandemResponse.DeviceInfo(type, text, raw)
    }

    private fun parseCommonStatus(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { code ->
            CommonInquiredType.entries.firstOrNull { it.code == code }
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

    private fun parseEqEbb(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        return SonyEqEbbPayloadParser.parse(EqEbbPayloadVersion.V2, command, payload, raw)
    }

    private fun parseNoiseControl(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { code ->
            NcAsmInquiredType.entries.firstOrNull { it.code == code }
        }
        val values = payload.drop(1).map { it.unsigned }
        val isParamResponse = command == NCASM_RET_PARAM || command == NCASM_NTFY_PARAM
        if (!isParamResponse) {
            return ParsedTandemResponse.NoiseControl(
                type = type,
                values = values,
                raw = raw,
            )
        }
        val ambientMode = when (type) {
            NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> payload.getOrNull(5)
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS,
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> payload.getOrNull(4)
            else -> payload.getOrNull(3)
        }?.let { byte ->
            AmbientSoundMode.entries.firstOrNull { it.code == byte }
        }
        val combinedEnabled = payload.getOrNull(2)?.let { it == NCASM_ON }
        val combinedMode = payload.getOrNull(3)
        val combinedControlMode = when (type) {
            NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> when {
                payload.getOrNull(1) == NCASM_EFFECT_OFF -> NoiseControlMode.OFF
                payload.getOrNull(3) == NC_VALUE_ON_SINGLE ||
                    payload.getOrNull(3) == NC_VALUE_ON_DUAL -> NoiseControlMode.NOISE_CANCELLING
                payload.getOrNull(3) == NC_VALUE_OFF &&
                    payload.getOrNull(1) != NCASM_EFFECT_OFF -> NoiseControlMode.AMBIENT_SOUND
                else -> null
            }
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS -> when {
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
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS -> when {
                payload.getOrNull(2) == NCASM_OFF -> NoiseControlMode.OFF
                payload.getOrNull(3) == NCASM_OFF -> NoiseControlMode.AMBIENT_SOUND
                payload.getOrNull(2) == NCASM_ON -> NoiseControlMode.NOISE_CANCELLING
                else -> null
            }
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> when {
                payload.getOrNull(2) == NCASM_OFF -> NoiseControlMode.OFF
                payload.getOrNull(3) == NC_VALUE_ON_SINGLE ||
                    payload.getOrNull(3) == NC_VALUE_ON_DUAL -> NoiseControlMode.NOISE_CANCELLING
                payload.getOrNull(3) == NC_VALUE_OFF &&
                    payload.getOrNull(2) == NCASM_ON -> NoiseControlMode.AMBIENT_SOUND
                else -> null
            }
            else -> null
        }
        return ParsedTandemResponse.NoiseControl(
            type = type,
            values = values,
            enabled = when (type) {
                NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> combinedControlMode == NoiseControlMode.NOISE_CANCELLING
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                    combinedControlMode == NoiseControlMode.NOISE_CANCELLING
                NcAsmInquiredType.NC_ON_OFF -> payload.getOrNull(3)?.let { it == NCASM_ON }
                    ?: payload.getOrNull(1)?.let { it == VALUE_ENABLE }
                NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS,
                NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> combinedControlMode == NoiseControlMode.NOISE_CANCELLING
                else -> null
            },
            ambientSoundEnabled = when (type) {
                NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> combinedControlMode == NoiseControlMode.AMBIENT_SOUND
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                    combinedControlMode == NoiseControlMode.AMBIENT_SOUND
                NcAsmInquiredType.ASM_ON_OFF -> payload.getOrNull(4)?.let { it == NCASM_ON }
                    ?: payload.getOrNull(1)?.let { it == VALUE_ENABLE }
                NcAsmInquiredType.ASM_SEAMLESS -> payload.getOrNull(2)?.let { it == NCASM_ON }
                NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS,
                NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> combinedControlMode == NoiseControlMode.AMBIENT_SOUND
                else -> null
            },
            ambientLevel = when (type) {
                NcAsmInquiredType.V1_TABLE_SET1_NC_ASM -> payload.getOrNull(6)?.unsigned
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS -> payload.getOrNull(5)?.unsigned
                NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS -> payload.getOrNull(5)?.unsigned
                NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> payload.getOrNull(5)?.unsigned?.plus(1)
                NcAsmInquiredType.ASM_SEAMLESS -> payload.getOrNull(4)?.unsigned
                else -> null
            },
            ambientMode = ambientMode,
            controlMode = combinedControlMode,
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
            raw = raw,
        )
    }

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
            raw = raw,
        )
    }

    private fun Byte.toLeaStreamingStatus(): LeaStreamingStatus? =
        LeaStreamingStatus.entries.firstOrNull { it.code == this }

    private fun parseSystemRetParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse =
        when (payload.firstOrNull()) {
            SystemInquiredType.QUICK_ACCESS.code -> parseQuickAccess(payload, raw)
            SystemInquiredType.WEARING_STATUS_DETECTOR.code -> parseWearingStatus(payload, raw)
            else -> ParsedTandemResponse.Unknown(null, SYSTEM_RET_PARAM.unsigned, payload, raw)
        }

    private fun parseQuickAccess(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val key = payload.getOrNull(1)?.let { k ->
            QuickAccessKey.entries.firstOrNull { it.code == k }
        }
        val function = payload.getOrNull(2)?.let { f ->
            QuickAccessFunction.entries.firstOrNull { it.code == f }
        }
        return ParsedTandemResponse.QuickAccess(
            key = key,
            function = function,
            values = payload.unsignedList(),
            raw = raw,
        )
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
}
