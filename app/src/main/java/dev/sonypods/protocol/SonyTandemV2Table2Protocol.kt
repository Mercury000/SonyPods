package dev.sonypods.protocol

import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR
import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR_NO2

/**
 * Sony Tandem V2 Table2 codec (MC channel, data type 0x0F).
 *
 * Command families:
 * - CONNECT (0x06-0x07): support function query
 * - POWER (0x20-0x29): auto standby, caring charge, USB submersion
 * - PERIPHERAL (0x30-0x3D): multi-point pairing, source switch, music hand-over
 * - VOICE_GUIDANCE (0x40-0x4F): language, on/off, volume, battery voice, beep settings
 * - SAFE_LISTENING (0x50-0x5B): safe listening modes, volume control
 * - LEA (0x60-0x69): LE Audio connection state, switch compatibility
 * - PARTY (0x70-0x7C): DJ control, illumination, karaoke
 * - SYSTEM (0xF0-0xFD): wearing detection, tap training, lighting, SVA, USB
 */
object SonyTandemV2Table2Protocol {

    // ── Data type ───────────────────────────────────────────────────────────

    private const val DT: Byte = DATA_MDR_NO2

    // ── Connect (0x06-0x07) ─────────────────────────────────────────────────

    private const val CONNECT_GET_SUPPORT_FUNCTION: Byte = 0x06
    private const val CONNECT_RET_SUPPORT_FUNCTION: Byte = 0x07

    // ── Power (0x20-0x29) ───────────────────────────────────────────────────

    private const val POWER_GET_CAPABILITY: Byte = 0x20
    private const val POWER_RET_CAPABILITY: Byte = 0x21
    private const val POWER_GET_STATUS: Byte = 0x22
    private const val POWER_RET_STATUS: Byte = 0x23
    private const val POWER_SET_STATUS: Byte = 0x24
    private const val POWER_NTFY_STATUS: Byte = 0x25
    private const val POWER_GET_PARAM: Byte = 0x26
    private const val POWER_RET_PARAM: Byte = 0x27
    private const val POWER_SET_PARAM: Byte = 0x28
    private const val POWER_NTFY_PARAM: Byte = 0x29

    // ── Peripheral (0x30-0x3D) ──────────────────────────────────────────────

    private const val PERI_GET_CAPABILITY: Byte = 0x30
    private const val PERI_RET_CAPABILITY: Byte = 0x31
    private const val PERI_GET_STATUS: Byte = 0x32
    private const val PERI_RET_STATUS: Byte = 0x33
    private const val PERI_SET_STATUS: Byte = 0x34
    private const val PERI_NTFY_STATUS: Byte = 0x35
    private const val PERI_GET_PARAM: Byte = 0x36
    private const val PERI_RET_PARAM: Byte = 0x37
    private const val PERI_SET_PARAM: Byte = 0x38
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
    private const val VG_GET_EXTENDED_PARAM: Byte = 0x4A
    private const val VG_RET_EXTENDED_PARAM: Byte = 0x4B

    // ── Safe Listening (0x50-0x5B) ──────────────────────────────────────────

    private const val SL_GET_CAPABILITY: Byte = 0x50
    private const val SL_RET_CAPABILITY: Byte = 0x51
    private const val SL_GET_STATUS: Byte = 0x52
    private const val SL_RET_STATUS: Byte = 0x53
    private const val SL_SET_STATUS: Byte = 0x54
    private const val SL_NTFY_STATUS: Byte = 0x55
    private const val SL_GET_PARAM: Byte = 0x56
    private const val SL_RET_PARAM: Byte = 0x57
    private const val SL_SET_PARAM: Byte = 0x58
    private const val SL_NTFY_PARAM: Byte = 0x59
    private const val SL_GET_EXTENDED_PARAM: Byte = 0x5A
    private const val SL_RET_EXTENDED_PARAM: Byte = 0x5B

    // ── LEA (0x60-0x69) ─────────────────────────────────────────────────────

