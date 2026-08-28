package dev.sonypods.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyTandemV2Table1ProtocolTest {
    /**
     * Ground truth captured from a LinkBuds S (firmware 4.3.1) over SPP while the
     * modes were switched with the headphone's own touch control, i.e. by a
     * controller that is known good. Payload after inquired type 0x17 is
     * `[valueChanged][ncAsmEffect][ncAsmMode][ambientSoundMode][ambientLevel]`.
     */
    @Test
    fun ncAsmNotify_ambientSound_parsesFromDeviceCapture() {
        val response = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x17, 0x01, 0x01, 0x01, 0x01, 0x14)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NoiseControlMode.AMBIENT_SOUND, response.controlMode)
        assertEquals(20, response.ambientLevel)
    }

    @Test
    fun ncAsmNotify_noiseCancelling_parsesFromDeviceCapture() {
        val response = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x17, 0x01, 0x01, 0x00, 0x01, 0x14)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NoiseControlMode.NOISE_CANCELLING, response.controlMode)
    }

    @Test
    fun ncAsmNotify_off_parsesFromDeviceCapture() {
        val response = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x17, 0x01, 0x00, 0x00, 0x01, 0x14)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NoiseControlMode.OFF, response.controlMode)
    }

    /** What we write must round-trip to the state the device reports back. */
    @Test
    fun ncAsmSetParam_roundTripsThroughParserForAllModes() {
        listOf(
            NoiseControlMode.OFF,
            NoiseControlMode.NOISE_CANCELLING,
            NoiseControlMode.AMBIENT_SOUND,
        ).forEach { mode ->
            val command = SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                mode,
                ambientLevel = 20,
                ambientMode = AmbientSoundMode.VOICE,
            )
            // Device echoes the applied state with the notify command byte.
            val echoed = command.copyOf().also { it[1] = 0x69 }
            val response = SonyTandemV2Table1Protocol.parse(echoed) as ParsedTandemResponse.NoiseControl

            assertEquals(mode, response.controlMode)
        }
    }

    @Test
    fun connectGetProtocolInfo_matchesExportedCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildGetProtocolInfo(),
        )
    }

    /**
     * Ground truth from a real LinkBuds S over SPP. The V2 RET_PROTOCOL_INFO
     * message is 8 bytes after the dataType (SC `ff0.C16477k.b.mo604b` length
     * gate): [cmd 0x01][type 0x00][v3][v2][v1][v0][ena][ena]. SC reads the
     * 4-byte BE version from body[2..5] (`m69436c()`). The engine parses this
     * V2 payload (`[type][v3][v2][v1][v0]...`) to 0x03002015, which is in SC's
     * V2 whitelist `C30916e.f88128b`.
     */
    @Test
    fun connectRetProtocolInfo_v2FourByteVersion_parsesFromDeviceCapture() {
        val raw = byteArrayOf(0x0E, 0x01, 0x00, 0x03, 0x00, 0x20, 0x15, 0x00, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue("Expected ProtocolInfo but got ${parsed::class.simpleName}", parsed is ParsedTandemResponse.ProtocolInfo)
        assertEquals(0x03002015, (parsed as ParsedTandemResponse.ProtocolInfo).protocolVersion)
        assertTrue(SonyTandemConstants.PROTOCOL_VERSIONS_V2.contains(0x03002015))
    }

    @Test
    fun connectRetProtocolInfo_v2TooShortPayload_isUnknown() {
        val raw = byteArrayOf(0x0E, 0x01, 0x00, 0x03, 0x00)
        assertTrue(SonyTandemV2Table1Protocol.parse(raw) is ParsedTandemResponse.Unknown)
    }

    @Test
    fun protocolVersions_v1Whitelist_containsLastScEntry() {
        assertEquals(0x7010, SonyTandemConstants.PROTOCOL_VERSIONS.last())
    }

    @Test
    fun connectGetDeviceInfo_modelName_matchesExportedCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x04, 0x01),
            SonyTandemV2Table1Protocol.buildGetDeviceInfo(DeviceInfoType.MODEL_NAME),
        )
    }

    @Test
    fun powerGetStatus_leftRightBattery_matchesExportedCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x22, 0x01),
            SonyTandemV2Table1Protocol.buildGetBatteryStatus(PowerInquiredType.LEFT_RIGHT_BATTERY),
        )
    }

    @Test
    fun v1PowerOff_matchesSoundConnectCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x22, 0x00, 0x01),
            SonyTandemV1Table1Protocol.buildPowerOff(),
        )
    }

    @Test
    fun v2PowerOff_matchesSoundConnectCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x24, 0x03, 0x01),
            SonyTandemV2Table1Protocol.buildPowerOff(),
        )
    }

    @Test
    fun v1CommonGetBattery_matchesReverseCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x10, 0x00),
            SonyTandemV1Table1Protocol.buildGetBatteryStatus(PowerInquiredType.BATTERY),
        )
    }

    @Test
    fun v1EqExtendedInfoGet_matchesCapturedXm4CommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x5A, 0x01),
            SonyTandemV1Table1Protocol.buildGetEqEbbExtendedInfo(EqEbbInquiredType.PRESET_EQ),
        )
    }

    @Test
    fun v1ConnectGetDeviceInfo_modelName_matchesReverseCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x04, 0x01),
            SonyTandemV1Table1Protocol.buildGetDeviceInfo(DeviceInfoType.MODEL_NAME),
        )
    }

    @Test
    fun commonGetStatus_displayFirmwareVersion_matchesExportedCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x12, 0x09),
            SonyTandemV2Table1Protocol.buildGetDisplayFirmwareVersion(),
        )
    }

    @Test
    fun ncAsmSetStatus_ncOn_matchesExportedCommandShape() {
        assertArrayEquals(
            // ncAsmEffect 0x01 = on, ncAsmMode 0x00 = NC (verified on LinkBuds S).
            byteArrayOf(0x0E, 0x68, 0x17, 0x01, 0x01, 0x00, 0x00, 0x0A),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(NoiseControlMode.NOISE_CANCELLING),
        )
    }

    @Test
    fun ncAsmSetParam_ambientLevel_matchesReverseCommandShape() {
        assertArrayEquals(
            // ncAsmEffect 0x01 = on, ncAsmMode 0x01 = ambient sound (verified on LinkBuds S).
            byteArrayOf(0x0E, 0x68, 0x17, 0x01, 0x01, 0x01, 0x01, 0x0C),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 12,
                ambientMode = AmbientSoundMode.VOICE,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_off_matchesReverseCommandShape() {
        assertArrayEquals(
            // ncAsmEffect 0x00 = off (verified on LinkBuds S).
            byteArrayOf(0x0E, 0x68, 0x17, 0x01, 0x00, 0x00, 0x00, 0x0A),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(NoiseControlMode.OFF),
        )
    }

    @Test
    fun ncAsmSetParam_ncModeSwitchAmbient_matchesReverseCommandShape() {
        // rf0/l: [NcValue][AmbientSoundMode][level]; totalEffect 0x01 = on
        // (NcAsmOnOffValue), ncValue 0x00 = off, ambient level sent raw (1-20).
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x14, 0x01, 0x01, 0x00, 0x00, 0x0C),
            SonyTandemV2Table1Protocol.buildSetNcModeSwitchAndAmbientLevel(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 12,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_ncModeSwitchNcDual_matchesReverseCommandShape() {
        // rf0/l: NC with ncValue 0x02 = ON_DUAL, default level 10.
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x14, 0x01, 0x01, 0x02, 0x00, 0x0A),
            SonyTandemV2Table1Protocol.buildSetNcModeSwitchAndAmbientLevel(NoiseControlMode.NOISE_CANCELLING),
        )
    }

    // Ground truth: btsnoop, official controller SET frame `0E 68 14 01 00 00 00 0B`.
    // rf0/l decodes it as ValueChangeStatus=0x01, totalEffect=NcAsmOnOffValue OFF
    // (0x00), NcValue OFF (0x00), AmbientSoundMode NORMAL (0x00), level raw 0x0B=11.
    // The level byte is e.k(level)=(byte)(level & 0xFF), no off-by-one.
    @Test
    fun ncAsmSetParam_ncModeSwitchOff_matchesDeviceCapture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x14, 0x01, 0x00, 0x00, 0x00, 0x0B),
            SonyTandemV2Table1Protocol.buildSetNcModeSwitchAndAmbientLevel(
                NoiseControlMode.OFF,
                ambientLevel = 11,
            ),
        )
    }

    // ── 0x15 MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS (WF-1000XM4) ──
    // Ground truth: btsnoop_hci_260730_113943.log, official controller frames
    // `68 15 01 01 01 02 01 10` (ambient+voice) / `68 15 01 01 00 02 01 10`
    // (NC+voice) / `68 15 01 00 00 02 01 0a` (off+voice), echoed identically by
    // the headphone in 0x67/0x69 responses. rf0/i layout:
    // [NcAsmMode][NcValue][AmbientSoundMode][level]; NcValue is ON_DUAL (0x02)
    // for every mode; the capture was taken with focus-on-voice enabled, so
    // ambientMode VOICE reproduces the exact wire bytes.

    @Test
    fun ncAsmSetParam_autoNcAmbient_matchesWf1000Xm4Capture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x15, 0x01, 0x01, 0x01, 0x02, 0x01, 0x10),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 16,
                ambientMode = AmbientSoundMode.VOICE,
                type = NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_autoNcNoiseCancelling_matchesWf1000Xm4Capture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x15, 0x01, 0x01, 0x00, 0x02, 0x01, 0x10),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.NOISE_CANCELLING,
                ambientLevel = 16,
                ambientMode = AmbientSoundMode.VOICE,
                type = NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_autoNcOff_matchesWf1000Xm4Capture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x15, 0x01, 0x00, 0x00, 0x02, 0x01, 0x0A),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.OFF,
                ambientLevel = 10,
                ambientMode = AmbientSoundMode.VOICE,
                type = NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            ),
        )
    }

    // The default ambientMode NORMAL yields the same frame with the ASM byte 0x00.
    @Test
    fun ncAsmSetParam_autoNcAmbient_normalVoice_zeroAsmByte() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x15, 0x01, 0x01, 0x01, 0x02, 0x00, 0x10),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 16,
                type = NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            ),
        )
    }

    @Test
    fun parser_autoNcNotify_ambient_parsesFromWf1000Xm4Capture() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x15, 0x01, 0x01, 0x01, 0x02, 0x01, 0x10)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(16, parsed.ambientLevel)
        assertEquals(true, parsed.ambientSoundEnabled)
        assertEquals(false, parsed.enabled)
    }

    @Test
    fun parser_autoNcRetParam_noiseCancelling_parsesFromWf1000Xm4Capture() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x67, 0x15, 0x01, 0x01, 0x00, 0x02, 0x01, 0x10)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NoiseControlMode.NOISE_CANCELLING, parsed.controlMode)
        assertEquals(true, parsed.enabled)
        assertEquals(false, parsed.ambientSoundEnabled)
    }

    @Test
    fun parser_autoNcRetParam_off_parsesEffectOff() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x67, 0x15, 0x01, 0x00, 0x00, 0x02, 0x01, 0x10)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NoiseControlMode.OFF, parsed.controlMode)
    }

    @Test
    fun ncAsmSetParam_autoNc_roundTripsThroughParserForAllModes() {
        listOf(
            NoiseControlMode.OFF,
            NoiseControlMode.NOISE_CANCELLING,
            NoiseControlMode.AMBIENT_SOUND,
        ).forEach { mode ->
            val command = SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                mode,
                ambientLevel = 16,
                type = NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            )
            val echoed = command.copyOf().also { it[1] = 0x69 }
            val response = SonyTandemV2Table1Protocol.parse(echoed) as ParsedTandemResponse.NoiseControl

            assertEquals(mode, response.controlMode)
        }
    }

    // ── 0x19 MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA (LinkBuds Fit) ──
    // Ground truth: btsnoop_hci_260730_125322.log, official controller frames
    // `68 19 01 01 01 00 0a 00 00` (ambient) / `68 19 01 00 01 00 0a 00 00` (off),
    // initial RETP `67 19 01 01 00 00 0a 00 00` (on+NC); headphone echoes the
    // identical layout in 0x67/0x69 responses.

    @Test
    fun ncAsmSetParam_naAmbient_matchesLinkBudsFitCapture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x01, 0x00, 0x0A, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 10,
                type = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_naNoiseCancelling_matchesLinkBudsFitCapture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x00, 0x00, 0x0A, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.NOISE_CANCELLING,
                ambientLevel = 10,
                type = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_naOff_zeroesEffectAndMode() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x00, 0x00, 0x00, 0x0A, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.OFF,
                type = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            ),
        )
    }

    // 人声 (focus-on-voice) SET: idx[4] must be 0x01. Ground truth:
    // btsnoop_hci_260730_133417.log where idx[4] toggled 0x00<->0x01.
    @Test
    fun ncAsmSetParam_naAmbientVoice_encodesVoiceByte() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x01, 0x01, 0x0A, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 10,
                ambientMode = AmbientSoundMode.VOICE,
                type = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_naAmbientNormal_encodesZeroVoiceByte() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x01, 0x00, 0x0A, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 10,
                ambientMode = AmbientSoundMode.NORMAL,
                type = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            ),
        )
    }

    @Test
    fun parser_naRetParam_noiseCancelling_parsesFromLinkBudsFitCapture() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x67, 0x19, 0x01, 0x01, 0x00, 0x00, 0x0A, 0x00, 0x00)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA, parsed.type)
        assertEquals(NoiseControlMode.NOISE_CANCELLING, parsed.controlMode)
        assertEquals(10, parsed.ambientLevel)
        assertEquals(true, parsed.enabled)
        assertEquals(false, parsed.ambientSoundEnabled)
    }

    @Test
    fun parser_naNotify_ambient_parsesFromLinkBudsFitCapture() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x19, 0x01, 0x01, 0x01, 0x00, 0x0A, 0x00, 0x00)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(10, parsed.ambientLevel)
        assertEquals(true, parsed.ambientSoundEnabled)
        assertEquals(false, parsed.enabled)
        assertEquals(AmbientSoundMode.NORMAL, parsed.ambientMode)
    }

    // 人声 NTF: idx[4] = 0x01 must round-trip to AmbientSoundMode.VOICE.
    @Test
    fun parser_naNotify_ambientVoice_parsesFromLinkBudsFitCapture() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x19, 0x01, 0x01, 0x01, 0x01, 0x0A, 0x00, 0x00)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(10, parsed.ambientLevel)
        assertEquals(true, parsed.ambientSoundEnabled)
        assertEquals(false, parsed.enabled)
        assertEquals(AmbientSoundMode.VOICE, parsed.ambientMode)
    }

    @Test
    fun parser_naNotify_off_parsesEffectOff() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x19, 0x01, 0x00, 0x00, 0x00, 0x0A, 0x00, 0x00)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(NoiseControlMode.OFF, parsed.controlMode)
    }

    @Test
    fun ncAsmSetParam_na_roundTripsThroughParserForAllModes() {
        listOf(
            NoiseControlMode.OFF,
            NoiseControlMode.NOISE_CANCELLING,
            NoiseControlMode.AMBIENT_SOUND,
        ).forEach { mode ->
            val command = SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                mode,
                ambientLevel = 10,
                type = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            )
            val echoed = command.copyOf().also { it[1] = 0x69 }
            val response = SonyTandemV2Table1Protocol.parse(echoed) as ParsedTandemResponse.NoiseControl

            assertEquals(mode, response.controlMode)
        }
    }

    // ── 0x19 noise-adaptive (Auto Ambient Sound) trailing pair: idx[6]=toggle
    // (NcAsmOnOffValue OFF=0/ON=1), idx[7]=NoiseAdaptiveSensitivity
    // (STANDARD=0/HIGH=1/LOW=2). SC reference frame: `68 19 01 01 01 00 0A 01 01`
    // (rf0/g writer; pf0/d1 builder). ──

    @Test
    fun ncAsmSetParam_noiseAdaptiveOn_encodesToggleAndSensitivity() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x01, 0x00, 0x0A, 0x01, 0x01),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 10,
                type = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
                noiseAdaptive = true,
                noiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.HIGH,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_noiseAdaptiveOn_lowSensitivity_encodesLowByte() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x01, 0x00, 0x0A, 0x01, 0x02),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 10,
                type = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
                noiseAdaptive = true,
                noiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.LOW,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_noiseAdaptivePreserved_whenModeSwitchesToNoiseCancelling() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x19, 0x01, 0x01, 0x00, 0x00, 0x0A, 0x01, 0x00),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.NOISE_CANCELLING,
                ambientLevel = 10,
                type = NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
                noiseAdaptive = true,
            ),
        )
    }

    @Test
    fun parser_naNotify_readsNoiseAdaptiveToggleAndSensitivity() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x19, 0x01, 0x01, 0x01, 0x00, 0x0A, 0x01, 0x01)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(true, parsed.noiseAdaptiveEnabled)
        assertEquals(NoiseAdaptiveSensitivity.HIGH, parsed.noiseAdaptiveSensitivity)
    }

    @Test
    fun parser_naRetParam_readsNoiseAdaptiveOffStandard() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x67, 0x19, 0x01, 0x01, 0x01, 0x00, 0x0A, 0x00, 0x00)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(false, parsed.noiseAdaptiveEnabled)
        assertEquals(NoiseAdaptiveSensitivity.STANDARD, parsed.noiseAdaptiveSensitivity)
    }

    @Test
    fun parser_nonNaType_leavesNoiseAdaptiveNull() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x17, 0x01, 0x01, 0x01, 0x00, 0x0A)
        ) as ParsedTandemResponse.NoiseControl

        assertEquals(null, parsed.noiseAdaptiveEnabled)
        assertEquals(null, parsed.noiseAdaptiveSensitivity)
    }

    // ── NC_AMB_TOGGLE (rf0/j) — 3-byte frame, no ValueChangeStatus/totalEffect ──
    // Function codes: NC_ASM_OFF=0x01 / NC_ASM=0x02 / NC_OFF=0x03 / ASM_OFF=0x04.
    @Test
    fun ncAsmSetParam_ncAmbToggle_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x30, 0x01),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.OFF,
                type = NcAsmInquiredType.NC_AMB_TOGGLE,
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x30, 0x03),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                type = NcAsmInquiredType.NC_AMB_TOGGLE,
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x30, 0x04),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.NOISE_CANCELLING,
                type = NcAsmInquiredType.NC_AMB_TOGGLE,
            ),
        )
    }

    // ── 0x01 NC_ON_OFF (rf0/n) — [NcAsmOnOffValue] ──
    @Test
    fun ncAsmSetParam_ncOnOff_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x01, 0x01, 0x01, 0x01),
            SonyTandemV2Table1Protocol.buildSetNcOnOff(enabled = true),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x01, 0x01, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetNcOnOff(enabled = false),
        )
    }

    // ── 0x13 NC_ON_OFF_AND_ASM_SEAMLESS (rf0/p) — [NcAsmOnOffValue][AmbientSoundMode][level] ──
    @Test
    fun ncAsmSetParam_ncOnOffAndAsmSeamless_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x13, 0x01, 0x01, 0x00, 0x00, 0x0C),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 12,
                type = NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS,
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x13, 0x01, 0x01, 0x01, 0x00, 0x0A),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.NOISE_CANCELLING,
                type = NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS,
            ),
        )
    }

    // ── 0x21 ASM_ON_OFF (rf0/d) — [AmbientSoundMode][NcAsmOnOffValue] ──
    @Test
    fun ncAsmSetParam_asmOnOff_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x21, 0x01, 0x01, 0x00, 0x01),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                type = NcAsmInquiredType.ASM_ON_OFF,
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x21, 0x01, 0x00, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.OFF,
                type = NcAsmInquiredType.ASM_ON_OFF,
            ),
        )
    }

    // ── 0x22 ASM_SEAMLESS (rf0/e) — [AmbientSoundMode][level], raw 1-20 ──
    @Test
    fun ncAsmSetParam_asmSeamless_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x22, 0x01, 0x01, 0x00, 0x0C),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 12,
                type = NcAsmInquiredType.ASM_SEAMLESS,
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x22, 0x01, 0x00, 0x00, 0x0A),
            SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.OFF,
                type = NcAsmInquiredType.ASM_SEAMLESS,
            ),
        )
    }

    // buildSetAmbientLevel uses the same ASM_SEAMLESS writer as the dispatcher.
    @Test
    fun ncAsmSetParam_ambientLevel_buildsAsmSeamlessFrame() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x22, 0x01, 0x01, 0x00, 0x0C),
            SonyTandemV2Table1Protocol.buildSetAmbientLevel(12),
        )
    }

    // ── EQEBB SET_PARAM (hf0/c PRESET_EQ, hf0/d PRESET_EQ_AND_ULT_MODE) ──
    // Wire frame carries the InquiredType byte before the payload that hf0
    // validates: PRESET_EQ payload [preset][count][steps], len==count+2;
    // ULT payload [preset][ultMode][count][steps], len==count+3.
    @Test
    fun eqEbbSetParam_presetWithBandSteps_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x00, 0x16, 0x02, 0x05, 0x0A),
            SonyTandemV2Table1Protocol.buildSetEqPreset(
                EqPresetId.BASS,
                bandSteps = listOf(5, 10),
            ),
        )
    }

    @Test
    fun eqEbbSetParam_ultMode_insertsUltByte() {
        // EqUltModeStatus.OFF = 0x00; engine never writes ULT modes.
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x03, 0x16, 0x00, 0x02, 0x01, 0x02),
            SonyTandemV2Table1Protocol.buildSetEqPreset(
                EqPresetId.BASS,
                type = EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE,
                bandSteps = listOf(1, 2),
            ),
        )
    }

    @Test
    fun ncAsmGetParam_tableSet1_matchesOfficialXm4Capture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x66, 0x02),
            SonyTandemV1Table1Protocol.buildGetNcAsmParam(),
        )
    }

    @Test
    fun ncAsmSetParam_tableSet1Ambient_matchesOfficialXm4Capture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x02, 0x11, 0x02, 0x00, 0x01, 0x00, 0x14),
            SonyTandemV1Table1Protocol.buildSetNoiseControlMode(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 20,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_tableSet1NcDual_matchesOfficialXm4Capture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x02, 0x11, 0x02, 0x02, 0x01, 0x00, 0x00),
            SonyTandemV1Table1Protocol.buildSetNoiseControlMode(NoiseControlMode.NOISE_CANCELLING),
        )
    }

    @Test
    fun ncAsmSetParam_tableSet1Off_matchesOfficialXm4Capture() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x02, 0x00, 0x02, 0x00, 0x01, 0x00, 0x00),
            SonyTandemV1Table1Protocol.buildSetNoiseControlMode(NoiseControlMode.OFF),
        )
    }

    @Test
    fun playSetStatus_play_matchesReverseDefaultCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xA4.toByte(), 0x01, 0x00, 0x07),
            SonyTandemV2Table1Protocol.buildPlayback(PlaybackControl.PLAY),
        )
    }

    @Test
    fun playSetStatus_play_supportsFunctionChangeType() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xA4.toByte(), 0x03, 0x00, 0x07),
            SonyTandemV2Table1Protocol.buildPlayback(
                PlaybackControl.PLAY,
                PlayInquiredType.PLAYBACK_CONTROL_WITH_FUNCTION_CHANGE,
            ),
        )
    }

    @Test
    fun playGetStatus_matchesReverseCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xA2.toByte(), 0x01),
            SonyTandemV2Table1Protocol.buildGetPlaybackStatus(),
        )
    }

    @Test
    fun v1PlaySetStatus_play_matchesReverseCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xA4.toByte(), 0x01, 0x00, 0x07),
            SonyTandemV1Table1Protocol.buildPlayback(PlaybackControl.PLAY),
        )
    }

    @Test
    fun v1PlayGetStatus_matchesReverseCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xA2.toByte(), 0x01),
            SonyTandemV1Table1Protocol.buildGetPlaybackStatus(),
        )
    }

    @Test
    fun eqEbbSetParam_bassPreset_matchesReverseCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x00, 0x16, 0x00),
            SonyTandemV2Table1Protocol.buildSetEqPreset(EqPresetId.BASS),
        )
    }

    @Test
    fun eqEbbSetParam_clearBassPositive_matchesReverseCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x01, 0x03),
            SonyTandemV2Table1Protocol.buildSetClearBass(3),
        )
    }

    @Test
    fun eqEbbSetParam_clearBassNegative_matchesReverseCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x01, 0xFE.toByte()),
            SonyTandemV2Table1Protocol.buildSetClearBass(-2),
        )
    }

    @Test
    fun v1EqEbbTypeCode_rejectsUnsupportedV2Types() {
        assertThrows(IllegalArgumentException::class.java) {
            SonyTandemV1Table1Protocol.v1TypeCode(EqEbbInquiredType.CUSTOM_EQ)
        }
    }

    @Test
    fun parser_unknownCommand_keepsRawPayload() {
        val raw = byteArrayOf(0x0E, 0x7F, 0x10, 0x20)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Unknown)
        parsed as ParsedTandemResponse.Unknown
        assertEquals(0x7F, parsed.command)
        assertArrayEquals(byteArrayOf(0x10, 0x20), parsed.payload)
        assertArrayEquals(raw, parsed.raw)
    }

    @Test
    fun parser_deviceInfoResponse_extractsText() {
        val model = "WF-1000XM5".encodeToByteArray()
        val raw = byteArrayOf(0x0E, 0x05, 0x01, model.size.toByte()) + model
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.DeviceInfo)
        parsed as ParsedTandemResponse.DeviceInfo
        assertEquals(DeviceInfoType.MODEL_NAME, parsed.type)
        assertEquals("WF-1000XM5", parsed.text)
        assertEquals(null, parsed.colorCode)
    }

    @Test
    fun parser_seriesAndColorResponse_extractsEnumLabels() {
        val raw = byteArrayOf(0x0E, 0x05, 0x03, 0x60, 0x01)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.DeviceInfo)
        parsed as ParsedTandemResponse.DeviceInfo
        assertEquals(DeviceInfoType.SERIES_AND_COLOR_INFO, parsed.type)
        assertEquals("LINK_BUDS / Black", parsed.text)
        assertEquals(0x01, parsed.colorCode)
    }

    @Test
    fun parser_leftRightBatteryResponse_extractsValues() {
        val raw = byteArrayOf(0x0E, 0x23, 0x01, 80.toByte(), 0x01, 70.toByte(), 0x01)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.LEFT_RIGHT_BATTERY, parsed.kind)
        assertEquals(listOf(80, 70), parsed.values)
    }

    @Test
    fun parser_singleBatteryResponse_extractsHeadsetValue() {
        val raw = byteArrayOf(0x0E, 0x23, 0x00, 60.toByte(), 0x01)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(60), parsed.values)
    }

    @Test
    fun parser_v1SingleBatteryResponse_extractsHeadsetValue() {
        val raw = byteArrayOf(0x0E, 0x11, 0x00, 88.toByte(), 0x00)
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(88), parsed.values)
    }

    @Test
    fun parser_v1DeviceInfoResponse_extractsFirmwareVersion() {
        val version = "2.5.1".encodeToByteArray()
        val raw = byteArrayOf(0x0E, 0x05, 0x02, version.size.toByte()) + version
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.DeviceInfo)
        parsed as ParsedTandemResponse.DeviceInfo
        assertEquals(DeviceInfoType.FW_VERSION, parsed.type)
        assertEquals("2.5.1", parsed.text)
        assertEquals(null, parsed.colorCode)
    }

    @Test
    fun parser_v1PlaybackResponse_extractsStatus() {
        val raw = byteArrayOf(0x0E, 0xA3.toByte(), 0x01, 0x00, 0x03)
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.PlaybackAck)
        parsed as ParsedTandemResponse.PlaybackAck
        assertEquals(listOf(1, 0, 3), parsed.values)
        assertEquals(PlaybackStatus.STOPPED, parsed.status)
    }

    @Test
    fun parser_shortBatteryResponse_doesNotCrash() {
        val raw = byteArrayOf(0x0E, 0x23, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.BATTERY, parsed.kind)
        assertEquals(listOf(null), parsed.values)
    }

    @Test
    fun parser_batteryNtfy0x09_mapsToLeftRight() {
        // 0x09 extended battery NTFY uses the LEFT_RIGHT 2-byte layout.
        // left = 0x52 0x00 = 82, right = 0x49 0x00 = 73.
        val raw = byteArrayOf(0x0E, 0x25, 0x09, 0x52, 0x00, 0x49, 0x00, 0x64, 0x64)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.LEFT_RIGHT_BATTERY, parsed.kind)
        assertEquals(listOf(82, 73), parsed.values)
    }

    @Test
    fun parser_sppPayloadWithoutDataType_isNormalized() {
        val raw = byteArrayOf(0x23, 0x02, 90.toByte(), 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Battery)
        parsed as ParsedTandemResponse.Battery
        assertEquals(PowerInquiredType.CRADLE_BATTERY, parsed.kind)
        assertEquals(listOf(90), parsed.values)
    }

    @Test
    fun parser_ambientLevelResponse_extractsLevel() {
        // ncAsmEffect 0x01 = on, ncAsmMode 0x01 = ambient (LinkBuds S capture semantics).
        val raw = byteArrayOf(0x0E, 0x67, 0x17, 0x01, 0x01, 0x01, 0x00, 0x0C)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(12, parsed.ambientLevel)
        assertEquals(AmbientSoundMode.NORMAL, parsed.ambientMode)
    }

    @Test
    fun parser_commonDisplayFirmwareVersion_extractsText() {
        val version = "2.5.0".encodeToByteArray()
        val raw = byteArrayOf(0x0E, 0x13, 0x09, version.size.toByte()) + version
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.CommonStatus)
        parsed as ParsedTandemResponse.CommonStatus
        assertEquals(CommonInquiredType.DISPLAY_FW_VERSION, parsed.type)
        assertEquals("2.5.0", parsed.text)
    }

    @Test
    fun parser_asmSeamlessResponse_extractsHeadsetAmbientLevel() {
        // rf0/e: [AmbientSoundMode][level]; totalEffect 0x01 = on.
        val raw = byteArrayOf(0x0E, 0x67, 0x22, 0x01, 0x01, 0x00, 0x0C)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(12, parsed.ambientLevel)
    }

    @Test
    fun parser_ncModeSwitchAsmSeamlessResponse_extractsXm4AmbientLevel() {
        // rf0/l: [NcValue][AmbientSoundMode][level]; totalEffect 0x01 = on.
        val raw = byteArrayOf(0x0E, 0x67, 0x14, 0x01, 0x01, 0x00, 0x00, 0x0C)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(12, parsed.ambientLevel)
        assertEquals(AmbientSoundMode.NORMAL, parsed.ambientMode)
    }

    @Test
    fun parser_ncModeSwitchAsmSeamlessResponse_extractsXm4NcDual() {
        // rf0/l: NcValue 0x02 = ON_DUAL => NC; totalEffect 0x01 = on.
        val raw = byteArrayOf(0x0E, 0x67, 0x14, 0x01, 0x01, 0x02, 0x00, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.NOISE_CANCELLING, parsed.controlMode)
    }

    @Test
    fun parser_ncModeSwitchAsmSeamlessOff_parsesDeviceCapture() {
        // Ground truth: btsnoop `0E 67 14 01 00 00 00 0B` — totalEffect OFF
        // (0x00) => controlMode OFF; level raw 0x0B = 11.
        val raw = byteArrayOf(0x0E, 0x67, 0x14, 0x01, 0x00, 0x00, 0x00, 0x0B)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.OFF, parsed.controlMode)
        assertEquals(11, parsed.ambientLevel)
        assertEquals(AmbientSoundMode.NORMAL, parsed.ambientMode)
    }

    @Test
    fun parser_ncOnOffAndAsmOnOffResponse_extractsNc() {
        val raw = byteArrayOf(0x0E, 0x67, 0x02, 0x01, 0x01, 0x01, 0x00, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.NC_ON_OFF_AND_ASM_ON_OFF, parsed.type)
        assertEquals(NoiseControlMode.NOISE_CANCELLING, parsed.controlMode)
        assertEquals(null, parsed.ambientLevel)
        assertEquals(AmbientSoundMode.NORMAL, parsed.ambientMode)
    }

    @Test
    fun parser_ncOnOffAndAsmOnOffNotification_extractsAmbient() {
        val raw = byteArrayOf(0x0E, 0x69, 0x02, 0x01, 0x01, 0x00, 0x01, 0x01)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.NC_ON_OFF_AND_ASM_ON_OFF, parsed.type)
        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(null, parsed.ambientLevel)
        assertEquals(AmbientSoundMode.VOICE, parsed.ambientMode)
    }

    @Test
    fun parser_v1UnknownNcAsmNotification_preservesNotificationCommand() {
        val raw = byteArrayOf(0x0E, 0x69, 0x7F, 0x01)
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Unknown)
        parsed as ParsedTandemResponse.Unknown
        assertEquals(0x0E, parsed.dataType)
        assertEquals(0x69, parsed.command)
        assertArrayEquals(byteArrayOf(0x7F, 0x01), parsed.payload)
    }

    @Test
    fun parser_ncasmStatus_doesNotTreatFeatureEnableAsCurrentMode() {
        val raw = byteArrayOf(0x0E, 0x63, 0x01, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.NC_ON_OFF, parsed.type)
        assertEquals(null, parsed.controlMode)
        assertEquals(null, parsed.enabled)
    }

    @Test
    fun parser_playbackNotification_extractsStatus() {
        val raw = byteArrayOf(0x0E, 0xA5.toByte(), 0x01, 0x00, 0x02, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.PlaybackAck)
        parsed as ParsedTandemResponse.PlaybackAck
        assertEquals(PlaybackStatus.PAUSED, parsed.status)
    }

    @Test
    fun parser_eqPresetParam_extractsPreset() {
        val raw = byteArrayOf(0x0E, 0x57, 0x00, 0x16, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.EqEbb)
        parsed as ParsedTandemResponse.EqEbb
        assertEquals(EqEbbInquiredType.PRESET_EQ, parsed.type)
        assertEquals(EqPresetId.BASS, parsed.preset)
        assertEquals(emptyList<Int>(), parsed.bandSteps)
    }

    @Test
    fun parser_clearBassParam_extractsSignedValue() {
        val raw = byteArrayOf(0x0E, 0x59, 0x01, 0xFE.toByte())
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.EqEbb)
        parsed as ParsedTandemResponse.EqEbb
        assertEquals(EqEbbInquiredType.EBB, parsed.type)
        assertEquals(-2, parsed.clearBass)
    }

    @Test
    fun parser_v2EbbCombinedEqParam_extractsPresetAndBands() {
        val raw = byteArrayOf(0x0E, 0x57, 0x01, 0x16, 0x06, 0x11, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.EqEbb)
        parsed as ParsedTandemResponse.EqEbb
        assertEquals(EqEbbInquiredType.EBB, parsed.type)
        assertEquals(EqPresetId.BASS, parsed.preset)
        assertEquals(17, parsed.clearBass)
        assertEquals(listOf(17, 10, 10, 10, 10, 10), parsed.bandSteps)
    }

    @Test
    fun parser_v1Xm4PresetEqParam_extractsPresetAndBands() {
        val raw = byteArrayOf(0x0E, 0x57, 0x01, 0xA2.toByte(), 0x06, 0x0A, 0x0A, 0x0A, 0x0A, 0x08, 0x11)
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.EqEbb)
        parsed as ParsedTandemResponse.EqEbb
        assertEquals(EqEbbInquiredType.PRESET_EQ, parsed.type)
        assertEquals(EqPresetId.USER_SETTING2, parsed.preset)
        assertEquals(listOf(10, 10, 10, 10, 8, 17), parsed.bandSteps)
    }

    @Test
    fun parser_v1Xm4EqExtendedInfo_extractsBandMetadata() {
        val raw = byteArrayOf(
            0x0E,
            0x5B,
            0x01,
            0x06,
            0x10,
            0x00,
            0x01,
            0x01,
            0x01,
            0x90.toByte(),
            0x01,
            0x03,
            0xE8.toByte(),
            0x01,
            0x09,
            0xC4.toByte(),
            0x01,
            0x18,
            0x9C.toByte(),
            0x01,
            0x3D,
            0x2E,
        )
        val parsed = SonyTandemV1Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.EqEbbExtendedInfo)
        parsed as ParsedTandemResponse.EqEbbExtendedInfo
        assertEquals(EqEbbInquiredType.PRESET_EQ, parsed.type)
        assertEquals(6, parsed.bands.size)
        assertEquals(EqBandInformationType.SPECIFIC_INFORMATION, parsed.bands[0].type)
        assertEquals(1, parsed.bands[0].value)
        assertEquals(EqBandInformationType.HZ, parsed.bands[1].type)
        assertEquals(400, parsed.bands[1].value)
        assertEquals(15_662, parsed.bands[5].value)
    }

    @Test
    fun parser_customEqParam_extractsBandStepsWithoutPreset() {
        val raw = byteArrayOf(0x0E, 0x57, 0x31, 0x03, 0x09, 0x0A, 0x0B)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.EqEbb)
        parsed as ParsedTandemResponse.EqEbb
        assertEquals(EqEbbInquiredType.CUSTOM_EQ, parsed.type)
        assertEquals(null, parsed.preset)
        assertEquals(listOf(9, 10, 11), parsed.bandSteps)
    }

    // ── LEA ────────────────────────────────────────────────────────────────

    @Test
    fun leaGetStatus_matchesTandemV2Shape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x42, 0x00),
            SonyTandemV2Table1Protocol.buildGetLeaStatus(LeaInquiredType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD),
        )
    }

    @Test
    fun leaGetPairedHistory_matchesTandemV2Shape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x46, 0x01),
            SonyTandemV2Table1Protocol.buildGetLeaPairedHistory(LeaInquiredType.HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD),
        )
    }

    @Test
    fun leaGetPersistentSetting_matchesSoundConnectInitialization() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x42, 0x0C),
            SonyTandemV2Table1Protocol.buildGetLeAudioSettingAvailability(),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x46, 0x0C),
            SonyTandemV2Table1Protocol.buildGetLeAudioSetting(),
        )
    }

    @Test
    fun leaPersistentSettingReplies_areNotParsedAsPairedHistory() {
        val availability = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x43, 0x0C, 0x00),
        ) as ParsedTandemResponse.LeaSettingAvailability
        assertTrue(availability.available)
        assertFalse(availability.isNotification)

        val setting = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x47, 0x0C, 0x01),
        ) as ParsedTandemResponse.LeaParameterNotification
        assertEquals(0x0C, setting.setting)
        assertEquals(LeaEnableDisable.DISABLE, setting.enabled)

        val notification = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x49, 0x0C, 0x00),
        ) as ParsedTandemResponse.LeaParameterNotification
        assertEquals(LeaEnableDisable.ENABLE, notification.enabled)
    }

    @Test
    fun leaSetEnabledAndChangeConnection_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x48, 0x0C, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetLeAudioEnabled(true, true),
        )
    }

    @Test
    fun leaSetEnabledOnly_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x48, 0x0C, 0x00, 0x01),
            SonyTandemV2Table1Protocol.buildSetLeAudioEnabled(true, false),
        )
    }

    @Test
    fun leaSetDisabled_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x48, 0x0C, 0x01, 0x01),
            SonyTandemV2Table1Protocol.buildSetLeAudioEnabled(false, false),
        )
    }

    @Test
    fun leaSetDisabledAndChangeConnection_matchesOfficialLayout() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x48, 0x0C, 0x01, 0x00),
            SonyTandemV2Table1Protocol.buildSetLeAudioEnabled(false, true),
        )
    }

    @Test
    fun parser_leaRetStatus_HBS_extractsEnableAndStreaming() {
        val raw = byteArrayOf(0x0E, 0x43, 0x01, 0x00, 0x02)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.LeaStatus)
        parsed as ParsedTandemResponse.LeaStatus
        assertEquals(LeaInquiredType.HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD, parsed.type)
        assertEquals(LeaEnableDisable.ENABLE, parsed.enabled)
        assertEquals(LeaStreamingStatus.VIA_A2DP, parsed.streamingStatusL)
        assertEquals(null, parsed.streamingStatusR)
    }

    @Test
    fun parser_leaRetStatus_TWS_extractsEnableAndDualStreaming() {
        val raw = byteArrayOf(0x0E, 0x43, 0x00, 0x00, 0x02, 0x03)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.LeaStatus)
        parsed as ParsedTandemResponse.LeaStatus
        assertEquals(LeaInquiredType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD, parsed.type)
        assertEquals(LeaEnableDisable.ENABLE, parsed.enabled)
        assertEquals(LeaStreamingStatus.VIA_A2DP, parsed.streamingStatusL)
        assertEquals(LeaStreamingStatus.VIA_LE_AUDIO_UNICAST, parsed.streamingStatusR)
    }

    @Test
    fun parser_leaNtfyStatus_TWS_sameAsRet() {
        val raw = byteArrayOf(0x0E, 0x45, 0x00, 0x01, 0x01, 0x02)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.LeaStatus)
        parsed as ParsedTandemResponse.LeaStatus
        assertEquals(LeaInquiredType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD, parsed.type)
        assertEquals(LeaEnableDisable.DISABLE, parsed.enabled)
        assertEquals(LeaStreamingStatus.NONE, parsed.streamingStatusL)
        assertEquals(LeaStreamingStatus.VIA_A2DP, parsed.streamingStatusR)
    }

    @Test
    fun parser_unknownLeaPayload_doesNotCrash() {
        val raw = byteArrayOf(0x0E, 0x43, 0x7F)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.LeaStatus)
        parsed as ParsedTandemResponse.LeaStatus
        assertEquals(null, parsed.type)
        assertEquals(null, parsed.enabled)
        assertEquals(null, parsed.streamingStatusL)
        assertEquals(null, parsed.streamingStatusR)
    }

    @Test
    fun parser_leaRetParam_extractsPairedHistory() {
        val raw = byteArrayOf(0x0E, 0x47, 0x00, 0x01)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.LeaPairedHistoryStatus)
        parsed as ParsedTandemResponse.LeaPairedHistoryStatus
        assertEquals(LeaInquiredType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD, parsed.type)
        assertEquals(LeaPairedHistory.ONLY_CLASSIC_BT, parsed.pairedHistory)
    }

    @Test
    fun parser_leaNtfyParam_extractsEnableDisableChange() {
        val raw = byteArrayOf(0x0E, 0x49, 0x0C, 0x01)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.LeaParameterNotification)
        parsed as ParsedTandemResponse.LeaParameterNotification
        assertEquals(0x0C, parsed.setting)
        assertEquals(LeaEnableDisable.DISABLE, parsed.enabled)
        assertEquals(listOf(0x0C, 0x01), parsed.values)
    }

    // ── Quick Access ───────────────────────────────────────────────────────

    @Test
    fun quickAccessGetParam_matchesTandemV2Shape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF6.toByte(), 0x0D),
            SonyTandemV2Table1Protocol.buildGetQuickAccess(),
        )
    }

    @Test
    fun parser_quickAccessRetParam_extractsFunctionList() {
        // QUICK_ACCESS RET_PARAM is [systemType][count][serviceId...].
        // The key belongs to the capability response, not the current value.
        val raw = byteArrayOf(0x0E, 0xF7.toByte(), 0x0D, 0x01, 0x02)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.QuickAccess)
        parsed as ParsedTandemResponse.QuickAccess
        assertEquals(null, parsed.key)
        assertEquals(listOf(QuickAccessFunction.ENDEL), parsed.functions)
        assertEquals(listOf(0x02), parsed.functionCodes)
    }

    @Test
    fun parser_quickAccessRetParam_mapsCurrentSoundConnectServices() {
        val serviceCodes = byteArrayOf(
            0x06, // KuGou Music (formerly used by the removed Audible service)
            0x08, // Eye Navi
            0x09, // NetEase Cloud Music
            0x0A, // Apple Music
            0x0C, // YouTube Music
        )
        val raw = byteArrayOf(0x0E, 0xF7.toByte(), 0x0D, serviceCodes.size.toByte()) + serviceCodes
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.QuickAccess)
        parsed as ParsedTandemResponse.QuickAccess
        assertEquals(
            listOf(
                QuickAccessFunction.KUGOU_MUSIC,
                QuickAccessFunction.EYE_NAVI,
                QuickAccessFunction.NETEASE_CLOUD_MUSIC,
                QuickAccessFunction.APPLE_MUSIC,
                QuickAccessFunction.YOUTUBE_MUSIC,
            ),
            parsed.functions,
        )
        assertEquals(serviceCodes.map { it.toInt() and 0xFF }, parsed.functionCodes)
    }

    // ── Wearing Detection ──────────────────────────────────────────────────

    @Test
    fun wearingGetStatus_matchesTandemV2Shape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF6.toByte(), 0x06),
            SonyTandemV2Table1Protocol.buildGetWearingStatus(),
        )
    }

    @Test
    fun parser_wearingStatusRet_extractsStatusAndResult() {
        val raw = byteArrayOf(0x0E, 0xF7.toByte(), 0x06, 0x02, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.WearingStatus)
        parsed as ParsedTandemResponse.WearingStatus
        assertEquals(WearingDetectionStatus.COMPLETED_SUCCESSFULLY, parsed.status)
        assertEquals(WearingDetectionResult.GOOD, parsed.result)
    }

    @Test
    fun parser_wearingStatusNtfy_extractsStatusAndResult() {
        val raw = byteArrayOf(0x0E, 0xF9.toByte(), 0x06, 0x01, 0x01)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.WearingStatus)
        parsed as ParsedTandemResponse.WearingStatus
        assertEquals(WearingDetectionStatus.STARTED, parsed.status)
        assertEquals(WearingDetectionResult.POOR, parsed.result)
    }

    @Test
    fun parser_unknownSystemParam_doesNotCrash() {
        val raw = byteArrayOf(0x0E, 0xF7.toByte(), 0x7F)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.Unknown)
    }

    // ── General Setting / multipoint toggle ────────────────────────────────

    @Test
    fun generalSettingMultipoint_roundTripsBooleanParamAndAlertReply() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xD8.toByte(), 0xD2.toByte(), 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetGeneralSetting(0xD2.toByte(), on = true),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xD8.toByte(), 0xD2.toByte(), 0x00, 0x01),
            SonyTandemV2Table1Protocol.buildSetGeneralSetting(0xD2.toByte(), on = false),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x98.toByte(), 0x00, 0x07, 0x01),
            SonyTandemV2Table1Protocol.buildReplyAlertFixingMessage(7, positive = true),
        )

        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xD7.toByte(), 0xD2.toByte(), 0x00, 0x00),
        ) as ParsedTandemResponse.GeneralSettingParam
        assertEquals(0xD2, parsed.type)
        assertTrue(parsed.on == true)
    }

    @Test
    fun generalSettingCapability_findsMultipointTitleAndAlertNotificationParses() {
        val title = SonyTandemV2Table1Protocol.GS_TITLE_MULTIPOINT_SETTING
        val rawCapability = byteArrayOf(
            0x0E, 0xD1.toByte(), 0xD2.toByte(), 0x00, 0x01, title.length.toByte(),
        ) + title.encodeToByteArray() + byteArrayOf(0x00)
        val capability = SonyTandemV2Table1Protocol.parse(rawCapability) as ParsedTandemResponse.GeneralSettingCapability
        assertEquals(0xD2, capability.type)
        assertEquals(title, capability.title)
        assertEquals(SonyTandemV2Table1Protocol.GS_STRING_FORMAT_ENUM_NAME.toInt(), capability.stringFormat)

        val alert = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x99.toByte(), 0x00, 0x07, 0x00),
        ) as ParsedTandemResponse.AlertFixedMessage
        assertEquals(7, alert.messageType)
        assertEquals(0, alert.actionType)
    }

    @Test
    fun flexibleLeAudioAlert_preservesItemsAndBuildsMatchingReply() {
        val raw = byteArrayOf(
            0x0E,
            0x99.toByte(),
            0x06,
            SonyTandemV2Table1Protocol.FLEXIBLE_CHANGE_CONNECTION_WITH_LE_AUDIO_LIMITATION.toByte(),
            0x04,
            0x06,
            0x0B,
            0x17,
            0x15,
            0x00,
        )

        val parsed = SonyTandemV2Table1Protocol.parse(raw) as ParsedTandemResponse.AlertFlexibleMessage
        assertEquals(SonyTandemV2Table1Protocol.FLEXIBLE_CHANGE_CONNECTION_WITH_LE_AUDIO_LIMITATION, parsed.messageType)
        assertEquals(listOf(6, 11, 23, 21), parsed.itemCodes)
        assertEquals(0, parsed.actionType)
        assertArrayEquals(raw, parsed.raw)
        assertArrayEquals(
            byteArrayOf(0x0E, 0x98.toByte(), 0x06, 0x0D, 0x01),
            SonyTandemV2Table1Protocol.buildReplyAlertFlexibleMessage(parsed.messageType, positive = true),
        )
    }

    @Test
    fun alertReplies_echoTheOfficialInquiredType() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x98.toByte(), 0x00, 0x31, 0x00),
            SonyTandemV2Table1Protocol.buildReplyAlertFixingMessage(49, positive = false),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x98.toByte(), 0x04, 0x31, 0x01),
            SonyTandemV2Table1Protocol.buildReplyAlertForegroundMessage(49, positive = true),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x98.toByte(), 0x02, 0x31, 0x01),
            SonyTandemV2Table1Protocol.buildReplyAlertFixedMessageWithLeftRightSelection(49, positive = true),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0x98.toByte(), 0x02, 0x31, 0x02),
            SonyTandemV2Table1Protocol.buildReplyAlertFixedMessageWithLeftRightSelection(49, action = 2),
        )
    }

    /**
     * PLAY_MODE (inquired type 0x40) shares the STATUS layout with the playback types: its
     * enable byte sits at [1] and its mode at [2], where the mode codes overlap
     * PLAYING/PAUSED/STOPPED. Reading it as playback would grey out the playback card and
     * report a bogus transport state, so both fields must stay unset.
     */
    @Test
    fun parser_playModeStatus_isNotReadAsPlaybackState() {
        listOf<Byte>(0xA3.toByte(), 0xA5.toByte()).forEach { command ->
            val parsed = SonyTandemV2Table1Protocol.parse(
                byteArrayOf(0x0E, command, 0x40, 0x00, 0x01)
            )

            assertTrue(parsed is ParsedTandemResponse.PlaybackAck)
            parsed as ParsedTandemResponse.PlaybackAck
            assertEquals(PlaybackStatus.UNKNOWN, parsed.status)
            assertEquals(null, parsed.enabled)
        }
    }

    @Test
    fun parser_v1PlayModeStatus_isNotReadAsPlaybackState() {
        listOf<Byte>(0xA3.toByte(), 0xA5.toByte()).forEach { command ->
            val parsed = SonyTandemV1Table1Protocol.parse(
                byteArrayOf(0x0E, command, 0x40, 0x00, 0x01)
            )

            assertTrue(parsed is ParsedTandemResponse.PlaybackAck)
            parsed as ParsedTandemResponse.PlaybackAck
            assertEquals(PlaybackStatus.UNKNOWN, parsed.status)
            assertEquals(null, parsed.enabled)
        }
    }

    /** The guard must not swallow the real playback types: 0x00 at [1] means ENABLE. */
    @Test
    fun parser_playbackStatus_stillReportsEnableForPlaybackTypes() {
        listOf<Byte>(0x01, 0x02, 0x03).forEach { inquiredType ->
            val v2 = SonyTandemV2Table1Protocol.parse(
                byteArrayOf(0x0E, 0xA5.toByte(), inquiredType, 0x00, 0x01)
            ) as ParsedTandemResponse.PlaybackAck
            assertEquals(PlaybackStatus.PLAYING, v2.status)
            assertEquals(true, v2.enabled)

            val v1 = SonyTandemV1Table1Protocol.parse(
                byteArrayOf(0x0E, 0xA3.toByte(), inquiredType, 0x01, 0x02)
            ) as ParsedTandemResponse.PlaybackAck
            assertEquals(PlaybackStatus.PAUSED, v1.status)
            assertEquals(false, v1.enabled)
        }
    }

    /** LinkBuds S 20:49:26 capture: connection-time answer while AVRCP has not
     * delivered track info yet — all four name slots are NOTHING, never UNSETTLED. */
    @Test
    fun playParam_metadata_emptyConnectionTimeCapture_isNothingNotUnsettled() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xA7.toByte(), 0x01, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00)
        ) as ParsedTandemResponse.PlaybackMetadata

        assertFalse(parsed.isUnsolicited)
        listOf(parsed.track, parsed.album, parsed.artist, parsed.genre).forEach { name ->
            assertEquals(PlaybackNameStatus.NOTHING, name.status)
            assertEquals("", name.text)
        }
    }

    /** LinkBuds S 21:15:25 capture: spontaneous NTFY carrying a settled track and
     * empty artist/album/genre — this is how LC3 sessions learn the track without
     * any re-query once AVRCP delivers it. */
    @Test
    fun playParam_metadata_ntfySettledTitle_parsesFromDeviceCapture() {
        val raw = byteArrayOf(0x0E, 0xA9.toByte(), 0x01, 0x02, 0x06) +
            byteArrayOf(0xE7.toByte(), 0xA7.toByte(), 0xAF.toByte()) +
            byteArrayOf(0xE9.toByte(), 0x9B.toByte(), 0xAA.toByte()) +
            byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x01, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw) as ParsedTandemResponse.PlaybackMetadata

        assertTrue(parsed.isUnsolicited)
        assertEquals(PlaybackNameStatus.SETTLED, parsed.track.status)
        assertTrue(parsed.track.text.isNotEmpty())
        listOf(parsed.album, parsed.artist, parsed.genre).forEach { name ->
            assertEquals(PlaybackNameStatus.NOTHING, name.status)
        }
    }

    /** AUDIO-domain DSEE toggle: RET carries AUTO(0x01), NTFY carries OFF(0x00);
     * an out-of-range value byte rejects the whole frame like SC's factory does. */
    @Test
    fun audioParam_upscaling_retNtfy_andOutOfRangeValue() {
        val ret = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xE7.toByte(), 0x01, 0x01)
        ) as ParsedTandemResponse.Upscaling
        assertFalse(ret.isUnsolicited)
        assertEquals(0x01, ret.inquiredTypeCode)
        assertTrue(ret.enabled)

        val ntfy = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xE9.toByte(), 0x0B, 0x00)
        ) as ParsedTandemResponse.Upscaling
        assertTrue(ntfy.isUnsolicited)
        assertEquals(0x0B, ntfy.inquiredTypeCode)
        assertFalse(ntfy.enabled)

        assertTrue(
            SonyTandemV2Table1Protocol.parse(
                byteArrayOf(0x0E, 0xE7.toByte(), 0x01, 0x7F)
            ) is ParsedTandemResponse.Unknown
        )
    }

    /** AUDIO_RET_CAPABILITY (0xE1) carries the DSEE generation byte (`cf0.e0`);
     * foreign inquired types and out-of-range generations reject the frame. */
    @Test
    fun audioCapability_upscalingType_strictValidation() {
        val ret = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xE1.toByte(), 0x01, 0x02)
        ) as ParsedTandemResponse.UpscalingCapability
        assertEquals(0x01, ret.inquiredTypeCode)
        // DSEE_HX_AI — the generation Sound Connect titles "DSEE Extreme".
        assertEquals(0x02, ret.upscalingTypeCode)

        assertTrue(
            SonyTandemV2Table1Protocol.parse(
                byteArrayOf(0x0E, 0xE1.toByte(), 0x01, 0x04)
            ) is ParsedTandemResponse.Unknown
        )
        assertTrue(
            SonyTandemV2Table1Protocol.parse(
                byteArrayOf(0x0E, 0xE1.toByte(), 0x02, 0x02)
            ) is ParsedTandemResponse.Unknown
        )
    }

    /** V2 codec badge: RET `0E 13 02 10` — LDAC active (SC `ef0.o`, len==3). */
    @Test
    fun commonStatus_audioCodec_ret_ldac_parsesFromWire() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x13, 0x02, 0x10)
        ) as ParsedTandemResponse.AudioCodecStatus

        assertFalse(parsed.isUnsolicited)
        assertEquals(SoundQualityCodec.LDAC, parsed.codec)
    }

    /** V2 codec badge: unsolicited NTFY `0E 15 02 30` — LC3 under LE Audio. */
    @Test
    fun commonStatus_audioCodec_ntfy_lc3_isUnsolicitedPush() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x15, 0x02, 0x30)
        ) as ParsedTandemResponse.AudioCodecStatus

        assertTrue(parsed.isUnsolicited)
        assertEquals(SoundQualityCodec.LC3, parsed.codec)
    }

    /** Wrong length or an out-of-table codec byte drops the frame whole. */
    @Test
    fun commonStatus_audioCodec_malformed_isUnknown() {
        assertTrue(
            SonyTandemV2Table1Protocol.parse(
                byteArrayOf(0x0E, 0x13, 0x02, 0x10, 0x00)
            ) is ParsedTandemResponse.Unknown
        )
        assertTrue(
            SonyTandemV2Table1Protocol.parse(
                byteArrayOf(0x0E, 0x13, 0x02, 0x55)
            ) is ParsedTandemResponse.Unknown
        )
    }

    /** V2 DSEE badge: RET `0E 13 03 03 01` — Ultimate generation, VALID effect. */
    @Test
    fun commonStatus_upscalingEffect_ret_ultimateValid_parses() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x13, 0x03, 0x03, 0x01)
        ) as ParsedTandemResponse.UpscalingEffect

        assertFalse(parsed.isUnsolicited)
        assertEquals(DseeGeneration.DSEE_ULTIMATE, parsed.generation)
        assertEquals(DseeEffectState.VALID, parsed.state)
    }

    /** NTFY push with OFF status still parses; visibility is decided upstream. */
    @Test
    fun commonStatus_upscalingEffect_ntfy_off_keepsGeneration() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x15, 0x03, 0x01, 0x00)
        ) as ParsedTandemResponse.UpscalingEffect

        assertTrue(parsed.isUnsolicited)
        assertEquals(DseeGeneration.DSEE, parsed.generation)
        assertEquals(DseeEffectState.OFF, parsed.state)
    }

    /** Effect frames must be exactly [inq][type][status]; longer is dropped. */
    @Test
    fun commonStatus_upscalingEffect_wrongLength_isUnknown() {
        assertTrue(
            SonyTandemV2Table1Protocol.parse(
                byteArrayOf(0x0E, 0x13, 0x03, 0x03, 0x01, 0x00)
            ) is ParsedTandemResponse.Unknown
        )
    }

    /** AUDIO_RET_PARAM (E7) for CONNECTION_MODE: [inq][PriorMode], three bytes total. */
    @Test
    fun connectionQuality_ret_classicConnectionMode_parsesMode() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xE7.toByte(), 0x00, 0x00)
        ) as ParsedTandemResponse.ConnectionQuality

        assertFalse(parsed.isUnsolicited)
        assertEquals(ConnectionQualityMode.SOUND_QUALITY_PRIOR, parsed.mode)
        assertEquals(0x00, parsed.inquiredTypeCode)
        assertEquals(null, parsed.switchingStreamCode)
    }

    /** LE-era NTFY (E9, inq 0x05) carries a fourth SwitchingStream byte. */
    @Test
    fun connectionQuality_ntfy_leDualWithSwitchingStream_parses() {
        val parsed = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xE9.toByte(), 0x05, 0x01, 0x02)
        ) as ParsedTandemResponse.ConnectionQuality

        assertTrue(parsed.isUnsolicited)
        assertEquals(ConnectionQualityMode.CONNECTION_QUALITY_PRIOR, parsed.mode)
        assertEquals(0x05, parsed.inquiredTypeCode)
        assertEquals(0x02, parsed.switchingStreamCode)
    }

    /** Out-of-range PriorMode values reject the whole frame like SC's factories. */
    @Test
    fun connectionQuality_param_unknownPriorMode_isUnknown() {
        assertTrue(
            SonyTandemV2Table1Protocol.parse(
                byteArrayOf(0x0E, 0xE7.toByte(), 0x00, 0x7F)
            ) is ParsedTandemResponse.Unknown
        )
    }

    /** STATUS (E3 RET / E5 NTFY) carries the EnableDisable availability byte. */
    @Test
    fun connectionQuality_status_disabled_greysOptions() {
        val ret = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xE3.toByte(), 0x00, 0x01)
        ) as ParsedTandemResponse.ConnectionQualityAvailability
        val ntfy = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xE5.toByte(), 0x05, 0x00)
        ) as ParsedTandemResponse.ConnectionQualityAvailability

        assertFalse(ret.enabled)
        assertFalse(ret.isUnsolicited)
        assertTrue(ntfy.enabled)
        assertTrue(ntfy.isUnsolicited)
    }

    @Test
    fun windNoiseReduction_autoMode_buildsAndParsesAccurately() {
        // Build auto wind noise reduction enabled (0x03)
        val cmdAutoOn = SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
            controlMode = NoiseControlMode.NOISE_CANCELLING,
            type = NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            windNoiseReduction = true,
        )
        // [0x0E][0x68][type 0x15][changed 0x01][effect 0x01][mode 0x00][ncValue 0x03][ambientMode 0x00][level 0x0A]
        assertEquals(0x03.toByte(), cmdAutoOn[6])

        // Build standard NC (0x02 ON_DUAL)
        val cmdAutoOff = SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
            controlMode = NoiseControlMode.NOISE_CANCELLING,
            type = NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            windNoiseReduction = false,
        )
        assertEquals(0x02.toByte(), cmdAutoOff[6])

        // Parse notification with AUTO (0x03)
        val ntfyAuto = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x15, 0x01, 0x01, 0x00, 0x03, 0x00, 0x0A)
        ) as ParsedTandemResponse.NoiseControl
        assertEquals(NoiseControlMode.NOISE_CANCELLING, ntfyAuto.controlMode)
        assertTrue(ntfyAuto.windNoiseReduction == true)

        // Parse notification with ON_DUAL (0x02)
        val ntfyDual = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x15, 0x01, 0x01, 0x00, 0x02, 0x00, 0x0A)
        ) as ParsedTandemResponse.NoiseControl
        assertEquals(NoiseControlMode.NOISE_CANCELLING, ntfyDual.controlMode)
        assertFalse(ntfyDual.windNoiseReduction == true)
    }

    @Test
    fun windNoiseReduction_dualSingleMode_buildsAndParsesAccurately() {
        // Build single-mic wind noise reduction enabled (0x01 ON_SINGLE)
        val cmdSingleOn = SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
            controlMode = NoiseControlMode.NOISE_CANCELLING,
            type = NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            windNoiseReduction = true,
        )
        assertEquals(0x01.toByte(), cmdSingleOn[6])

        // Build standard dual-mic NC (0x02 ON_DUAL)
        val cmdSingleOff = SonyTandemV2Table1Protocol.buildSetNoiseControlMode(
            controlMode = NoiseControlMode.NOISE_CANCELLING,
            type = NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            windNoiseReduction = false,
        )
        assertEquals(0x02.toByte(), cmdSingleOff[6])

        // Parse notification with ON_SINGLE (0x01)
        val ntfySingle = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x69, 0x16, 0x01, 0x01, 0x00, 0x01, 0x00, 0x0A)
        ) as ParsedTandemResponse.NoiseControl
        assertEquals(NoiseControlMode.NOISE_CANCELLING, ntfySingle.controlMode)
        assertTrue(ntfySingle.windNoiseReduction == true)
    }

    @Test
    fun speakToChat_buildCommands_matchReverseEngineeredSpec() {
        val enableCmd = SonyTandemV2Table1Protocol.buildSetSpeakToChatEnabled(true, SystemInquiredType.SMART_TALKING_MODE_TYPE1)
        assertArrayEquals(byteArrayOf(0x0E, 0xF8.toByte(), 0x02, 0x00, 0x01), enableCmd)

        val disableCmd = SonyTandemV2Table1Protocol.buildSetSpeakToChatEnabled(false, SystemInquiredType.SMART_TALKING_MODE_TYPE1)
        assertArrayEquals(byteArrayOf(0x0E, 0xF8.toByte(), 0x02, 0x01, 0x01), disableCmd)

        val extType1Cmd = SonyTandemV2Table1Protocol.buildSetSpeakToChatExtParam(
            sensitivity = SmartTalkingDetectionSensitivity.HIGH,
            modeOutTime = SmartTalkingModeOutTime.SLOW,
            voiceFocus = true,
            type = SystemInquiredType.SMART_TALKING_MODE_TYPE1,
        )
        assertArrayEquals(byteArrayOf(0x0E, 0xFC.toByte(), 0x02, 0x01, 0x00, 0x02), extType1Cmd)

        val extType2Cmd = SonyTandemV2Table1Protocol.buildSetSpeakToChatExtParam(
            sensitivity = SmartTalkingDetectionSensitivity.LOW,
            modeOutTime = SmartTalkingModeOutTime.FAST,
            type = SystemInquiredType.SMART_TALKING_MODE_TYPE2,
        )
        assertArrayEquals(byteArrayOf(0x0E, 0xFC.toByte(), 0x0C, 0x02, 0x00), extType2Cmd)
    }

    @Test
    fun speakToChat_parseStatus_reportsEffectOnlyNotTheToggle() {
        // RET_STATUS = [type][EnableDisable][effectStatus]. EnableDisable is the
        // control's availability (official app grays the switch with it), so the
        // toggle must not be derived here — only the effect status is, with
        // official codes NOT_ACTIVE=0x00 / ACTIVE=0x01.
        val activeFrame = byteArrayOf(0x0E, 0xF3.toByte(), 0x02, 0x00, 0x01)
        val parsedActive = SonyTandemV2Table1Protocol.parse(activeFrame) as ParsedTandemResponse.SpeakToChatStatus
        assertEquals(SmartTalkingEffectStatus.ACTIVE, parsedActive.effectStatus)

        val idleFrame = byteArrayOf(0x0E, 0xF3.toByte(), 0x02, 0x00, 0x00)
        val parsedIdle = SonyTandemV2Table1Protocol.parse(idleFrame) as ParsedTandemResponse.SpeakToChatStatus
        assertEquals(SmartTalkingEffectStatus.IDLE, parsedIdle.effectStatus)
    }

    @Test
    fun speakToChat_parseParam_carriesTheToggleForBothTypes() {
        // RET_PARAM = [type][OnOffSettingValue value][preview]; V2 polarity ON=0x00.
        val onFrame = byteArrayOf(0x0E, 0xF7.toByte(), 0x02, 0x00, 0x01)
        val parsedOn = SonyTandemV2Table1Protocol.parse(onFrame) as ParsedTandemResponse.SpeakToChatParam
        assertEquals(true, parsedOn.enabled)

        val offFrame = byteArrayOf(0x0E, 0xF7.toByte(), 0x0C, 0x01, 0x01)
        val parsedOff = SonyTandemV2Table1Protocol.parse(offFrame) as ParsedTandemResponse.SpeakToChatParam
        assertEquals(false, parsedOff.enabled)
    }

    @Test
    fun speakToChat_parseExtendedParam_type1AndType2_parsesCorrectly() {
        val extType1Frame = byteArrayOf(0x0E, 0xFB.toByte(), 0x02, 0x01, 0x00, 0x01) // Type1, High, VoiceFocus ON, Mid
        val parsedType1 = SonyTandemV2Table1Protocol.parse(extType1Frame) as ParsedTandemResponse.SpeakToChatParam
        assertEquals(SmartTalkingDetectionSensitivity.HIGH, parsedType1.sensitivity)
        assertEquals(true, parsedType1.voiceFocus)
        assertEquals(SmartTalkingModeOutTime.MID, parsedType1.modeOutTime)

        val extType2Frame = byteArrayOf(0x0E, 0xFB.toByte(), 0x0C, 0x02, 0x03) // Type2, Low, None
        val parsedType2 = SonyTandemV2Table1Protocol.parse(extType2Frame) as ParsedTandemResponse.SpeakToChatParam
        assertEquals(SmartTalkingDetectionSensitivity.LOW, parsedType2.sensitivity)
        assertEquals(SmartTalkingModeOutTime.NONE, parsedType2.modeOutTime)
    }
}