package dev.sonypods.headphones

import dev.sonypods.protocol.AmbientSoundMode
import dev.sonypods.protocol.DeviceInfoType
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.LeaInquiredType
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.PlaybackControl
import dev.sonypods.protocol.PlayInquiredType
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.SonyTandemV1Table1Protocol
import dev.sonypods.protocol.SonyTandemV1Table2Protocol
import dev.sonypods.protocol.SonyTandemV2Table1Protocol
import dev.sonypods.protocol.SonyTandemV2Table2Protocol

interface TandemCodec {
    val variant: HeadphoneProtocolVariant
    val defaultChannel: TandemChannel
    fun parse(raw: ByteArray): ParsedTandemResponse
    fun buildGetProtocolInfo(): ByteArray? = null
    fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray? = null
    fun buildGetDisplayFirmwareVersion(): ByteArray? = null
    fun buildGetBatteryStatus(type: PowerInquiredType): ByteArray? = null
    fun buildGetEqEbbStatus(type: EqEbbInquiredType): ByteArray? = null
    fun buildGetEqEbbParam(type: EqEbbInquiredType): ByteArray? = null
    fun buildGetEqEbbExtendedInfo(type: EqEbbInquiredType): ByteArray? = null
    fun buildSetEqPreset(
        preset: EqPresetId,
        type: EqEbbInquiredType,
        bandSteps: List<Int> = emptyList(),
    ): ByteArray? = null
    fun buildSetEqBands(
        preset: EqPresetId,
        type: EqEbbInquiredType,
        bandSteps: List<Int>,
    ): ByteArray? = buildSetEqPreset(preset, type, bandSteps)
    fun buildSetClearBass(level: Int): ByteArray? = null
    fun buildGetNcAsmStatus(type: NcAsmInquiredType): ByteArray? = null
    fun buildGetNcAsmParam(type: NcAsmInquiredType): ByteArray? = null
    fun buildSetNoiseControlMode(
        type: NcAsmInquiredType,
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
    ): ByteArray? = null
    fun buildSetNcOnOff(enabled: Boolean): ByteArray? = null
    fun buildSetAmbientSound(enabled: Boolean, mode: AmbientSoundMode): ByteArray? = null
    fun buildSetAmbientLevel(level: Int, enabled: Boolean, mode: AmbientSoundMode): ByteArray? = null
    fun buildGetPlaybackStatus(
        type: PlayInquiredType = PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT,
    ): ByteArray? = null
    fun buildPlayback(
        control: PlaybackControl,
        type: PlayInquiredType = PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT,
    ): ByteArray? = null
    fun buildGetLeaStatus(type: LeaInquiredType): ByteArray? = null
    fun buildGetLeaPairedHistory(type: LeaInquiredType): ByteArray? = null
    fun buildGetQuickAccess(): ByteArray? = null
    fun buildGetWearingStatus(): ByteArray? = null
}

object TandemCodecRegistry {
    fun codecFor(variant: HeadphoneProtocolVariant): TandemCodec = when (variant) {
        HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1 -> SonyTandemV1Table1Codec
        HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2 -> SonyTandemV1Table2Codec
        HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1 -> SonyTandemV2Table1Codec
        HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2 -> SonyTandemV2Table2Codec
        HeadphoneProtocolVariant.UNKNOWN -> UnknownTandemCodec
    }
}

object UnknownTandemCodec : TandemCodec {
    override val variant: HeadphoneProtocolVariant = HeadphoneProtocolVariant.UNKNOWN
    override val defaultChannel: TandemChannel
        get() = error("Unknown codec has no default channel")
    override fun parse(raw: ByteArray): ParsedTandemResponse =
        ParsedTandemResponse.Unknown(
            dataType = raw.getOrNull(0)?.toInt()?.and(0xFF),
            command = raw.getOrNull(1)?.toInt()?.and(0xFF),
            payload = raw.drop(2).toByteArray(),
            raw = raw,
        )
}

