package dev.sonypods.protocol

import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR

/**
 * Sony Tandem V1 Table2 codec (MC channel).
 *
 * V1 Table2 covers:
 * - PERIPHERAL (0x30-0x3D): multi-point pairing management
 * - VOICE_GUIDANCE (0x40-0x49): language, on/off settings
 *
 * The data type byte is 0x0C (DATA_MDR over SPP) or inline within GATT.
 * This codec normalizes from the SPP-escaped frame to raw Tandem payloads.
 */
object SonyTandemV1Table2Protocol {

    private const val DT: Byte = DATA_MDR

    // ── Peripheral (0x30-0x3D) ──────────────────────────────────────────────

    private const val PERI_GET_CAPABILITY: Byte = 0x30
    private const val PERI_RET_CAPABILITY: Byte = 0x31
    private const val PERI_GET_STATUS: Byte = 0x32
    private const val PERI_RET_STATUS: Byte = 0x33
    private const val PERI_SET_STATUS: Byte = 0x34
    private const val PERI_NTFY_STATUS: Byte = 0x35
    private const val PERI_GET_PARAM: Byte = 0x36
    private const val PERI_RET_PARAM: Byte = 0x37
    private const val PERI_NTFY_PARAM: Byte = 0x39
    private const val PERI_SET_EXTENDED_PARAM: Byte = 0x3C
    private const val PERI_NTFY_EXTENDED_PARAM: Byte = 0x3D

    // ── Voice Guidance (0x40-0x4F) ──────────────────────────────────────────

    private const val VG_GET_CAPABILITY: Byte = 0x40
    private const val VG_RET_CAPABILITY: Byte = 0x41
    private const val VG_GET_STATUS: Byte = 0x42
    private const val VG_RET_STATUS: Byte = 0x43
    private const val VG_SET_STATUS: Byte = 0x44
    private const val VG_NTFY_STATUS: Byte = 0x45
    private const val VG_GET_PARAM: Byte = 0x46
    private const val VG_RET_PARAM: Byte = 0x47
    private const val VG_SET_PARAM: Byte = 0x48
    private const val VG_NTFY_PARAM: Byte = 0x49

    // ── Command classification ──────────────────────────────────────────────

    enum class Table2Family {
        PERIPHERAL, VOICE_GUIDANCE, UNKNOWN,
    }

    fun classifyFamily(command: Byte): Table2Family = when (command) {
        in PERI_GET_CAPABILITY..PERI_NTFY_EXTENDED_PARAM -> Table2Family.PERIPHERAL
        in VG_GET_CAPABILITY..VG_NTFY_PARAM -> Table2Family.VOICE_GUIDANCE
        else -> Table2Family.UNKNOWN
    }

    // ── GET builders ────────────────────────────────────────────────────────

    fun buildGetPeripheralStatus(type: PeripheralInquiredTypeV1Table2): ByteArray =
        TandemMessage(DT, PERI_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetPeripheralParam(type: PeripheralInquiredTypeV1Table2): ByteArray =
        TandemMessage(DT, PERI_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetVoiceGuidanceStatus(type: VoiceGuidanceInquiredTypeV1Table2): ByteArray =
        TandemMessage(DT, VG_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetVoiceGuidanceParam(type: VoiceGuidanceInquiredTypeV1Table2): ByteArray =
        TandemMessage(DT, VG_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    // ── Parser ──────────────────────────────────────────────────────────────

    fun parse(raw: ByteArray): ParsedTandemResponse {
        val normalized = if (raw.firstOrNull() == DT) raw else byteArrayOf(DT) + raw
        if (normalized.size < 2) {
            return ParsedTandemResponse.Unknown(null, null, byteArrayOf(), raw)
        }
        val dataType = normalized[0]
        val command = normalized[1]
        val payload = normalized.drop(2).map { it }.toByteArray()

        if (dataType != DT) {
            return ParsedTandemResponse.Unknown(dataType.unsigned, command.unsigned, payload, raw)
        }

        return when (command) {
            PERI_RET_CAPABILITY, PERI_RET_STATUS, PERI_NTFY_STATUS,
            PERI_RET_PARAM, PERI_NTFY_PARAM -> parsePeripheral(payload, raw)
            VG_RET_CAPABILITY, VG_RET_STATUS, VG_NTFY_STATUS,
            VG_RET_PARAM, VG_NTFY_PARAM -> parseVoiceGuidance(payload, raw)
            else -> ParsedTandemResponse.Unknown(dataType.unsigned, command.unsigned, payload, raw)
        }
    }

    private fun parsePeripheral(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { PeripheralInquiredTypeV1Table2.fromCode(it) }
        return ParsedTandemResponse.Table2Generic(
            family = Table2Family.PERIPHERAL.name,
            inquiredType = type?.code?.unsigned,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }

    private fun parseVoiceGuidance(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { VoiceGuidanceInquiredTypeV1Table2.fromCode(it) }
        return ParsedTandemResponse.Table2Generic(
            family = Table2Family.VOICE_GUIDANCE.name,
            inquiredType = type?.code?.unsigned,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }
}

// ── V1 Table2 inquired type enums ───────────────────────────────────────────

enum class PeripheralInquiredTypeV1Table2(val code: Byte) {
    NO_USE(0x00),
    PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT(0x01),
    ;
    companion object {
        // Sony V1 enums fold unknown bytes to NO_USE; mirror that even though diagnostics lose the raw value.
        fun fromCode(b: Byte): PeripheralInquiredTypeV1Table2 =
            entries.firstOrNull { it.code == b } ?: NO_USE
    }
}

enum class VoiceGuidanceInquiredTypeV1Table2(val code: Byte) {
    NO_USE(0x00),
    VOICE_GUIDANCE_SETTING(0x01),
    ;
    companion object {
        // Sony V1 enums fold unknown bytes to NO_USE; mirror that even though diagnostics lose the raw value.
        fun fromCode(b: Byte): VoiceGuidanceInquiredTypeV1Table2 =
            entries.firstOrNull { it.code == b } ?: NO_USE
    }
}
