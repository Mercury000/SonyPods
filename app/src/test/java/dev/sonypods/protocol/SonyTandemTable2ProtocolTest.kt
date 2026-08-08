package dev.sonypods.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6: Tests for V1 and V2 Table2 protocol codecs.
 *
 * Covers:
 * - Command family classification for all families
 * - GET builder byte-level accuracy
 * - Parser: known commands return Table2Common/Table2Generic, not Unknown
 * - Parser: unrecognized commands return Unknown
 * - Normalization: Table2 uses the 0x0F DATA_MDR_NO2 marker
 * - V1 Table2: peripheral + voice guidance
 */
class SonyTandemTable2ProtocolTest {

    // ── V2 Table2: command family classification ────────────────────────────

    @Test
    fun v2_classifyFamily_connect() {
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.CONNECT,
            SonyTandemV2Table2Protocol.classifyFamily(0x06),
        )
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.CONNECT,
            SonyTandemV2Table2Protocol.classifyFamily(0x07),
        )
    }

    @Test
    fun v2_classifyFamily_power() {
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.POWER,
            SonyTandemV2Table2Protocol.classifyFamily(0x22),
        )
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.POWER,
            SonyTandemV2Table2Protocol.classifyFamily(0x23),
        )
    }

    @Test
    fun v2_classifyFamily_peripheral() {
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.PERIPHERAL,
            SonyTandemV2Table2Protocol.classifyFamily(0x32),
        )
    }

    @Test
    fun v2_classifyFamily_voiceGuidance() {
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.VOICE_GUIDANCE,
            SonyTandemV2Table2Protocol.classifyFamily(0x42),
        )
    }

    @Test
    fun v2_classifyFamily_safeListening() {
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.SAFE_LISTENING,
            SonyTandemV2Table2Protocol.classifyFamily(0x52),
        )
    }

    @Test
    fun v2_classifyFamily_lea() {
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.LEA,
            SonyTandemV2Table2Protocol.classifyFamily(0x62),
        )
    }

    @Test
    fun v2_classifyFamily_party() {
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.PARTY,
            SonyTandemV2Table2Protocol.classifyFamily(0x72),
        )
    }

    @Test
    fun v2_classifyFamily_system() {
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.SYSTEM,
            SonyTandemV2Table2Protocol.classifyFamily(0xF0.toByte()),
        )
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.SYSTEM,
            SonyTandemV2Table2Protocol.classifyFamily(0xFD.toByte()),
        )
    }

    @Test
    fun v2_classifyFamily_unknown() {
        assertEquals(
            SonyTandemV2Table2Protocol.Table2Family.UNKNOWN,
            SonyTandemV2Table2Protocol.classifyFamily(0x7F),
        )
    }

    // ── V2 Table2: GET builders ─────────────────────────────────────────────

    @Test
    fun v2_buildGetSupportFunction_matchesCommandShape() {
        // CONNECT_GET_SUPPORT_FUNCTION 0x06 with FIXED_VALUE payload 0x00
        val bytes = SonyTandemV2Table2Protocol.buildGetSupportFunction()
        assertArrayEquals(byteArrayOf(0x0F, 0x06, 0x00), bytes)
    }

    @Test
    fun v2_buildGetPowerStatus_matchesCommandShape() {
        val bytes = SonyTandemV2Table2Protocol.buildGetPowerStatus(
            PowerInquiredTypeTable2.AUTO_STANDBY,
        )
        assertArrayEquals(byteArrayOf(0x0F, 0x22, 0x00), bytes)
    }

    @Test
    fun v2_buildGetPeripheralStatus_matchesCommandShape() {
        val bytes = SonyTandemV2Table2Protocol.buildGetPeripheralStatus(
            PeripheralInquiredTypeTable2.SOURCE_SWITCH_CONTROL,
        )
        assertArrayEquals(byteArrayOf(0x0F, 0x32, 0x01), bytes)
    }

    @Test
    fun v2_buildGetVoiceGuidanceStatus_matchesCommandShape() {
        val bytes = SonyTandemV2Table2Protocol.buildGetVoiceGuidanceStatus(
            VoiceGuidanceInquiredTypeTable2.SUPPORT_LANGUAGE_SWITCH,
        )
        assertArrayEquals(byteArrayOf(0x0F, 0x42, 0x02), bytes)
    }

    @Test
    fun v2_buildGetSafeListeningStatus_matchesCommandShape() {
        val bytes = SonyTandemV2Table2Protocol.buildGetSafeListeningStatus(
            SafeListeningInquiredTypeTable2.SAFE_LISTENING_TWS_1,
        )
        assertArrayEquals(byteArrayOf(0x0F, 0x52, 0x01), bytes)
    }

    @Test
    fun v2_buildGetLeaStatus_matchesCommandShape() {
        val bytes = SonyTandemV2Table2Protocol.buildGetLeaStatus(
            LeaInquiredTypeTable2.LE_AUDIO_CONNECTION_STATE_NOTIFICATION,
        )
        assertArrayEquals(byteArrayOf(0x0F, 0x62, 0x00), bytes)
    }

    @Test
    fun v2_buildGetPartyStatus_matchesCommandShape() {
        val bytes = SonyTandemV2Table2Protocol.buildGetPartyStatus(
            PartyInquiredTypeTable2.DJ_CONTROL,
        )
        assertArrayEquals(byteArrayOf(0x0F, 0x72, 0x00), bytes)
    }

    @Test
    fun v2_buildGetSystemStatus_matchesCommandShape() {
        val bytes = SonyTandemV2Table2Protocol.buildGetSystemStatus(
            SystemInquiredTypeTable2.WEARING_STATUS_CHECKER,
        )
        assertArrayEquals(byteArrayOf(0x0F, 0xF2.toByte(), 0x00), bytes)
    }

    // ── V2 Table2: parser ───────────────────────────────────────────────────

    @Test
    fun v2_parse_powerRetStatus_returnsTable2Generic() {
        // POWER_RET_STATUS (0x23) with AUTO_STANDBY (0x00) + payload
        val raw = byteArrayOf(0x0F, 0x23, 0x00, 0x01)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue("Expected Table2Generic but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Table2Generic)
        parsed as ParsedTandemResponse.Table2Generic
        assertEquals("POWER", parsed.family)
        assertEquals(0x00, parsed.inquiredType)
        assertEquals(listOf(0x01), parsed.values)
    }

    @Test
    fun v2_parse_peripheralRetStatus_returnsTable2Generic() {
        val raw = byteArrayOf(0x0F, 0x33, 0x01, 0x02, 0x03)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue("Expected Table2Generic but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Table2Generic)
        parsed as ParsedTandemResponse.Table2Generic
        assertEquals("PERIPHERAL", parsed.family)
        assertEquals(0x01, parsed.inquiredType)
    }

    @Test
    fun v2_parse_connectRetSupportFunction_returnsSupportFunction() {
        // 0x0F 0x07 [0x00 fixed] [0x03 count] (FunctionType.code, order) pairs,
        // resolved via Table NO_2 and sorted by order.
        val raw = byteArrayOf(
            0x0F, 0x07, 0x00, 0x03,
            0x50, 0x00, // SAFE_LISTENING_HBS_1
            0x62, 0x02, // LE_AUDIO_CONNECTION_MODE
            0x51, 0x01, // SAFE_LISTENING_TWS_1
        )
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue(
            "Expected SupportFunction but got ${parsed::class.simpleName}",
            parsed is ParsedTandemResponse.SupportFunction,
        )
        parsed as ParsedTandemResponse.SupportFunction
        assertEquals(
            listOf(
                SonySupportedFunction(0x50, 0),
                SonySupportedFunction(0x51, 1),
                SonySupportedFunction(0x62, 2),
            ),
            parsed.functions,
        )
    }

    @Test
    fun v2_parse_connectRetSupportFunction_emptyPayloadIsEmptyList() {
        val raw = byteArrayOf(0x0F, 0x07, 0x00)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)
        assertTrue("Expected SupportFunction but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.SupportFunction)
        parsed as ParsedTandemResponse.SupportFunction
        assertEquals(emptyList<SonySupportedFunction>(), parsed.functions)
    }

    @Test
    fun v2_parse_systemRetParam_returnsTable2Generic() {
        val raw = byteArrayOf(0x0F, 0xF7.toByte(), 0x00, 0x02, 0x00)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue("Expected Table2Generic but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Table2Generic)
        parsed as ParsedTandemResponse.Table2Generic
        assertEquals("SYSTEM", parsed.family)
        assertEquals(0x00, parsed.inquiredType)
    }

    @Test
    fun v2_parse_unknownCommand_returnsUnknown() {
        val raw = byteArrayOf(0x0F, 0x7F, 0x10)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
    }

    @Test
    fun v2_parse_accepts0x0eDataTypeForSppCompatibility() {
        // SPP normalizes Table2 payloads to 0x0E data type.
        // The Table2 parser should accept 0x0E as well as 0x0F.
        val raw = byteArrayOf(0x0E, 0x23, 0x00, 0x01)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue("Expected Table2Generic but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Table2Generic)
        parsed as ParsedTandemResponse.Table2Generic
        assertEquals("POWER", parsed.family)
    }

    @Test
    fun v2_parse_preservesRawPayload() {
        val raw = byteArrayOf(0x0F, 0x53, 0x03, 0x04, 0x05)
        val parsed = SonyTandemV2Table2Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Table2Generic)
        assertArrayEquals(raw, parsed.raw)
    }

    @Test
    fun table2Common_equalityUsesRawContent() {
        val first = ParsedTandemResponse.Table2Common(
            family = "CONNECT",
            command = 0x07,
            values = listOf(0x01),
            raw = byteArrayOf(0x0F, 0x07, 0x01),
        )
        val second = ParsedTandemResponse.Table2Common(
            family = "CONNECT",
            command = 0x07,
            values = listOf(0x01),
            raw = byteArrayOf(0x0F, 0x07, 0x01),
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun table2Generic_equalityUsesRawContent() {
        val first = ParsedTandemResponse.Table2Generic(
            family = "POWER",
            inquiredType = 0x00,
            values = listOf(0x01),
            raw = byteArrayOf(0x0F, 0x23, 0x00, 0x01),
        )
        val second = ParsedTandemResponse.Table2Generic(
            family = "POWER",
            inquiredType = 0x00,
            values = listOf(0x01),
            raw = byteArrayOf(0x0F, 0x23, 0x00, 0x01),
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    // ── V1 Table2: command family classification ────────────────────────────

    @Test
    fun v1_classifyFamily_peripheral() {
        assertEquals(
            SonyTandemV1Table2Protocol.Table2Family.PERIPHERAL,
            SonyTandemV1Table2Protocol.classifyFamily(0x32),
        )
    }

    @Test
    fun v1_classifyFamily_voiceGuidance() {
        assertEquals(
            SonyTandemV1Table2Protocol.Table2Family.VOICE_GUIDANCE,
            SonyTandemV1Table2Protocol.classifyFamily(0x42),
        )
    }

    @Test
    fun v1_classifyFamily_unknown() {
        assertEquals(
            SonyTandemV1Table2Protocol.Table2Family.UNKNOWN,
            SonyTandemV1Table2Protocol.classifyFamily(0x60),
        )
    }

    // ── V1 Table2: GET builders ─────────────────────────────────────────────

    @Test
    fun v1_buildGetPeripheralStatus_matchesCommandShape() {
        val bytes = SonyTandemV1Table2Protocol.buildGetPeripheralStatus(
            PeripheralInquiredTypeV1Table2.PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT,
        )
        assertArrayEquals(byteArrayOf(0x0F, 0x32, 0x01), bytes)
    }

    @Test
    fun v1_buildGetVoiceGuidanceStatus_matchesCommandShape() {
        val bytes = SonyTandemV1Table2Protocol.buildGetVoiceGuidanceStatus(
            VoiceGuidanceInquiredTypeV1Table2.VOICE_GUIDANCE_SETTING,
        )
        assertArrayEquals(byteArrayOf(0x0F, 0x42, 0x01), bytes)
    }

    // ── V1 Table2: parser ───────────────────────────────────────────────────

    @Test
    fun v1_parse_peripheralRetStatus_returnsTable2Generic() {
        val raw = byteArrayOf(0x0F, 0x33, 0x01, 0x02, 0x03)
        val parsed = SonyTandemV1Table2Protocol.parse(raw)

        assertTrue("Expected Table2Generic but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Table2Generic)
        parsed as ParsedTandemResponse.Table2Generic
        assertEquals("PERIPHERAL", parsed.family)
        assertEquals(0x01, parsed.inquiredType)
    }

    @Test
    fun v1_parse_voiceGuidanceRetStatus_returnsTable2Generic() {
        val raw = byteArrayOf(0x0F, 0x43, 0x01, 0x00)
        val parsed = SonyTandemV1Table2Protocol.parse(raw)

        assertTrue("Expected Table2Generic but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Table2Generic)
        parsed as ParsedTandemResponse.Table2Generic
        assertEquals("VOICE_GUIDANCE", parsed.family)
    }

    @Test
    fun v1_parse_unknownPeripheralInquiredType_foldsToNoUse() {
        val raw = byteArrayOf(0x0F, 0x33, 0x7F, 0x01)
        val parsed = SonyTandemV1Table2Protocol.parse(raw)

        assertTrue("Expected Table2Generic but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Table2Generic)
        parsed as ParsedTandemResponse.Table2Generic
        assertEquals("PERIPHERAL", parsed.family)
        assertEquals(0x00, parsed.inquiredType)
        assertEquals(listOf(0x01), parsed.values)
    }

    @Test
    fun v1_parse_unknownVoiceGuidanceInquiredType_foldsToNoUse() {
        val raw = byteArrayOf(0x0F, 0x43, 0x7F, 0x01)
        val parsed = SonyTandemV1Table2Protocol.parse(raw)

        assertTrue("Expected Table2Generic but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Table2Generic)
        parsed as ParsedTandemResponse.Table2Generic
        assertEquals("VOICE_GUIDANCE", parsed.family)
        assertEquals(0x00, parsed.inquiredType)
        assertEquals(listOf(0x01), parsed.values)
    }

    @Test
    fun v1_parse_unknownCommand_returnsUnknown() {
        val raw = byteArrayOf(0x0E, 0x7F, 0x10)
        val parsed = SonyTandemV1Table2Protocol.parse(raw)

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
    }

    @Test
    fun v1_parse_unknownDataType_returnsUnknown() {
        val raw = byteArrayOf(0xFF.toByte(), 0x33, 0x01)
        val parsed = SonyTandemV1Table2Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Unknown)
    }

    @Test
    fun inquiredTypeCode02_isResolvedByProtocolVersion() {
        assertEquals(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM, NcAsmInquiredType.fromV1Table1Code(0x02))
        assertEquals(NcAsmInquiredType.NC_ON_OFF_AND_ASM_ON_OFF, NcAsmInquiredType.fromV2Table1Code(0x02))
    }

    @Test
    fun v2Table2_extendedInquiredTypes_areRecognized() {
        assertEquals(
            LeaInquiredTypeTable2.PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
            LeaInquiredTypeTable2.fromCode(0x04),
        )
        assertEquals(PartyInquiredTypeTable2.LIVE_KARAOKE, PartyInquiredTypeTable2.fromCode(0x05))
    }

}
