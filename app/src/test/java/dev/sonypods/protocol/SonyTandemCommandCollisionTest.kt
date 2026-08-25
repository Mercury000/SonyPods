package dev.sonypods.protocol

import dev.sonypods.headphones.ConnectedHeadphoneProfile
import dev.sonypods.headphones.HeadphoneAdapterRegistry
import dev.sonypods.headphones.HeadphoneFeature
import dev.sonypods.headphones.HeadphoneProtocolVariant
import dev.sonypods.headphones.HeadphoneTransport
import dev.sonypods.headphones.SonyCapabilityProbe
import dev.sonypods.headphones.SonyTandemHeadphoneAdapter
import dev.sonypods.headphones.TandemChannel
import dev.sonypods.ble.DiscoveredSonyDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1: Tests for the 0x13 command collision between V2 COMMON_RET_STATUS
 * and V1 COMMON_NTFY_BATTERY_LEVEL.
 *
 * The key insight: the same raw byte can legitimately mean different things
 * depending on the protocol version binding. The classify0x13 heuristics in
 * SonyTandemHeadphoneAdapter resolve the ambiguity using payload shape
 * inspection. These tests verify that the resolution is correct in all
 * documented cases.
 */
class SonyTandemCommandCollisionTest {

    // ── Raw protocol level: V1 parser ───────────────────────────────────────

