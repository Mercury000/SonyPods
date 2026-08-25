package dev.sonypods.protocol

import com.mercury.sonypods.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAccessServiceCatalogTest {
    @Test
    fun nameRes_matchSoundConnectServiceIds() {
        assertEquals(R.string.qa_service_netease_music, QuickAccessServiceCatalog.nameRes(0x09))
        assertEquals(R.string.qa_service_qq_music, QuickAccessServiceCatalog.nameRes(0x07))
        assertEquals(R.string.qa_service_tencent_xiaowei, QuickAccessServiceCatalog.nameRes(0x04))
    }

    @Test
    fun nameRes_unknownCodeReturnsNull_forUiSideFallback() {
        // Region-specific / newer raw IDs render through the UI-side
        // qa_service_unknown_fmt resource, not a catalog entry.
        assertNull(QuickAccessServiceCatalog.nameRes(0x0B))
    }

    @Test
    fun candidates_keepCatalogServicesWhenCapabilityOmitsSelectedService() {
        val candidates = QuickAccessServiceCatalog.candidates(
            capabilityCodes = listOf(0x00, 0x01),
            currentCode = 0x09,
        )

        assertTrue(0x09 in candidates)
        assertTrue(0x07 in candidates)
        assertTrue(0x01 in candidates)
        assertFalse(0x0B in candidates)
    }
}
