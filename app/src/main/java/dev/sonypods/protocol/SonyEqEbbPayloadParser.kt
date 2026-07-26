package dev.sonypods.protocol

internal enum class EqEbbPayloadVersion {
    V1,
    V2,
}

internal object SonyEqEbbPayloadParser {
    private const val EQEBB_RET_STATUS: Byte = 0x53
    private const val EQEBB_NTFY_STATUS: Byte = 0x55
    private const val EQEBB_RET_PARAM: Byte = 0x57
    private const val EQEBB_NTFY_PARAM: Byte = 0x59
    private const val VALUE_ENABLE: Byte = 0x00

    private const val V1_PRESET_EQ: Byte = 0x01
    private const val V1_EBB: Byte = 0x02
    private const val V1_PRESET_EQ_NONCUSTOMIZABLE: Byte = 0x03

    fun parse(
        version: EqEbbPayloadVersion,
        command: Byte,
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { typeFor(version, it) }
        val values = payload.drop(1).map { it.unsigned }
        val isParamResponse = command == EQEBB_RET_PARAM || command == EQEBB_NTFY_PARAM
        val enabled = if (command == EQEBB_RET_STATUS || command == EQEBB_NTFY_STATUS) {
            payload.getOrNull(1)?.let { it == VALUE_ENABLE }
        } else {
            null
        }
        val bandCountOffset = if (isParamResponse) {
            bandCountOffset(version, type, payload)
        } else {
            null
        }
        val bandSteps = bandCountOffset?.let { offset ->
            payload.getOrNull(offset)?.unsigned?.let { count ->
                payload.drop(offset + 1).take(count).map { it.unsigned }
            }
        }.orEmpty()
        return ParsedTandemResponse.EqEbb(
            type = type,
            enabled = enabled,
            preset = if (isParamResponse) parsePreset(version, type, payload) else null,
            clearBass = if (isParamResponse) parseClearBass(version, type, payload, bandSteps) else null,
            bandSteps = bandSteps,
            values = values,
            raw = raw,
        )
    }

    fun parseExtendedInfo(
        version: EqEbbPayloadVersion,
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { typeFor(version, it) }
        val count = payload.getOrNull(1)?.unsigned ?: 0
        val bands = buildList {
            var offset = 2
            while (size < count && offset + 2 < payload.size) {
                val infoType = payload[offset].toEqBandInformationType()
                val value = (payload[offset + 1].unsigned shl 8) or payload[offset + 2].unsigned
                add(ParsedTandemResponse.EqBandInfo(infoType, value))
                offset += 3
            }
        }
        return ParsedTandemResponse.EqEbbExtendedInfo(
            type = type,
            bands = bands,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }

    private fun typeFor(version: EqEbbPayloadVersion, code: Byte): EqEbbInquiredType? =
        when (version) {
            EqEbbPayloadVersion.V1 -> when (code) {
                V1_PRESET_EQ -> EqEbbInquiredType.PRESET_EQ
                V1_EBB -> EqEbbInquiredType.EBB
                V1_PRESET_EQ_NONCUSTOMIZABLE -> EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE
                else -> null
            }
            EqEbbPayloadVersion.V2 -> EqEbbInquiredType.entries.firstOrNull { it.code == code }
        }

    private fun parsePreset(
        version: EqEbbPayloadVersion,
        type: EqEbbInquiredType?,
        payload: ByteArray,
    ): EqPresetId? =
        when (version) {
            EqEbbPayloadVersion.V1 -> when (type) {
                EqEbbInquiredType.PRESET_EQ,
                EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE -> payload.getOrNull(1)?.toEqPreset()
                else -> null
            }
            EqEbbPayloadVersion.V2 -> when (type) {
                EqEbbInquiredType.PRESET_EQ,
                EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE,
                EqEbbInquiredType.PRESET_EQ_AND_ERRORCODE,
                EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE -> payload.getOrNull(1)?.toEqPreset()
                EqEbbInquiredType.EBB -> if (v2EbbHasPresetField(payload)) {
                    payload.getOrNull(1)?.toEqPreset()
                } else {
                    null
                }
                else -> null
            }
        }

    private fun bandCountOffset(
        version: EqEbbPayloadVersion,
        type: EqEbbInquiredType?,
        payload: ByteArray,
    ): Int? =
        when (version) {
            EqEbbPayloadVersion.V1 -> when (type) {
                EqEbbInquiredType.PRESET_EQ,
                EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE -> 2
                else -> null
            }
            EqEbbPayloadVersion.V2 -> when (type) {
                EqEbbInquiredType.CUSTOM_EQ -> 1
                EqEbbInquiredType.EBB -> if (v2EbbHasPresetField(payload)) 2 else 1
                EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE -> 3
                null -> 0
                else -> 2
            }
        }

    private fun parseClearBass(
        version: EqEbbPayloadVersion,
        type: EqEbbInquiredType?,
        payload: ByteArray,
        bandSteps: List<Int>,
    ): Int? =
        when (version) {
            EqEbbPayloadVersion.V1 -> if (type == EqEbbInquiredType.EBB) {
                payload.getOrNull(1)?.toInt()
            } else {
                null
            }
            EqEbbPayloadVersion.V2 -> when {
                type == EqEbbInquiredType.EBB && !v2EbbHasPresetField(payload) ->
                    payload.getOrNull(1)?.toInt()
                type == EqEbbInquiredType.EBB && bandSteps.isNotEmpty() -> bandSteps[0]
                else -> null
            }
        }

    private fun v2EbbHasPresetField(payload: ByteArray): Boolean =
        payload.size >= 4 &&
            payload.getOrNull(2)?.unsigned?.let { count -> payload.size == count + 3 } == true

    private fun Byte.toEqPreset(): EqPresetId? =
        EqPresetId.entries.firstOrNull { it.code == this }

    private fun Byte.toEqBandInformationType(): EqBandInformationType? =
        EqBandInformationType.entries.firstOrNull { it.code == this }
}
