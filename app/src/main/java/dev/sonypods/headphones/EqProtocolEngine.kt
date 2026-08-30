package dev.sonypods.headphones

import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.ParsedTandemResponse

/**
 * Band step encoding, per SC `EqBandStepsStandard` / `EqBandSteps10band`:
 * standard devices (Clear Bass slot + 5 bands) carry raw steps 0..20 centered
 * at 10, while 10-band devices (31 Hz..16 kHz, no Clear Bass slot) carry raw
 * steps 0..12 centered at 6.
 */
enum class EqBandStepScale(val rawCenter: Int, val displayRange: IntRange) {
    STANDARD(10, -10..10),
    TEN_BAND(6, -6..6);

    fun displayOf(rawStep: Int): Int =
        (rawStep - rawCenter).coerceIn(displayRange.first, displayRange.last)

    fun rawOf(displayStep: Int): Int =
        (displayStep.coerceIn(displayRange.first, displayRange.last) + rawCenter).coerceIn(0, 255)

    companion object {
        fun forConfig(config: EqDeviceConfig): EqBandStepScale =
            if (config.isTenBand) TEN_BAND else STANDARD
    }
}

/** True when the raw band array carries Clear Bass at index 0 — every
 * `EqBandStepsStandard` geometry; 10-band arrays have no Clear Bass slot. */
fun hasClearBassSlot(config: EqDeviceConfig): Boolean =
    config.hasClearBass && !config.isTenBand

data class EqDeviceConfig(
    val availablePresets: List<EqPresetId>,
    val writeInquiredType: EqEbbInquiredType,
    val statusQueryTypes: List<EqEbbInquiredType>,
    val paramQueryTypes: List<EqEbbInquiredType>,
    val extendedInfoQueryTypes: List<EqEbbInquiredType> = emptyList(),
    val bandCount: Int,
    val hasClearBass: Boolean,
    /** Extended info reported the SC `EqBandSteps10band` geometry: ten
     * frequency bands (31 Hz..16 kHz) and no Clear Bass slot in the raw array,
     * with raw steps 0..12 centered at 6. */
    val isTenBand: Boolean = false,
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

    fun buildSetPreset(preset: EqPresetId, basePreset: EqPresetId? = null): ByteArray =
        requireNotNull(codec.buildSetEqPreset(preset, config.writeInquiredType, basePreset = basePreset)) {
            "Codec ${codec.variant} does not support EQ preset writes"
        }

    fun buildSetBands(bands: List<Int>, preset: EqPresetId, basePreset: EqPresetId? = null): ByteArray =
        requireNotNull(codec.buildSetEqBands(preset, config.writeInquiredType, bands, basePreset)) {
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
        private val DEFAULT_BAND_LABELS = listOf("400 Hz", "1 kHz", "2.5 kHz", "6.3 kHz", "16 kHz")

        fun uiCapability(config: EqDeviceConfig): EqUiCapability {
            val labels = config.bandLabels.ifEmpty { DEFAULT_BAND_LABELS }
            val scale = EqBandStepScale.forConfig(config)
            val clearBassSlot = hasClearBassSlot(config)
            val visibleCount = if (config.bandLabels.isNotEmpty()) {
                config.bandLabels.size
            } else if (clearBassSlot) {
                (config.bandCount - 1).coerceAtLeast(0)
            } else {
                config.bandCount.coerceAtLeast(0)
            }
            return EqUiCapability(
                availablePresets = config.availablePresets,
                visibleBandCount = visibleCount,
                bandLabels = labels,
                bandDisplayRange = scale.displayRange,
                hasClearBass = clearBassSlot,
                clearBassDisplayRange = EqBandStepScale.STANDARD.displayRange,
                bandStepCenter = scale.rawCenter,
            )
        }
    }
}