    private const val LEA_GET_CAPABILITY: Byte = 0x60
    private const val LEA_RET_CAPABILITY: Byte = 0x61
    private const val LEA_GET_STATUS: Byte = 0x62
    private const val LEA_RET_STATUS: Byte = 0x63
    private const val LEA_NTFY_STATUS: Byte = 0x65
    private const val LEA_GET_PARAM: Byte = 0x66
    private const val LEA_RET_PARAM: Byte = 0x67
    private const val LEA_SET_PARAM: Byte = 0x68
    private const val LEA_NTFY_PARAM: Byte = 0x69

    // ── Party (0x70-0x7C) ───────────────────────────────────────────────────

    private const val PARTY_GET_CAPABILITY: Byte = 0x70
    private const val PARTY_RET_CAPABILITY: Byte = 0x71
    private const val PARTY_GET_STATUS: Byte = 0x72
    private const val PARTY_RET_STATUS: Byte = 0x73
    private const val PARTY_SET_STATUS: Byte = 0x74
    private const val PARTY_NTFY_STATUS: Byte = 0x75
    private const val PARTY_GET_PARAM: Byte = 0x76
    private const val PARTY_RET_PARAM: Byte = 0x77
    private const val PARTY_SET_PARAM: Byte = 0x78
    private const val PARTY_NTFY_PARAM: Byte = 0x79
    private const val PARTY_SET_EXTENDED_PARAM: Byte = 0x7C

    // ── System (0xF0-0xFD) ──────────────────────────────────────────────────

    private const val SYS_GET_CAPABILITY: Byte = 0xF0.toByte()
    private const val SYS_RET_CAPABILITY: Byte = 0xF1.toByte()
    private const val SYS_GET_STATUS: Byte = 0xF2.toByte()
    private const val SYS_RET_STATUS: Byte = 0xF3.toByte()
    private const val SYS_SET_STATUS: Byte = 0xF4.toByte()
    private const val SYS_NTFY_STATUS: Byte = 0xF5.toByte()
    private const val SYS_GET_PARAM: Byte = 0xF6.toByte()
    private const val SYS_RET_PARAM: Byte = 0xF7.toByte()
    private const val SYS_SET_PARAM: Byte = 0xF8.toByte()
    private const val SYS_NTFY_PARAM: Byte = 0xF9.toByte()
    private const val SYS_GET_EXTENDED_PARAM: Byte = 0xFA.toByte()
    private const val SYS_RET_EXTENDED_PARAM: Byte = 0xFB.toByte()
    private const val SYS_SET_EXTENDED_PARAM: Byte = 0xFC.toByte()
    private const val SYS_NTFY_EXTENDED_PARAM: Byte = 0xFD.toByte()

    // ── Command classification ──────────────────────────────────────────────

    enum class Table2Family {
        CONNECT, POWER, PERIPHERAL, VOICE_GUIDANCE,
        SAFE_LISTENING, LEA, PARTY, SYSTEM, UNKNOWN,
    }

    fun classifyFamily(command: Byte): Table2Family = when (command) {
        CONNECT_GET_SUPPORT_FUNCTION, CONNECT_RET_SUPPORT_FUNCTION -> Table2Family.CONNECT
        in POWER_GET_CAPABILITY..POWER_NTFY_PARAM -> Table2Family.POWER
        in PERI_GET_CAPABILITY..PERI_NTFY_EXTENDED_PARAM -> Table2Family.PERIPHERAL
        in VG_GET_CAPABILITY..VG_RET_EXTENDED_PARAM -> Table2Family.VOICE_GUIDANCE
        in SL_GET_CAPABILITY..SL_RET_EXTENDED_PARAM -> Table2Family.SAFE_LISTENING
        in LEA_GET_CAPABILITY..LEA_NTFY_PARAM -> Table2Family.LEA
        in PARTY_GET_CAPABILITY..PARTY_SET_EXTENDED_PARAM -> Table2Family.PARTY
        in 0xF0.toByte()..0xFD.toByte() -> Table2Family.SYSTEM
        else -> Table2Family.UNKNOWN
    }

    // ── GET builders ────────────────────────────────────────────────────────

    fun buildGetSupportFunction(): ByteArray =
        TandemMessage(DT, CONNECT_GET_SUPPORT_FUNCTION).toByteArray()

