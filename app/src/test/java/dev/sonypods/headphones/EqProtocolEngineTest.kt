package dev.sonypods.headphones

import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EqProtocolEngineTest {
    private val tenBandLabels = listOf(
        "31 Hz", "63 Hz", "125 Hz", "250 Hz", "500 Hz",
        "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz",
    )

    private fun tenBandConfig() = EqDeviceConfig(
        availablePresets = listOf(EqPresetId.OFF, EqPresetId.CUSTOM),
        writeInquiredType = EqEbbInquiredType.PRESET_EQ,
        statusQueryTypes = emptyList(),
        paramQueryTypes = emptyList(),
        bandCount = 10,
        hasClearBass = false,
        isTenBand = true,
        bandLabels = tenBandLabels,
    )

    private fun standardConfig() = EqDeviceConfig(
        availablePresets = listOf(EqPresetId.OFF, EqPresetId.CUSTOM),
        writeInquiredType = EqEbbInquiredType.PRESET_EQ,
        statusQueryTypes = emptyList(),
        paramQueryTypes = emptyList(),
        bandCount = 6,
        hasClearBass = true,
    )

    @Test
    fun tenBandCapability_matchesScEqBandSteps10band() {
        val capability = EqProtocolEngine.uiCapability(tenBandConfig())

        assertEquals(10, capability.visibleBandCount)
        assertEquals(tenBandLabels, capability.bandLabels)
        assertEquals(-6..6, capability.bandDisplayRange)
        assertEquals(6, capability.bandStepCenter)
        assertFalse(capability.hasClearBass)
        assertFalse(hasClearBassSlot(tenBandConfig()))
    }

    @Test
    fun standardCapability_keepsClearBassAndTenStepRange() {
        val capability = EqProtocolEngine.uiCapability(standardConfig())

        // No dynamic labels: default 5-band table, Clear Bass excluded.
        assertEquals(5, capability.visibleBandCount)
        assertEquals(-10..10, capability.bandDisplayRange)
        assertEquals(10, capability.bandStepCenter)
        assertTrue(capability.hasClearBass)
        assertTrue(hasClearBassSlot(standardConfig()))
    }

    @Test
    fun tenBandScaleConversions_matchScCenters() {
        val scale = EqBandStepScale.TEN_BAND

        assertEquals(0, scale.displayOf(6))
        assertEquals(6, scale.displayOf(12))
        assertEquals(-6, scale.displayOf(0))
        assertEquals(6, scale.rawOf(0))
        assertEquals(12, scale.rawOf(6))
        assertEquals(0, scale.rawOf(-6))
        // Raw steps above 12 still clamp into the display range.
        assertEquals(6, scale.displayOf(17))
    }

    @Test
    fun standardScaleConversions_matchScCenters() {
        val scale = EqBandStepScale.STANDARD

        assertEquals(0, scale.displayOf(10))
        assertEquals(10, scale.displayOf(20))
        assertEquals(-10, scale.displayOf(0))
        assertEquals(10, scale.rawOf(0))
        assertEquals(20, scale.rawOf(10))
        assertEquals(0, scale.rawOf(-10))
    }
}
