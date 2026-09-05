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

    /** Identity Resolving Key length, per the Core spec. */
    private const val IRK_LENGTH = 16

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
        TandemMessage(DT, CONNECT_GET_SUPPORT_FUNCTION, byteArrayOf(0x00)).toByteArray()

    fun buildGetPowerStatus(type: PowerInquiredTypeTable2): ByteArray =
        TandemMessage(DT, POWER_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetPowerParam(type: PowerInquiredTypeTable2): ByteArray =
        TandemMessage(DT, POWER_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetPeripheralStatus(type: PeripheralInquiredTypeTable2): ByteArray =
        TandemMessage(DT, PERI_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetPeripheralParam(type: PeripheralInquiredTypeTable2): ByteArray =
        TandemMessage(DT, PERI_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetPeripheralCapability(type: PeripheralInquiredTypeTable2): ByteArray =
        TandemMessage(DT, PERI_GET_CAPABILITY, byteArrayOf(type.code)).toByteArray()

    /** SC `lg0.e0.b.h`: [type, PeripheralBluetoothMode, EnableDisable]. The
     * official sender (`x30/b.java`) always writes ENABLE (0x00); the mode byte
     * alone selects entering (INQUIRY_SCAN) or leaving (NORMAL) pairing. */
    fun buildSetPeripheralPairingMode(
        type: PeripheralInquiredTypeTable2,
        mode: PeripheralBluetoothModeTable2,
    ): ByteArray = TandemMessage(
        DT,
        PERI_SET_STATUS,
        byteArrayOf(type.code, mode.code, 0x00),
    ).toByteArray()

    /** OnOffSettingValue: ON=0x00, OFF=0x01 (SC `lg0.c0.b.h`). */
    private fun onOffValue(on: Boolean): Byte = if (on) 0x00 else 0x01

    fun buildSetPeripheralSourceSwitch(enabled: Boolean): ByteArray = TandemMessage(
        DT,
        PERI_SET_PARAM,
        byteArrayOf(PeripheralInquiredTypeTable2.SOURCE_SWITCH_CONTROL.code, onOffValue(enabled)),
    ).toByteArray()

    fun buildSetPeripheralFixedSource(address: String): ByteArray = TandemMessage(
        DT,
        PERI_SET_EXTENDED_PARAM,
        byteArrayOf(PeripheralInquiredTypeTable2.SOURCE_SWITCH_CONTROL.code) + address.toByteArray(Charsets.US_ASCII),
    ).toByteArray()

    /** SC `lg0.b0.b.h(z)`: writes OnOffSettingValue.from(z). The UI-level
     * inversion lives in the repository (official `x30/d.a`: `h(!z11)`). */
    fun buildSetPeripheralMusicHandOver(on: Boolean): ByteArray = TandemMessage(
        DT,
        PERI_SET_PARAM,
        byteArrayOf(PeripheralInquiredTypeTable2.MUSIC_HAND_OVER_SETTING.code, onOffValue(on)),
    ).toByteArray()

    fun buildSetPeripheralConnectivity(
        type: PeripheralInquiredTypeTable2,
        action: ConnectivityActionTypeTable2,
        address: String,
    ): ByteArray = TandemMessage(
        DT,
        PERI_SET_EXTENDED_PARAM,
        byteArrayOf(type.code, action.code) + address.toByteArray(Charsets.US_ASCII),
    ).toByteArray()

    fun buildGetVoiceGuidanceStatus(type: VoiceGuidanceInquiredTypeTable2): ByteArray =
        TandemMessage(DT, VG_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetVoiceGuidanceParam(type: VoiceGuidanceInquiredTypeTable2): ByteArray =
        TandemMessage(DT, VG_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    fun buildGetSafeListeningStatus(type: SafeListeningInquiredTypeTable2): ByteArray =
        TandemMessage(DT, SL_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    /** SAFE_LISTENING_GET_PARAM (0x56). Kept for the Tandem debug page; the engine
     * does not use it, because the reply (0x57) carries a single unrelated flag that
     * reads ENABLE whatever the two switches are — not the switch states. */
    fun buildGetSafeListeningParam(type: SafeListeningInquiredTypeTable2): ByteArray =
        TandemMessage(DT, SL_GET_PARAM, byteArrayOf(type.code)).toByteArray()

    /** SAFE_LISTENING_GET_EXTENDED_PARAM (0x5A): the headset replies with the
     * current sound pressure level (RET_EXTENDED_PARAM 0x5B, [type, level, errorCause]). */
    fun buildGetSafeListeningExtendedParam(type: SafeListeningInquiredTypeTable2): ByteArray =
        TandemMessage(DT, SL_GET_EXTENDED_PARAM, byteArrayOf(type.code)).toByteArray()

    /** SAFE_LISTENING_GET_CAPABILITY (0x50): the reply (RET_CAPABILITY 0x51)
     * carries the device's Safe Listening config, including the minimum poll
     * interval SC uses for the current-sound-pressure readout. */
    fun buildGetSafeListeningCapability(type: SafeListeningInquiredTypeTable2): ByteArray =
        TandemMessage(DT, SL_GET_CAPABILITY, byteArrayOf(type.code)).toByteArray()

    /** SAFE_LISTENING_SET_PARAM (0x58): payload [type, safeListening, preview].
     * (ON, OFF) is SC's `setParamOn` — it enables the persistent Safe Listening
     * feature, i.e. the headset's listening-log collection, which is the user's
     * own setting. (OFF, ON) is `setParamPreview`, what SC's live sound-pressure
     * view uses; (OFF, OFF) is `setParamOff`. The extended-param readout answers
     * with a zero level until one of the two is on. */
    fun buildSetSafeListeningParam(
        type: SafeListeningInquiredTypeTable2,
        first: Boolean,
        second: Boolean,
    ): ByteArray = TandemMessage(
        DT,
        SL_SET_PARAM,
        byteArrayOf(type.code, onOffValue(first), onOffValue(second)),
    ).toByteArray()

    fun buildGetLeaStatus(type: LeaInquiredTypeTable2): ByteArray =
        TandemMessage(DT, LEA_GET_STATUS, byteArrayOf(type.code)).toByteArray()

    fun buildGetLeaCapability(type: LeaInquiredTypeTable2): ByteArray =
        TandemMessage(DT, LEA_GET_CAPABILITY, byteArrayOf(type.code)).toByteArray()

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
            CONNECT_RET_SUPPORT_FUNCTION -> ParsedTandemResponse.SupportFunction(
                functions = parseSupportFunction(payload),
                table = SonyTable.NO_2,
                raw = raw,
            )
            POWER_RET_CAPABILITY, POWER_RET_STATUS, POWER_NTFY_STATUS,
            POWER_RET_PARAM, POWER_NTFY_PARAM -> parsePower(payload, raw)
            PERI_RET_CAPABILITY -> parsePeripheralCapability(payload, raw)
            PERI_RET_STATUS, PERI_NTFY_STATUS -> parsePeripheralStatus(payload, raw)
            PERI_RET_PARAM, PERI_NTFY_PARAM -> parsePeripheralParam(payload, raw)
            PERI_NTFY_EXTENDED_PARAM -> parsePeripheralExtendedParam(payload, raw)
            VG_RET_CAPABILITY, VG_RET_STATUS, VG_NTFY_STATUS,
            VG_RET_PARAM, VG_NTFY_PARAM -> parseVoiceGuidance(payload, raw)
            SL_RET_CAPABILITY -> parseSafeListeningCapability(payload, raw)
            SL_RET_STATUS, SL_NTFY_STATUS -> parseSafeListening(payload, raw)
            SL_RET_PARAM -> parseSafeListeningParam(payload, raw, carriesPreview = false)
            SL_NTFY_PARAM -> parseSafeListeningParam(payload, raw, carriesPreview = true)
            SL_RET_EXTENDED_PARAM -> parseSafeListeningExtendedParam(payload, raw)
            LEA_RET_CAPABILITY, LEA_RET_STATUS, LEA_NTFY_STATUS,
            LEA_RET_PARAM, LEA_NTFY_PARAM -> parseLea(command, payload, raw)
            PARTY_RET_CAPABILITY, PARTY_RET_STATUS, PARTY_NTFY_STATUS,
            PARTY_RET_PARAM, PARTY_NTFY_PARAM -> parseParty(payload, raw)
            SYS_RET_CAPABILITY, SYS_RET_STATUS, SYS_NTFY_STATUS,
            SYS_RET_PARAM, SYS_NTFY_PARAM,
            SYS_RET_EXTENDED_PARAM, SYS_NTFY_EXTENDED_PARAM -> parseSystem(payload, raw)
            else -> ParsedTandemResponse.Unknown(dataType.unsigned, command.unsigned, payload, raw)
        }
    }

    /**
     * Parse a V2 Table2 CONNECT_RET_SUPPORT_FUNCTION payload. Same wire layout
     * as Table1 (SC `ff0.C16478l`: [0]=0x07 [1]=0x00 [2]=count [3..]=(code,order)
     * pairs) but the FunctionTypes belong to Table NO_2. Returns the list
     * sorted by the order field.
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
                    val resolved = SonyV2FunctionType.fromByteCode(SonyTable.NO_2, code)
                    if (resolved != SonyV2FunctionType.OUT_OF_RANGE) {
                        add(SonySupportedFunction(code, order, SonyTable.NO_2))
                    }
                }
            }.sortedBy { it.order }
        }

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

    /** SC `lg0.p`: full frame is [cmd, type, maxPaired, maxConnected,
     * fileTransfer] (length 5); our payload excludes the command byte. */
    private fun parsePeripheralCapability(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        if (payload.size >= 4 && isMultipointType(payload[0])) {
            return ParsedTandemResponse.MultipointCapability(
                inquiredType = payload[0].unsigned,
                maxPairedDevices = payload[1].unsigned,
                maxConnectedDevices = payload[2].unsigned,
                fileTransferInMultiConnection = payload[3].unsigned,
                raw = raw,
            )
        }
        return parsePeripheral(payload, raw)
    }

    private fun parsePeripheralStatus(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        if (payload.size >= 3 && isMultipointType(payload[0])) {
            return ParsedTandemResponse.MultipointStatus(
                inquiredType = payload[0].unsigned,
                bluetoothMode = payload[1].unsigned,
                // EnableDisable: ENABLE=0x00, DISABLE=0x01 (SC `lg0.w`).
                enabled = payload[2].unsigned == 0,
                raw = raw,
            )
        }
        return parsePeripheral(payload, raw)
    }

    private fun parsePeripheralDevices(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.unsigned ?: return parsePeripheral(payload, raw)
        if (!isMultipointType(payload[0])) return parsePeripheral(payload, raw)
        val count = payload.getOrNull(1)?.unsigned ?: return parsePeripheral(payload, raw)
        val withClassOfDevice = type == PeripheralInquiredTypeTable2.PAIRING_DEVICE_MANAGEMENT_WITH_BT_CLASS_OF_DEVICE.code.unsigned
        val devices = ArrayList<MultipointDevice>(count)
        var index = 2
        repeat(count) {
            // SC `lg0.s` / `mg0.b`: address[17], connectedStatus[1], optional
            // class-of-device[3], nameLength[1], name[n]. connectedStatus is a
            // 1-based connection order, not a boolean; 0 means history/paired.
            val nameLengthOffset = index + if (withClassOfDevice) 21 else 18
            if (nameLengthOffset >= payload.size) return@repeat
            val address = payload.copyOfRange(index, index + 17).decodeToString().trimEnd('\u0000')
            val connectedStatus = payload[index + 17].unsigned
            val nameLength = payload[nameLengthOffset].unsigned
            val nameStart = nameLengthOffset + 1
            if (nameStart + nameLength > payload.size) return@repeat
            val name = payload.copyOfRange(nameStart, nameStart + nameLength).decodeToString()
            val deviceClass = if (withClassOfDevice) {
                (payload[index + 18].unsigned shl 16) or
                    (payload[index + 19].unsigned shl 8) or
                    payload[index + 20].unsigned
            } else 0xFFFFFF
            devices += MultipointDevice(address, connectedStatus, name, deviceClass)
            index = nameStart + nameLength
        }
        // Trailing byte (SC `lg0.s.h`): the connectedStatus value of the device
        // currently holding the playback right, 0 when nobody does.
        val playbackRight = payload.getOrNull(index)?.unsigned ?: 0
        return ParsedTandemResponse.MultipointDevices(type, devices, playbackRight, raw)
    }

    private fun parsePeripheralParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        when (payload.firstOrNull()?.unsigned) {
            PeripheralInquiredTypeTable2.SOURCE_SWITCH_CONTROL.code.unsigned -> {
                // RET `lg0.u`: [type, onOff]; NTFY `lg0.l` appends a result byte.
                if (payload.size >= 2) return ParsedTandemResponse.SourceSwitchStatus(payload[1].unsigned == 0, raw)
            }
            PeripheralInquiredTypeTable2.MUSIC_HAND_OVER_SETTING.code.unsigned -> {
                // OnOffSettingValue: ON=0x00 (SC `mg0.a.a` returns isOn()).
                if (payload.size >= 2) return ParsedTandemResponse.MusicHandOverStatus(payload[1].unsigned == 0, raw)
            }
        }
        return parsePeripheralDevices(payload, raw)
    }

    private fun parsePeripheralActionResult(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        if (payload.size >= 20 && isMultipointType(payload[0])) {
            return ParsedTandemResponse.MultipointActionResult(
                inquiredType = payload[0].unsigned,
                action = payload[1].unsigned,
                result = payload[2].unsigned,
                address = payload.copyOfRange(3, 20).decodeToString().trimEnd('\u0000'),
                raw = raw,
            )
        }
        return parsePeripheral(payload, raw)
    }

    private fun parsePeripheralExtendedParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        if (payload.size >= 19 && payload[0].unsigned == PeripheralInquiredTypeTable2.SOURCE_SWITCH_CONTROL.code.unsigned) {
            return ParsedTandemResponse.SourceSwitchResult(
                result = payload[1].unsigned,
                address = payload.copyOfRange(2, 19).decodeToString().trimEnd('\u0000'),
                raw = raw,
            )
        }
        return parsePeripheralActionResult(payload, raw)
    }

    private fun isMultipointType(type: Byte): Boolean =
        type == PeripheralInquiredTypeTable2.PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT.code ||
            type == PeripheralInquiredTypeTable2.PAIRING_DEVICE_MANAGEMENT_WITH_BT_CLASS_OF_DEVICE.code

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

    /** RET_PARAM (0x57) is [type, EnableDisable] — the persistent feature flag
     * only. NTFY_PARAM (0x59) is [type, safeListening, preview]. ENABLE and ON
     * are both 0x00. A 0x57 read cannot answer for preview, so it reports null
     * rather than defaulting to a value the headset never sent. */
    private fun parseSafeListeningParam(
        payload: ByteArray,
        raw: ByteArray,
        carriesPreview: Boolean,
    ): ParsedTandemResponse =
        ParsedTandemResponse.SafeListeningParam(
            type = payload.firstOrNull()?.let { SafeListeningInquiredTypeTable2.fromCode(it) },
            featureOn = payload.getOrNull(1)?.unsigned == 0x00,
            previewOn = if (carriesPreview) payload.getOrNull(2)?.unsigned == 0x00 else null,
            raw = raw,
        )

    /** SAFE_LISTENING_RET_EXTENDED_PARAM: payload [type, level, errorCause]. */
    private fun parseSafeListeningExtendedParam(payload: ByteArray, raw: ByteArray): ParsedTandemResponse =
        ParsedTandemResponse.SafeListeningExtendedParam(
            type = payload.firstOrNull()?.let { SafeListeningInquiredTypeTable2.fromCode(it) },
            level = payload.getOrNull(1)?.unsigned ?: 0,
            errorCause = payload.getOrNull(2)?.unsigned ?: 0,
            raw = raw,
        )

    /** SAFE_LISTENING_RET_CAPABILITY (HBS/TWS): payload after dataType+command is
     * [type, roundBase, timestampBase(4B BE), minimumInterval, logCapacity] — the
     * exact five fields SC's SlDeviceData carries (`wv.a.m112083J`). The headset
     * reports its minimum poll interval (seconds) here; SC polls the current
     * sound pressure every `1000 * minimumInterval`. */
    private fun parseSafeListeningCapability(payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val type = payload.firstOrNull()?.let { SafeListeningInquiredTypeTable2.fromCode(it) }
        if (payload.size < 7) {
            return ParsedTandemResponse.Table2Generic(
                family = Table2Family.SAFE_LISTENING.name,
                inquiredType = type?.code?.unsigned,
                values = payload.map { it.unsigned },
                raw = raw,
            )
        }
        return ParsedTandemResponse.SafeListeningCapability(
            type = type,
            minimumInterval = payload[6].unsigned,
            raw = raw,
        )
    }

    private fun parseLea(command: Byte, payload: ByteArray, raw: ByteArray): ParsedTandemResponse {
        val typeCode = payload.firstOrNull()?.unsigned
        val type = payload.firstOrNull()?.let { LeaInquiredTypeTable2.fromCode(it) }
        return when {
            command == LEA_RET_CAPABILITY -> when (typeCode) {
                0x01 -> ParsedTandemResponse.LeaCapability(
                    inquiredTypeCode = typeCode,
                    compatibility = payload.getOrNull(1)?.unsigned,
                    table = SonyTable.NO_2,
                    raw = raw,
                )
                0x02 -> ParsedTandemResponse.LeaCapability(
                    inquiredTypeCode = typeCode,
                    connectionModes = payload.drop(1).map { it.unsigned },
                    table = SonyTable.NO_2,
                    raw = raw,
                )
                0x04 -> ParsedTandemResponse.LeaCapability(
                    inquiredTypeCode = typeCode,
                    addresses = payload.drop(1)
                        .chunked(17)
                        .filter { it.size == 17 }
                        .map { bytes -> String(bytes.toByteArray(), Charsets.US_ASCII).trimEnd('\u0000') },
                    table = SonyTable.NO_2,
                    raw = raw,
                )
                else -> table2LeaGeneric(type, payload, raw)
            }
            command == LEA_RET_STATUS || command == LEA_NTFY_STATUS -> when (typeCode) {
                0x02 -> ParsedTandemResponse.LeaConnectionMode(
                    inquiredTypeCode = typeCode,
                    mode = payload.getOrNull(1)?.unsigned,
                    table = SonyTable.NO_2,
                    raw = raw,
                )
                0x04 -> ParsedTandemResponse.LeaStatus(
                    type = null,
                    values = payload.unsignedList(),
                    enabled = payload.getOrNull(1)?.let { code ->
                        LeaEnableDisable.entries.firstOrNull { it.code == code }
                    },
                    streamingStatusL = payload.getOrNull(2)?.let { code ->
                        LeaStreamingStatus.entries.firstOrNull { it.code == code }
                    },
                    streamingStatusR = null,
                    inquiredTypeCode = typeCode,
                    table = SonyTable.NO_2,
                    raw = raw,
                )
                else -> table2LeaGeneric(type, payload, raw)
            }
            command == LEA_RET_PARAM && typeCode == 0x02 -> ParsedTandemResponse.LeaConnectionMode(
                inquiredTypeCode = typeCode,
                mode = payload.getOrNull(1)?.unsigned,
                table = SonyTable.NO_2,
                raw = raw,
            )
            // Type 0x03 is the Identity Resolving Key (SC V2GetIdentityResolvingKeyRepository,
            // parsed by qb0.C26493v: sixteen bytes right after the inquired type).
            command == LEA_RET_PARAM && typeCode == 0x03 ->
                payload.drop(1).take(IRK_LENGTH).toByteArray()
                    .takeIf { it.size == IRK_LENGTH }
                    ?.let {
                        ParsedTandemResponse.LeaIdentityResolvingKey(
                            key = it,
                            table = SonyTable.NO_2,
                            raw = raw,
                        )
                    }
                    ?: table2LeaGeneric(type, payload, raw)
            command == LEA_RET_PARAM && typeCode == 0x04 -> ParsedTandemResponse.LeaPairedHistoryStatus(
                type = null,
                values = payload.unsignedList(),
                pairedHistory = payload.getOrNull(1)?.let { code ->
                    LeaPairedHistory.entries.firstOrNull { it.code == code }
                },
                inquiredTypeCode = typeCode,
                table = SonyTable.NO_2,
                raw = raw,
            )
            command == LEA_NTFY_PARAM && typeCode == 0x02 -> ParsedTandemResponse.LeaConnectionMode(
                inquiredTypeCode = typeCode,
                mode = payload.getOrNull(1)?.unsigned,
                result = payload.getOrNull(2)?.unsigned,
                table = SonyTable.NO_2,
                raw = raw,
            )
            else -> table2LeaGeneric(type, payload, raw)
        }
    }

    private fun table2LeaGeneric(
        type: LeaInquiredTypeTable2?,
        payload: ByteArray,
        raw: ByteArray,
    ): ParsedTandemResponse = ParsedTandemResponse.Table2Generic(
        family = Table2Family.LEA.name,
        inquiredType = type?.code?.unsigned,
        values = payload.drop(1).map { it.unsigned },
        raw = raw,
    )

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

enum class PeripheralBluetoothModeTable2(val code: Byte) {
    NORMAL_MODE(0x00),
    INQUIRY_SCAN_MODE(0x01),
    OUT_OF_RANGE(0xFF.toByte()),
}

enum class ConnectivityActionTypeTable2(val code: Byte) {
    DISCONNECT(0x00),
    CONNECT(0x01),
    UNPAIR(0x02),
    OUT_OF_RANGE(0xFF.toByte()),
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
    PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD(0x04),
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
    LIVE_KARAOKE(0x05),
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
