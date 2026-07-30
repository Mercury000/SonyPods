package dev.sonypods.headphones

import dev.sonypods.ble.DiscoveredSonyDevice
import dev.sonypods.protocol.AmbientSoundMode
import dev.sonypods.protocol.DeviceInfoType
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.PlaybackStatus
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.SonyTandemV1Table1Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyTandemHeadphoneAdapterTest {
    @Test
    fun match_wh1000xm4_returnsPremiumProfile() {
        val profile = SonyTandemHeadphoneAdapter.match(
            xm4Device(),
            reportedModelName = "WH-1000XM4",
        )

        requireNotNull(profile)
        assertEquals("sony-tandem", profile.adapterId)
        assertEquals("Sony", profile.brand)
        assertEquals("WH-1000XM4", profile.modelName)
        assertEquals("PREMIUM", profile.series)
        assertTrue(profile.supports(HeadphoneFeature.NOISE_CONTROL))
        assertFalse(profile.supports(HeadphoneFeature.LEA_STATUS))
        assertFalse(profile.supports(HeadphoneFeature.QUICK_ACCESS))
        assertFalse(profile.supports(HeadphoneFeature.WEARING_STATUS))
        assertEquals(listOf(PowerInquiredType.BATTERY), profile.capabilities.batteryQueries)
        assertEquals(HeadphoneFormFactor.HEADSET, profile.capabilities.formFactor)
        // XM4: all supported Tandem features use V1 Table1.
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            profile.protocolFor(HeadphoneFeature.BATTERY),
        )
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            profile.protocolFor(HeadphoneFeature.NOISE_CONTROL),
        )
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            profile.protocolFor(HeadphoneFeature.AMBIENT_LEVEL),
        )
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            profile.protocolFor(HeadphoneFeature.AMBIENT_VOICE_MODE),
        )
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            profile.protocolFor(HeadphoneFeature.EQ),
        )
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            profile.protocolFor(HeadphoneFeature.CLEAR_BASS),
        )
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            profile.protocolFor(HeadphoneFeature.PLAYBACK_CONTROL),
        )
    }

    @Test
    fun refreshCommands_wh1000xm4_includeExistingFeatureReads() {
        val profile = SonyTandemHeadphoneAdapter.match(xm4Device(), "WH-1000XM4")!!
        val commands = SonyTandemHeadphoneAdapter.buildRefreshCommands(profile)
        val labels = commands.map { it.label }

        assertFalse(labels.any { it == "GET protocol info" })
        assertTrue(labels.any { it == "GET device info MODEL_NAME" })
        assertTrue(labels.any { it == "GET device info FW_VERSION" })
        assertTrue(labels.any { it == "GET battery BATTERY" })
        assertFalse(labels.any { it == "GET battery LEFT_RIGHT_BATTERY" })
        assertFalse(labels.any { it == "GET battery CRADLE_BATTERY" })
        assertFalse(labels.any { it == "GET EQ param CUSTOM_EQ" })
        assertTrue(labels.any { it == "GET EQ param PRESET_EQ" })
        assertTrue(labels.any { it == "GET EQ extended PRESET_EQ" })
        assertTrue(labels.any { it == "GET playback status" })
        assertFalse(labels.any { it == "GET display firmware version" })
        assertFalse(labels.any { it.contains("LEA") })
        assertFalse(labels.any { it.contains("Quick Access") })
        assertFalse(labels.any { it.contains("Wearing") })
        // XM4 NC refresh now routes through V1 path → label is "GET NC/ASM param V1"
        assertTrue(commands.any { it.label == "GET NC/ASM param V1" })
        assertArrayEquals(
            byteArrayOf(0x0E, 0x04, 0x01),
            commands.first { it.label == "GET device info MODEL_NAME" }.bytes,
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x10, 0x00),
            commands.first { it.label == "GET battery BATTERY" }.bytes,
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xA2.toByte(), 0x01),
            commands.first { it.label == "GET playback status" }.bytes,
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x5A, 0x01),
            commands.first { it.label == "GET EQ extended PRESET_EQ" }.bytes,
        )
        assertFalse(commands.any { it.bytes.contentEquals(byteArrayOf(0x0E, 0x22, 0x00)) })
    }

    @Test
    fun noiseControlCommands_wh1000xm4_useTableSet1NcAsmWrites() {
        val profile = SonyTandemHeadphoneAdapter.match(xm4Device(), "WH-1000XM4")!!
        val commands = SonyTandemHeadphoneAdapter.buildSetNoiseControlModeCommands(
            profile = profile,
            mode = NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 12,
            ambientMode = dev.sonypods.protocol.AmbientSoundMode.NORMAL,
        )

        assertEquals(1, commands.size)
        assertTrue(commands.first().bytes.contentEquals(SonyTandemV1Table1Protocol.buildSetNoiseControlMode(
            NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 12,
        )))
    }

    @Test
    fun registry_resolvesWh1000xm4ByDeviceName() {
        val profile = HeadphoneAdapterRegistry.resolve(xm4Device())

        assertEquals("WH-1000XM4", profile.modelName)
        assertEquals("Sony Tandem", profile.protocolName)
        assertEquals("sony-tandem", profile.adapterId)
    }

    @Test
    fun match_linkBudsS_usesV2Table1AndTrueWirelessBattery() {
        val profile = HeadphoneAdapterRegistry.resolve(
            DiscoveredSonyDevice(
                name = "LinkBuds S",
                address = "00:11:22:33:44:56",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            )
        )

        assertEquals(HeadphoneFormFactor.TRUE_WIRELESS, profile.capabilities.formFactor)
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
            profile.protocolFor(HeadphoneFeature.NOISE_CONTROL),
        )
        assertEquals(
            listOf(
                PowerInquiredType.LEFT_RIGHT_BATTERY,
                PowerInquiredType.CRADLE_BATTERY,
            ),
            profile.capabilities.batteryQueries,
        )
        assertEquals(listOf(EqEbbInquiredType.PRESET_EQ), profile.capabilities.eqConfig.statusQueryTypes)
        assertEquals(listOf(EqEbbInquiredType.PRESET_EQ), profile.capabilities.eqConfig.paramQueryTypes)
    }

    @Test
    fun match_linkBudsFit_usesNaNoiseControlAndTrueWirelessBattery() {
        val profile = HeadphoneAdapterRegistry.resolve(
            DiscoveredSonyDevice(
                name = "LinkBuds Fit",
                address = "80:99:E7:DC:79:6E",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            )
        )

        assertEquals("LinkBuds Fit", profile.modelName)
        assertEquals(HeadphoneFormFactor.TRUE_WIRELESS, profile.capabilities.formFactor)
        assertEquals(
            listOf(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA),
            profile.capabilities.noiseControlQueryTypes,
        )
        assertEquals(
            listOf(
                PowerInquiredType.LEFT_RIGHT_BATTERY,
                PowerInquiredType.CRADLE_BATTERY,
            ),
            profile.capabilities.batteryQueries,
        )
    }

    @Test
    fun setNoiseControl_linkBudsFit_emitsCaptureExactNaBytes() {
        val profile = SonyTandemHeadphoneAdapter.match(
            DiscoveredSonyDevice(
                name = "LinkBuds Fit",
                address = "80:99:E7:DC:79:6E",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            )
        )!!
        val commands = SonyTandemHeadphoneAdapter.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 10,
            ambientMode = AmbientSoundMode.NORMAL,
        )

        assertEquals(1, commands.size)
        // Ground truth: btsnoop_hci_260730_125322.log `68 19 01 01 01 00 0a 00 00`.
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x01, 0x00, 0x0A, 0x00, 0x00),
            commands.single().bytes,
        )
    }

    @Test
    fun setNoiseControl_linkBudsFit_voice_emitsCaptureExactNaBytes() {
        val profile = SonyTandemHeadphoneAdapter.match(
            DiscoveredSonyDevice(
                name = "LinkBuds Fit",
                address = "80:99:E7:DC:79:6E",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            )
        )!!
        val commands = SonyTandemHeadphoneAdapter.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 10,
            ambientMode = AmbientSoundMode.VOICE,
        )

        assertEquals(1, commands.size)
        // 人声: idx[4] = 0x01, verified via btsnoop_hci_260730_133417.log.
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x01, 0x01, 0x0A, 0x00, 0x00),
            commands.single().bytes,
        )
    }

    @Test
    fun match_wh1000xm5_usesV2Table1AndHeadsetBattery() {
        val profile = SonyTandemHeadphoneAdapter.match(
            DiscoveredSonyDevice(
                name = "WH-1000XM5",
                address = "88:C9:E8:2C:F3:1A",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            ),
            reportedModelName = "WH-1000XM5",
        )!!

        assertEquals("WH-1000XM5", profile.modelName)
        assertEquals("PREMIUM", profile.series)
        assertEquals(HeadphoneFormFactor.HEADSET, profile.capabilities.formFactor)
        assertEquals(listOf(PowerInquiredType.BATTERY), profile.capabilities.batteryQueries)
        assertEquals(
            listOf(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS),
            profile.capabilities.noiseControlQueryTypes,
        )
        // WH-1000XM5 is V2_TABLE1: same 0x17 NC/ASM as WF-1000XM5 / LinkBuds S.
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
            profile.protocolFor(HeadphoneFeature.NOISE_CONTROL),
        )
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
            profile.protocolFor(HeadphoneFeature.AMBIENT_VOICE_MODE),
        )
        // Over-ear headphone: no TWS / LEA / quick-access / wearing-status features.
        assertTrue(profile.supports(HeadphoneFeature.AMBIENT_VOICE_MODE))
        assertFalse(profile.supports(HeadphoneFeature.LEA_STATUS))
        assertFalse(profile.supports(HeadphoneFeature.QUICK_ACCESS))
        assertFalse(profile.supports(HeadphoneFeature.WEARING_STATUS))
    }

    @Test
    fun setNoiseControl_wh1000xm5_emitsCaptureExactV2Bytes() {
        val profile = SonyTandemHeadphoneAdapter.match(
            DiscoveredSonyDevice(
                name = "WH-1000XM5",
                address = "88:C9:E8:2C:F3:1A",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            ),
            reportedModelName = "WH-1000XM5",
        )!!
        // Ground truth: btsnoop_hci_260730_134818.log NTF `69 17 01 01 01 01 14`
        // (ASM, focus-on-voice ON, level 20) -> SET `68 17 01 01 01 01 14`.
        val commands = SonyTandemHeadphoneAdapter.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 20,
            ambientMode = AmbientSoundMode.VOICE,
        )

        assertEquals(1, commands.size)
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x17, 0x01, 0x01, 0x01, 0x01, 0x14),
            commands.single().bytes,
        )
    }

    @Test
    fun parse_xm4TableSet1NcAsmResponse_routesViaV1_returnsNoiseControl() {
        val profile = SonyTandemHeadphoneAdapter.match(xm4Device(), "WH-1000XM4")!!
        // 0x67 = NCASM_RET_PARAM, 0x02 = V1_TABLE_SET1_NC_ASM type
        val raw = byteArrayOf(0x0E, 0x67, 0x02, 0x01, 0x02, 0x02, 0x01, 0x00, 0x00)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected NoiseControl but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM, parsed.type)
        assertEquals(NoiseControlMode.NOISE_CANCELLING, parsed.controlMode)
    }

    @Test
    fun parse_xm4BatteryResponse_routesToV1Parser_returnsBattery() {
        val profile = SonyTandemHeadphoneAdapter.match(xm4Device(), "WH-1000XM4")!!
        val raw = byteArrayOf(0x0E, 0x11, 0x00, 88.toByte(), 0x00)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected Battery but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(88), parsed.values)
    }

    @Test
    fun parse_xm4FirmwareVersionDeviceInfo_routesViaV1() {
        val profile = SonyTandemHeadphoneAdapter.match(xm4Device(), "WH-1000XM4")!!
        val version = "2.5.0".encodeToByteArray()
        val raw = byteArrayOf(0x0E, 0x05, 0x02, version.size.toByte()) + version
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected DeviceInfo but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.DeviceInfo)
        parsed as ParsedTandemResponse.DeviceInfo
        assertEquals(DeviceInfoType.FW_VERSION, parsed.type)
        assertEquals("2.5.0", parsed.text)
    }

    @Test
    fun parse_xm4PlaybackResponse_routesViaV1() {
        val profile = SonyTandemHeadphoneAdapter.match(xm4Device(), "WH-1000XM4")!!
        val raw = byteArrayOf(0x0E, 0xA3.toByte(), 0x01, 0x00, 0x02)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected PlaybackAck but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.PlaybackAck)
        parsed as ParsedTandemResponse.PlaybackAck
        assertEquals(PlaybackStatus.PAUSED, parsed.status)
    }

    @Test
    fun parse_xm4EqParamResponse_routesViaV1_returnsPresetAndBands() {
        val profile = SonyTandemHeadphoneAdapter.match(xm4Device(), "WH-1000XM4")!!
        val raw = byteArrayOf(0x0E, 0x57, 0x01, 0xA2.toByte(), 0x06, 0x0A, 0x0A, 0x0A, 0x0A, 0x08, 0x11)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected EqEbb but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.EqEbb)
        parsed as ParsedTandemResponse.EqEbb
        assertEquals(EqEbbInquiredType.PRESET_EQ, parsed.type)
        assertEquals(EqPresetId.USER_SETTING2, parsed.preset)
        assertEquals(listOf(0x0A, 0x0A, 0x0A, 0x0A, 0x08, 0x11), parsed.bandSteps)
    }

    @Test
    fun parse_linkBudsSNcAsmResponse_routesToV2Parser_returnsNoiseControl() {
        val profile = HeadphoneAdapterRegistry.resolve(
            DiscoveredSonyDevice(
                name = "LinkBuds S",
                address = "00:11:22:33:44:56",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            )
        )
        // ncAsmEffect 0x01 = on, ncAsmMode 0x01 = ambient (LinkBuds S capture semantics).
        val raw = byteArrayOf(0x0E, 0x67, 0x17, 0x01, 0x01, 0x01, 0x00, 0x0C)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected NoiseControl but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(12, parsed.ambientLevel)
    }

    // ── 0x13 collision tests ──

    @Test
    fun parse_xm4NonBattery0x13Payload_notRoutedAsBattery() {
        val profile = SonyTandemHeadphoneAdapter.match(xm4Device(), "WH-1000XM4")!!
        val raw = byteArrayOf(0x0E, 0x13, 0x01, 0x00, 0x01)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
    }

    @Test
    fun parse_xm4V1Battery0x00_stillRoutedAsBattery() {
        val profile = SonyTandemHeadphoneAdapter.match(xm4Device(), "WH-1000XM4")!!
        val raw = byteArrayOf(0x0E, 0x13, 0x00, 88.toByte())
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected Battery but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(88), parsed.values)
    }

    // ── Model name normalization tests ──

    @Test
    fun registry_resolvesWh1000xm4WithSpaces() {
        val profile = HeadphoneAdapterRegistry.resolve(
            DiscoveredSonyDevice(
                name = "WH 1000XM4",
                address = "00:11:22:33:44:55",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            )
        )
        assertEquals("WH-1000XM4", profile.modelName)
    }

    @Test
    fun registry_resolvesWh1000xm4WithUnderscores() {
        val profile = HeadphoneAdapterRegistry.resolve(
            DiscoveredSonyDevice(
                name = "WH_1000XM4",
                address = "00:11:22:33:44:55",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            )
        )
        assertEquals("WH-1000XM4", profile.modelName)
    }

    private fun xm4Device(): DiscoveredSonyDevice =
        DiscoveredSonyDevice(
            name = "WH-1000XM4",
            address = "00:11:22:33:44:55",
            rssi = 0,
            source = "bonded",
            isLikelyControlEndpoint = true,
        )
}
