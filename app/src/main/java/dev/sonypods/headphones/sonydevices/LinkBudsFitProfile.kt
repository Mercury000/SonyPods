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

/**
 * LinkBuds Fit (fw 1.5.1). Ground truth from an official-controller btsnoop
 * capture (btsnoop_hci_260730_125322.log) plus the official app's
 * exchanged-capabilities cache:
 *  - NC/ASM inquired type 0x19 (MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA),
 *    8-byte param layout, 20 ambient levels (capability `61 19 02 00 01 14 01 01 14 01`).
 *  - Battery: LEFT_RIGHT (`23 09 ...`) + cradle (`23 0a ...`) -> true-wireless.
 *  - EQ: PRESET_EQ with 6 bands (`57 00 00 06 0a x6`, 0x0A = zero-offset center).
 */
object LinkBudsFitProfile {
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
        modelName = "LinkBuds Fit",
        series = "LINK_BUDS",
        capabilities = HeadphoneCapabilities(
            features = features,
            formFactor = HeadphoneFormFactor.TRUE_WIRELESS,
            batteryQueries = listOf(
                PowerInquiredType.LEFT_RIGHT_BATTERY,
                PowerInquiredType.CRADLE_BATTERY,
            ),
            noiseControlQueryTypes = listOf(
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            ),
            writableNoiseControlTypes = setOf(
                NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
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
