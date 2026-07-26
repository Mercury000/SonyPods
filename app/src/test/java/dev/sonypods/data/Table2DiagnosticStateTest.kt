package dev.sonypods.data

import dev.sonypods.headphones.TandemChannel
import dev.sonypods.protocol.ParsedTandemResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Table2DiagnosticStateTest {
    @Test
    fun table2Common_mapsToDiagnosticState() {
        val diagnostic = table2DiagnosticStateFor(
            TandemChannel.GATT_V2_MC,
            ParsedTandemResponse.Table2Common(
                family = "CONNECT",
                command = 0x07,
                values = listOf(0x01, 0x02),
                raw = byteArrayOf(0x0F, 0x07, 0x01, 0x02),
            ),
        )

        requireNotNull(diagnostic)
        assertEquals("GATT_V2_MC", diagnostic.channel)
        assertEquals("CONNECT", diagnostic.family)
        assertEquals(0x07, diagnostic.command)
        assertNull(diagnostic.inquiredType)
        assertEquals(listOf(0x01, 0x02), diagnostic.values)
        assertEquals("0F 07 01 02", diagnostic.rawHex)
    }

    @Test
    fun table2Generic_mapsToDiagnosticState() {
        val diagnostic = table2DiagnosticStateFor(
            TandemChannel.GATT_V1_MC,
            ParsedTandemResponse.Table2Generic(
                family = "PERIPHERAL",
                inquiredType = 0x01,
                values = listOf(0x02, 0x03),
                raw = byteArrayOf(0x0E, 0x33, 0x01, 0x02, 0x03),
            ),
        )

        requireNotNull(diagnostic)
        assertEquals("GATT_V1_MC", diagnostic.channel)
        assertEquals("PERIPHERAL", diagnostic.family)
        assertEquals(0x33, diagnostic.command)
        assertEquals(0x01, diagnostic.inquiredType)
        assertEquals(listOf(0x02, 0x03), diagnostic.values)
        assertEquals("0E 33 01 02 03", diagnostic.rawHex)
    }
}
