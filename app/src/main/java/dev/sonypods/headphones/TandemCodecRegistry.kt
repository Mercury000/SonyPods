package dev.sonypods.headphones

import dev.sonypods.protocol.AmbientSoundMode
import dev.sonypods.protocol.DeviceInfoType
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.LeaInquiredType
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.NoiseAdaptiveSensitivity
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.PlaybackControl
import dev.sonypods.protocol.PlaybackDetailedDataType
import dev.sonypods.protocol.PlayInquiredType
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.SonyTandemV1Table1Protocol
import dev.sonypods.protocol.SystemInquiredType
import dev.sonypods.protocol.SonyTandemV1Table2Protocol
import dev.sonypods.protocol.SonyTandemV2Table1Protocol
import dev.sonypods.protocol.SonyTandemV2Table2Protocol
import dev.sonypods.protocol.AssignableSettingsMapping
import dev.sonypods.protocol.AssignableSettingsPreset

interface TandemCodec {
    val variant: HeadphoneProtocolVariant
    val defaultChannel: TandemChannel
    fun parse(raw: ByteArray): ParsedTandemResponse
    fun buildGetSupportFunction(): ByteArray? = null
    fun buildGetProtocolInfo(): ByteArray? = null
    fun buildGetCapabilityInfo(): ByteArray? = null
    fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray? = null
    fun buildGetDisplayFirmwareVersion(): ByteArray? = null
    fun buildGetBatteryStatus(type: PowerInquiredType): ByteArray? = null
    fun buildPowerOff(): ByteArray? = null
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
    fun buildGetNcAsmCapability(type: NcAsmInquiredType): ByteArray? = null
    fun buildGetEqEbbCapability(type: EqEbbInquiredType): ByteArray? = null
    fun buildGetPlayCapability(type: PlayInquiredType): ByteArray? = null
    fun buildGetSystemCapability(type: SystemInquiredType): ByteArray? = null
    fun buildSetNoiseControlMode(
        type: NcAsmInquiredType,
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
        noiseAdaptive: Boolean = false,
        noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.STANDARD,
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
    /** Fetch track metadata; v1 needs four single-field GETs, v2 one bulk GET. */
    fun buildGetPlaybackMetadata(type: PlayInquiredType): List<ByteArray> = emptyList()
    /** volumeType is only meaningful on v2 (0x20 plain / 0x30 AND_MUTE variant). */
    fun buildGetPlaybackVolume(volumeType: PlayInquiredType): ByteArray? = null
    fun buildSetPlaybackVolume(volume: Int, volumeType: PlayInquiredType): ByteArray? = null
    fun buildGetLeaStatus(type: LeaInquiredType): ByteArray? = null
    fun buildGetLeaPairedHistory(type: LeaInquiredType): ByteArray? = null
    fun buildGetLeAudioSettingAvailability(): ByteArray? = null
    fun buildGetLeAudioSetting(): ByteArray? = null
    fun buildSetLeAudioEnabled(
        enabled: Boolean,
        changeConnectionMethod: Boolean = true,
    ): ByteArray? = null
    fun buildGetQuickAccess(): ByteArray? = null
    fun buildGetQuickAccessCapability(): ByteArray? = null
    fun buildGetQuickAccessStatus(): ByteArray? = null
    fun buildSetQuickAccess(functionCodes: List<Int>): ByteArray? = null
    fun buildGetWearingStatus(): ByteArray? = null
    fun buildGetAssignableSettingsCapability(): ByteArray? = null
    fun buildGetAssignableSettingsCapability(type: SystemInquiredType): ByteArray? =
        if (type == SystemInquiredType.ASSIGNABLE_SETTINGS) buildGetAssignableSettingsCapability() else null
    fun buildGetAssignableSettingsStatus(): ByteArray? = null
    fun buildGetAssignableSettingsStatus(type: SystemInquiredType): ByteArray? =
        if (type == SystemInquiredType.ASSIGNABLE_SETTINGS) buildGetAssignableSettingsStatus() else null
    fun buildGetAssignableSettingsPresets(): ByteArray? = null
    fun buildGetAssignableSettingsPresets(type: SystemInquiredType): ByteArray? =
        if (type == SystemInquiredType.ASSIGNABLE_SETTINGS) buildGetAssignableSettingsPresets() else null
    fun buildGetAssignableSettingsExtendedParam(): ByteArray? = null
    fun buildGetAssignableSettingsExtendedParam(type: SystemInquiredType): ByteArray? =
        if (type == SystemInquiredType.ASSIGNABLE_SETTINGS) buildGetAssignableSettingsExtendedParam() else null
    fun buildSetAssignableSettingsPresets(presets: List<AssignableSettingsPreset>): ByteArray? = null
    fun buildSetAssignableSettingsPresets(
        type: SystemInquiredType,
        presets: List<AssignableSettingsPreset>,
    ): ByteArray? = if (type == SystemInquiredType.ASSIGNABLE_SETTINGS) {
        buildSetAssignableSettingsPresets(presets)
    } else {
        null
    }
    fun buildSetAssignableSettingsExtendedParam(mappings: List<AssignableSettingsMapping>): ByteArray? = null
    fun buildSetAssignableSettingsExtendedParam(
        type: SystemInquiredType,
        mappings: List<AssignableSettingsMapping>,
    ): ByteArray? = if (type == SystemInquiredType.ASSIGNABLE_SETTINGS) {
        buildSetAssignableSettingsExtendedParam(mappings)
    } else {
        null
    }
    fun buildGetGeneralSettingCapability(type: Byte): ByteArray? = null
    fun buildGetGeneralSettingStatus(type: Byte): ByteArray? = null
    fun buildGetGeneralSettingParam(type: Byte): ByteArray? = null
    fun buildSetGeneralSetting(type: Byte, on: Boolean): ByteArray? = null
    fun generalSettingSlots(): List<Byte> = emptyList()
    fun buildReplyAlertFixingMessage(messageType: Int, positive: Boolean): ByteArray? = null
    fun buildReplyAlertForegroundMessage(messageType: Int, positive: Boolean): ByteArray? = null
    fun buildReplyAlertFlexibleMessage(messageType: Int, positive: Boolean): ByteArray? = null
    fun buildReplyAlertFixedMessageWithLeftRightSelection(messageType: Int, positive: Boolean): ByteArray? = null
    fun buildSetAlertAppBecomesForeground(enable: Boolean): ByteArray? = null
    fun buildSetAlertFixedMessage(enable: Boolean): ByteArray? = null
    fun buildSetAlertLeAudioNotification(enable: Boolean): ByteArray? = null
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

    override fun buildGetSupportFunction(): ByteArray =
        SonyTandemV1Table1Protocol.buildGetSupportFunction()

    override fun buildGetProtocolInfo(): ByteArray =
        SonyTandemV1Table1Protocol.buildGetProtocolInfo()

    override fun buildGetCapabilityInfo(): ByteArray =
        SonyTandemV1Table1Protocol.buildGetCapabilityInfo()

    override fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetDeviceInfo(type)

    override fun buildGetBatteryStatus(type: PowerInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetBatteryStatus(type)

    override fun buildPowerOff(): ByteArray =
        SonyTandemV1Table1Protocol.buildPowerOff()

    fun buildGetNcAsmParam(): ByteArray =
        SonyTandemV1Table1Protocol.buildGetNcAsmParam()

    override fun buildGetNcAsmParam(type: NcAsmInquiredType): ByteArray? =
        if (type == NcAsmInquiredType.V1_TABLE_SET1_NC_ASM) {
            SonyTandemV1Table1Protocol.buildGetNcAsmParam()
        } else {
            null
        }

    override fun buildGetNcAsmCapability(type: NcAsmInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetNcAsmCapability(type)

    override fun buildGetEqEbbCapability(type: EqEbbInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetEqEbbCapability(type)

    override fun buildGetPlayCapability(type: PlayInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetPlayCapability(type)

    override fun buildGetSystemCapability(type: SystemInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetSystemCapability(type)

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
        noiseAdaptive: Boolean,
        noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity,
    ): ByteArray? =
        if (type == NcAsmInquiredType.V1_TABLE_SET1_NC_ASM) {
            // V1 has no noise-adaptive concept; the extra params are ignored.
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

    override fun buildGetPlaybackMetadata(type: PlayInquiredType): List<ByteArray> = listOf(
        PlaybackDetailedDataType.TRACK_NAME,
        PlaybackDetailedDataType.ALBUM_NAME,
        PlaybackDetailedDataType.ARTIST_NAME,
        PlaybackDetailedDataType.GENRE_NAME,
    ).map(SonyTandemV1Table1Protocol::buildGetPlaybackParam)

    override fun buildGetPlaybackVolume(volumeType: PlayInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildGetPlaybackParam(PlaybackDetailedDataType.VOLUME)

    override fun buildSetPlaybackVolume(volume: Int, volumeType: PlayInquiredType): ByteArray =
        SonyTandemV1Table1Protocol.buildSetPlaybackVolume(volume)

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

    override fun buildGetSupportFunction(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetSupportFunction()

    override fun buildGetProtocolInfo(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetProtocolInfo()

    override fun buildGetCapabilityInfo(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetCapabilityInfo()

    override fun buildGetDeviceInfo(type: DeviceInfoType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetDeviceInfo(type)

    override fun buildGetDisplayFirmwareVersion(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetDisplayFirmwareVersion()

    override fun buildGetBatteryStatus(type: PowerInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetBatteryStatus(type)

    override fun buildPowerOff(): ByteArray =
        SonyTandemV2Table1Protocol.buildPowerOff()

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

    override fun buildGetNcAsmCapability(type: NcAsmInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetNcAsmCapability(type)

    override fun buildGetEqEbbCapability(type: EqEbbInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetEqEbbCapability(type)

    override fun buildGetPlayCapability(type: PlayInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetPlayCapability(type)

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
        noiseAdaptive: Boolean,
        noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity,
    ): ByteArray? =
        when (type) {
            // All V2 NCASM types share the same official-layout dispatcher; V1
            // and the test-mode type have no V2 SET_PARAM and stay unsupported.
            NcAsmInquiredType.V1_TABLE_SET1_NC_ASM,
            NcAsmInquiredType.NC_TEST_MODE -> null
            else -> SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                mode, ambientLevel, ambientMode, type, noiseAdaptive, noiseAdaptiveSensitivity,
            )
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

    override fun buildGetPlaybackMetadata(type: PlayInquiredType): List<ByteArray> =
        listOf(SonyTandemV2Table1Protocol.buildGetPlaybackParam(type))

    override fun buildGetPlaybackVolume(volumeType: PlayInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetPlaybackParam(volumeType)

    override fun buildSetPlaybackVolume(volume: Int, volumeType: PlayInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildSetPlaybackVolume(volume, volumeType)

    override fun buildGetLeaStatus(type: LeaInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetLeaStatus(type)

    override fun buildGetLeaPairedHistory(type: LeaInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetLeaPairedHistory(type)

    override fun buildGetLeAudioSettingAvailability(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetLeAudioSettingAvailability()

    override fun buildGetLeAudioSetting(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetLeAudioSetting()

    override fun buildSetLeAudioEnabled(
        enabled: Boolean,
        changeConnectionMethod: Boolean,
    ): ByteArray =
        SonyTandemV2Table1Protocol.buildSetLeAudioEnabled(enabled, changeConnectionMethod)

    override fun buildGetQuickAccess(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetQuickAccess()

    override fun buildGetQuickAccessCapability(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetQuickAccessCapability()

    override fun buildGetQuickAccessStatus(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetQuickAccessStatus()

    override fun buildSetQuickAccess(functionCodes: List<Int>): ByteArray =
        SonyTandemV2Table1Protocol.buildSetQuickAccessCodes(functionCodes)

    override fun buildGetWearingStatus(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetWearingStatus()

    override fun buildGetAssignableSettingsCapability(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetAssignableSettingsCapability()

    override fun buildGetAssignableSettingsCapability(type: SystemInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetAssignableSettingsCapability(type)

    override fun buildGetSystemCapability(type: SystemInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetSystemCapability(type)

    override fun buildGetAssignableSettingsStatus(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetAssignableSettingsStatus()

    override fun buildGetAssignableSettingsStatus(type: SystemInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetAssignableSettingsStatus(type)

    override fun buildGetAssignableSettingsPresets(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetAssignableSettingsPresets()

    override fun buildGetAssignableSettingsPresets(type: SystemInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetAssignableSettingsPresets(type)

    override fun buildGetAssignableSettingsExtendedParam(): ByteArray =
        SonyTandemV2Table1Protocol.buildGetAssignableSettingsExtendedParam()

    override fun buildGetAssignableSettingsExtendedParam(type: SystemInquiredType): ByteArray =
        SonyTandemV2Table1Protocol.buildGetAssignableSettingsExtendedParam(type)

    override fun buildSetAssignableSettingsPresets(presets: List<AssignableSettingsPreset>): ByteArray =
        SonyTandemV2Table1Protocol.buildSetAssignableSettingsPresets(presets)

    override fun buildSetAssignableSettingsPresets(
        type: SystemInquiredType,
        presets: List<AssignableSettingsPreset>,
    ): ByteArray = SonyTandemV2Table1Protocol.buildSetAssignableSettingsPresets(type, presets)

    override fun buildSetAssignableSettingsExtendedParam(mappings: List<AssignableSettingsMapping>): ByteArray =
        SonyTandemV2Table1Protocol.buildSetAssignableSettingsExtendedParam(mappings)

    override fun buildSetAssignableSettingsExtendedParam(
        type: SystemInquiredType,
        mappings: List<AssignableSettingsMapping>,
    ): ByteArray = SonyTandemV2Table1Protocol.buildSetAssignableSettingsExtendedParam(type, mappings)

    override fun buildGetGeneralSettingCapability(type: Byte): ByteArray =
        SonyTandemV2Table1Protocol.buildGetGeneralSettingCapability(type)

    override fun buildGetGeneralSettingStatus(type: Byte): ByteArray =
        SonyTandemV2Table1Protocol.buildGetGeneralSettingStatus(type)

    override fun buildGetGeneralSettingParam(type: Byte): ByteArray =
        SonyTandemV2Table1Protocol.buildGetGeneralSettingParam(type)

    override fun buildSetGeneralSetting(type: Byte, on: Boolean): ByteArray =
        SonyTandemV2Table1Protocol.buildSetGeneralSetting(type, on)

    override fun generalSettingSlots(): List<Byte> = SonyTandemV2Table1Protocol.generalSettingSlots()

    override fun buildReplyAlertFixingMessage(messageType: Int, positive: Boolean): ByteArray =
        SonyTandemV2Table1Protocol.buildReplyAlertFixingMessage(messageType, positive)

    override fun buildReplyAlertForegroundMessage(messageType: Int, positive: Boolean): ByteArray =
        SonyTandemV2Table1Protocol.buildReplyAlertForegroundMessage(messageType, positive)

    override fun buildReplyAlertFlexibleMessage(messageType: Int, positive: Boolean): ByteArray =
        SonyTandemV2Table1Protocol.buildReplyAlertFlexibleMessage(messageType, positive)

    override fun buildReplyAlertFixedMessageWithLeftRightSelection(messageType: Int, positive: Boolean): ByteArray =
        SonyTandemV2Table1Protocol.buildReplyAlertFixedMessageWithLeftRightSelection(messageType, positive)

    fun buildReplyAlertFixedMessageWithLeftRightSelection(messageType: Int, action: Int): ByteArray =
        SonyTandemV2Table1Protocol.buildReplyAlertFixedMessageWithLeftRightSelection(messageType, action)

    override fun buildSetAlertAppBecomesForeground(enable: Boolean): ByteArray =
        SonyTandemV2Table1Protocol.buildSetAlertAppBecomesForeground(enable)

    override fun buildSetAlertFixedMessage(enable: Boolean): ByteArray =
        SonyTandemV2Table1Protocol.buildSetAlertFixedMessage(enable)

    override fun buildSetAlertLeAudioNotification(enable: Boolean): ByteArray =
        SonyTandemV2Table1Protocol.buildSetAlertLeAudioNotification(enable)

    override fun parse(raw: ByteArray): ParsedTandemResponse =
        SonyTandemV2Table1Protocol.parse(raw)
}

object SonyTandemV2Table2Codec : TandemCodec {
    override val variant: HeadphoneProtocolVariant = HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2
    override val defaultChannel: TandemChannel = TandemChannel.GATT_V2_MC
    override fun parse(raw: ByteArray): ParsedTandemResponse =
        SonyTandemV2Table2Protocol.parse(raw)
}
