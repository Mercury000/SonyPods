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
            byteArrayOf(0x0E, 0x00),
            SonyTandemV2Table1Protocol.buildGetProtocolInfo(),
        )
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
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x14, 0x01, 0x00, 0x00, 0x00, 0x0B),
            SonyTandemV2Table1Protocol.buildSetNcModeSwitchAndAmbientLevel(
                NoiseControlMode.AMBIENT_SOUND,
                ambientLevel = 12,
            ),
        )
    }

    @Test
    fun ncAsmSetParam_ncModeSwitchNcDual_matchesReverseCommandShape() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0x68, 0x14, 0x01, 0x00, 0x02, 0x00, 0x00),
            SonyTandemV2Table1Protocol.buildSetNcModeSwitchAndAmbientLevel(NoiseControlMode.NOISE_CANCELLING),
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
        val raw = byteArrayOf(0x0E, 0x67, 0x22, 0x01, 0x00, 0x00, 0x0C)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(12, parsed.ambientLevel)
    }

    @Test
    fun parser_ncModeSwitchAsmSeamlessResponse_extractsXm4AmbientLevel() {
        val raw = byteArrayOf(0x0E, 0x67, 0x14, 0x01, 0x00, 0x00, 0x00, 0x0B)
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
        val raw = byteArrayOf(0x0E, 0x67, 0x14, 0x01, 0x00, 0x02, 0x00, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS, parsed.type)
        assertEquals(NoiseControlMode.NOISE_CANCELLING, parsed.controlMode)
    }

    @Test
    fun parser_tableSet1NcAsmResponse_extractsXm4NcDual() {
        val raw = byteArrayOf(0x0E, 0x67, 0x02, 0x01, 0x02, 0x02, 0x01, 0x00, 0x00)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM, parsed.type)
        assertEquals(NoiseControlMode.NOISE_CANCELLING, parsed.controlMode)
        assertEquals(0, parsed.ambientLevel)
        assertEquals(AmbientSoundMode.NORMAL, parsed.ambientMode)
    }

    @Test
    fun parser_tableSet1NcAsmNotification_extractsXm4Ambient() {
        val raw = byteArrayOf(0x0E, 0x69, 0x02, 0x01, 0x02, 0x00, 0x01, 0x01, 0x14)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.NoiseControl)
        parsed as ParsedTandemResponse.NoiseControl
        assertEquals(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM, parsed.type)
        assertEquals(NoiseControlMode.AMBIENT_SOUND, parsed.controlMode)
        assertEquals(20, parsed.ambientLevel)
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
    fun parser_quickAccessRetParam_extractsKeyAndFunction() {
        val raw = byteArrayOf(0x0E, 0xF7.toByte(), 0x0D, 0x00, 0x02)
        val parsed = SonyTandemV2Table1Protocol.parse(raw)

        assertTrue(parsed is ParsedTandemResponse.QuickAccess)
        parsed as ParsedTandemResponse.QuickAccess
        assertEquals(QuickAccessKey.L_R_KEY, parsed.key)
        assertEquals(QuickAccessFunction.NC_ASM, parsed.function)
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
