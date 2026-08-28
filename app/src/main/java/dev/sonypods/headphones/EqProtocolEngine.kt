package dev.sonypods.headphones

import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.ParsedTandemResponse

data class EqDeviceConfig(
    val availablePresets: List<EqPresetId>,
    val writeInquiredType: EqEbbInquiredType,
    val statusQueryTypes: List<EqEbbInquiredType>,
    val paramQueryTypes: List<EqEbbInquiredType>,
    val extendedInfoQueryTypes: List<EqEbbInquiredType> = emptyList(),
    val bandCount: Int,
    val hasClearBass: Boolean,
    val clearBassWriteMode: ClearBassWriteMode = ClearBassWriteMode.EBB_PARAM,
    val bandLabels: List<String> = emptyList(),
)

enum class ClearBassWriteMode {
    EBB_PARAM,
    PRESET_EQ_BANDS,
}

data class EqUiCapability(
    val availablePresets: List<EqPresetId>,
    val visibleBandCount: Int,
    val bandLabels: List<String>,
    val bandDisplayRange: IntRange,
    val hasClearBass: Boolean,
    val clearBassDisplayRange: IntRange,
    val bandStepCenter: Int,
)

class EqProtocolEngine(
    private val config: EqDeviceConfig,
    private val codec: TandemCodec,
) {
    constructor(config: EqDeviceConfig, variant: HeadphoneProtocolVariant) : this(
        config,
        TandemCodecRegistry.codecFor(variant),
    )

    // ── Refresh ──

    fun buildRefreshCommands(
        buildCommand: (String, ByteArray) -> HeadphoneCommand,
    ): List<HeadphoneCommand> = buildList {
        config.statusQueryTypes.forEach { type ->
            codec.buildGetEqEbbStatus(type)?.let { bytes ->
                add(buildCommand("GET EQ status $type", bytes))
            }
        }
        config.paramQueryTypes.forEach { type ->
            codec.buildGetEqEbbParam(type)?.let { bytes ->
                add(buildCommand("GET EQ param $type", bytes))
            }
        }
        config.extendedInfoQueryTypes.forEach { type ->
            codec.buildGetEqEbbExtendedInfo(type)?.let { bytes ->
                add(buildCommand("GET EQ extended $type", bytes))
            }
        }
    }

    // ── Writes ──

    fun buildSetPreset(preset: EqPresetId): ByteArray =
        requireNotNull(codec.buildSetEqPreset(preset, config.writeInquiredType)) {
            "Codec ${codec.variant} does not support EQ preset writes"
        }

    fun buildSetBands(bands: List<Int>, preset: EqPresetId): ByteArray =
        requireNotNull(codec.buildSetEqBands(preset, config.writeInquiredType, bands)) {
            "Codec ${codec.variant} does not support EQ band writes"
        }

    fun buildSetClearBass(level: Int): ByteArray =
        requireNotNull(codec.buildSetClearBass(level)) {
            "Codec ${codec.variant} does not support Clear Bass writes"
        }

    // ── Parse ──

    /** Parse delegates to the selected codec so EQ routing stays protocol-variant local. */
    fun parseResponse(raw: ByteArray): ParsedTandemResponse.EqEbb? {
        val result = codec.parse(raw)
        return result as? ParsedTandemResponse.EqEbb
    }

    companion object {
        const val BAND_STEP_CENTER: Int = 10

        private val DEFAULT_BAND_LABELS = listOf("400 Hz", "1 kHz", "2.5 kHz", "6.3 kHz", "16 kHz")

        fun uiCapability(config: EqDeviceConfig): EqUiCapability {
            val labels = config.bandLabels.ifEmpty { DEFAULT_BAND_LABELS }
            val visibleCount = if (config.bandLabels.isNotEmpty()) {
                config.bandLabels.size
            } else if (config.hasClearBass) {
                (config.bandCount - 1).coerceAtLeast(0)
            } else {
                config.bandCount.coerceAtLeast(0)
            }
            return EqUiCapability(
                availablePresets = config.availablePresets,
                visibleBandCount = visibleCount,
                bandLabels = labels,
                bandDisplayRange = -10..10,
                hasClearBass = config.hasClearBass,
                clearBassDisplayRange = -10..10,
                bandStepCenter = BAND_STEP_CENTER,
            )
        }
    }
}