    @Test
    fun v1Parser_0x13_batteryPayload_returnsBattery() {
        // V1 treats 0x13 (and 0x11) as battery notification/response
        val raw = byteArrayOf(0x0E, 0x13, 0x00, 88.toByte())
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue("Expected Battery but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(88), parsed.values)
    }

    @Test
    fun v1Parser_0x13_leftRightPayload_returnsBattery() {
        val raw = byteArrayOf(0x0E, 0x13, 0x01, 80.toByte(), 0x00, 70.toByte())
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue("Expected Battery but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.LEFT_RIGHT_BATTERY, parsed.kind)
        assertEquals(listOf(80, 70), parsed.values)
    }

    @Test
    fun v1Parser_0x13_cradlePayload_returnsBattery() {
        val raw = byteArrayOf(0x0E, 0x13, 0x02, 95.toByte())
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue("Expected Battery but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.CRADLE_BATTERY, parsed.kind)
        assertEquals(listOf(95), parsed.values)
    }

    @Test
    fun v1Parser_0x11_batteryResponse_returnsBattery() {
        // 0x11 = COMMON_RET_BATTERY_LEVEL (V1), unambiguous
        val raw = byteArrayOf(0x0E, 0x11, 0x00, 60.toByte())
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue("Expected Battery but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(60), parsed.values)
    }

    // ── Raw protocol level: V2 parser ───────────────────────────────────────

    @Test
    fun v2Parser_0x13_displayFirmware_returnsCommonStatus() {
        // 0x13 with 0x09 (DISPLAY_FW_VERSION) — non-overlapping CommonInquiredType
        val version = "2.5.0".encodeToByteArray()
        val raw = byteArrayOf(0x0E, 0x13, 0x09, version.size.toByte()) + version
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue("Expected CommonStatus but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.CommonStatus)
        parsed as ParsedTandemResponse.CommonStatus
        assertEquals(CommonInquiredType.DISPLAY_FW_VERSION, parsed.type)
        assertEquals("2.5.0", parsed.text)
    }

    @Test
    fun v2Parser_0x13_neverReturnsBattery() {
        // V2 parser treats 0x13 strictly as common status, never as battery.
        // Even with a battery-like payload, the V2 parser returns CommonStatus.
        val raw = byteArrayOf(0x0E, 0x13, 0x00, 88.toByte())
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue("Expected CommonStatus but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.CommonStatus)
        parsed as ParsedTandemResponse.CommonStatus
        // 0x00 = CONCIERGE in CommonInquiredType
        assertEquals(CommonInquiredType.CONCIERGE, parsed.type)
    }

    @Test
    fun v2Parser_0x13_connectionStatus_returnsCommonStatus() {
        val raw = byteArrayOf(0x0E, 0x13, 0x01, 0x00, 0x01)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue("Expected CommonStatus but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.CommonStatus)
        parsed as ParsedTandemResponse.CommonStatus
        assertEquals(CommonInquiredType.CONNECTION_STATUS, parsed.type)
    }

    @Test
    fun v2Parser_0x13_audioCodec_returnsTypedCodecStatus() {
        // V2 COMMON_RET_STATUS(0x13) with inqType AUDIO_CODEC(0x02) is the live
        // codec-badge reply and parses into its own typed response; a same-prefix
        // V1 battery frame is disambiguated by the V1 parser (xm4 cases below).
        val parsed = SonyTandemV2Table1Protocol.parse(byteArrayOf(0x0E, 0x13, 0x02, 0x01))

        assertTrue("Expected AudioCodecStatus but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.AudioCodecStatus)
        assertEquals(SoundQualityCodec.SBC, (parsed as ParsedTandemResponse.AudioCodecStatus).codec)
    }

    // ── Adapter level: classify0x13 heuristics ───────────────────────────────

    @Test
    fun xm4_0x13_batteryShapedPayload_classifiedAsBattery_routedToV1() {
        // 0x13 0x00 88 → classified as BATTERY (overlapping type, looks like V1 battery)
        // XM4 BATTERY → V1 → V1 parser → Battery
        val profile = xm4Profile()
        val raw = byteArrayOf(0x0E, 0x13, 0x00, 88.toByte())
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected Battery but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(88), parsed.values)
    }

    @Test
    fun xm4_0x13_nonOverlappingCommonType_classifiedAsDeviceInfo_returnsUnknownOnV1Profile() {
        // 0x13 0x09 ... = DISPLAY_FW_VERSION (non-overlapping CommonInquiredType)
        // XM4 no longer routes DEVICE_INFO to V2, so this V2 common status shape is unsupported.
        val profile = xm4Profile()
        val version = "2.5.0".encodeToByteArray()
        val raw = byteArrayOf(0x0E, 0x13, 0x09, version.size.toByte()) + version
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
    }

    @Test
    fun xm4_0x13_staminaType_returnsUnknownOnV1BatteryParser() {
        // 0x13 0x0E ... = STAMINA (PowerInquiredType only, non-overlapping)
        // classified as BATTERY, but V1 battery notifications only accept known battery payload shapes.
        val profile = xm4Profile()
        val raw = byteArrayOf(0x0E, 0x13, 0x0E, 0x01)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
    }

    @Test
    fun xm4_0x13_autoPowerOff_classifiedAsDeviceInfo() {
        // 0x13 0x04 = overlapping (AUTO_POWER_OFF / BLE_SETUP)
        // looksLikeV1BatteryPayload → false for type 0x04 → DEVICE_INFO, unsupported on XM4 V1 profile.
        val profile = xm4Profile()
        val raw = byteArrayOf(0x0E, 0x13, 0x04, 0x00)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue(
            "Expected Unknown (unsupported V2 common status route) but got ${parsed::class.simpleName}",
            parsed is ParsedTandemResponse.Unknown,
        )
    }

    @Test
    fun xm4_0x13_powerSaveMode_classifiedAsDeviceInfo() {
        // 0x13 0x06 = overlapping (POWER_SAVE_MODE / DEVICE_SPECIAL_MODE)
        // looksLikeV1BatteryPayload → false for type 0x06 → DEVICE_INFO, unsupported on XM4 V1 profile.
        val profile = xm4Profile()
        val raw = byteArrayOf(0x0E, 0x13, 0x06, 0x00)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue(
            "Expected Unknown (unsupported V2 common status route) but got ${parsed::class.simpleName}",
            parsed is ParsedTandemResponse.Unknown,
        )
    }

    // ── Same raw, different binding → different parse ───────────────────────

    @Test
    fun samePayload_0x130x0088_differentResultBasedOnBinding() {
        // Both XM4 (BATTERY→V1) and LinkBuds S (BATTERY→V2) see the same raw bytes.
        // XM4 routes to V1 parser → Battery. LinkBuds S routes to V2 parser → CommonStatus.
        val raw = byteArrayOf(0x0E, 0x13, 0x00, 88.toByte())

        val xm4Parsed = SonyTandemHeadphoneAdapter.parse(xm4Profile(), raw)
        val lbsParsed = SonyTandemHeadphoneAdapter.parse(linkBudsSProfile(), raw)

        // XM4 → Battery (V1 binding)
        assertTrue("XM4: Expected Battery", xm4Parsed is ParsedTandemResponse.Battery)
        // LinkBuds S → CommonStatus (V2 binding, V2 parser treats 0x13 as common)
        assertTrue("LinkBuds S: Expected CommonStatus", lbsParsed is ParsedTandemResponse.CommonStatus)

        // The parse results are different for the same raw input — this is correct
        // and necessary for the collision resolution to work.
        assertNotEquals(xm4Parsed::class, lbsParsed::class)
    }

    @Test
    fun samePayload_0x130x01_dualBattery_differentResultBasedOnBinding() {
        // 0x13 0x01 80 0x00 70
        // 0x01 = overlapping (LEFT_RIGHT_BATTERY / CONNECTION_STATUS)
        // XM4 (BATTERY → V1): looksLikeV1BatteryPayload → true (size 4, positions valid)
        //   → BATTERY → V1 parser → Battery(LEFT_RIGHT, [80, 70])
        // LinkBuds S (BATTERY → V2):
        //   → BATTERY → V2 parser → CommonStatus(CONNECTION_STATUS, values=[80, 0, 70])
        val raw = byteArrayOf(0x0E, 0x13, 0x01, 80.toByte(), 0x00, 70.toByte())

        val xm4Parsed = SonyTandemHeadphoneAdapter.parse(xm4Profile(), raw)
        val lbsParsed = SonyTandemHeadphoneAdapter.parse(linkBudsSProfile(), raw)

        assertTrue("XM4: Expected Battery", xm4Parsed is ParsedTandemResponse.Battery)
        assertTrue("LinkBuds S: Expected CommonStatus", lbsParsed is ParsedTandemResponse.CommonStatus)
        assertNotEquals(xm4Parsed::class, lbsParsed::class)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun v2(type: SonyV2FunctionType, order: Int = 0) = SonySupportedFunction(type.code, order)
    private fun v1(type: SonyV1FunctionType, order: Int = 0) = SonySupportedFunction(type.code, order)

    private fun v2NcAsm17(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT, 0),
    )

    private fun v2Eq(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.PRESET_EQ, 1),
        v2(SonyV2FunctionType.EBB, 2),
    )

    private fun v2TwsBattery(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.LEFT_RIGHT_BATTERY_LEVEL_INDICATOR, 3),
        v2(SonyV2FunctionType.CRADLE_BATTERY_LEVEL_INDICATOR, 4),
    )

    private fun v2Play(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT, 5),
    )

    private fun v1FullSet(): List<SonySupportedFunction> = listOf(
        v1(SonyV1FunctionType.BATTERY_LEVEL, 0),
        v1(SonyV1FunctionType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE, 1),
        v1(SonyV1FunctionType.PRESET_EQ, 2),
        v1(SonyV1FunctionType.EBB, 3),
        v1(SonyV1FunctionType.PLAYBACK_CONTROLLER, 4),
    )

    private fun xm4Device(): DiscoveredSonyDevice =
        DiscoveredSonyDevice(
            name = "WH-1000XM4",
            address = "00:11:22:33:44:55",
            rssi = 0,
            source = "bonded",
            isLikelyControlEndpoint = true,
        )

    private fun linkBudsSDevice(): DiscoveredSonyDevice =
        DiscoveredSonyDevice(
            name = "LinkBuds S",
            address = "00:11:22:33:44:56",
            rssi = 0,
            source = "bonded",
            isLikelyControlEndpoint = true,
        )

    private fun xm4Profile(): ConnectedHeadphoneProfile {
        val neutral = SonyTandemHeadphoneAdapter.withEndpointChannels(
            HeadphoneAdapterRegistry.resolve(xm4Device()),
            setOf(TandemChannel.GATT_V1_MC),
        )
        return SonyCapabilityProbe.applyToProfile(neutral, v1FullSet(), HeadphoneTransport.GATT_MC)
    }

    private fun linkBudsSProfile(): ConnectedHeadphoneProfile {
        val functions = listOf(v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play())
            .flatMap { it }
            .sortedBy { it.order }
        return SonyCapabilityProbe.applyToProfile(
            HeadphoneAdapterRegistry.resolve(linkBudsSDevice()),
            functions,
            HeadphoneTransport.GATT_HPC,
        )
    }
}
