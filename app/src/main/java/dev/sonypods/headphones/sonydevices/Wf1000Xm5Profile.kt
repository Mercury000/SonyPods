package dev.sonypods.headphones.sonydevices

import dev.sonypods.headphones.ClearBassWriteMode
import dev.sonypods.headphones.EqDeviceConfig
import dev.sonypods.headphones.HeadphoneCapabilities
import dev.sonypods.headphones.HeadphoneFeature
import dev.sonypods.headphones.HeadphoneFormFactor
import dev.sonypods.headphones.HeadphoneProtocolVariant
import dev.sonypods.headphones.ProfileTemplate
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.PowerInquiredType

object Wf1000Xm5Profile {
    private val features = setOf(
        HeadphoneFeature.DEVICE_INFO,
        HeadphoneFeature.BATTERY,
        HeadphoneFeature.NOISE_CONTROL,
        HeadphoneFeature.AMBIENT_LEVEL,
        HeadphoneFeature.AMBIENT_VOICE_MODE,
        HeadphoneFeature.PLAYBACK_CONTROL,
        HeadphoneFeature.EQ,
        HeadphoneFeature.CLEAR_BASS,
        HeadphoneFeature.LEA_STATUS,
        HeadphoneFeature.QUICK_ACCESS,
        HeadphoneFeature.WEARING_STATUS,
    )

    val template = ProfileTemplate(
        modelName = "WF-1000XM5",
        series = "PREMIUM",
        capabilities = HeadphoneCapabilities(
            features = features,
            formFactor = HeadphoneFormFactor.TRUE_WIRELESS,
            batteryQueries = listOf(
                PowerInquiredType.LEFT_RIGHT_BATTERY,
                PowerInquiredType.CRADLE_BATTERY,
            ),
            noiseControlQueryTypes = listOf(
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            ),
            writableNoiseControlTypes = setOf(
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            ),
            eqConfig = EqDeviceConfig(
                availablePresets = listOf(
                    EqPresetId.OFF,
                    EqPresetId.BRIGHT,
                    EqPresetId.EXCITED,
                    EqPresetId.MELLOW,
                    EqPresetId.RELAXED,
                    EqPresetId.VOCAL,
                    EqPresetId.TREBLE,
                    EqPresetId.BASS,
                    EqPresetId.SPEECH,
                    EqPresetId.CUSTOM,
                    EqPresetId.USER_SETTING1,
                    EqPresetId.USER_SETTING2,
                ),
                writeInquiredType = EqEbbInquiredType.PRESET_EQ,
                statusQueryTypes = listOf(EqEbbInquiredType.PRESET_EQ),
                paramQueryTypes = listOf(EqEbbInquiredType.PRESET_EQ),
                bandCount = 6,
                hasClearBass = true,
                clearBassWriteMode = ClearBassWriteMode.PRESET_EQ_BANDS,
            ),
        ),
        featureProtocolMap = features.associateWith { HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1 },
    )
}
