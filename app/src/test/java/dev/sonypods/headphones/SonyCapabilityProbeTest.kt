package dev.sonypods.headphones

import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.PlayInquiredType
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.AssignableSettingsAction
import dev.sonypods.protocol.AssignableSettingsActionCapability
import dev.sonypods.protocol.AssignableSettingsActionFunction
import dev.sonypods.protocol.AssignableSettingsFunction
import dev.sonypods.protocol.AssignableSettingsKey
import dev.sonypods.protocol.AssignableSettingsKeyCapability
import dev.sonypods.protocol.AssignableSettingsMapping
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.AssignableSettingsType
import dev.sonypods.protocol.SonySupportedFunction
import dev.sonypods.protocol.SonyTable
import dev.sonypods.protocol.SonyV1FunctionType
import dev.sonypods.protocol.SonyV2FunctionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SonyCapabilityProbe] — the connection-time dynamic capability
 * discovery mirroring Sound Connect 13.2.1 (C29903d V1 / C30916e V2 sequencers):
 * RET_SUPPORT_FUNCTION FunctionTypes are mapped to per-domain GET_CAPABILITY
 * probes and to the engine's feature/query/writable sets.
 */
class SonyCapabilityProbeTest {

    private val v2Profile = ConnectedHeadphoneProfile(
        adapterId = "sony-tandem",
        brand = "Sony",
        modelName = "TEST",
        displayName = "TEST",
        protocolName = "Sony Tandem V2",
        transport = HeadphoneTransport.GATT_HPC,
        capabilities = baseCapabilities(),
        featureProtocolMap = mapOf(
            HeadphoneFeature.DEVICE_INFO to HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
            HeadphoneFeature.NOISE_CONTROL to HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
            HeadphoneFeature.EQ to HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
            HeadphoneFeature.PLAYBACK_CONTROL to HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
            HeadphoneFeature.BATTERY to HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
        ),
        protocolEvidence = listOf("static-profile:TEST"),
    )

    private val v1Profile = v2Profile.copy(
        protocolName = "Sony Tandem V1",
        featureProtocolMap = mapOf(
            HeadphoneFeature.DEVICE_INFO to HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            HeadphoneFeature.NOISE_CONTROL to HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            HeadphoneFeature.EQ to HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
            HeadphoneFeature.BATTERY to HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
        ),
    )

    private fun baseCapabilities() = HeadphoneCapabilities(
        features = setOf(HeadphoneFeature.DEVICE_INFO),
        formFactor = HeadphoneFormFactor.TRUE_WIRELESS,
        batteryQueries = emptyList(),
        noiseControlQueryTypes = emptyList(),
        writableNoiseControlTypes = emptySet(),
        eqConfig = EqDeviceConfig(
            availablePresets = listOf(EqPresetId.OFF),
            writeInquiredType = EqEbbInquiredType.PRESET_EQ,
            statusQueryTypes = emptyList(),
            paramQueryTypes = emptyList(),
            bandCount = 0,
            hasClearBass = false,
        ),
    )

    private fun fn(type: SonyV2FunctionType, order: Int = 0) =
        SonySupportedFunction(type.code, order)

    private fun fn1(type: SonyV1FunctionType, order: Int = 0) =
        SonySupportedFunction(type.code, order)

    // ── Domain derivation from FunctionTypes ─────────────────────────────────

