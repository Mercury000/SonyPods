package dev.sonypods.protocol

data class TandemMessage(
    val dataType: Byte,
    val command: Byte,
    val payload: ByteArray = byteArrayOf(),
) {
    fun toByteArray(): ByteArray = byteArrayOf(dataType, command) + payload

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TandemMessage) return false
        return dataType == other.dataType &&
            command == other.command &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = dataType.toInt()
        result = 31 * result + command
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object SonyTandemFrame {
    fun message(command: Byte, payload: ByteArray = byteArrayOf()): ByteArray =
        TandemMessage(SonyTandemConstants.DATA_MDR, command, payload).toByteArray()
}

/**
 * Parse a CONNECT_RET_PROTOCOL_INFO payload (command 0x01, V1 or V2).
 *
 * V1 (`v2=false`, SC `qe0.C26580m2`): message body after dataType is
 *   [cmd 0x01][type 0x00][vhi][vlo]
 * so the engine payload (dataType+command stripped) is
 *   [0]=type, [1]=version-hi, [2]=version-lo  (2-byte BE version).
 *
 * V2 (`v2=true`, SC `ff0.C16477k`): message body after dataType is
 *   [cmd 0x01][type 0x00][v3][v2][v1][v0][ena][ena]  (8 bytes, length-gated
 *   in SC by `mo604b`), and SC reads the 4-byte BE version from body[2..5]
 *   via `m69436c()`. The engine payload (dataType+command stripped) therefore
 *   is [0]=type, [1]=v3, [2]=v2, [3]=v1, [4]=v0.
 */
fun parseProtocolInfoPayload(payload: ByteArray, v2: Boolean = false): ParsedTandemResponse.ProtocolInfo? {
    if (v2) {
        if (payload.size < 5) return null
        val version = (payload[1].unsigned shl 24) or
            (payload[2].unsigned shl 16) or
            (payload[3].unsigned shl 8) or
            payload[4].unsigned
        return ParsedTandemResponse.ProtocolInfo(
            protocolVersion = version,
            raw = payload.copyOf(),
        )
    }
    if (payload.size < 3) return null
    val version = (payload[1].unsigned shl 8) or payload[2].unsigned
    return ParsedTandemResponse.ProtocolInfo(
        protocolVersion = version,
        raw = payload.copyOf(),
    )
}

/** Parse a CONNECT_RET_CAPABILITY_INFO (0x03) engine payload — identical layout in
 * V1 and V2. The engine payload (dataType+command stripped) is
 * `[0]=type FIXED_VALUE 0x00, [1]=capabilityCounter, [2]=identifierLen, [3..3+len-1]=identifier`.
 * SC V1 (`qe0.C26624v1.mo94092c`: type=bArr[1], counter=bArr[2], len=bArr[3],
 * id=bArr[4..]) and V2 (`ff0.C16471e`: gate `bArr.length - 4 == bArr[3]`, counter
 * m69415c()=bArr[2], id m69416e()=bArr[4..]) both read the counter from header
 * position 2 and cap the identifier at 128 bytes (excess is dropped, never an error).
 * Returns null for non-FIXED_VALUE type or truncated payloads.
 */
fun parseConnectRetCapabilityInfoPayload(
    payload: ByteArray,
): ParsedTandemResponse.ConnectCapabilityInfo? {
    if (payload.size < 4) return null
    if (payload[0].unsigned != 0x00) return null
    val counter = payload[1].unsigned
    val len = payload[2].unsigned
    val effectiveLen = minOf(len, 128)
    if (payload.size < 3 + effectiveLen) return null
    val identifier = String(payload, 3, effectiveLen, Charsets.UTF_8)
    return ParsedTandemResponse.ConnectCapabilityInfo(
        capabilityCounter = counter,
        identifier = identifier,
        raw = payload.copyOf(),
    )
}

sealed interface ParsedTandemResponse {
    val raw: ByteArray

    data class DeviceInfo(
        val type: DeviceInfoType?,
        val text: String?,
        override val raw: ByteArray,
        /** Raw numeric colour code from a SERIES_AND_COLOR_INFO payload (`payload[2]`), if any.
         * Used to match the catalog image by code so the fragile per-protocol colour-label
         * tables (which disagree between V1 and V2 for codes 0x06–0x0B) are bypassed. */
        val colorCode: Int? = null,
    ) : ParsedTandemResponse