object SonyTandemV1Table1Codec : TandemCodec {
    override val variant: HeadphoneProtocolVariant = HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1
    override val defaultChannel: TandemChannel = TandemChannel.GATT_V1_MC

    override fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetDeviceInfo(type)

    override fun buildGetBatteryStatus(type: PowerInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetBatteryStatus(type)

    fun buildGetNcAsmParam(): ByteArray =
        SonyTandemV1Table1Protocol.buildGetNcAsmParam()

    override fun buildGetNcAsmParam(type: NcAsmInquiredType): ByteArray? =
        if (type == NcAsmInquiredType.V1_TABLE_SET1_NC_ASM) {
            SonyTandemV1Table1Protocol.buildGetNcAsmParam()
        } else {
            null
        }

    fun buildSetNoiseControlMode(
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
    ): ByteArray =
        SonyTandemV1Table1Protocol.buildSetNoiseControlMode(mode, ambientLevel, ambientMode)

    override fun buildSetNoiseControlMode(
        type: NcAsmInquiredType,
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
    ): ByteArray? =
        if (type == NcAsmInquiredType.V1_TABLE_SET1_NC_ASM) {
            SonyTandemV1Table1Protocol.buildSetNoiseControlMode(mode, ambientLevel, ambientMode)
        } else {
            null
        }

    // ── EQ/EBB (V1 type codes) ──

    override fun buildGetEqEbbStatus(type: EqEbbInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetEqEbbStatus(type)

    override fun buildGetEqEbbParam(type: EqEbbInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetEqEbbParam(type)

    override fun buildGetEqEbbExtendedInfo(type: EqEbbInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetEqEbbExtendedInfo(type)

    override fun buildSetEqPreset(
        preset: EqPresetId,
        type: EqEbbInquiredType,
        bandSteps: List<Int>,
    ): ByteArray =
        SonyTandemV1Table1Protocol.buildSetEqPreset(preset, type, bandSteps)

    override fun buildSetEqBands(
        preset: EqPresetId,
        type: EqEbbInquiredType,
        bandSteps: List<Int>,
    ): ByteArray =
        SonyTandemV1Table1Protocol.buildSetEqPreset(
            EqPresetId.UNSPECIFIED,
            EqEbbInquiredType.PRESET_EQ,
            bandSteps,
        )

    override fun buildSetClearBass(level: Int): ByteArray =
        SonyTandemV1Table1Protocol.buildSetClearBass(level)

    override fun buildGetPlaybackStatus(type: PlayInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetPlaybackStatus()

    override fun buildPlayback(control: PlaybackControl, type: PlayInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildPlayback(control)

    override fun parse(raw: ByteArray): ParsedTandemResponse =
        SonyTandemV1Table1Protocol.parse(raw)
}

object SonyTandemV1Table2Codec : TandemCodec {
    override val variant: HeadphoneProtocolVariant = HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2
    override val defaultChannel: TandemChannel = TandemChannel.GATT_V1_MC
    override fun parse(raw: ByteArray): ParsedTandemResponse =
        SonyTandemV1Table2Protocol.parse(raw)
}

object SonyTandemV2Table1Codec : TandemCodec {
    override val variant: HeadphoneProtocolVariant = HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1
    override val defaultChannel: TandemChannel = TandemChannel.GATT_V2_HPC

    override fun buildGetProtocolInfo(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetProtocolInfo()

    override fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetDeviceInfo(type)

    override fun buildGetDisplayFirmwareVersion(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetDisplayFirmwareVersion()

    override fun buildGetBatteryStatus(type: PowerInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetBatteryStatus(type)

    override fun buildGetEqEbbStatus(type: EqEbbInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetEqEbbStatus(type)

    fun buildGetEqEbbStatus(typeCode: Byte): ByteArray =
        SonyTandemV2Table1Protocol.buildGetEqEbbStatus(typeCode)

    override fun buildGetEqEbbParam(type: EqEbbInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetEqEbbParam(type)

    override fun buildGetEqEbbExtendedInfo(type: EqEbbInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetEqEbbExtendedInfo(type)

    fun buildGetEqEbbParam(typeCode: Byte): ByteArray =
        SonyTandemV2Table1Protocol.buildGetEqEbbParam(typeCode)

    override fun buildSetEqPreset(
        preset: EqPresetId,
        type: EqEbbInquiredType,
        bandSteps: List<Int>,
    ): ByteArray =
        SonyTandemV2Table1Protocol.buildSetEqPreset(preset, type, bandSteps)

    fun buildSetEqPreset(
        preset: EqPresetId,
        typeCode: Byte,
        bandSteps: List<Int> = emptyList(),
    ): ByteArray =
        SonyTandemV2Table1Protocol.buildSetEqPreset(preset, typeCode, bandSteps)

    override fun buildSetClearBass(level: Int): ByteArray =
        SonyTandemV2Table1Protocol.buildSetClearBass(level)

    fun buildSetClearBass(level: Int, ebbTypeCode: Byte): ByteArray =
        SonyTandemV2Table1Protocol.buildSetClearBass(level, ebbTypeCode)

    override fun buildGetNcAsmStatus(type: NcAsmInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetNcAsmStatus(type)

    override fun buildGetNcAsmParam(type: NcAsmInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetNcAsmParam(type)

    fun buildSetNoiseControlMode(
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
    ): ByteArray =
        SonyTandemV2Table1Protocol.buildSetNoiseControlMode(mode, ambientLevel, ambientMode)

    fun buildSetNcModeSwitchAndAmbientLevel(
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
    ): ByteArray =
        SonyTandemV2Table1Protocol.buildSetNcModeSwitchAndAmbientLevel(mode, ambientLevel, ambientMode)

    override fun buildSetNoiseControlMode(
        type: NcAsmInquiredType,
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
    ): ByteArray? =
        when (type) {
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                SonyTandemV2Table1Protocol.buildSetNcModeSwitchAndAmbientLevel(mode, ambientLevel, ambientMode, type)
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                SonyTandemV2Table1Protocol.buildSetNoiseControlMode(mode, ambientLevel, ambientMode, type)
            NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                SonyTandemV2Table1Protocol.buildSetAutoNcModeSwitchAndAmbientLevel(mode, ambientLevel, ambientMode)
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA ->
                SonyTandemV2Table1Protocol.buildSetDualNcAsmSeamlessNa(mode, ambientLevel, ambientMode)
            else -> null
        }

    override fun buildSetNcOnOff(enabled: Boolean): ByteArray =
        SonyTandemV2Table1Protocol.buildSetNcOnOff(enabled)

    override fun buildSetAmbientSound(enabled: Boolean, mode: AmbientSoundMode): ByteArray =
        SonyTandemV2Table1Protocol.buildSetAmbientSound(enabled, mode)

    override fun buildSetAmbientLevel(level: Int, enabled: Boolean, mode: AmbientSoundMode): ByteArray =
        SonyTandemV2Table1Protocol.buildSetAmbientLevel(level, enabled, mode)

    override fun buildGetPlaybackStatus(type: PlayInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetPlaybackStatus(type)

    override fun buildPlayback(control: PlaybackControl, type: PlayInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildPlayback(control, type)

    override fun buildGetLeaStatus(type: LeaInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetLeaStatus(type)

    override fun buildGetLeaPairedHistory(type: LeaInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetLeaPairedHistory(type)

    override fun buildGetQuickAccess(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetQuickAccess()

    override fun buildGetWearingStatus(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetWearingStatus()

    override fun parse(raw: ByteArray): ParsedTandemResponse =
        SonyTandemV2Table1Protocol.parse(raw)
}

object SonyTandemV2Table2Codec : TandemCodec {
    override val variant: HeadphoneProtocolVariant = HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2
    override val defaultChannel: TandemChannel = TandemChannel.GATT_V2_MC
    override fun parse(raw: ByteArray): ParsedTandemResponse =
        SonyTandemV2Table2Protocol.parse(raw)
}
