package dev.sonypods.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAccessServiceCatalogTest {
    @Test
    fun labels_matchSoundConnectServiceIds() {
        assertEquals("网易云音乐", QuickAccessServiceCatalog.label(0x09))
        assertEquals("QQ音乐", QuickAccessServiceCatalog.label(0x07))
        assertEquals("腾讯小微", QuickAccessServiceCatalog.label(0x04))
        assertEquals("服务（0x0B）", QuickAccessServiceCatalog.label(0x0B))
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