    data class CommonStatus(
        val type: CommonInquiredType?,
        val text: String?,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class Battery(
        val kind: PowerInquiredType?,
        val values: List<Int?>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class EqEbb(
        val type: EqEbbInquiredType?,
        val enabled: Boolean? = null,
        val preset: EqPresetId? = null,
        val clearBass: Int? = null,
        val bandSteps: List<Int> = emptyList(),
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class EqBandInfo(
        val type: EqBandInformationType?,
        val value: Int,
    )

    data class EqEbbExtendedInfo(
        val type: EqEbbInquiredType?,
        val bands: List<EqBandInfo>,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class NoiseControl(
        val type: NcAsmInquiredType?,
        val values: List<Int>,
        val enabled: Boolean? = null,
        val ambientSoundEnabled: Boolean? = null,
        val ambientLevel: Int? = null,
        val ambientMode: AmbientSoundMode? = null,
        val controlMode: NoiseControlMode? = null,
        val noiseAdaptiveEnabled: Boolean? = null,
        val noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity? = null,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class PlaybackAck(
        val values: List<Int>,
        val status: PlaybackStatus = PlaybackStatus.UNKNOWN,
        /** STATUS payload[1]: 0x00=ENABLE. null = payload too short. */
        val enabled: Boolean? = null,
        val isUnsolicited: Boolean = false,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** V2: RET/NTFY_PARAM carries all four names in one message. */
    data class PlaybackMetadata(
        val track: PlaybackName,
        val album: PlaybackName,
        val artist: PlaybackName,
        val genre: PlaybackName,
        val isUnsolicited: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** V1: RET_PARAM single-field reply. */
    data class PlaybackMetadataField(
        val dataType: PlaybackDetailedDataType,
        val name: PlaybackName,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** V1: NTFY_PARAM content-less trigger; re-GET every name field on receipt. */
    data class PlaybackMetadataInvalidated(
        val dataType: PlaybackDetailedDataType,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class PlaybackVolume(
        val volume: Int,
        val isUnsolicited: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** AUDIO_RET/NTFY_PARAM for the DSEE / upscaling inquired types:
     * [enabled] mirrors UpscalingTypeAutoOff AUTO/OFF; null never occurs from a
     * parsed frame (off-range values are rejected as Unknown). */
    data class Upscaling(
        val enabled: Boolean,
        val inquiredTypeCode: Int,
        val isUnsolicited: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /**
     * AUDIO_RET_CAPABILITY for the upscaling inquired types (`cf0.e0`): the
     * [upscalingTypeCode] byte is the DSEE generation the headset reports
     * (DSEE_HX=0, DSEE=1, DSEE_HX_AI/Extreme=2, DSEE_ULTIMATE=3) and is what the
     * official row's title/description are picked from.
     */
    data class UpscalingCapability(
        val inquiredTypeCode: Int,
        val upscalingTypeCode: Int,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /**
     * AUDIO_RET/NTFY_PARAM for the connection-quality inquired types
     * (`cf0.i0` RET / `cf0.u` NTFY): [mode] is PriorMode. The LE-era 0x05 NTFY
     * appends a fourth byte announcing which audio stream is switching
     * (SC `SwitchingStream`); it rides in [switchingStreamCode] when present.
     */
    data class ConnectionQuality(
        val mode: ConnectionQualityMode,
        val switchingStreamCode: Int?,
        val inquiredTypeCode: Int,
        val isUnsolicited: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /**
     * AUDIO_RET/NTFY_STATUS for a connection-quality inquired type
     * (`cf0.t0` / `cf0.m`): the EnableDisable byte that decides whether the
     * setting is currently usable — official greys the options while DISABLED.
     */
    data class ConnectionQualityAvailability(
        val enabled: Boolean,
        val isUnsolicited: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** Structured PLAY_RET_CAPABILITY (v1 and v2). */
    data class PlaybackCapability(
        val inquiredTypeCode: Int,
        /** Volume step count; 0 = no volume control on this device. */
        val musicVolumeStep: Int,
        /** v1 reports these; v2 wire format omits them and SC hardcodes true. */
        val supportsPlaybackButtons: Boolean,
        val supportsMetadata: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class LeaStatus(
        val type: LeaInquiredType?,
        val values: List<Int>,
        val enabled: LeaEnableDisable? = null,
        val streamingStatusL: LeaStreamingStatus? = null,
        val streamingStatusR: LeaStreamingStatus? = null,
        val inquiredTypeCode: Int? = null,
        val table: SonyTable = SonyTable.NO_1,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class LeaPairedHistoryStatus(
        val type: LeaInquiredType?,
        val values: List<Int>,
        val pairedHistory: LeaPairedHistory? = null,
        val inquiredTypeCode: Int? = null,
        val table: SonyTable = SonyTable.NO_1,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** Table2 LEA_RET_CAPABILITY (0x61); its shape depends on the inquired type. */
    data class LeaCapability(
        val inquiredTypeCode: Int,
        val compatibility: Int? = null,
        val connectionModes: List<Int> = emptyList(),
        val addresses: List<String> = emptyList(),
        val table: SonyTable = SonyTable.NO_2,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** Table2 LE Audio connection-mode state/result. This is not paired history. */
    data class LeaConnectionMode(
        val inquiredTypeCode: Int,
        val mode: Int?,
        val result: Int? = null,
        val table: SonyTable = SonyTable.NO_2,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** V2 Table1 LEA_RET/NTFY_STATUS for CLASSIC_ONLY_LE_CLASSIC_SETTING (0x0C). */
    data class LeaSettingAvailability(
        val available: Boolean,
        val isNotification: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** LEA_RET/NTFY_PARAM for CLASSIC_ONLY_LE_CLASSIC_SETTING: [0x0C][OnOff]. */
    data class LeaParameterNotification(
        val setting: Int?,
        val enabled: LeaEnableDisable?,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class QuickAccess(
        val key: QuickAccessKey? = null,
        val functions: List<QuickAccessFunction> = emptyList(),
        /** Raw service IDs.  The list is cloud/device supplied and is not a
         * closed enum; unknown IDs must survive a read/write round trip. */
        val functionCodes: List<Int> = emptyList(),
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        val function: QuickAccessFunction?
            get() = functions.firstOrNull()
    }

    data class QuickAccessActionCapability(
        val action: AssignableSettingsAction,
        val defaultFunction: QuickAccessFunction?,
        val defaultFunctionCode: Int,
        val availableFunctions: List<QuickAccessFunction>,
        val availableFunctionCodes: List<Int>,
    )

    data class QuickAccessCapability(
        val key: QuickAccessKey,
        val type: AssignableSettingsType,
        val actions: List<QuickAccessActionCapability>,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class QuickAccessStatus(
        val enabled: Boolean,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** One action/function pair reported by ASSIGNABLE_SETTINGS capability data. */
    data class AssignableSettingsActionCapability(
        val action: AssignableSettingsAction,
        val defaultFunction: AssignableSettingsFunction,
        val availableFunctions: List<AssignableSettingsFunction>,
    )

    /** Capability data for one physical key/control group. */
    data class AssignableSettingsKeyCapability(
        val key: AssignableSettingsKey,
        val type: AssignableSettingsType,
        val defaultPreset: AssignableSettingsPreset,
        val presets: List<AssignableSettingsPreset>,
        val actionsByPreset: Map<AssignableSettingsPreset, List<AssignableSettingsActionCapability>>,
    )

    data class AssignableSettingsCapability(
        val keys: List<AssignableSettingsKeyCapability>,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** SYSTEM_RET_PARAM / NTFY_PARAM for ASSIGNABLE_SETTINGS: current preset per key. */
    data class AssignableSettingsPresets(
        val presets: List<AssignableSettingsPreset>,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** SYSTEM_RET_STATUS / NTFY_STATUS for ASSIGNABLE_SETTINGS: enabled per key. */
    data class AssignableSettingsStatus(
        val enabled: List<Boolean>,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class AssignableSettingsMapping(
        val preset: AssignableSettingsPreset,
        val mappings: List<AssignableSettingsActionFunction>,
    )

    data class AssignableSettingsActionFunction(
        val action: AssignableSettingsAction,
        val function: AssignableSettingsFunction,
    )

    /** SYSTEM_RET_EXT_PARAM / NTFY_EXT_PARAM for ASSIGNABLE_SETTINGS. */
    data class AssignableSettingsExtendedParam(
        val mappings: List<AssignableSettingsMapping>,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class WearingStatus(
        val status: WearingDetectionStatus? = null,
        val result: WearingDetectionResult? = null,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** COMMON GET/RET/NTFY_STATUS for AUDIO_CODEC (V2 inqType 0x02; V1 has the
     * dedicated 0x18/0x19/0x1B commands with the same body). Wire payload after
     * dataType+command is `[inqType][codecByte]`; [codec] is null for bytes that
     * no badge draws (UNSETTLED/OTHER/unknown), mirroring SC hiding the view. */
    data class AudioCodecStatus(
        val codec: SoundQualityCodec?,
        val isUnsolicited: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioCodecStatus) return false
            return codec == other.codec && isUnsolicited == other.isUnsolicited &&
                raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int = 31 * (codec?.hashCode() ?: 0) + raw.contentHashCode()
    }

    /** COMMON UPSCALING_EFFECT report (V2 `0E 13/15 03 <type> <status>`; V1 uses
     * dedicated 0x14/0x15/0x17 commands, same field order). [generation] null on
     * out-of-table bytes — V2 may report DSEE_ULTIMATE(3) where V1's table stops
     * at DSEE_HX_AI(2). */
    data class UpscalingEffect(
        val generation: DseeGeneration?,
        val state: DseeEffectState?,
        val isUnsolicited: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UpscalingEffect) return false
            return generation == other.generation && state == other.state &&
                isUnsolicited == other.isUnsolicited && raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int =
            31 * ((generation?.hashCode() ?: 0) * 31 + (state?.hashCode() ?: 0)) +
                raw.contentHashCode()
    }

    /** CONNECT_RET_PROTOCOL_INFO: the runtime protocol-version number the device
     * negotiates at connection time. SC validates it against a fixed whitelist
     * (`C29903d.f85968b`, protocol versions 0x1000..0x7030) and refuses to
     * continue for out-of-whitelist values. Wire payload (after dataType):
     * `[cmd 0x01][type 0x00][protocol-version 2 bytes BE]`. */
    data class ProtocolInfo(
        val protocolVersion: Int,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProtocolInfo) return false
            return protocolVersion == other.protocolVersion && raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int = 31 * protocolVersion + raw.contentHashCode()
    }

    /** CONNECT_RET_SUPPORT_FUNCTION: the authoritative per-model FunctionType list. */
    data class SupportFunction(
        val functions: List<SonySupportedFunction>,
        val table: SonyTable = SonyTable.INVALID,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SupportFunction) return false
            return functions == other.functions && raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int = 31 * functions.hashCode() + raw.contentHashCode()
    }

    /**
     * CONNECT_RET_CAPABILITY_INFO (0x03): the connect-time capability counter and
     * identifier. SC (`C29903d.m109368F` / `C30916e`) compares the counter against
     * the persisted one for this device (keyed by the identifier); a match means the
     * cached capability tableset is still valid, so the per-domain capability probe
     * is skipped ("Omit the getting capability") and the stored tableset restored.
     * Wire layout is identical in V1 and V2 — engine payload (dataType+command
     * stripped) is `[0]=type FIXED_VALUE, [1]=capabilityCounter, [2]=identifierLen,
     * [3..]=identifier` (SC V1 `qe0.C26624v1`, V2 `ff0.C16471e`).
     */
    data class ConnectCapabilityInfo(
        val capabilityCounter: Int,
        val identifier: String,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ConnectCapabilityInfo) return false
            return capabilityCounter == other.capabilityCounter &&
                identifier == other.identifier &&
                raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int {
            var result = capabilityCounter
            result = 31 * result + identifier.hashCode()
            result = 31 * result + raw.contentHashCode()
            return result
        }
    }

    /** RET_CAPABILITY for a capability domain (NCASM/EQEBB/PLAY/...). The raw
     * payload is the authoritative per-(domain, InquiredType) capability blob the
     * device returns to a GET_CAPABILITY probe; the engine records it as probe
     * evidence and derives its feature/query/writable sets from the FunctionType
     * list that triggered the probe (SC builds its capability tableset the same way). */
    data class CapabilityInfo(
        val domain: String,
        val inquiredTypeCode: Int?,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CapabilityInfo) return false
            return domain == other.domain &&
                inquiredTypeCode == other.inquiredTypeCode &&
                values == other.values &&
                raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int {
            var result = domain.hashCode()
            result = 31 * result + (inquiredTypeCode ?: 0)
            result = 31 * result + values.hashCode()
            result = 31 * result + raw.contentHashCode()
            return result
        }
    }

    data class Unknown(
        val dataType: Int?,
        val command: Int?,
        val payload: ByteArray,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class Table2Common(
        val family: String,
        val command: Int,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Table2Common) return false
            return family == other.family &&
                command == other.command &&
                values == other.values &&
                raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int {
            var result = family.hashCode()
            result = 31 * result + command
            result = 31 * result + values.hashCode()
            result = 31 * result + raw.contentHashCode()
            return result
        }
    }

    data class Table2Generic(
        val family: String,
        val inquiredType: Int?,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Table2Generic) return false
            return family == other.family &&
                inquiredType == other.inquiredType &&
                values == other.values &&
                raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int {
            var result = family.hashCode()
            result = 31 * result + (inquiredType ?: 0)
            result = 31 * result + values.hashCode()
            result = 31 * result + raw.contentHashCode()
            return result
        }
    }

    data class MultipointCapability(
        val inquiredType: Int,
        val maxPairedDevices: Int,
        val maxConnectedDevices: Int,
        val fileTransferInMultiConnection: Int,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class MultipointStatus(
        val inquiredType: Int,
        val bluetoothMode: Int,
        val enabled: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class MultipointDevices(
        val inquiredType: Int,
        val devices: List<MultipointDevice>,
        /** connectedStatus value of the playback-right holder, 0 = none. */
        val playbackRight: Int,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class MultipointActionResult(
        val inquiredType: Int,
        val action: Int,
        val result: Int,
        val address: String,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class SourceSwitchStatus(
        val enabled: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class SourceSwitchResult(
        val result: Int,
        val address: String,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class MusicHandOverStatus(
        val enabled: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /**
     * GENERAL_SETTING_RET_CAPABILITY (V2 Table1, 0xD1). A GS slot's title is
     * matched against `GS_TITLE_MULTIPOINT_SETTING` ("MULTIPOINT_SETTING") —
     * the official app's `DeviceCapabilityTableset2.E1()` discovery — to find
     * which of the 0xD1..0xD4 slots is the "同时连接2台设备" toggle.
     */
    data class GeneralSettingCapability(
        val type: Int?,
        val settingType: Int?,
        val stringFormat: Int?,
        val title: String,
        val description: String,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** GENERAL_SETTING_RET/NTFY_STATUS (0xD3/0xD5): EnableDisable of the slot. */
    data class GeneralSettingStatus(
        val type: Int?,
        val enabled: Boolean?,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** GENERAL_SETTING_RET/NTFY_PARAM (0xD7/0xD9): the boolean slot value. */
    data class GeneralSettingParam(
        val type: Int?,
        val settingType: Int?,
        val on: Boolean?,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** ALERT_NTFY_PARAM (0x99, FIXED_MESSAGE): device requests app confirmation for
     * a change (e.g. multipoint reconnection). Reply via ALERT_SET_PARAM echoing the
     * same messageType; see [dev.sonypods.protocol.SonyTandemV2Table1Protocol.buildReplyAlertFixingMessage]. */
    data class AlertFixedMessage(
        val messageType: Int,
        val actionType: Int,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /** ALERT_NTFY_PARAM FIXED_MESSAGE sent while the app is foreground. */
    data class AlertForegroundMessage(
        val messageType: Int,
        val actionType: Int,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class AlertFixedMessageWithLeftRightSelection(
        val messageType: Int,
        val defaultSelectedSide: Int,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class AlertLeAudioNotification(
        val confirmationType: Int,
        val isNotification: Boolean,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    /**
     * ALERT_NTFY_PARAM FLEXIBLE_MESSAGE. The item list is device supplied;
     * unknown item ids are retained so every model can be displayed and
     * acknowledged without a model-specific table.
     */
    data class AlertFlexibleMessage(
        val messageType: Int,
        val itemCodes: List<Int>,
        val actionType: Int,
        override val raw: ByteArray,
    ) : ParsedTandemResponse
}

typealias AssignableSettingsActionCapability = ParsedTandemResponse.AssignableSettingsActionCapability
typealias AssignableSettingsKeyCapability = ParsedTandemResponse.AssignableSettingsKeyCapability
typealias AssignableSettingsCapability = ParsedTandemResponse.AssignableSettingsCapability
typealias AssignableSettingsMapping = ParsedTandemResponse.AssignableSettingsMapping
typealias AssignableSettingsActionFunction = ParsedTandemResponse.AssignableSettingsActionFunction

data class MultipointDevice(
    val address: String,
    /** SC `lg0.a.d()`: 1-based connection order; 0 = paired but not connected. */
    val connectedStatus: Int,
    val name: String,
    /** Bluetooth Class of Device; 0xFFFFFF when the type does not carry it. */
    val deviceClass: Int = 0xFFFFFF,
) {
    val connected: Boolean get() = connectedStatus > 0
}

/** One playback name field (track/album/artist/genre). */
data class PlaybackName(
    val text: String,
    val status: PlaybackNameStatus,
)

val Byte.unsigned: Int
    get() = toInt() and 0xFF

fun ByteArray.hexString(): String = joinToString(" ") { "%02X".format(it.unsigned) }

fun ByteArray.unsignedList(): List<Int> = map { it.unsigned }

fun Byte.percentageOrNull(): Int? = unsigned.takeIf { it in 0..100 }