    fun buildGetPowerStatus(type: PowerInquiredTypeTable2): ByteArray =
        TandemMessage(DT, POWER_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetPowerParam(type: PowerInquiredTypeTable2): ByteArray =
        TandemMessage(DT, POWER_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetPeripheralStatus(type: PeripheralInquiredTypeTable2): ByteArray =
        TandemMessage(DT, PERI_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetPeripheralParam(type: PeripheralInquiredTypeTable2): ByteArray =
        TandemMessage(DT, PERI_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetVoiceGuidanceStatus(type: VoiceGuidanceInquiredTypeTable2): ByteArray =
        TandemMessage(DT, VG_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetVoiceGuidanceParam(type: VoiceGuidanceInquiredTypeTable2): ByteArray =
        TandemMessage(DT, VG_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetSafeListeningStatus(type: SafeListeningInquiredTypeTable2): ByteArray =
        TandemMessage(DT, SL_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetSafeListeningParam(type: SafeListeningInquiredTypeTable2): ByteArray =
        TandemMessage(DT, SL_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetLeaStatus(type: LeaInquiredTypeTable2): ByteArray =
        TandemMessage(DT, LEA_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetLeaParam(type: LeaInquiredTypeTable2): ByteArray =
        TandemMessage(DT, LEA_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetPartyStatus(type: PartyInquiredTypeTable2): ByteArray =
        TandemMessage(DT, PARTY_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetPartyParam(type: PartyInquiredTypeTable2): ByteArray =
        TandemMessage(DT, PARTY_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetSystemStatus(type: SystemInquiredTypeTable2): ByteArray =
        TandemMessage(DT, SYS_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetSystemParam(type: SystemInquiredTypeTable2): ByteArray =
        TandemMessage(DT, SYS_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    // ── Parser ──────────────────────────────────────────────────────────────

    fun parse(raw: ByteArray): ParsedTandemResponse {
        val normalized = when {
            raw.firstOrNull() == DT -> raw
            raw.firstOrNull() == DATA_MDR -> raw // SPP-normalized: treat 0x0E as 0x0F for Table2 context
            else -> byteArrayOf(DT) + raw
        }
        if (normalized.size < 2) {
            return ParsedTandemResponse.Unknown(null, null, byteArrayOf(), raw)
        }
        val dataType = normalized[0]
        val command = normalized[1]
        val payload = normalized.drop(2).map { it }.toByteArray()

        if (dataType != DT && dataType != DATA_MDR) {
            return ParsedTandemResponse.Unknown(dataType.unsigned, command.unsigned, payload, raw)
        }

        return when (command) {
            CONNECT_RET_SUPPORT_FUNCTION -> parseCommon(payload, raw, command)
            POWER_RET_CAPABILITY, POWER_RET_STATUS, POWER_NTFY_STATUS,
            POWER_RET_PARAM, POWER_NTFY_PARAM -> parsePower(payload, raw)
            PERI_RET_CAPABILITY, PERI_RET_STATUS, PERI_NTFY_STATUS,
            PERI_RET_PARAM, PERI_NTFY_PARAM -> parsePeripheral(payload, raw)
            VG_RET_CAPABILITY, VG_RET_STATUS, VG_NTFY_STATUS,
            VG_RET_PARAM, VG_NTFY_PARAM -> parseVoiceGuidance(payload, raw)
            SL_RET_CAPABILITY, SL_RET_STATUS, SL_NTFY_STATUS,
            SL_RET_PARAM, SL_NTFY_PARAM -> parseSafeListening(payload, raw)
            LEA_RET_CAPABILITY, LEA_RET_STATUS, LEA_NTFY_STATUS,
            LEA_RET_PARAM, LEA_NTFY_PARAM -> parseLea(payload, raw)
            PARTY_RET_CAPABILITY, PARTY_RET_STATUS, PARTY_NTFY_STATUS,
            PARTY_RET_PARAM, PARTY_NTFY_PARAM -> parseParty(payload, raw)
            SYS_RET_CAPABILITY, SYS_RET_STATUS, SYS_NTFY_STATUS,
            SYS_RET_PARAM, SYS_NTFY_PARAM,
            SYS_RET_EXTENDED_PARAM, SYS_NTFY_EXTENDED_PARAM -> parseSystem(payload, raw)
            else -> ParsedTandemResponse.Unknown(dataType.unsigned, command.unsigned, payload, raw)
        }
    }

    private fun parseCommon(payload: ByteArray, raw: ByteArray, command: Byte): ParsedTandemResponse =
        ParsedTandemResponse.Table2Common(
            family = Table2Family.CONNECT.name,
            command = command.unsigned,
            values = payload.unsignedList(),
            raw = raw,
        )

    private fun parsePower(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { PowerInquiredTypeTable2.fromCode(it) }
        return ParsedTandemResponse.Table2Generic(
            family = Table2Family.POWER.name,
            inquiredType = type?.code?.unsigned,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }

    private fun parsePeripheral(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { PeripheralInquiredTypeTable2.fromCode(it) }
        return ParsedTandemResponse.Table2Generic(
            family = Table2Family.PERIPHERAL.name,
            inquiredType = type?.code?.unsigned,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }

    private fun parseVoiceGuidance(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { VoiceGuidanceInquiredTypeTable2.fromCode(it) }
        return ParsedTandemResponse.Table2Generic(
            family = Table2Family.VOICE_GUIDANCE.name,
            inquiredType = type?.code?.unsigned,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }

    private fun parseSafeListening(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { SafeListeningInquiredTypeTable2.fromCode(it) }
        return ParsedTandemResponse.Table2Generic(
            family = Table2Family.SAFE_LISTENING.name,
            inquiredType = type?.code?.unsigned,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }

    private fun parseLea(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { LeaInquiredTypeTable2.fromCode(it) }
        return ParsedTandemResponse.Table2Generic(
            family = Table2Family.LEA.name,
            inquiredType = type?.code?.unsigned,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }

    private fun parseParty(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { PartyInquiredTypeTable2.fromCode(it) }
        return ParsedTandemResponse.Table2Generic(
            family = Table2Family.PARTY.name,
            inquiredType = type?.code?.unsigned,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }

    private fun parseSystem(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { SystemInquiredTypeTable2.fromCode(it) }
        return ParsedTandemResponse.Table2Generic(
            family = Table2Family.SYSTEM.name,
            inquiredType = type?.code?.unsigned,
            values = payload.drop(1).map { it.unsigned },
            raw = raw,
        )
    }
}

// ── V2 Table2 inquired type enums ───────────────────────────────────────────

enum class PowerInquiredTypeTable2(val code: Byte) {
    AUTO_STANDBY(0x00),
    CARING_CHARGE_WITH_THRESHOLD(0x01),
    USB_SUBMERSION(0x02),
    OUT_OF_RANGE(0xFF.toByte()),
    ;
    companion object {
        fun fromCode(b: Byte): PowerInquiredTypeTable2 =
            entries.firstOrNull { it.code == b } ?: OUT_OF_RANGE
    }
}

enum class PeripheralInquiredTypeTable2(val code: Byte) {
    PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT(0x00),
    SOURCE_SWITCH_CONTROL(0x01),
    PAIRING_DEVICE_MANAGEMENT_WITH_BT_CLASS_OF_DEVICE(0x02),
    MUSIC_HAND_OVER_SETTING(0x03),
    OUT_OF_RANGE(0xFF.toByte()),
    ;
    companion object {
        fun fromCode(b: Byte): PeripheralInquiredTypeTable2 =
            entries.firstOrNull { it.code == b } ?: OUT_OF_RANGE
    }
}

enum class VoiceGuidanceInquiredTypeTable2(val code: Byte) {
    MTK_TRANSFER_WO_DISCONNECTION_NOT_SUPPORT_LANGUAGE_SWITCH(0x00),
    MTK_TRANSFER_WO_DISCONNECTION_SUPPORT_LANGUAGE_SWITCH(0x01),
    SUPPORT_LANGUAGE_SWITCH(0x02),
    ONLY_ON_OFF_SETTING(0x03),
    VOLUME(0x20),
    VOLUME_SETTING_FIXED_TO_5_STEPS(0x21),
    BATTERY_LV_VOICE(0x30),
    POWER_ONOFF_SOUND(0x31),
    SOUNDEFFECT_ULT_BEEP_ONOFF(0x32),
    OUT_OF_RANGE(0xFF.toByte()),
    ;
    companion object {
        fun fromCode(b: Byte): VoiceGuidanceInquiredTypeTable2 =
            entries.firstOrNull { it.code == b } ?: OUT_OF_RANGE
    }
}

enum class SafeListeningInquiredTypeTable2(val code: Byte) {
    SAFE_LISTENING_HBS_1(0x00),
    SAFE_LISTENING_TWS_1(0x01),
    SAFE_LISTENING_HBS_2(0x02),
    SAFE_LISTENING_TWS_2(0x03),
    SAFE_VOLUME_CONTROL(0x04),
    MAX_VOL_LV_LIMIT(0x05),
    OUT_OF_RANGE(0xFF.toByte()),
    ;
    companion object {
        fun fromCode(b: Byte): SafeListeningInquiredTypeTable2 =
            entries.firstOrNull { it.code == b } ?: OUT_OF_RANGE
    }
}

enum class LeaInquiredTypeTable2(val code: Byte) {
    LE_AUDIO_CONNECTION_STATE_NOTIFICATION(0x00),
    LE_AUDIO_SWITCH_SUPPORTED_COMPATIBILITY(0x01),
    LE_AUDIO_CONNECTION_MODE_WITH_BT_RECONNECTION(0x02),
    GET_IDENTITY_RESOLVING_KEY(0x03),
    LINK_AUTO_SWITCH_CANT_BE_USED_WITH_LEA_CONNECTION(0xFE.toByte()),
    OUT_OF_RANGE(0xFF.toByte()),
    ;
    companion object {
        fun fromCode(b: Byte): LeaInquiredTypeTable2 =
            entries.firstOrNull { it.code == b } ?: OUT_OF_RANGE
    }
}

enum class PartyInquiredTypeTable2(val code: Byte) {
    DJ_CONTROL(0x00),
    ILLUMINATION(0x01),
    KARAOKE(0x02),
    DJ_CONTROL_WITH_STATUS_DISABLE_REASON(0x03),
    KARAOKE_WITH_STATUS_DISABLE_REASON(0x04),
    OUT_OF_RANGE(0xFF.toByte()),
    ;
    companion object {
        fun fromCode(b: Byte): PartyInquiredTypeTable2 =
            entries.firstOrNull { it.code == b } ?: OUT_OF_RANGE
    }
}

enum class SystemInquiredTypeTable2(val code: Byte) {
    WEARING_STATUS_CHECKER(0x00),
    REPEAT_TAP_TRAINING_MODE(0x01),
    QUICK_ACCESS_EASY_SETTING(0x02),
    AUTO_VOLUME_OPTIMIZER(0x03),
    AUTO_VOLUME_WITH_LIMITATION(0x04),
    SONY_VOICE_ASSISTANT_SETTING(0x05),
    SONY_VOICE_ASSISTANT_COMMAND(0x06),
    WEARING_DEVICE_INFORMATION(0x07),
    WEARING_POSITION_JUDGMENT_BY_SENSOR(0x08),
    LINK_AUTO_SWITCH_FOR_SPEAKER(0x09),
    LINK_AUTO_SWITCH_FOR_HEADSETS(0x0A),
    MIC_ON_OFF_BY_HEADPHONE_OPERATION(0x0B),
    FUNCTION_CHANGE(0x0C),
    USB_BROWSER(0x0D),
    LIGHTING_MODE(0x0E),
    VOICE_ASSISTANT_WITH_SPECIFIC_SETUP(0x0F),
    LIGHTING_DEFAULT_COLOR_COLOR_TYPE(0x10),
    LIGHTING_DEFAULT_COLOR_CUSTOM_COLOR(0x11),
    OUT_OF_RANGE(0xFF.toByte()),
    ;
    companion object {
        fun fromCode(b: Byte): SystemInquiredTypeTable2 =
            entries.firstOrNull { it.code == b } ?: OUT_OF_RANGE
    }
}
