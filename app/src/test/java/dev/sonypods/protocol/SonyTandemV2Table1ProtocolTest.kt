package dev.sonypods.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
