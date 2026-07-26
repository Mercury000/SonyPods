package dev.sonypods.protocol

import dev.sonypods.headphones.HeadphoneProtocolVariant
import dev.sonypods.headphones.TandemCodecRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1: Tests for Table2 routing correctness.
 *
 * Verifies that:
 * - V1_TABLE2 and V2_TABLE2 variants exist (not just placeholder enums)
 * - Table2 payloads do not fall through to V2 Table1 parser
 * - SonyTandemV2Table2Protocol returns Unknown for GET/unsupported inputs
 * - The codec registry routes each variant to the right codec
 */
class SonyTandemTable2RoutingTest {

    // ── Enum completeness ────────────────────────────────────────────────────

    @Test
    fun protocolVariant_hasBothTable2Variants() {
        // Verify V1_TABLE2 and V2_TABLE2 are not just theoretical — they exist
        assertNotNull(HeadphoneProtocolVariant.valueOf("SONY_TANDEM_V1_TABLE2"))
        assertNotNull(HeadphoneProtocolVariant.valueOf("SONY_TANDEM_V2_TABLE2"))
    }

    @Test
    fun protocolVariant_hasAllFourSpecificVariantsPlusUnknown() {
        val variants = HeadphoneProtocolVariant.entries
        assertEquals(5, variants.size)
        assertTrue(variants.contains(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1))
        assertTrue(variants.contains(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2))
        assertTrue(variants.contains(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1))
        assertTrue(variants.contains(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2))
        assertTrue(variants.contains(HeadphoneProtocolVariant.UNKNOWN))
    }

    // ── Table2 payload isolation (Phase 6 fixed normalization) ──────────────

    /**
     * Phase 6 fix: V2 Table2 now correctly uses DATA_MDR_NO2 (0x0F) as its
     * native data type. 0x32 = PERI_GET_STATUS (a GET, not a response), so
     * it falls through to Unknown.
     */
    @Test
    fun v2Table2Parser_returnsUnknownForGetCommands() {
        val raw = byteArrayOf(0x0F.toByte(), 0x32, 0x00)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
        parsed as ParsedTandemResponse.Unknown
        // Phase 6: normalizer recognises 0x0F natively
        assertEquals(0x0F, parsed.dataType)
        assertEquals(0x32, parsed.command)
    }

    @Test
    fun v2Table2Parser_preservesRawPayload() {
        val raw = byteArrayOf(0x0F.toByte(), 0x42, 0x01, 0x02, 0x03)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Unknown)
        parsed as ParsedTandemResponse.Unknown
        // Phase 6: normalizer recognises 0x0F, so payload = [01, 02, 03]
        assertTrue(parsed.payload.contentEquals(byteArrayOf(0x01, 0x02, 0x03)))
        assertTrue(parsed.raw.contentEquals(raw))
    }

    @Test
    fun v2Table2Parser_handlesEmptyPayload() {
        val raw = byteArrayOf(0x0F.toByte(), 0x32)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Unknown)
        parsed as ParsedTandemResponse.Unknown
        // Phase 6: normalised=[0F, 32], payload=[], size=0
        assertEquals(0, parsed.payload.size)
    }

    @Test
    fun v2Table2Parser_normalizesMissingDataType() {
        val raw = byteArrayOf(0x32, 0x00)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Unknown)
        parsed as ParsedTandemResponse.Unknown
        // Phase 6: DATA_MDR_NO2 prepended when no data type byte present
        assertEquals(0x0F, parsed.dataType)
        assertEquals(0x32, parsed.command)
    }

    // ── Table2 payloads do NOT fall through to V2 Table1 ─────────────────────

    @Test
    fun v2Table1Parser_returnsUnknownForTable2DataType() {
        // Table2 uses DATA_MDR_NO2 (0x0F). V2 Table1 normalizer prepends 0x0E
        // (since 0x0F != 0x0E), so normalized=[0E, 0F, 32, 00].
        // dataType=0x0E passes the DATA_MDR check, and 0x0F is not in any
        // when branch → Unknown. The response is Unknown but the shifted
        // command byte (0x0F) is preserved.
        val raw = byteArrayOf(0x0F.toByte(), 0x32, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
        parsed as ParsedTandemResponse.Unknown
        // After normalization, dataType=0x0E (prepended), command=0x0F (original dataType)
        assertEquals(0x0E, parsed.dataType)
    }

    @Test
    fun v2Table1Parser_returnsUnknownForUnrecognizedTable2Command() {
        // Even with DATA_MDR, unrecognized commands return Unknown.
        // 0x32 = PERIPHERAL_GET_STATUS (Table2 range, not in V2 Table1 parser's when branches)
        val raw = byteArrayOf(0x0E, 0x32, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
    }

    @Test
    fun v2Table1Parser_peripheralResponse_notMistakenForOtherFeatures() {
        // Simulate a Table2 peripheral RET_STATUS response (0x33).
        // V2 Table1 parser should NOT interpret this as a known Table1 response.
        val raw = byteArrayOf(0x0E, 0x33, 0x01, 0x02, 0x03)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(
            "Peripheral RET_STATUS (0x33) should be Unknown in V2 Table1, got ${parsed::class.simpleName}",
            parsed is ParsedTandemResponse.Unknown,
        )
    }

    // ── Codec registry routing ──────────────────────────────────────────────

    @Test
    fun codecRegistry_mapsV1Table2ToCorrectVariant() {
        val variant = HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2
        val resolved = resolveCodecVariant(variant)
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2, resolved)
    }

    @Test
    fun codecRegistry_mapsV2Table2ToCorrectVariant() {
        val variant = HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2
        val resolved = resolveCodecVariant(variant)
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2, resolved)
    }

    @Test
    fun codecRegistry_unknownMapsToUnknown() {
        val resolved = resolveCodecVariant(HeadphoneProtocolVariant.UNKNOWN)
        assertEquals(HeadphoneProtocolVariant.UNKNOWN, resolved)
    }

    @Test
    fun codecRegistry_allVariantsAreCovered() {
        HeadphoneProtocolVariant.entries.forEach { variant ->
            val resolved = resolveCodecVariant(variant)
            assertEquals(
                "Registry must return the same variant for $variant",
                variant,
                resolved,
            )
        }
    }

    @Test
    fun codecRegistry_v2Table2DoesNotMapToV2Table1() {
        val variant = HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2
        val resolved = resolveCodecVariant(variant)
        // Must not silently downgrade Table2 to Table1
        assertTrue(resolved != HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1)
    }

    @Test
    fun codecRegistry_v1Table2DoesNotMapToV1Table1() {
        val variant = HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2
        val resolved = resolveCodecVariant(variant)
        assertTrue(resolved != HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1)
    }

    // ── Codec resolution ────────────────────────────────────────────────────

    /**
     * Each variant maps to itself; UNKNOWN maps to UNKNOWN.
     */
    private fun resolveCodecVariant(variant: HeadphoneProtocolVariant): HeadphoneProtocolVariant =
        TandemCodecRegistry.codecFor(variant).variant
}