    @Test
    fun ncAsmFunction_derivesNoiseControlAndAmbientLevel() {
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(fn(SonyV2FunctionType.NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT)),
            fallback = baseCapabilities(),
        )
        assertTrue(HeadphoneFeature.NOISE_CONTROL in caps.features)
        assertTrue(HeadphoneFeature.AMBIENT_LEVEL in caps.features)
        assertEquals(
            listOf(NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS),
            caps.noiseControlQueryTypes,
        )
        assertEquals(
            setOf(NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS),
            caps.writableNoiseControlTypes,
        )
    }

    @Test
    fun ncOnOffOnlyFunction_derivesNoiseControlWithoutAmbientLevel() {
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(fn(SonyV2FunctionType.NOISE_CANCELLING_ONOFF)),
            fallback = baseCapabilities(),
        )
        assertTrue(HeadphoneFeature.NOISE_CONTROL in caps.features)
        assertFalse(HeadphoneFeature.AMBIENT_LEVEL in caps.features)
        assertEquals(listOf(NcAsmInquiredType.NC_ON_OFF), caps.noiseControlQueryTypes)
    }

    @Test
    fun v1NcFunction_mapsToV1TableSet1Type() {
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(fn1(SonyV1FunctionType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE)),
            fallback = baseCapabilities(),
            profile = v1Profile,
        )
        assertTrue(HeadphoneFeature.NOISE_CONTROL in caps.features)
        assertEquals(
            listOf(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM),
            caps.noiseControlQueryTypes,
        )
    }

    @Test
    fun v1EqFunction_mapsToV1EqTypes() {
        // V1 EBB=0x52 collides with V2 PRESET_EQ_NON_CUSTOMIZABLE=0x52; the V1
        // profile must interpret it as EBB, not as a V2 EQ type.
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(fn1(SonyV1FunctionType.EBB)),
            fallback = baseCapabilities(),
            profile = v1Profile,
        )
        assertTrue(HeadphoneFeature.EQ in caps.features)
        assertEquals(EqEbbInquiredType.EBB, caps.eqConfig.writeInquiredType)
    }

    @Test
    fun autoAndDualNcAsm_prefersDualForWrites() {
        // A device advertising both the AUTO layout (function 0x68 → 0x15) and
        // the DUAL layout (0x6B → 0x17) only honors level/voice through the
        // DUAL layout, so the writable set must prefer DUAL even when AUTO is
        // listed first.
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(
                fn(SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AUTO_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT, 1),
                fn(SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT, 6),
            ),
            fallback = baseCapabilities(),
        )
        assertTrue(HeadphoneFeature.AMBIENT_LEVEL in caps.features)
        assertTrue(HeadphoneFeature.AMBIENT_VOICE_MODE in caps.features)
        assertEquals(
            setOf(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS),
            caps.writableNoiseControlTypes,
        )
    }

    @Test
    fun autoNcAsmOnly_keepsAutoForWrites() {
        // The genuine DUAL_AUTO function (0x68) alone selects the 0x15 layout.
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(
                fn(SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AUTO_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT, 1),
            ),
            fallback = baseCapabilities(),
        )
        assertEquals(
            setOf(NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS),
            caps.writableNoiseControlTypes,
        )
    }

    /**
     * AUTO_NCASM (0x70) is Adaptive Sound Control, registered under the SENSE
     * domain in SC (`SenseInquiredType.ADAPTIVE_CONTROL`), NOT the NCASM 0x15
     * layout. A device advertising only ASC must not get noise-control types,
     * let alone the wind-noise capability.
     */
    @Test
    fun adaptiveControlOnly_doesNotEnableNcAsmOrWindNoise() {
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(
                fn(SonyV2FunctionType.AUTO_NCASM, 1),
                fn(SonyV2FunctionType.ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION, 2),
            ),
            fallback = baseCapabilities(),
        )
        assertFalse(HeadphoneFeature.NOISE_CONTROL in caps.features)
        assertFalse(caps.supportsAutoWindNoiseReduction)
        assertFalse(caps.supportsWindNoiseReduction)
        assertTrue(caps.writableNoiseControlTypes.isEmpty())
        assertTrue(caps.noiseControlQueryTypes.isEmpty())
    }

    @Test
    fun noiseAdaptationFunction_derivesNoiseAdaptiveFeature() {
        // FunctionType 0x6D (LinkBuds Fit et al.) is the only capability signal
        // for the Auto Ambient Sound toggle; it maps to inquired type 0x19.
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(
                fn(SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_NOISE_ADAPTATION),
            ),
            fallback = baseCapabilities(),
        )
        assertTrue(HeadphoneFeature.NOISE_ADAPTIVE in caps.features)
        assertTrue(HeadphoneFeature.AMBIENT_LEVEL in caps.features)
        assertTrue(HeadphoneFeature.AMBIENT_VOICE_MODE in caps.features)
        assertEquals(
            setOf(NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA),
            caps.writableNoiseControlTypes,
        )
    }

    @Test
    fun dualNcAsmWithoutNoiseAdaptation_lacksNoiseAdaptiveFeature() {
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(
                fn(SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT),
            ),
            fallback = baseCapabilities(),
        )
        assertFalse(HeadphoneFeature.NOISE_ADAPTIVE in caps.features)
    }

    @Test
    fun eqFunction_enablesEqWithWriteType() {
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(fn(SonyV2FunctionType.PRESET_EQ), fn(SonyV2FunctionType.EBB, 1)),
            fallback = baseCapabilities(),
        )
        assertTrue(HeadphoneFeature.EQ in caps.features)
        assertEquals(EqEbbInquiredType.PRESET_EQ, caps.eqConfig.writeInquiredType)
        assertTrue(caps.eqConfig.hasClearBass)
        // A device advertising PRESET_EQ writes Clear Bass by resending the
        // PRESET_EQ bands (capture evidence), not via a standalone EBB param.
        assertEquals(ClearBassWriteMode.PRESET_EQ_BANDS, caps.eqConfig.clearBassWriteMode)
    }

    @Test
    fun playFunction_enablesPlaybackControl() {
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(fn(SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT)),
            fallback = baseCapabilities(),
        )
        assertTrue(HeadphoneFeature.PLAYBACK_CONTROL in caps.features)
        assertEquals(
            PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT,
            caps.playbackControlType,
        )
    }

    @Test
    fun batteryFunctions_collectQueries() {
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(
                fn(SonyV2FunctionType.LEFT_RIGHT_BATTERY_LEVEL_INDICATOR),
                fn(SonyV2FunctionType.CRADLE_BATTERY_LEVEL_INDICATOR, 1),
            ),
            fallback = baseCapabilities(),
        )
        assertTrue(HeadphoneFeature.BATTERY in caps.features)
        assertEquals(
            listOf(PowerInquiredType.LEFT_RIGHT_BATTERY, PowerInquiredType.CRADLE_BATTERY),
            caps.batteryQueries,
        )
    }

    @Test
    fun powerOffFunction_isEnabledOnlyForTable1Profiles() {
        val functions = listOf(fn(SonyV2FunctionType.POWER_OFF))
        val table1 = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = functions,
            fallback = baseCapabilities(),
            profile = v2Profile,
        )
        val table2 = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = functions,
            fallback = baseCapabilities(),
            profile = v2Profile.copy(
                featureProtocolMap = v2Profile.featureProtocolMap.mapValues { HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2 },
            ),
        )

        assertTrue(HeadphoneFeature.POWER_OFF in table1.features)
        assertFalse(HeadphoneFeature.POWER_OFF in table2.features)
    }

    @Test
    fun unknownFunction_isSkipped() {
        val caps = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(fn(SonyV2FunctionType.CONCIERGE_DATA)),
            fallback = baseCapabilities(),
        )
        assertEquals(setOf(HeadphoneFeature.DEVICE_INFO), caps.features)
        assertEquals(emptyList<NcAsmInquiredType>(), caps.noiseControlQueryTypes)
    }

    // ── Capability probe command generation ──────────────────────────────────

    @Test
    fun probeCommands_generatesPerDomainGetCap() {
        val commands = SonyCapabilityProbe.buildCapabilityProbeCommands(
            profile = v2Profile,
            functions = listOf(
                fn(SonyV2FunctionType.NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT, 0),
                fn(SonyV2FunctionType.PRESET_EQ, 1),
                fn(SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT, 2),
            ),
        )
        val labels = commands.map { it.label }
        assertTrue(labels.any { it.contains("GET NCASM capability") })
        assertTrue(labels.any { it.contains("GET EQEBB capability") })
        assertTrue(labels.any { it.contains("GET EQEBB extended") })
        assertTrue(labels.any { it.contains("GET PLAY capability") })
        // NCASM GET_CAPABILITY payload: [NCASM 0x60][NcAsm type code]
        val ncasm = commands.first { it.label.contains("GET NCASM") }
        assertEquals(
            listOf(0x0E, 0x60, NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS.code.toInt().and(0xFF)),
            ncasm.bytes.toList().map { it.toInt().and(0xFF) },
        )
        // EQEBB GET_CAPABILITY payload: [EQEBB 0x50][type code][DisplayLanguage 0x00]
        val eqebb = commands.first { it.label.contains("GET EQEBB capability") }
        assertEquals(
            listOf(0x0E, 0x50, EqEbbInquiredType.PRESET_EQ.code.toInt().and(0xFF), 0x00),
            eqebb.bytes.toList().map { it.toInt().and(0xFF) },
        )
        // PLAY GET_CAPABILITY payload: [PLAY 0xA0][type code]
        val play = commands.first { it.label.contains("GET PLAY capability") }
        assertEquals(
            listOf(0x0E, 0xA0, PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT.code.toInt().and(0xFF)),
            play.bytes.toList().map { it.toInt().and(0xFF) },
        )
    }

    @Test
    fun probeCommands_skipsUnrecognisedFunctions() {
        val commands = SonyCapabilityProbe.buildCapabilityProbeCommands(
            profile = v2Profile,
            functions = listOf(fn(SonyV2FunctionType.CONCIERGE_DATA)),
        )
        assertEquals(emptyList<HeadphoneCommand>(), commands)
    }

    @Test
    fun probeCommands_generatesGsGetCapForAdvertisedGeneralSetting() {
        // The device advertises GENERAL_SETTING_2 (0xD2) → the probe must emit
        // GET_GS_CAPABILITY for that slot. SC enumerates every advertised GS type
        // (uv.d.c / wv.e.e: GsInquiredType.isGeneralSettingType) without any
        // MULTIPOINT-support gate; the slot's reply ("MULTIPOINT_SETTING") is
        // what enables the 2-device switch.
        val commands = SonyCapabilityProbe.buildCapabilityProbeCommands(
            profile = v2Profile,
            functions = listOf(fn(SonyV2FunctionType.GENERAL_SETTING_2)),
        )
        val gs = commands.firstOrNull { it.label.contains("GET GS capability") }
        assertNotNull("expected a GET GS capability probe", gs)
        assertEquals(
            // [DATA_MDR 0x0E][GS_GET_CAPABILITY 0xD0][slot 0xD2][language 0x01]
            listOf(0x0E, 0xD0, 0xD2, 0x01),
            gs!!.bytes.toList().map { it.toInt().and(0xFF) },
        )
    }

    @Test
    fun applyToProfile_recordsProbeEvidenceAndKeepsStaticMarkers() {
        val applied = SonyCapabilityProbe.applyToProfile(
            profile = v2Profile,
            functions = listOf(
                fn(SonyV2FunctionType.NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT, 0),
                fn(SonyV2FunctionType.PRESET_EQ, 1),
            ),
            transport = HeadphoneTransport.GATT_HPC,
        )
        assertTrue(applied.protocolEvidence.any { it.startsWith("static-profile:") })
        assertTrue(applied.protocolEvidence.any { it == "probe:ret-support-function(2)" })
        assertTrue(applied.protocolEvidence.any { it.startsWith("probe:NO_1:NCASM:") })
        assertTrue(applied.protocolEvidence.any { it.startsWith("probe:NO_1:EQEBB:") })
        assertTrue(HeadphoneFeature.NOISE_CONTROL in applied.capabilities.features)
        assertTrue(HeadphoneFeature.EQ in applied.capabilities.features)
    }

    /**
     * The capability table having been applied is what every surface gates on, and
     * [SonyCapabilityProbe.applyToProfile] is the only place a real table becomes a profile —
     * the live probe, the counter-matched cache restore and the connection-time restore all
     * funnel through it. A profile that has not been through it must not claim the table:
     * rendering that state is what showed a pair of buds as a single-battery headband.
     */
    @Test
    fun applyToProfile_marksCapabilitiesKnown() {
        assertFalse(v2Profile.capabilitiesKnown)

        val applied = SonyCapabilityProbe.applyToProfile(
            profile = v2Profile,
            functions = listOf(fn(SonyV2FunctionType.PRESET_EQ, 0)),
            transport = HeadphoneTransport.GATT_HPC,
        )

        assertTrue(applied.capabilitiesKnown)
    }

    /**
     * A cache restore re-derives the same table without a live probe, so it must
     * not stamp `probe:ret-support-function` — the engine fires its one-shot
     * per-domain probe burst only while that stamp is absent, and a restore
     * claiming it silently suppressed every later genuine probe (the AUDIO
     * capability GET never went out and the DSEE generation stayed unknown).
     */
    @Test
    fun applyToProfile_restoreDoesNotClaimProbeEvidence() {
        val restored = SonyCapabilityProbe.applyToProfile(
            profile = v2Profile,
            functions = listOf(fn(SonyV2FunctionType.PRESET_EQ, 0)),
            transport = HeadphoneTransport.GATT_HPC,
            markProbed = false,
        )

        assertTrue(restored.capabilitiesKnown)
        assertTrue(restored.protocolEvidence.none { it.startsWith("probe:ret-support-function") })
        assertTrue(restored.protocolEvidence.any { it.startsWith("probe:NO_1:EQEBB:") })
    }

    /** The upscaling probe rides the same burst as the other domains whenever the
     * support list advertises either upscaling FunctionType (`u70.p1` picks inq
     * 0x01 over 0x0B); its RET carries the DSEE generation byte. */
    @Test
    fun probeCommands_emitsAudioUpscalingCapability() {
        val commands = SonyCapabilityProbe.buildCapabilityProbeCommands(
            profile = v2Profile.copy(
                featureProtocolMap = v2Profile.featureProtocolMap +
                    (HeadphoneFeature.UPSCALING to HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1),
            ),
            functions = listOf(fn(SonyV2FunctionType.UPSCALING_AUTO_OFF, 0)),
        )
        assertEquals(1, commands.size)
        assertEquals("GET AUDIO capability upscaling", commands.single().label)
        assertEquals("0E E0 01", commands.single().bytes.joinToString(" ") { "%02X".format(it) })
    }

    @Test
    fun v1ProbeCommands_generatesNcAsmSystemProbe() {
        val commands = SonyCapabilityProbe.buildCapabilityProbeCommands(
            profile = v1Profile,
            functions = listOf(
                fn1(SonyV1FunctionType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE, 0),
                fn1(SonyV1FunctionType.SMART_TALKING_MODE, 1),
            ),
        )
        val labels = commands.map { it.label }
        assertTrue(labels.any { it.contains("GET NCASM capability V1") })
        assertTrue(labels.any { it.contains("GET SYSTEM capability") })
        val system = commands.first { it.label.contains("GET SYSTEM capability") }
        // V1 SYSTEM GET_CAPABILITY: [SYSTEM 0xF0][SMART_TALKING_MODE 0x05]; the V2
        // TYPE1 code 0x02 means POWER_SAVING_MODE on the V1 wire.
        assertEquals(
            listOf(0x0E, 0xF0, 0x05),
            system.bytes.toList().map { it.toInt().and(0xFF) },
        )
    }

    /**
     * V1 `CONTROL_BY_WEARING` is a playback-control on/off setting (V2's
     * PLAYBACK_CONTROL_BY_WEARING), not a wearing-status detector, and V1 has no
     * such detector at all. It must neither be probed — 0x06 is ASSIGNABLE_SETTINGS
     * on the V1 wire — nor claim wearing support.
     */
    @Test
    fun v1ControlByWearing_isNotProbedAsWearingStatus() {
        val functions = listOf(fn1(SonyV1FunctionType.CONTROL_BY_WEARING, 0))
        val commands = SonyCapabilityProbe.buildCapabilityProbeCommands(
            profile = v1Profile,
            functions = functions,
        )
        assertTrue(commands.none { it.label.contains("GET SYSTEM capability") })

        val capabilities = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = functions,
            fallback = baseCapabilities(),
            profile = v1Profile,
        )
        assertFalse(HeadphoneFeature.WEARING_STATUS in capabilities.features)
    }

    @Test
    fun limitationAssignableSetting_usesLimitationSystemType() {
        val commands = SonyCapabilityProbe.buildCapabilityProbeCommands(
            profile = v2Profile,
            functions = listOf(fn(SonyV2FunctionType.ASSIGNABLE_SETTING_WITH_LIMITATION)),
        )
        val system = commands.single()
        assertEquals(
            listOf(0x0E, 0xF0, 0x0E),
            system.bytes.toList().map { it.toInt().and(0xFF) },
        )

        val capabilities = SonyCapabilityProbe.capabilitiesFromFunctions(
            functions = listOf(fn(SonyV2FunctionType.ASSIGNABLE_SETTING_WITH_LIMITATION)),
            fallback = baseCapabilities(),
            profile = v2Profile,
        )
        assertTrue(HeadphoneFeature.GESTURE_OPERATIONS in capabilities.features)
        assertEquals(
            dev.sonypods.protocol.SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION,
            capabilities.gestureSettingsType,
        )
    }

    @Test
    fun gestureUiKeepsDuplicatePresetMappingsInCapabilityOrder() {
        val action = AssignableSettingsAction.SINGLE_TAP
        val preset = AssignableSettingsPreset.PLAYBACK_CONTROL
        val capability = { key: AssignableSettingsKey, defaultFunction: AssignableSettingsFunction ->
            AssignableSettingsKeyCapability(
                key = key,
                type = AssignableSettingsType.TOUCH_SENSOR,
                defaultPreset = preset,
                presets = listOf(preset),
                actionsByPreset = mapOf(
                    preset to listOf(
                        AssignableSettingsActionCapability(
                            action = action,
                            defaultFunction = defaultFunction,
                            availableFunctions = listOf(
                                AssignableSettingsFunction.PLAY_PAUSE,
                                AssignableSettingsFunction.NEXT_TRACK,
                            ),
                        )
                    )
                ),
            )
        }
        val state = dev.sonypods.data.GestureOperationsState(
            capabilities = listOf(
                capability(AssignableSettingsKey.LEFT_SIDE, AssignableSettingsFunction.PLAY_PAUSE),
                capability(AssignableSettingsKey.RIGHT_SIDE, AssignableSettingsFunction.NEXT_TRACK),
            ),
            presets = listOf(preset, preset),
            mappings = listOf(
                AssignableSettingsMapping(
                    preset,
                    listOf(AssignableSettingsActionFunction(action, AssignableSettingsFunction.PLAY_PAUSE)),
                ),
                AssignableSettingsMapping(
                    preset,
                    listOf(AssignableSettingsActionFunction(action, AssignableSettingsFunction.NEXT_TRACK)),
                ),
            ),
        )

        assertEquals(
            listOf(AssignableSettingsFunction.PLAY_PAUSE, AssignableSettingsFunction.NEXT_TRACK),
            state.uiKeys().map { it.actions.single().function },
        )
    }

    @Test
    fun gestureUiFallsBackToDefaultPresetOnOutOfRangePlaceholder() {
        // V1 keeps an OUT_OF_RANGE placeholder in the preset list when a device
        // reports a byte off the shared table, preserving index alignment with
        // the capability keys. It must surface as the key's default preset —
        // never as a selectable/writable value (the builders reject it).
        val preset = AssignableSettingsPreset.PLAYBACK_CONTROL
        val capability = AssignableSettingsKeyCapability(
            key = AssignableSettingsKey.LEFT_SIDE,
            type = AssignableSettingsType.TOUCH_SENSOR,
            defaultPreset = preset,
            presets = listOf(preset),
            actionsByPreset = emptyMap(),
        )
        val state = dev.sonypods.data.GestureOperationsState(
            capabilities = listOf(capability),
            presets = listOf(AssignableSettingsPreset.OUT_OF_RANGE),
        )

        assertEquals(preset, state.uiKeys().single().currentPreset)
    }
}
