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
import dev.sonypods.protocol.SonySupportedFunction
import dev.sonypods.protocol.SonyTable
import dev.sonypods.protocol.SonyTandemV1Table1Protocol
import dev.sonypods.protocol.SonyTandemV2Table1Protocol
import dev.sonypods.protocol.SonyV1FunctionType
import dev.sonypods.protocol.SonyV2FunctionType
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-dynamic adapter regression tests. No model is matched by name: the
 * neutral profile is refined purely from the transport endpoints (generation),
 * the RET_PROTOCOL_INFO version and the RET_SUPPORT_FUNCTION capability probe.
 */
class SonyTandemHeadphoneAdapterTest {

    // ── Pure-dynamic matching ────────────────────────────────────────────────

    @Test
    fun match_neverMatchesByName() {
        assertNull(SonyTandemHeadphoneAdapter.match(xm4Device(), reportedModelName = "WH-1000XM4"))
        assertNull(SonyTandemHeadphoneAdapter.match(linkBudsSDevice()))
    }

    @Test
    fun resolve_returnsNeutralV2ProfileWithProbeOnlyEvidence() {
        val profile = HeadphoneAdapterRegistry.resolve(xm4Device())

        assertEquals("WH-1000XM4", profile.modelName)
        assertEquals("sony-tandem", profile.adapterId)
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1, profile.protocolFor(HeadphoneFeature.DEVICE_INFO))
        // Neutral: only the always-safe reads are enabled until the probe answers.
        assertTrue(profile.supports(HeadphoneFeature.DEVICE_INFO))
        assertTrue(profile.supports(HeadphoneFeature.BATTERY))
        assertFalse(profile.supports(HeadphoneFeature.NOISE_CONTROL))
        assertFalse(profile.supports(HeadphoneFeature.EQ))
        assertFalse(profile.supports(HeadphoneFeature.PLAYBACK_CONTROL))
        assertFalse(profile.supports(HeadphoneFeature.CLEAR_BASS))
        assertFalse(profile.supports(HeadphoneFeature.LEA_STATUS))
        assertFalse(profile.supports(HeadphoneFeature.QUICK_ACCESS))
        assertFalse(profile.supports(HeadphoneFeature.WEARING_STATUS))
        assertTrue(profile.protocolEvidence.any { it.startsWith("probe-only:") })
        assertFalse(profile.protocolEvidence.any { it.startsWith("static-profile:") })
    }

    @Test
    fun neutralProfile_hasResolvableBindingForEveryFeature() {
        val profile = HeadphoneAdapterRegistry.resolve(xm4Device())
        // channelFor must resolve for any domain the probe may address, even
        // before the probe enables the feature.
        for (feature in HeadphoneFeature.entries) {
            assertEquals(TandemChannel.GATT_V2_HPC, profile.channelFor(feature))
        }
    }

    @Test
    fun neutralProfile_writableFeaturesAreDisabled() {
        val profile = HeadphoneAdapterRegistry.resolve(xm4Device())
        assertFalse(SonyTandemHeadphoneAdapter.canWrite(profile, HeadphoneFeature.NOISE_CONTROL))
        assertFalse(SonyTandemHeadphoneAdapter.canWrite(profile, HeadphoneFeature.EQ))
        assertFalse(SonyTandemHeadphoneAdapter.canWrite(profile, HeadphoneFeature.CLEAR_BASS))
        assertFalse(SonyTandemHeadphoneAdapter.canWrite(profile, HeadphoneFeature.PLAYBACK_CONTROL))
    }

    // ── Transport-endpoint generation rebinding ──────────────────────────────

    @Test
    fun withEndpointChannels_v2HpcKeepsV2() {
        val profile = SonyTandemHeadphoneAdapter.withEndpointChannels(
            HeadphoneAdapterRegistry.resolve(xm4Device()),
            setOf(TandemChannel.GATT_V2_HPC, TandemChannel.GATT_V2_MC),
        )
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1, profile.protocolFor(HeadphoneFeature.BATTERY))
        assertEquals(TandemChannel.GATT_V2_HPC, profile.channelFor(HeadphoneFeature.BATTERY))
    }

    @Test
    fun withEndpointChannels_sppBindsV2() {
        val profile = SonyTandemHeadphoneAdapter.withEndpointChannels(
            HeadphoneAdapterRegistry.resolve(linkBudsSDevice()),
            setOf(TandemChannel.SPP_MDR),
        )
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1, profile.protocolFor(HeadphoneFeature.NOISE_CONTROL))
    }

    @Test
    fun withEndpointChannels_sppV1UuidBindsV1() {
        // SC: SPP UUID 96cc203e… is TABLE_SET_1 (V1). WH-1000XM4 binds to this
        // UUID, so SPP must NOT be forced to V2 or the device hard-disconnects.
        val profile = SonyTandemHeadphoneAdapter.withEndpointChannels(
            HeadphoneAdapterRegistry.resolve(xm4Device()),
            setOf(TandemChannel.SPP_MDR),
            sppUuid = java.util.UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba"),
        )
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1, profile.protocolFor(HeadphoneFeature.NOISE_CONTROL))
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1, profile.protocolFor(HeadphoneFeature.BATTERY))
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1, profile.protocolFor(HeadphoneFeature.EQ))
    }

    @Test
    fun withEndpointChannels_sppV2UuidBindsV2() {
        val profile = SonyTandemHeadphoneAdapter.withEndpointChannels(
            HeadphoneAdapterRegistry.resolve(linkBudsSDevice()),
            setOf(TandemChannel.SPP_MDR),
            sppUuid = java.util.UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2"),
        )
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1, profile.protocolFor(HeadphoneFeature.NOISE_CONTROL))
    }

    @Test
    fun withEndpointChannels_v1McRebindsToV1() {
        val profile = SonyTandemHeadphoneAdapter.withEndpointChannels(
            HeadphoneAdapterRegistry.resolve(xm4Device()),
            setOf(TandemChannel.GATT_V1_MC),
        )
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1, profile.protocolFor(HeadphoneFeature.BATTERY))
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1, profile.protocolFor(HeadphoneFeature.NOISE_CONTROL))
        assertEquals(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1, profile.protocolFor(HeadphoneFeature.EQ))
        assertEquals(TandemChannel.GATT_V1_MC, profile.channelFor(HeadphoneFeature.NOISE_CONTROL))
        assertTrue(profile.rebindGeneration > 0)
    }

    @Test
    fun withEndpointChannels_emptyChannelsLeavesProfileUnchanged() {
        val profile = HeadphoneAdapterRegistry.resolve(xm4Device())
        assertEquals(profile, SonyTandemHeadphoneAdapter.withEndpointChannels(profile, emptySet()))
    }

    // ── Protocol info ────────────────────────────────────────────────────────

    @Test
    fun refreshCommands_alwaysRequestProtocolInfo() {
        val profile = HeadphoneAdapterRegistry.resolve(xm4Device())
        val commands = SonyTandemHeadphoneAdapter.buildRefreshCommands(profile)
        assertTrue(commands.any { it.label == "GET protocol info" })
        // Official GET_PROTOCOL_INFO body: [dataType 0x0E][cmd 0x00][type 0x00]
        assertArrayEquals(
            byteArrayOf(0x0E, 0x00, 0x00),
            commands.first { it.label == "GET protocol info" }.bytes,
        )
    }

    @Test
    fun parse_protocolInfoResponse_returnsWhitelistedVersion() {
        val profile = SonyTandemHeadphoneAdapter.withEndpointChannels(
            HeadphoneAdapterRegistry.resolve(xm4Device()),
            setOf(TandemChannel.GATT_V1_MC),
        )
        // RET_PROTOCOL_INFO: [0x0E][0x01][type 0x00][0x70][0x10] -> 0x7010
        val raw = byteArrayOf(0x0E, 0x01, 0x00, 0x70, 0x10)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected ProtocolInfo but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.ProtocolInfo)
        assertEquals(0x7010, (parsed as ParsedTandemResponse.ProtocolInfo).protocolVersion)
    }

    // ── Probe-derived V2 profile (LinkBuds S / WF-1000XM5 / WH-1000XM5) ─────

    @Test
    fun probe_linkBudsS_derivesV2FeaturesAndTrueWirelessBattery() {
        val profile = probed(linkBudsSDevice(), v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play(), v2Lea())

        assertTrue(profile.supports(HeadphoneFeature.NOISE_CONTROL))
        assertTrue(profile.supports(HeadphoneFeature.AMBIENT_LEVEL))
        assertTrue(profile.supports(HeadphoneFeature.EQ))
        assertTrue(profile.supports(HeadphoneFeature.CLEAR_BASS))
        assertTrue(profile.supports(HeadphoneFeature.PLAYBACK_CONTROL))
        assertTrue(profile.supports(HeadphoneFeature.LEA_STATUS))
        assertFalse(profile.supports(HeadphoneFeature.QUICK_ACCESS))
        assertFalse(profile.supports(HeadphoneFeature.WEARING_STATUS))
        assertEquals(HeadphoneFormFactor.TRUE_WIRELESS, profile.capabilities.formFactor)
        assertEquals(
            listOf(PowerInquiredType.LEFT_RIGHT_BATTERY, PowerInquiredType.CRADLE_BATTERY),
            profile.capabilities.batteryQueries,
        )
        assertEquals(
            listOf(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS),
            profile.capabilities.noiseControlQueryTypes,
        )
        assertEquals(
            listOf(EqEbbInquiredType.PRESET_EQ, EqEbbInquiredType.EBB),
            profile.capabilities.eqConfig.statusQueryTypes,
        )
        assertEquals(
            listOf(EqEbbInquiredType.PRESET_EQ, EqEbbInquiredType.EBB),
            profile.capabilities.eqConfig.paramQueryTypes,
        )
        assertEquals(PlaybackDispatchStrategy.TANDEM_FIRST, profile.playbackDispatchStrategy)
        // Every domain stays on V2.
        assertTrue(profile.featureBindings.values.all { it.variant == HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1 })
    }

    @Test
    fun probe_linkBudsS_refreshUsesV2Builders() {
        val profile = probed(linkBudsSDevice(), v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play(), v2Lea())
        val commands = SonyTandemHeadphoneAdapter.buildRefreshCommands(profile)
        val labels = commands.map { it.label }

        assertTrue(labels.any { it == "GET protocol info" })
        // Battery uses V2 POWER_GET_STATUS (0x22).
        val batteryCmd = commands.first { it.label.startsWith("GET battery") }
        assertEquals(0x22, batteryCmd.bytes[1].toInt() and 0xFF)
        // NC/ASM uses V2 NCASM_GET_PARAM (0x66) with the 0x17 inquired type.
        val ncCmd = commands.first { it.label.startsWith("GET NC/ASM") }
        assertEquals(0x66, ncCmd.bytes[1].toInt() and 0xFF)
        assertEquals(0x17, ncCmd.bytes[2].toInt() and 0xFF)
        assertTrue(labels.any { it == "GET EQ param PRESET_EQ" })
        assertTrue(labels.any { it == "GET EQ extended PRESET_EQ" })
        assertTrue(labels.any { it == "GET playback status" })
        assertTrue(labels.any { it.contains("LEA") })
    }

    @Test
    fun probe_linkBudsS_noiseControlWrite_usesV217InquiredType() {
        val profile = probed(linkBudsSDevice(), v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play())
        val commands = SonyTandemHeadphoneAdapter.buildSetNoiseControlModeCommands(
            profile = profile,
            mode = NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 15,
            ambientMode = AmbientSoundMode.VOICE,
        )
        assertEquals(1, commands.size)
        assertEquals(0x17, commands[0].bytes[2].toInt() and 0xFF)
    }

    @Test
    fun probe_linkBudsS_matchesStaticProfileOracle() {
        // Ground truth: the static LinkBuds S profile (git a291b72) is the
        // oracle. The dynamic probe, fed the same FunctionType list the device
        // advertises, must reproduce that profile exactly.
        val profile = probed(
            linkBudsSDevice(),
            v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play(), v2Lea(), v2QuickAccess(), v2Wearing(),
        )
        val caps = profile.capabilities
        assertEquals(HeadphoneFormFactor.TRUE_WIRELESS, caps.formFactor)
        assertEquals(
            listOf(PowerInquiredType.LEFT_RIGHT_BATTERY, PowerInquiredType.CRADLE_BATTERY),
            caps.batteryQueries,
        )
        assertEquals(
            listOf(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS),
            caps.noiseControlQueryTypes,
        )
        assertEquals(
            setOf(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS),
            caps.writableNoiseControlTypes,
        )
        for (feature in setOf(
            HeadphoneFeature.NOISE_CONTROL,
            HeadphoneFeature.AMBIENT_LEVEL,
            HeadphoneFeature.AMBIENT_VOICE_MODE,
            HeadphoneFeature.PLAYBACK_CONTROL,
            HeadphoneFeature.EQ,
            HeadphoneFeature.CLEAR_BASS,
            HeadphoneFeature.LEA_STATUS,
            HeadphoneFeature.QUICK_ACCESS,
            HeadphoneFeature.WEARING_STATUS,
        )) {
            assertTrue("static oracle feature missing: ${feature.name}", profile.supports(feature))
        }
        assertEquals(
            listOf(EqEbbInquiredType.PRESET_EQ, EqEbbInquiredType.EBB),
            caps.eqConfig.statusQueryTypes,
        )
        assertEquals(
            listOf(EqEbbInquiredType.PRESET_EQ, EqEbbInquiredType.EBB),
            caps.eqConfig.paramQueryTypes,
        )
        assertTrue(caps.eqConfig.hasClearBass)
        assertEquals(EqEbbInquiredType.PRESET_EQ, caps.eqConfig.writeInquiredType)
        // Write path stays on the probed 0x17 type, not the fallback builders.
        assertTrue(SonyTandemHeadphoneAdapter.canWrite(profile, HeadphoneFeature.NOISE_CONTROL))
        assertTrue(SonyTandemHeadphoneAdapter.canWrite(profile, HeadphoneFeature.AMBIENT_LEVEL))
    }

    @Test
    fun probe_lrOnlyBattery_derivesTrueWirelessFormFactor() {
        // A TWS advertising only LEFT_RIGHT_BATTERY (no separate cradle
        // FunctionType) is still an in-ear device. Regression for the
        // single-earbud layout: it must not fall back to the UNKNOWN form factor.
        val profile = probed(
            linkBudsSDevice(),
            listOf(
                v2(SonyV2FunctionType.LEFT_RIGHT_BATTERY_LEVEL_INDICATOR, 0),
                v2(SonyV2FunctionType.NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT, 1),
            ),
        )
        assertEquals(HeadphoneFormFactor.TRUE_WIRELESS, profile.capabilities.formFactor)
        assertEquals(
            listOf(PowerInquiredType.LEFT_RIGHT_BATTERY),
            profile.capabilities.batteryQueries,
        )
        assertEquals(
            listOf(NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS),
            profile.capabilities.noiseControlQueryTypes,
        )
        assertTrue(profile.supports(HeadphoneFeature.NOISE_CONTROL))
    }

    @Test
    fun parse_v2SupportFunction_everyScCodeResolves() {
        // `就算回报 SC 也能接收`：any code in the complete SC 13.2.1 table must
        // resolve to a concrete FunctionType, never OUT_OF_RANGE, so a model
        // advertising an unusual feature keeps that feature instead of silently
        // dropping it. Builds the full NO_1 list from the enum itself.
        val payload = ByteArrayOutputStream().apply {
            write(0x00) // FIXED_VALUE
            val entries = SonyV2FunctionType.entries.filter {
                it != SonyV2FunctionType.OUT_OF_RANGE && it.table == SonyTable.NO_1
            }
            write(entries.size)
            entries.forEachIndexed { i, type ->
                write(type.code.toInt())
                write(i and 0xFF)
            }
        }.toByteArray()
        val parsed = SonyTandemV2Table1Protocol.parseSupportFunction(payload)
        // Every table entry is received; none is dropped.
        assertEquals(
            SonyV2FunctionType.entries.filter { it != SonyV2FunctionType.OUT_OF_RANGE && it.table == SonyTable.NO_1 }.size,
            parsed.size,
        )
        // Battery + NCASM codes that a cut-down table would drop must survive.
        val codes = parsed.map { it.code }.toSet()
        assertTrue(SonyV2FunctionType.LR_BATTERY_LEVEL_WITH_THRESHOLD.code in codes)
        assertTrue(SonyV2FunctionType.ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION.code in codes)
        assertEquals(SonyV2FunctionType.INTEGRATED_AUTO_PLAY.code.toInt() and 0xFF, 0xB8)
        assertEquals(SonyV2FunctionType.SAR_OPTIMIZATION_ACCEL_TYPE.code.toInt() and 0xFF, 0xB6)
    }

    @Test
    fun probe_wh1000xm5_emitsCaptureExactV2Bytes() {
        // Ground truth: btsnoop_hci_260730_134818.log NTF `69 17 01 01 01 01 14`
        // (ASM, focus-on-voice ON, level 20) -> SET `68 17 01 01 01 01 14`.
        val profile = probed(
            DiscoveredSonyDevice(
                name = "WH-1000XM5",
                address = "88:C9:E8:2C:F3:1A",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            ),
            v2NcAsm17(), v2Eq(), v2SingleBattery(), v2Play(),
        )
        assertTrue(profile.supports(HeadphoneFeature.AMBIENT_VOICE_MODE))
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
    fun probe_wh1000xm5_derivesHeadsetBatteryWithoutTwsFeatures() {
        val profile = probed(
            DiscoveredSonyDevice(
                name = "WH-1000XM5",
                address = "88:C9:E8:2C:F3:1A",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            ),
            v2NcAsm17(), v2Eq(), v2SingleBattery(), v2Play(),
        )
        assertEquals(HeadphoneFormFactor.HEADSET, profile.capabilities.formFactor)
        assertEquals(listOf(PowerInquiredType.BATTERY), profile.capabilities.batteryQueries)
        assertFalse(profile.supports(HeadphoneFeature.LEA_STATUS))
        assertFalse(profile.supports(HeadphoneFeature.QUICK_ACCESS))
        assertFalse(profile.supports(HeadphoneFeature.WEARING_STATUS))
    }

    @Test
    fun probe_wf1000xm4_emitsCaptureExactV2Bytes() {
        // Ground truth: btsnoop_hci_260730_113943.log `68 15 01 01 01 02 01 10`
        // (ASM, level 16, dual NC + voice). rf0/i layout: [NcAsmMode][NcValue]
        // [AmbientSoundMode][level]; NcValue is always ON_DUAL (0x02), and with
        // default NORMAL the ASM byte is 0x00.
        val profile = probed(
            DiscoveredSonyDevice(
                name = "WF-1000XM4",
                address = "F8:4E:17:9A:3C:0D",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            ),
            v2NcAsm15(), v2Eq(), v2TwsBattery(), v2Play(), v2Lea(), v2Wearing(), v2QuickAccess(),
        )
        assertTrue(profile.supports(HeadphoneFeature.LEA_STATUS))
        assertTrue(profile.supports(HeadphoneFeature.QUICK_ACCESS))
        assertTrue(profile.supports(HeadphoneFeature.WEARING_STATUS))
        assertEquals(
            listOf(NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS),
            profile.capabilities.noiseControlQueryTypes,
        )
        val commands = SonyTandemHeadphoneAdapter.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 16,
            ambientMode = AmbientSoundMode.NORMAL,
        )

        assertEquals(1, commands.size)
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x15, 0x01, 0x01, 0x01, 0x02, 0x00, 0x10),
            commands.single().bytes,
        )
    }

    @Test
    fun probe_linkBudsFit_emitsCaptureExactNaBytes() {
        // Ground truth: btsnoop_hci_260730_125322.log `68 19 01 01 01 00 0a 00 00`.
        val profile = probed(
            DiscoveredSonyDevice(
                name = "LinkBuds Fit",
                address = "80:99:E7:DC:79:6E",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            ),
            v2NcAsm19(), v2Eq(), v2TwsBattery(), v2Play(),
        )
        assertEquals(
            listOf(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA),
            profile.capabilities.noiseControlQueryTypes,
        )
        val commands = SonyTandemHeadphoneAdapter.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 10,
            ambientMode = AmbientSoundMode.NORMAL,
        )

        assertEquals(1, commands.size)
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x01, 0x00, 0x0A, 0x00, 0x00),
            commands.single().bytes,
        )
    }

    @Test
    fun probe_linkBudsFit_voice_emitsCaptureExactNaBytes() {
        // 人声: idx[4] = 0x01, verified via btsnoop_hci_260730_133417.log.
        val profile = probed(
            DiscoveredSonyDevice(
                name = "LinkBuds Fit",
                address = "80:99:E7:DC:79:6E",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            ),
            v2NcAsm19(), v2Eq(), v2TwsBattery(), v2Play(),
        )
        val commands = SonyTandemHeadphoneAdapter.buildSetNoiseControlModeCommands(
            profile,
            NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 10,
            ambientMode = AmbientSoundMode.VOICE,
        )

        assertEquals(1, commands.size)
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x01, 0x01, 0x0A, 0x00, 0x00),
            commands.single().bytes,
        )
    }

    @Test
    fun probe_v2EqPresetWrite_usesOfficialPayloadWithoutCachedBands() {
        val profile = probed(linkBudsSDevice(), v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play())
        val context = EqWriteContext(rawBandSteps = listOf(0x13, 0x0A, 0x0A, 0x0A, 0x0B, 0x0C))

        val speech = SonyTandemHeadphoneAdapter.buildSetEqPresetCommands(profile, EqPresetId.SPEECH, context).single()
        val userSetting2 = SonyTandemHeadphoneAdapter.buildSetEqPresetCommands(
            profile,
            EqPresetId.USER_SETTING2,
            context,
        ).single()

        assertArrayEquals(byteArrayOf(0x0E, 0x58, 0x00, 0x17, 0x00), speech.bytes)
        assertEquals(TandemChannel.GATT_V2_HPC, speech.channel)
        assertArrayEquals(byteArrayOf(0x0E, 0x58, 0x00, 0xA2.toByte(), 0x00), userSetting2.bytes)
        assertEquals(TandemChannel.GATT_V2_HPC, userSetting2.channel)
    }

    @Test
    fun probe_v2EqBandWrite_usesOfficialPresetEqBandPayload() {
        val profile = probed(linkBudsSDevice(), v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play())
        val rawSteps = listOf(0x13, 0x0A, 0x0A, 0x0A, 0x0B, 0x02)
        val context = EqWriteContext(rawBandSteps = rawSteps)

        val command = SonyTandemHeadphoneAdapter.buildSetEqBandCommands(
            profile, rawSteps, EqPresetId.USER_SETTING2, context,
        ).single()

        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x00, 0xA2.toByte(), 0x06, 0x13, 0x0A, 0x0A, 0x0A, 0x0B, 0x02),
            command.bytes,
        )
    }

    // ── Probe-derived V1 profile (WH-1000XM4) ───────────────────────────────

    @Test
    fun probe_xm4V1_derivesOwnV1FeatureSet() {
        val profile = probedV1(xm4Device(), v1FullSet())

        assertEquals(HeadphoneFormFactor.HEADSET, profile.capabilities.formFactor)
        assertEquals(
            setOf(
                HeadphoneFeature.DEVICE_INFO,
                HeadphoneFeature.BATTERY,
                HeadphoneFeature.NOISE_CONTROL,
                HeadphoneFeature.AMBIENT_LEVEL,
                HeadphoneFeature.AMBIENT_VOICE_MODE,
                HeadphoneFeature.PLAYBACK_CONTROL,
                HeadphoneFeature.EQ,
                HeadphoneFeature.CLEAR_BASS,
            ),
            profile.capabilities.features,
        )
        assertTrue(profile.featureBindings.values.all { it.variant == HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1 })
        assertTrue(profile.featureBindings.values.all { it.channel == TandemChannel.GATT_V1_MC })
        assertFalse(profile.supports(HeadphoneFeature.LEA_STATUS))
        assertFalse(profile.supports(HeadphoneFeature.QUICK_ACCESS))
        assertFalse(profile.supports(HeadphoneFeature.WEARING_STATUS))
    }

    @Test
    fun probe_xm4_batteryRefresh_usesV1Builder() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val batteryCmd = SonyTandemHeadphoneAdapter.buildRefreshBatteryCommands(profile)
            .first { it.label == "GET battery BATTERY" }
        // V1: COMMON_GET_BATTERY_LEVEL (0x10), not V2 POWER_GET_STATUS (0x22).
        assertArrayEquals(byteArrayOf(0x0E, 0x10, 0x00), batteryCmd.bytes)
        assertEquals(TandemChannel.GATT_V1_MC, batteryCmd.channel)
    }

    @Test
    fun probe_xm4_ncRefresh_usesV1Routing() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val ncCmd = SonyTandemHeadphoneAdapter.buildRefreshNoiseControlCommands(profile).first()
        assertEquals("GET NC/ASM param V1", ncCmd.label)
        // V1 buildGetNcAsmParam produces [0x0E, 0x66, 0x02].
        assertArrayEquals(
            byteArrayOf(0x0E, 0x66, NcAsmInquiredType.V1_TABLE_SET1_NC_ASM.code),
            ncCmd.bytes,
        )
        assertEquals(TandemChannel.GATT_V1_MC, ncCmd.channel)
    }

    @Test
    fun probe_xm4_noiseControlWrite_usesV1Builder() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val commands = SonyTandemHeadphoneAdapter.buildSetNoiseControlModeCommands(
            profile = profile,
            mode = NoiseControlMode.AMBIENT_SOUND,
            ambientLevel = 12,
            ambientMode = AmbientSoundMode.NORMAL,
        )

        assertEquals(1, commands.size)
        assertArrayEquals(
            SonyTandemV1Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 12,
            ),
            commands[0].bytes,
        )
        assertEquals(TandemChannel.GATT_V1_MC, commands[0].channel)
    }

    @Test
    fun probe_xm4_playbackRoutesToV1() {
        val profile = probedV1(xm4Device(), v1FullSet())
        assertEquals(
            HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            profile.protocolFor(HeadphoneFeature.PLAYBACK_CONTROL),
        )
        val refresh = SonyTandemHeadphoneAdapter.buildRefreshPlaybackCommands(profile).single()
        assertArrayEquals(byteArrayOf(0x0E, 0xA2.toByte(), 0x01), refresh.bytes)
        assertEquals(TandemChannel.GATT_V1_MC, refresh.channel)
    }

    @Test
    fun probe_xm4_eqRefresh_onlyPresetEqInParamQueries() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val labels = SonyTandemHeadphoneAdapter.buildRefreshEqCommands(profile).map { it.label }

        assertFalse(labels.any { it.contains("CUSTOM_EQ") })
        assertTrue(labels.any { it == "GET EQ param PRESET_EQ" })
        assertTrue(labels.any { it == "GET EQ extended PRESET_EQ" })
        assertArrayEquals(
            byteArrayOf(0x0E, 0x5A, 0x01),
            SonyTandemHeadphoneAdapter.buildRefreshEqCommands(profile)
                .first { it.label == "GET EQ extended PRESET_EQ" }.bytes,
        )
    }

    @Test
    fun probe_xm4_eqPresetWrite_usesSonyV1PresetPayload() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val context = EqWriteContext(rawBandSteps = listOf(0x11, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A))

        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x01, 0x16, 0x00),
            SonyTandemHeadphoneAdapter.buildSetEqPresetCommands(profile, EqPresetId.BASS, context).single().bytes,
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x01, 0x13, 0x00),
            SonyTandemHeadphoneAdapter.buildSetEqPresetCommands(profile, EqPresetId.RELAXED, context).single().bytes,
        )
    }

    @Test
    fun probe_xm4_userEqBandWrite_matchesCapturedManualEditShape() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val rawSteps = listOf(0x0A, 0x0A, 0x0A, 0x0A, 0x08, 0x11)
        val context = EqWriteContext(rawBandSteps = rawSteps, preset = EqPresetId.USER_SETTING2)

        val command = SonyTandemHeadphoneAdapter.buildSetEqBandCommands(
            profile, rawSteps, EqPresetId.USER_SETTING2, context,
        ).single()

        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x01, 0xFF.toByte(), 0x06, 0x0A, 0x0A, 0x0A, 0x0A, 0x08, 0x11),
            command.bytes,
        )
        assertEquals(TandemChannel.GATT_V1_MC, command.channel)
    }

    @Test
    fun probe_clearBassWrite_usesDeviceSpecificShape() {
        val context = EqWriteContext(
            rawBandSteps = listOf(0x13, 0x0A, 0x0A, 0x0A, 0x0B, 0x0C),
            preset = EqPresetId.USER_SETTING2,
        )

        // LinkBuds S: captured standard EQ path writes Clear Bass by resending PRESET_EQ bands.
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x00, 0xA2.toByte(), 0x06, 0x08, 0x0A, 0x0A, 0x0A, 0x0B, 0x0C),
            SonyTandemHeadphoneAdapter.buildSetClearBassCommands(
                probed(linkBudsSDevice(), v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play()),
                level = -2,
                context,
            ).single().bytes,
        )
        // WH-1000XM4: V1 PRESET_EQ bands, where raw band 0 is Clear Bass.
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x01, 0xFF.toByte(), 0x06, 0x08, 0x0A, 0x0A, 0x0A, 0x0B, 0x0C),
            SonyTandemHeadphoneAdapter.buildSetClearBassCommands(
                probedV1(xm4Device(), v1FullSet()),
                level = -2,
                context,
            ).single().bytes,
        )
    }

    // ── Parse routing through probe-derived profiles ─────────────────────────

    @Test
    fun parse_v1BatteryResponse_0x11_parsedViaV1() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, byteArrayOf(0x0E, 0x11, 0x00, 88.toByte(), 0x00))

        assertTrue("Expected Battery but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(88), parsed.values)
    }

    @Test
    fun parse_v1NcAsmResponse_0x67_parsedViaV1() {
        val profile = probedV1(xm4Device(), v1FullSet())
        // 0x67 = NCASM_RET_PARAM, 0x02 = V1_TABLE_SET1_NC_ASM type
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, byteArrayOf(0x0E, 0x67, 0x02, 0x01, 0x02, 0x02, 0x01, 0x00, 0x00))

        assertTrue("Expected NoiseControl but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM, parsed.type)
        assertEquals(NoiseControlMode.NOISE_CANCELLING, parsed.controlMode)
    }

    @Test
    fun parse_v1PlaybackResponse_0xa3_parsedViaV1() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, byteArrayOf(0x0E, 0xA3.toByte(), 0x01, 0x00, 0x01))

        assertTrue("Expected PlaybackAck but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.PlaybackAck)
        parsed as ParsedTandemResponse.PlaybackAck
        assertEquals(PlaybackStatus.PLAYING, parsed.status)
    }

    @Test
    fun parse_v1EqParamResponse_0x57_parsedViaV1() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val raw = byteArrayOf(0x0E, 0x57, 0x01, 0xA2.toByte(), 0x06, 0x0A, 0x0A, 0x0A, 0x0A, 0x08, 0x11)
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, raw)

        assertTrue("Expected EqEbb but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.EqEbb)
        parsed as ParsedTandemResponse.EqEbb
        assertEquals(EqEbbInquiredType.PRESET_EQ, parsed.type)
        assertEquals(EqPresetId.USER_SETTING2, parsed.preset)
        assertEquals(listOf(0x0A, 0x0A, 0x0A, 0x0A, 0x08, 0x11), parsed.bandSteps)
    }

    @Test
    fun parse_v1DeviceInfoResponse_0x05_parsedViaV1() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val version = "2.5.1".encodeToByteArray()
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, byteArrayOf(0x0E, 0x05, 0x02, version.size.toByte()) + version)

        assertTrue("Expected DeviceInfo but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.DeviceInfo)
        parsed as ParsedTandemResponse.DeviceInfo
        assertEquals(DeviceInfoType.FW_VERSION, parsed.type)
        assertEquals("2.5.1", parsed.text)
    }

    @Test
    fun parse_v2NcAsmResponse_0x67_parsedViaV2() {
        val profile = probed(linkBudsSDevice(), v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play())
        // ncAsmEffect 0x01 = on, ncAsmMode 0x01 = ambient (LinkBuds S capture semantics).
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, byteArrayOf(0x0E, 0x67, 0x17, 0x01, 0x01, 0x01, 0x00, 0x0C))

        assertTrue("Expected NoiseControl but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(12, parsed.ambientLevel)
    }

    @Test
    fun parse_v2DataMdrNo2Response_routesToV2Table2() {
        val profile = probed(linkBudsSDevice(), v2NcAsm17(), v2Eq(), v2TwsBattery(), v2Play())
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, byteArrayOf(0x0F, 0x23, 0x00, 0x01))

        assertTrue("Expected Table2Generic but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Table2Generic)
        (parsed as ParsedTandemResponse.Table2Generic).let { assertEquals("POWER", it.family) }
    }

    @Test
    fun parse_v1DataMdrNo2Response_doesNotRouteToV2Table2() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, byteArrayOf(0x0F, 0x23, 0x00, 0x01))

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
    }

    // ── 0x13 collision tests ─────────────────────────────────────────────────

    @Test
    fun parse_v1NonBattery0x13Payload_notRoutedAsBattery() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, byteArrayOf(0x0E, 0x13, 0x01, 0x00, 0x01))

        assertTrue("Expected Unknown but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Unknown)
    }

    @Test
    fun parse_v1Battery0x00_stillRoutedAsBattery() {
        val profile = probedV1(xm4Device(), v1FullSet())
        val parsed = SonyTandemHeadphoneAdapter.parse(profile, byteArrayOf(0x0E, 0x13, 0x00, 88.toByte()))

        assertTrue("Expected Battery but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(88), parsed.values)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fn(code: Byte, order: Int = 0) = SonySupportedFunction(code, order)
    private fun v2(type: SonyV2FunctionType, order: Int = 0) = SonySupportedFunction(type.code, order)
    private fun v1(type: SonyV1FunctionType, order: Int = 0) = SonySupportedFunction(type.code, order)

    private fun v2NcAsm17(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT, 0),
    )

    private fun v2NcAsm15(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AUTO_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT, 0),
    )

    private fun v2NcAsm19(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_NOISE_ADAPTATION, 0),
    )

    private fun v2Eq(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.PRESET_EQ, 1),
        v2(SonyV2FunctionType.EBB, 2),
    )

    private fun v2TwsBattery(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.LEFT_RIGHT_BATTERY_LEVEL_INDICATOR, 3),
        v2(SonyV2FunctionType.CRADLE_BATTERY_LEVEL_INDICATOR, 4),
    )

    private fun v2SingleBattery(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.BATTERY_LEVEL_INDICATOR, 3),
    )

    private fun v2Play(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT, 5),
    )

    private fun v2Lea(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD, 6),
    )

    private fun v2QuickAccess(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.QUICK_ACCESS, 7),
    )

    private fun v2Wearing(): List<SonySupportedFunction> = listOf(
        v2(SonyV2FunctionType.WEARING_STATUS_DETECTOR, 8),
    )

    private fun v1FullSet(): List<SonySupportedFunction> = listOf(
        v1(SonyV1FunctionType.BATTERY_LEVEL, 0),
        v1(SonyV1FunctionType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE, 1),
        v1(SonyV1FunctionType.PRESET_EQ, 2),
        v1(SonyV1FunctionType.EBB, 3),
        v1(SonyV1FunctionType.PLAYBACK_CONTROLLER, 4),
    )

    private fun resolve(device: DiscoveredSonyDevice): ConnectedHeadphoneProfile =
        HeadphoneAdapterRegistry.resolve(device)

    private fun probed(
        device: DiscoveredSonyDevice,
        vararg functionSets: List<SonySupportedFunction>,
    ): ConnectedHeadphoneProfile {
        val functions = functionSets.flatMap { it }.sortedBy { it.order }
        return SonyCapabilityProbe.applyToProfile(resolve(device), functions, HeadphoneTransport.GATT_HPC)
    }

    private fun probedV1(
        device: DiscoveredSonyDevice,
        functions: List<SonySupportedFunction>,
    ): ConnectedHeadphoneProfile {
        val neutral = SonyTandemHeadphoneAdapter.withEndpointChannels(
            resolve(device),
            setOf(TandemChannel.GATT_V1_MC),
        )
        return SonyCapabilityProbe.applyToProfile(neutral, functions, HeadphoneTransport.GATT_MC)
    }

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
}
