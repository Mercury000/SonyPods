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
 * Sony WH-1000XM5 — over-ear headphone (HEADSET form factor, single battery).
 *
 * Protocol verified against btsnoop_hci_260730_134818.log + tandem-capabilities.db
 * (ident 88:C9:E8:2C:F3:1A, FW 2.0.2):
 *  - Noise control uses inquired type 0x17 (MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS),
 *    the same V2_TABLE1 builder/parser already used by WF-1000XM5 / LinkBuds S. The
 *    ambientMode (focus-on-voice) byte is carried at payload[4] and round-trips correctly.
 *  - Battery is a single unit: PowerInquiredType.BATTERY (0x00). Log frame `23 00 53 00`
 *    decodes to 83% (0x53).
 *  - EQ is PRESET_EQ with 6 bands (log `57 00 00 06 0a 0a 0a 0a 0a 0a`); clear bass uses
 *    PRESET_EQ_BANDS write mode.
 *  - Feature set matches WH-1000XM4 (no LEA_STATUS / QUICK_ACCESS / WEARING_STATUS).
 */
object Wh1000Xm5Profile {
    private val features = setOf(
        HeadphoneFeature.DEVICE_INFO,
        HeadphoneFeature.BATTERY,
        HeadphoneFeature.NOISE_CONTROL,
        HeadphoneFeature.AMBIENT_LEVEL,
        HeadphoneFeature.AMBIENT_VOICE_MODE,
        HeadphoneFeature.PLAYBACK_CONTROL,
        HeadphoneFeature.EQ,
        HeadphoneFeature.CLEAR_BASS,
    )

    val template = ProfileTemplate(
        modelName = "WH-1000XM5",
        series = "PREMIUM",
        capabilities = HeadphoneCapabilities(
            features = features,
            formFactor = HeadphoneFormFactor.HEADSET,
            batteryQueries = listOf(PowerInquiredType.BATTERY),
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
                extendedInfoQueryTypes = listOf(EqEbbInquiredType.PRESET_EQ),
                bandCount = 6,
                hasClearBass = true,
                clearBassWriteMode = ClearBassWriteMode.PRESET_EQ_BANDS,
            ),
        ),
        featureProtocolMap = features.associateWith { HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1 },
    )
}
