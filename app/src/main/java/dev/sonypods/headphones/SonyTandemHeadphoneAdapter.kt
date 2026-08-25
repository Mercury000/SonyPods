package dev.sonypods.headphones

import dev.sonypods.ble.DiscoveredSonyDevice
import dev.sonypods.protocol.AmbientSoundMode
import dev.sonypods.protocol.CommonInquiredType
import dev.sonypods.protocol.DeviceInfoType
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.LeaInquiredType
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.NoiseAdaptiveSensitivity
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.PlaybackControl
import dev.sonypods.protocol.PlayInquiredType
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.AssignableSettingsMapping
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.ConnectivityActionTypeTable2
import dev.sonypods.protocol.PeripheralBluetoothModeTable2
import dev.sonypods.protocol.PeripheralInquiredTypeTable2
import dev.sonypods.protocol.SonyTandemV2Table2Protocol
import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR
import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR_NO2
import dev.sonypods.protocol.unsigned

object SonyTandemHeadphoneAdapter : HeadphoneAdapter {
    private const val COMMON_RET_BATTERY_LEVEL: Byte = 0x11
    private const val COMMON_NTFY_BATTERY_LEVEL: Byte = 0x13
    private const val POWER_RET_STATUS: Byte = 0x23
    private const val POWER_NTFY_STATUS: Byte = 0x25
    private const val EQEBB_GET_STATUS: Byte = 0x52
    private const val EQEBB_RET_STATUS: Byte = 0x53
    private const val EQEBB_NTFY_STATUS: Byte = 0x55
    private const val EQEBB_GET_PARAM: Byte = 0x56
    private const val EQEBB_RET_PARAM: Byte = 0x57
    private const val EQEBB_SET_PARAM: Byte = 0x58
    private const val EQEBB_NTFY_PARAM: Byte = 0x59
    private const val EQEBB_GET_EXTENDED_INFO: Byte = 0x5A
    private const val EQEBB_RET_EXTENDED_INFO: Byte = 0x5B
    private const val NCASM_GET_STATUS: Byte = 0x62
    private const val NCASM_RET_STATUS: Byte = 0x63
    private const val NCASM_NTFY_STATUS: Byte = 0x65
    private const val NCASM_GET_PARAM: Byte = 0x66
    private const val NCASM_RET_PARAM: Byte = 0x67
    private const val NCASM_SET_PARAM: Byte = 0x68
    private const val NCASM_NTFY_PARAM: Byte = 0x69
    private const val SYSTEM_RET_CAPABILITY: Byte = 0xF1.toByte()
    private const val SYSTEM_RET_STATUS: Byte = 0xF3.toByte()
    private const val SYSTEM_NTFY_STATUS: Byte = 0xF5.toByte()
    private const val SYSTEM_RET_PARAM: Byte = 0xF7.toByte()
    private const val SYSTEM_NTFY_PARAM: Byte = 0xF9.toByte()
    private const val SYSTEM_RET_EXT_PARAM: Byte = 0xFB.toByte()
    private const val SYSTEM_NTFY_EXT_PARAM: Byte = 0xFD.toByte()
    private const val PLAY_RET_STATUS: Byte = 0xA3.toByte()
    private const val PLAY_NTFY_STATUS: Byte = 0xA5.toByte()
    override val id: String = "sony-tandem"
    override val brand: String = "Sony"
    override val protocolName: String = "Sony Tandem"

    val legacyIds: Set<String> = setOf("sony-tandem-v2")

    private fun command(
        profile: ConnectedHeadphoneProfile,
        feature: HeadphoneFeature,
        label: String,
        bytes: ByteArray,
    ): HeadphoneCommand =
        HeadphoneCommand(label = label, bytes = bytes, channel = profile.channelFor(feature))

    private fun codecFor(profile: ConnectedHeadphoneProfile, feature: HeadphoneFeature): TandemCodec =
        TandemCodecRegistry.codecFor(profile.protocolFor(feature))

    /**
     * Pure-dynamic matching: no model is judged by its name. A neutral profile
     * is always returned and refined at connection time from the transport
     * endpoints (generation) plus the RET_PROTOCOL_INFO version and the
     * RET_SUPPORT_FUNCTION capability probe.
     */
    override fun match(
        device: DiscoveredSonyDevice,
        reportedModelName: String?,
    ): ConnectedHeadphoneProfile? = null

    override fun fallbackProfile(device: DiscoveredSonyDevice): ConnectedHeadphoneProfile =
        neutralProfile(device.name)

    private fun neutralProfile(deviceName: String): ConnectedHeadphoneProfile =
        ProfileTemplate(
            modelName = deviceName.removePrefix("LE_").takeIf { it.isNotBlank() } ?: "Sony audio device",
            series = null,
            capabilities = HeadphoneCapabilities(
                features = setOf(
                    HeadphoneFeature.DEVICE_INFO,
                    HeadphoneFeature.BATTERY,
                ),
                formFactor = HeadphoneFormFactor.UNKNOWN,
                batteryQueries = listOf(PowerInquiredType.BATTERY),
                noiseControlQueryTypes = emptyList(),
                writableNoiseControlTypes = emptySet(),
                eqConfig = EqDeviceConfig(
                    // Empty until the probe confirms EQ; the probe fills the
                    // official preset set (see SonyCapabilityProbe.DEFAULT_PRESETS).
                    availablePresets = emptyList(),
                    writeInquiredType = EqEbbInquiredType.PRESET_EQ,
                    statusQueryTypes = emptyList(),
                    paramQueryTypes = emptyList(),
                    bandCount = 0,
                    hasClearBass = false,
                ),
            ),
            // Generic V2 channel map: every feature has a resolvable binding so
            // the probe and refresh can address any domain. Which features are
            // actually usable is gated by the probed `capabilities.features`.
            featureProtocolMap = allFeatures.associateWith { HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1 },
            knownStaticProfile = false,
        ).toProfile(id, brand, protocolName, deviceName)

    /** Every feature the Sony engine can address, mapped onto a neutral V2 channel. */
    private val allFeatures: Set<HeadphoneFeature> =
        HeadphoneFeature.entries.toSet()

    /**
     * Bind a profile to the protocol generation implied by the transport
     * endpoints. For SPP, the generation is resolved from the SPP service UUID
     * the SDP handshake bound to — matching SC, which keys V1/V2 off the SPP
     * UUID (96cc203e… → TABLE_SET_1/V1, 956c7b26… → TABLE_SET_2/V2), not the
     * model name. A V1 MC GATT endpoint means V1; V2 HPC/MC means V2 (the V1
     * fallback only triggers when no V2/SPP endpoint exists). Returns the
     * profile unchanged when the generation already matches.
     */
    fun withEndpointChannels(
        profile: ConnectedHeadphoneProfile,
        channels: Set<TandemChannel>,
        sppUuid: java.util.UUID? = null,
    ): ConnectedHeadphoneProfile {
        val variant = bindVariantFromChannels(channels, sppUuid) ?: return profile
        if (variant == profile.protocolFor(HeadphoneFeature.DEVICE_INFO)) {
            // Same table, but the endpoints may still have changed — a reconnect over LE Audio
            // exposes only the HPC service where the previous session had MC.
            return profile.copy(
                featureBindings = buildFeatureBindings(
                    profile.featureProtocolMap,
                    profile.capabilities,
                    channels,
                ),
            )
        }
        return rebindProfile(profile, variant, channels)
    }

    fun bindVariantFromChannels(
        channels: Set<TandemChannel>,
        sppUuid: java.util.UUID? = null,
    ): HeadphoneProtocolVariant? = when {
        channels.isEmpty() -> null
        TandemChannel.SPP_MDR in channels ->
            when (sppUuid) {
                // SC: SPP UUID 96cc203e… is TABLE_SET_1 (V1), 956c7b26… is TABLE_SET_2 (V2).
                null -> HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1
                else -> if (sppUuid.toString().startsWith("96cc203e")) {
                    HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1
                } else {
                    HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1
                }
            }
        TandemChannel.GATT_V2_HPC in channels -> HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1
        TandemChannel.GATT_V2_MC in channels -> HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1
        TandemChannel.GATT_V1_MC in channels -> HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1
        else -> null
    }

    private fun rebindProfile(
        profile: ConnectedHeadphoneProfile,
        variant: HeadphoneProtocolVariant,
        endpoints: Set<TandemChannel> = emptySet(),
    ): ConnectedHeadphoneProfile {
        val newMap = profile.featureProtocolMap.mapValues { variant }
        return profile.copy(
            featureProtocolMap = newMap,
            featureBindings = buildFeatureBindings(newMap, profile.capabilities, endpoints),
        ).rebounded()
    }

    override fun buildRefreshCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        buildList {
            val deviceInfoCodec = codecFor(profile, HeadphoneFeature.DEVICE_INFO)
            // GET_PROTOCOL_INFO is deliberately NOT here: SC sends it as the very
            // first exchange of a session (C30916e.m112238v0), and the probe path
            // now does the same. Mid-burst it deterministically wedges the
            // headset's HPC ACK state (see repository probeCapabilities).
            if (profile.supports(HeadphoneFeature.DEVICE_INFO)) {
                DeviceInfoType.entries.forEach {
                    deviceInfoCodec.buildGetDeviceInfo(it)?.let { bytes ->
                        add(command(profile, HeadphoneFeature.DEVICE_INFO, "GET device info $it", bytes))
                    }
                }
                deviceInfoCodec.buildGetDisplayFirmwareVersion()?.let {
                    add(command(profile, HeadphoneFeature.DEVICE_INFO, "GET display firmware version", it))
                }
            }
            addAll(buildRefreshBatteryCommands(profile))
            if (profile.supports(HeadphoneFeature.NOISE_CONTROL)) {
                addAll(buildRefreshNoiseControlCommands(profile))
            }
            if (profile.supports(HeadphoneFeature.EQ)) {
                addAll(buildRefreshEqCommands(profile))
            }
            if (profile.supports(HeadphoneFeature.PLAYBACK_CONTROL)) {
                addAll(buildRefreshPlaybackCommands(profile))
            }
            if (profile.supports(HeadphoneFeature.LEA_STATUS)) {
                addAll(buildRefreshLeaCommands(profile))
            }
            if (profile.supports(HeadphoneFeature.UPSCALING)) {
                addAll(buildRefreshUpscalingCommands(profile))
            }
            if (profile.supports(HeadphoneFeature.QUICK_ACCESS)) {
                val codec = codecFor(profile, HeadphoneFeature.QUICK_ACCESS)
                codec.buildGetQuickAccessCapability()?.let {
                    add(command(profile, HeadphoneFeature.QUICK_ACCESS, "GET Quick Access capability", it))
                }
                codec.buildGetQuickAccessStatus()?.let {
                    add(command(profile, HeadphoneFeature.QUICK_ACCESS, "GET Quick Access status", it))
                }
                codec.buildGetQuickAccess()?.let {
                    add(command(profile, HeadphoneFeature.QUICK_ACCESS, "GET Quick Access", it))
                }
            }
            if (profile.supports(HeadphoneFeature.GESTURE_OPERATIONS)) {
                addAll(buildRefreshGestureOperationsCommands(profile))
            }
            // Query PERIPHERAL before capability probing adds MULTIPOINT to
            // the neutral profile. V2 Table2 exposes it on the MC endpoint.
            if (profile.protocolFor(HeadphoneFeature.MULTIPOINT) in setOf(
                    HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
                    HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2,
                )
            ) {
                addAll(buildRefreshMultipointCommands(profile))
            }
            if (profile.supports(HeadphoneFeature.WEARING_STATUS)) {
                codecFor(profile, HeadphoneFeature.WEARING_STATUS).buildGetWearingStatus()?.let {
                    add(command(profile, HeadphoneFeature.WEARING_STATUS, "GET Wearing status", it))
                }
            }
            // Live sound-quality badges: one COMMON_GET_STATUS each, then NTFY
            // pushes keep them current (SC `n10.a.mo313a` / `u60.a.mo313a` query
            // once on feature start and never re-poll).
            val badgeCodec = codecFor(profile, HeadphoneFeature.DEVICE_INFO)
            if (profile.capabilities.codecIndicatorSupported) {
                badgeCodec.buildGetAudioCodecStatus()?.let {
                    add(command(profile, HeadphoneFeature.DEVICE_INFO, "GET audio codec status", it))
                }
            }
            if (profile.capabilities.upscalingIndicatorSupported) {
                badgeCodec.buildGetUpscalingEffectStatus()?.let {
                    add(command(profile, HeadphoneFeature.DEVICE_INFO, "GET upscaling effect status", it))
                }
            }
            // SC arms the alert domain or the device never pushes the 0x99
            // confirmation for GS SETs that need one (multipoint reconnect etc.):
            // 0x94 [APP_BECOMES_FOREGROUND=0x02][ENABLE] on UI shown, 0x94
            // [FIXED_MESSAGE=0x00][ENABLE] on connect. Both are idempotent, so the
            // refresh (connection + UI entry) is the right place.
            if (profile.protocolFor(HeadphoneFeature.DEVICE_INFO) == HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1) {
                addAll(buildRefreshAlertCommands(profile))
            }
        }

    /**
     * The refresh burst above, reduced to the domains that answer with a value.
     *
     * Derived by asking the same builders the burst uses, not by restating its
     * conditions: a domain is listed only when a GET that carries a value was
     * actually built for this profile. A domain declared without a query behind it
     * would hold every consumer until the idle timeout, so "supported" alone is not
     * enough — the capability table can advertise a feature whose codec has no
     * getter on this protocol generation.
     *
     * Capability-only queries are excluded on the same grounds: their replies
     * describe the model rather than its state, so a domain that can only answer
     * with one (Quick Access, gestures, playback) does not count as answered. The
     * alert-arming SETs and the protocol-info exchange are absent for the same
     * reason.
     */
    override fun initialValueDomains(profile: ConnectedHeadphoneProfile): Set<HeadphoneFeature> =
        buildSet {
            if (profile.supports(HeadphoneFeature.DEVICE_INFO)) {
                val codec = codecFor(profile, HeadphoneFeature.DEVICE_INFO)
                if (DeviceInfoType.entries.any { codec.buildGetDeviceInfo(it) != null }) {
                    add(HeadphoneFeature.DEVICE_INFO)
                }
            }
            // Already gated on supports(BATTERY) and on a non-empty query list.
            if (buildRefreshBatteryCommands(profile).isNotEmpty()) add(HeadphoneFeature.BATTERY)
            if (profile.supports(HeadphoneFeature.NOISE_CONTROL) &&
                buildRefreshNoiseControlCommands(profile).isNotEmpty()
            ) {
                add(HeadphoneFeature.NOISE_CONTROL)
            }
            if (profile.supports(HeadphoneFeature.EQ) && buildRefreshEqCommands(profile).isNotEmpty()) {
                add(HeadphoneFeature.EQ)
            }
            if (profile.supports(HeadphoneFeature.PLAYBACK_CONTROL)) {
                val codec = codecFor(profile, HeadphoneFeature.PLAYBACK_CONTROL)
                val type = profile.capabilities.playbackControlType
                val hasValueQuery = codec.buildGetPlaybackStatus(type) != null ||
                    codec.buildGetPlaybackMetadata(type).isNotEmpty() ||
                    codec.buildGetPlaybackVolume(volumeType(profile)) != null
                if (hasValueQuery) add(HeadphoneFeature.PLAYBACK_CONTROL)
            }
            if (profile.supports(HeadphoneFeature.LEA_STATUS) &&
                buildRefreshLeaCommands(profile).isNotEmpty()
            ) {
                add(HeadphoneFeature.LEA_STATUS)
            }
            if (profile.supports(HeadphoneFeature.QUICK_ACCESS)) {
                val codec = codecFor(profile, HeadphoneFeature.QUICK_ACCESS)
                if (codec.buildGetQuickAccessStatus() != null || codec.buildGetQuickAccess() != null) {
                    add(HeadphoneFeature.QUICK_ACCESS)
                }
            }
            if (profile.supports(HeadphoneFeature.GESTURE_OPERATIONS)) {
                val codec = codecFor(profile, HeadphoneFeature.GESTURE_OPERATIONS)
                val type = profile.capabilities.gestureSettingsType
                val hasValueQuery = codec.buildGetAssignableSettingsStatus(type) != null ||
                    codec.buildGetAssignableSettingsPresets(type) != null ||
                    codec.buildGetAssignableSettingsExtendedParam(type) != null
                if (hasValueQuery) add(HeadphoneFeature.GESTURE_OPERATIONS)
            }
            // The burst queries the peripheral domain before the probe has enabled
            // multipoint; wait for it only where the headset actually has it, or a
            // model without multipoint would stall on a reply that never comes.
            if (profile.supports(HeadphoneFeature.MULTIPOINT) &&
                buildRefreshMultipointCommands(profile).isNotEmpty()
            ) {
                add(HeadphoneFeature.MULTIPOINT)
            }
            if (profile.supports(HeadphoneFeature.WEARING_STATUS) &&
                codecFor(profile, HeadphoneFeature.WEARING_STATUS).buildGetWearingStatus() != null
            ) {
                add(HeadphoneFeature.WEARING_STATUS)
            }
        }

    override fun canWrite(profile: ConnectedHeadphoneProfile, feature: HeadphoneFeature): Boolean =
        when (feature) {
            HeadphoneFeature.LEA_STATUS ->
                profile.supports(feature) &&
                    profile.capabilities.lea?.controlSupported == true &&
                    runCatching { profile.channelFor(HeadphoneFeature.DEVICE_INFO) }.isSuccess
            HeadphoneFeature.NOISE_CONTROL,
            HeadphoneFeature.AMBIENT_LEVEL,
            HeadphoneFeature.AMBIENT_VOICE_MODE,
            HeadphoneFeature.NOISE_ADAPTIVE ->
                profile.supports(feature) && profile.capabilities.writableNoiseControlTypes.isNotEmpty()
            // EQ/Clear Bass/playback writes are gated purely by the probed
            // capability set; no static-profile evidence is required.
            else -> profile.supports(feature)
        }

    override fun buildSetNoiseControlModeCommands(
        profile: ConnectedHeadphoneProfile,
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
        noiseAdaptive: Boolean,
        noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity,
    ): List<HeadphoneCommand> {
        val level = ambientLevel.coerceIn(1, 20)
        val codec = codecFor(profile, HeadphoneFeature.NOISE_CONTROL)

        profile.capabilities.writableNoiseControlTypes.forEach { type ->
            val bytes = codec.buildSetNoiseControlMode(
                type, mode, level, ambientMode, noiseAdaptive, noiseAdaptiveSensitivity,
            ) ?: return@forEach
            return listOf(
                command(
                    profile,
                    HeadphoneFeature.NOISE_CONTROL,
                    noiseControlWriteLabel(type, mode, level, ambientMode, noiseAdaptive, noiseAdaptiveSensitivity),
                    bytes,
                )
            )
        }

        return when (mode) {
            NoiseControlMode.NOISE_CANCELLING ->
                codec.buildSetNcOnOff(true)
                    ?.let { listOf(command(profile, HeadphoneFeature.NOISE_CONTROL, "SET NC on", it)) }
                    .orEmpty()
            NoiseControlMode.AMBIENT_SOUND ->
                codec.buildSetAmbientLevel(level, enabled = true, mode = ambientMode)
                    ?.let {
                        listOf(
                            command(
                                profile,
                                HeadphoneFeature.NOISE_CONTROL,
                                "SET ASM level $level voice=${ambientMode == AmbientSoundMode.VOICE}",
                                it,
                            )
                        )
                    }
                    .orEmpty()
            NoiseControlMode.OFF -> listOfNotNull(
                codec.buildSetNcOnOff(false)?.let {
                    command(profile, HeadphoneFeature.NOISE_CONTROL, "SET NC off", it)
                },
                codec.buildSetAmbientSound(false, ambientMode)?.let {
                    command(profile, HeadphoneFeature.NOISE_CONTROL, "SET ASM off", it)
                },
            )
        }
    }

    private fun noiseControlWriteLabel(
        type: NcAsmInquiredType,
        mode: NoiseControlMode,
        level: Int,
        ambientMode: AmbientSoundMode,
        noiseAdaptive: Boolean = false,
        noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.STANDARD,
    ): String {
        val voice = ambientMode == AmbientSoundMode.VOICE
        return when (type) {
            NcAsmInquiredType.V1_TABLE_SET1_NC_ASM ->
                "SET NC/ASM V1 table1 mode $mode level=$level voice=$voice"
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                "SET NC/ASM 0x14 mode $mode level=$level voice=$voice"
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA ->
                "SET NC/ASM 0x19 mode $mode level=$level voice=$voice na=$noiseAdaptive/$noiseAdaptiveSensitivity"
            else -> "SET NC/ASM mode $mode level=$level voice=$voice"
        }
    }

    override fun buildSetEqPresetCommands(
        profile: ConnectedHeadphoneProfile,
        preset: EqPresetId,
        context: EqWriteContext,
    ): List<HeadphoneCommand> {
        val engine = EqProtocolEngine(profile.capabilities.eqConfig, codecFor(profile, HeadphoneFeature.EQ))
        return listOf(
            command(
                profile,
                HeadphoneFeature.EQ,
                "SET EQ preset ${preset.name} type=${profile.capabilities.eqConfig.writeInquiredType}",
                engine.buildSetPreset(preset),
            )
        )
    }

    override fun buildSetEqBandCommands(
        profile: ConnectedHeadphoneProfile,
        rawSteps: List<Int>,
        preset: EqPresetId?,
        context: EqWriteContext,
    ): List<HeadphoneCommand> {
        val engine = EqProtocolEngine(profile.capabilities.eqConfig, codecFor(profile, HeadphoneFeature.EQ))
        val targetPreset = preset ?: EqPresetId.CUSTOM
        val writeType = profile.capabilities.eqConfig.writeInquiredType
        return listOf(
            command(
                profile,
                HeadphoneFeature.EQ,
                "SET EQ bands type=$writeType preset=${targetPreset.name}",
                engine.buildSetBands(rawSteps, targetPreset),
            )
        )
    }

    override fun buildSetClearBassCommands(
        profile: ConnectedHeadphoneProfile,
        level: Int,
        context: EqWriteContext,
    ): List<HeadphoneCommand> {
        val engine = EqProtocolEngine(profile.capabilities.eqConfig, codecFor(profile, HeadphoneFeature.CLEAR_BASS))
        return when (profile.capabilities.eqConfig.clearBassWriteMode) {
            ClearBassWriteMode.EBB_PARAM -> listOf(
                command(
                    profile,
                    HeadphoneFeature.CLEAR_BASS,
                    "SET Clear Bass $level",
                    engine.buildSetClearBass(level),
                )
            )
            ClearBassWriteMode.PRESET_EQ_BANDS -> {
                val targetPreset = context.userEqPresetOrDefault()
                val rawSteps = mergedClearBassRawSteps(profile.capabilities.eqConfig, context.rawBandSteps, level)
                listOf(
                    command(
                        profile,
                        HeadphoneFeature.CLEAR_BASS,
                        "SET Clear Bass $level via EQ bands preset=${targetPreset.name}",
                        engine.buildSetBands(rawSteps, targetPreset),
                    )
                )
            }
        }
    }

    private fun EqWriteContext.userEqPresetOrDefault(): EqPresetId =
        when (preset) {
            EqPresetId.CUSTOM,
            EqPresetId.USER_SETTING1,
            EqPresetId.USER_SETTING2 -> preset
            else -> EqPresetId.CUSTOM
        }

    private fun mergedClearBassRawSteps(
        config: EqDeviceConfig,
        currentRawSteps: List<Int>,
        level: Int,
    ): List<Int> {
        val bandCount = config.bandCount.takeIf { it > 0 } ?: currentRawSteps.size.coerceAtLeast(1)
        val rawSteps = if (currentRawSteps.size == bandCount) {
            currentRawSteps.toMutableList()
        } else {
            MutableList(bandCount) { EqProtocolEngine.BAND_STEP_CENTER }
        }
        rawSteps[0] = clearBassDisplayStepToRaw(level)
        return rawSteps
    }

    private fun clearBassDisplayStepToRaw(level: Int): Int =
        (level.coerceIn(-10, 10) + EqProtocolEngine.BAND_STEP_CENTER).coerceIn(0, 255)

    override fun buildRefreshNoiseControlCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        buildList {
            val codec = codecFor(profile, HeadphoneFeature.NOISE_CONTROL)
            profile.capabilities.noiseControlQueryTypes.forEach { type ->
                val bytes = if (profile.capabilities.queryNoiseControlParams) {
                    codec.buildGetNcAsmParam(type)
                } else {
                    codec.buildGetNcAsmStatus(type)
                }
                bytes?.let {
                    val label = if (type == NcAsmInquiredType.V1_TABLE_SET1_NC_ASM) {
                        "GET NC/ASM param V1"
                    } else if (profile.capabilities.queryNoiseControlParams) {
                        "GET NC/ASM param $type"
                    } else {
                        "GET NC/ASM status $type"
                    }
                    add(command(profile, HeadphoneFeature.NOISE_CONTROL, label, it))
                }
            }
        }

    override fun buildRefreshEqCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> {
        val engine = EqProtocolEngine(profile.capabilities.eqConfig, codecFor(profile, HeadphoneFeature.EQ))
        return engine.buildRefreshCommands { label, bytes ->
            command(profile, HeadphoneFeature.EQ, label, bytes)
        }
    }

    override fun buildRefreshBatteryCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        if (!profile.supports(HeadphoneFeature.BATTERY)) {
            emptyList()
        } else {
            val codec = codecFor(profile, HeadphoneFeature.BATTERY)
            profile.capabilities.batteryQueries.mapNotNull {
                codec.buildGetBatteryStatus(it)?.let { bytes ->
                    command(profile, HeadphoneFeature.BATTERY, "GET battery $it", bytes)
                }
            }
        }

    override fun buildRefreshGestureOperationsCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        if (!profile.supports(HeadphoneFeature.GESTURE_OPERATIONS)) {
            emptyList()
        } else {
            val codec = codecFor(profile, HeadphoneFeature.GESTURE_OPERATIONS)
            val type = profile.capabilities.gestureSettingsType
            listOfNotNull(
                codec.buildGetAssignableSettingsCapability(type)?.let {
                    command(profile, HeadphoneFeature.GESTURE_OPERATIONS, "GET gesture capability", it)
                },
                codec.buildGetAssignableSettingsStatus(type)?.let {
                    command(profile, HeadphoneFeature.GESTURE_OPERATIONS, "GET gesture status", it)
                },
                codec.buildGetAssignableSettingsPresets(type)?.let {
                    command(profile, HeadphoneFeature.GESTURE_OPERATIONS, "GET gesture presets", it)
                },
                codec.buildGetAssignableSettingsExtendedParam(type)?.let {
                    command(profile, HeadphoneFeature.GESTURE_OPERATIONS, "GET gesture mappings", it)
                },
            )
        }

    override fun buildSetQuickAccessFunction(
        profile: ConnectedHeadphoneProfile,
        functionCodes: List<Int>,
    ): List<HeadphoneCommand> = if (!profile.supports(HeadphoneFeature.QUICK_ACCESS)) {
        emptyList()
    } else {
        codecFor(profile, HeadphoneFeature.QUICK_ACCESS)
            .buildSetQuickAccess(functionCodes)
            ?.let {
                listOf(command(profile, HeadphoneFeature.QUICK_ACCESS, "SET Quick Access", it))
            }
            .orEmpty()
    }

    override fun buildSetGesturePresetsCommands(
        profile: ConnectedHeadphoneProfile,
        presets: List<AssignableSettingsPreset>,
    ): List<HeadphoneCommand> = if (!profile.supports(HeadphoneFeature.GESTURE_OPERATIONS)) {
        emptyList()
    } else {
        codecFor(profile, HeadphoneFeature.GESTURE_OPERATIONS)
            .buildSetAssignableSettingsPresets(profile.capabilities.gestureSettingsType, presets)
            ?.let {
                listOf(command(profile, HeadphoneFeature.GESTURE_OPERATIONS, "SET gesture presets", it))
            }
            .orEmpty()
    }

    override fun buildSetGestureMappingsCommands(
        profile: ConnectedHeadphoneProfile,
        mappings: List<AssignableSettingsMapping>,
    ): List<HeadphoneCommand> = if (!profile.supports(HeadphoneFeature.GESTURE_OPERATIONS)) {
        emptyList()
    } else {
        codecFor(profile, HeadphoneFeature.GESTURE_OPERATIONS)
            .buildSetAssignableSettingsExtendedParam(profile.capabilities.gestureSettingsType, mappings)
            ?.let {
                listOf(command(profile, HeadphoneFeature.GESTURE_OPERATIONS, "SET gesture mappings", it))
            }
            .orEmpty()
    }

    override fun buildSetMultipointPairingModeCommands(
        profile: ConnectedHeadphoneProfile,
        inquiry: Boolean,
    ): List<HeadphoneCommand> = if (!profile.supports(HeadphoneFeature.MULTIPOINT)) {
        emptyList()
    } else {
        // SC `x30/b.java` e()/c(): the mode byte alone toggles pairing; the
        // trailing EnableDisable is always ENABLE.
        val mode = if (inquiry) PeripheralBluetoothModeTable2.INQUIRY_SCAN_MODE else PeripheralBluetoothModeTable2.NORMAL_MODE
        listOf(
            command(
                profile,
                HeadphoneFeature.MULTIPOINT,
                if (inquiry) "START multipoint pairing mode" else "STOP multipoint pairing mode",
                SonyTandemV2Table2Protocol.buildSetPeripheralPairingMode(multipointType(profile), mode),
            )
        )
    }

    override fun buildSetSourceSwitchCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
    ): List<HeadphoneCommand> = if (!profile.supports(HeadphoneFeature.MULTIPOINT)) emptyList() else listOf(
        multipointCommand(profile, if (enabled) "ENABLE source switch" else "DISABLE source switch", SonyTandemV2Table2Protocol.buildSetPeripheralSourceSwitch(enabled))
    )

    /**
     * Toggle the "同时连接2台设备" setting: V2 Table1 GS SET_PARAM on the slot
     * discovered via [buildRefreshGeneralSettingMultipointCommands]. Runs on the
     * Table1 channel (HPC/SPP). Empty when the slot is unknown or the protocol
     * isn't V2 Table1.
     */
    override fun buildSetMultipointEnabledCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
    ): List<HeadphoneCommand> {
        val slot = profile.multipointGsSlot ?: return emptyList()
        if (profile.protocolFor(HeadphoneFeature.DEVICE_INFO) != HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1) {
            return emptyList()
        }
        return listOf(
            HeadphoneCommand(
                label = if (enabled) "ENABLE 2-device multipoint" else "DISABLE 2-device multipoint",
                bytes = SonyTandemV2Table1Codec.buildSetGeneralSetting(slot.toByte(), enabled),
                channel = profile.channelFor(HeadphoneFeature.DEVICE_INFO),
            )
        )
    }

    /** V2 Table1 ALERT_SET_PARAM (0x98) FIXED_MESSAGE reply on the GS channel. */
    override fun buildReplyAlertCommand(
        profile: ConnectedHeadphoneProfile,
        messageType: Int,
        positive: Boolean,
    ): List<HeadphoneCommand> {
        if (profile.protocolFor(HeadphoneFeature.DEVICE_INFO) != HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1) {
            return emptyList()
        }
        return listOf(
            HeadphoneCommand(
                label = "REPLY multipoint alert ${if (positive) "POSITIVE" else "NEGATIVE"} ($messageType)",
                bytes = SonyTandemV2Table1Codec.buildReplyAlertFixingMessage(messageType, positive),
                channel = profile.channelFor(HeadphoneFeature.DEVICE_INFO),
            )
        )
    }

    override fun buildSetFixedSourceCommand(
        profile: ConnectedHeadphoneProfile,
        address: String,
    ): List<HeadphoneCommand> = if (!profile.supports(HeadphoneFeature.MULTIPOINT) || address.length != 17) emptyList() else listOf(
        multipointCommand(profile, "FIX source $address", SonyTandemV2Table2Protocol.buildSetPeripheralFixedSource(address))
    )

    override fun buildSetMusicHandOverCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
    ): List<HeadphoneCommand> = if (!profile.supports(HeadphoneFeature.MULTIPOINT)) emptyList() else listOf(
        multipointCommand(profile, if (enabled) "ENABLE music hand-over" else "DISABLE music hand-over", SonyTandemV2Table2Protocol.buildSetPeripheralMusicHandOver(!enabled))
    )

    override fun buildSetMultipointDeviceCommand(
        profile: ConnectedHeadphoneProfile,
        address: String,
        action: MultipointDeviceAction,
    ): List<HeadphoneCommand> = if (!profile.supports(HeadphoneFeature.MULTIPOINT)) {
        emptyList()
    } else {
        val actionType = when (action) {
            MultipointDeviceAction.CONNECT -> ConnectivityActionTypeTable2.CONNECT
            MultipointDeviceAction.DISCONNECT -> ConnectivityActionTypeTable2.DISCONNECT
            MultipointDeviceAction.UNPAIR -> ConnectivityActionTypeTable2.UNPAIR
        }
        listOf(
            command(
                profile,
                HeadphoneFeature.MULTIPOINT,
                "${action.name} multipoint device $address",
                SonyTandemV2Table2Protocol.buildSetPeripheralConnectivity(multipointType(profile), actionType, address),
            )
        )
    }

    private fun buildRefreshMultipointCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> {
        if (profile.protocolFor(HeadphoneFeature.MULTIPOINT) !in setOf(
                HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
                HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2,
            )
        ) return emptyList()
        // SC queries only the type from the capability table (`x30/a.a()`); we
        // learn the type from the first valid reply, so probe both until the
        // headset has answered once, then stick to its type.
        val types = profile.multipointTypeCode
            ?.let { code -> PeripheralInquiredTypeTable2.fromCode(code.toByte()) }
            ?.takeIf { it != PeripheralInquiredTypeTable2.OUT_OF_RANGE }
            ?.let { listOf(it) }
            ?: listOf(
                PeripheralInquiredTypeTable2.PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT,
                PeripheralInquiredTypeTable2.PAIRING_DEVICE_MANAGEMENT_WITH_BT_CLASS_OF_DEVICE,
            )
        return types.flatMap { type ->
            listOf(
                multipointCommand(profile, "GET multipoint capability $type", SonyTandemV2Table2Protocol.buildGetPeripheralCapability(type)),
                multipointCommand(profile, "GET multipoint status $type", SonyTandemV2Table2Protocol.buildGetPeripheralStatus(type)),
                multipointCommand(profile, "GET multipoint devices $type", SonyTandemV2Table2Protocol.buildGetPeripheralParam(type)),
            )
        } + listOf(
            multipointCommand(profile, "GET source switch status", SonyTandemV2Table2Protocol.buildGetPeripheralParam(PeripheralInquiredTypeTable2.SOURCE_SWITCH_CONTROL)),
            multipointCommand(profile, "GET music hand-over status", SonyTandemV2Table2Protocol.buildGetPeripheralParam(PeripheralInquiredTypeTable2.MUSIC_HAND_OVER_SETTING)),
        ) + buildRefreshGeneralSettingMultipointCommands(profile)
    }

    /**
     * V2 Table1 General Setting probes for the "同时连接2台设备" toggle. The GS
     * slot is discovered by GET_CAPABILITY on 0xD1..0xD4 and matched by title
     * ("MULTIPOINT_SETTING") like SC `DeviceCapabilityTableset2.E1()`; once the
     * slot is known, its status/param are read on every refresh. GS is a Table1
     * domain, so the commands ride the Table1 channel (HPC/SPP), not MC.
     */
    private fun buildRefreshGeneralSettingMultipointCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> {
        if (profile.protocolFor(HeadphoneFeature.DEVICE_INFO) != HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1) {
            return emptyList()
        }
        val codec = SonyTandemV2Table1Codec
        val gsChannel = profile.channelFor(HeadphoneFeature.DEVICE_INFO)
        val slot = profile.multipointGsSlot
        return if (slot == null) {
            codec.generalSettingSlots().map { slotCode ->
                HeadphoneCommand(
                    label = "GET GS capability slot 0x%02X".format(slotCode.unsigned),
                    bytes = codec.buildGetGeneralSettingCapability(slotCode),
                    channel = gsChannel,
                )
            }
        } else {
            val type = slot.toByte()
            listOf(
                HeadphoneCommand("GET GS multipoint status", codec.buildGetGeneralSettingStatus(type), gsChannel),
                HeadphoneCommand("GET GS multipoint param", codec.buildGetGeneralSettingParam(type), gsChannel),
            )
        }
    }

    private fun multipointCommand(
        profile: ConnectedHeadphoneProfile,
        label: String,
        bytes: ByteArray,
    ): HeadphoneCommand = HeadphoneCommand(
        label,
        bytes,
        // The profile knows which endpoint this connection actually has; MC does not exist on
        // an LE Audio session, where the headset exposes only the HPC service.
        profile.bindingFor(HeadphoneFeature.MULTIPOINT)?.channel
            ?: profile.defaultResponseChannel(),
    )

    /**
     * AUDIO-domain DSEE / upscaling status query. SC reads the toggle through
     * the same AUDIO_GET_PARAM its switch uses; there is no capability probe for
     * this domain. The inquired type is the one the support-function list chose.
     */
    private fun buildRefreshUpscalingCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> {
        if (profile.protocolFor(HeadphoneFeature.DEVICE_INFO) != HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1) {
            return emptyList()
        }
        val inquiredTypeCode = profile.capabilities.upscalingInquiredTypeCode ?: return emptyList()
        return listOf(
            HeadphoneCommand(
                "GET upscaling",
                codecFor(profile, HeadphoneFeature.DEVICE_INFO)
                    .buildGetUpscaling(inquiredTypeCode.toByte()) ?: return emptyList(),
                profile.channelFor(HeadphoneFeature.UPSCALING),
            ),
        )
    }

    /**
     * ALERT_SET_STATUS (0x94) ENABLE for the alert inquired types SC arms:
     * APP_BECOMES_FOREGROUND on UI shown, FIXED_MESSAGE on device connect.
     * Table1 domain, same channel as GS.
     *
     * SC never registers LE_AUDIO_ALERT_NOTIFICATION: its V2 ALERT_SET_PARAM
     * factory (`bf0.l$b`) dispatches only types 0/1/2/4/6 and throws on type 5 —
     * the app only listens for that notification (`i00.b`), it does not arm it.
     * Arming it here deterministically wedges the headset's HPC ACK state: after
     * this SET, the next DATA frame's ACK repeats stale values forever while
     * payloads keep being processed (observed twice, 19:48:00 / 19:48:15).
     */
    private fun buildRefreshAlertCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> {
        val channel = profile.channelFor(HeadphoneFeature.DEVICE_INFO)
        return listOf(
            HeadphoneCommand(
                "SET alert status APP_BECOMES_FOREGROUND ENABLE",
                SonyTandemV2Table1Codec.buildSetAlertAppBecomesForeground(true),
                channel,
            ),
            HeadphoneCommand(
                "SET alert status FIXED_MESSAGE ENABLE",
                SonyTandemV2Table1Codec.buildSetAlertFixedMessage(true),
                channel,
            ),
        )
    }

    private fun multipointType(profile: ConnectedHeadphoneProfile): PeripheralInquiredTypeTable2 =
        profile.multipointTypeCode
            ?.let { code -> PeripheralInquiredTypeTable2.fromCode(code.toByte()) }
            ?.takeIf {
                it == PeripheralInquiredTypeTable2.PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT ||
                    it == PeripheralInquiredTypeTable2.PAIRING_DEVICE_MANAGEMENT_WITH_BT_CLASS_OF_DEVICE
            }
            ?: PeripheralInquiredTypeTable2.PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT

    override fun buildPowerOffCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        if (!profile.supports(HeadphoneFeature.POWER_OFF)) {
            emptyList()
        } else {
            codecFor(profile, HeadphoneFeature.POWER_OFF).buildPowerOff()?.let {
                listOf(command(profile, HeadphoneFeature.POWER_OFF, "POWER OFF", it))
            }.orEmpty()
        }

    override fun buildSetLeAudioEnabledCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
        changeConnectionMethod: Boolean,
    ): List<HeadphoneCommand> {
        if (!profile.supports(HeadphoneFeature.LEA_STATUS)) return emptyList()
        val lea = profile.capabilities.lea ?: return emptyList()
        if (!lea.controlSupported) return emptyList()
        // The Classic-only setting sender is attached to the main Table1
        // endpoint. LEA history can use Table2/MC for PAS and is independent.
        val controlChannel = runCatching {
            profile.channelFor(HeadphoneFeature.DEVICE_INFO)
        }.getOrNull() ?: return emptyList()

        // Query and control are separate official flows. PAS reads LEA through
        // Table2/MC, while the persistent switch is still Table1 0x48/0x0C.
        return listOf(
            HeadphoneCommand(
                label = "SET LE Audio ${if (enabled) "ENABLE" else "DISABLE"}",
                bytes = SonyTandemV2Table1Codec.buildSetLeAudioEnabled(
                    enabled = enabled,
                    changeConnectionMethod = changeConnectionMethod,
                ),
                channel = controlChannel,
            )
        )
    }

    override fun buildReplyAlertCommand(
        profile: ConnectedHeadphoneProfile,
        alert: ParsedTandemResponse,
        positive: Boolean,
    ): List<HeadphoneCommand> {
        if (profile.protocolFor(HeadphoneFeature.DEVICE_INFO) != HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1) {
            return emptyList()
        }
        val bytes = when (alert) {
            is ParsedTandemResponse.AlertFixedMessage ->
                SonyTandemV2Table1Codec.buildReplyAlertFixingMessage(alert.messageType, positive)
            is ParsedTandemResponse.AlertForegroundMessage ->
                SonyTandemV2Table1Codec.buildReplyAlertForegroundMessage(alert.messageType, positive)
            is ParsedTandemResponse.AlertFixedMessageWithLeftRightSelection ->
                SonyTandemV2Table1Codec.buildReplyAlertFixedMessageWithLeftRightSelection(
                    alert.messageType,
                    if (positive) alert.defaultSelectedSide else 0,
                )
            is ParsedTandemResponse.AlertFlexibleMessage ->
                SonyTandemV2Table1Codec.buildReplyAlertFlexibleMessage(alert.messageType, positive)
            else -> return emptyList()
        }
        return listOf(HeadphoneCommand("REPLY Sony alert", bytes, profile.channelFor(HeadphoneFeature.DEVICE_INFO)))
    }

    override fun buildRefreshLeaPairedHistoryCommands(
        profile: ConnectedHeadphoneProfile,
    ): List<HeadphoneCommand> {
        if (!profile.supports(HeadphoneFeature.LEA_STATUS)) return emptyList()
        val lea = profile.capabilities.lea ?: return emptyList()
        if (lea.kind == LeaDeviceKind.PAS_CTKD) {
            return listOf(
                command(
                    profile,
                    HeadphoneFeature.LEA_STATUS,
                    "GET LEA endpoint addresses PAS",
                    SonyTandemV2Table2Protocol.buildGetLeaCapability(
                        dev.sonypods.protocol.LeaInquiredTypeTable2.PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
                    ),
                ).copy(channel = lea.historyChannel),
                command(
                    profile,
                    HeadphoneFeature.LEA_STATUS,
                    "GET LEA paired history PAS",
                    SonyTandemV2Table2Protocol.buildGetLeaParam(
                        dev.sonypods.protocol.LeaInquiredTypeTable2.PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
                    ),
                ).copy(channel = lea.historyChannel)
            )
        }
        val codec = codecFor(profile, HeadphoneFeature.LEA_STATUS)
        val type = LeaInquiredType.entries.firstOrNull {
            it.code.toInt().and(0xFF) == lea.historyInquiredTypeCode
        } ?: return emptyList()
        return codec.buildGetLeaPairedHistory(type)?.let { bytes ->
            listOf(
                command(
                    profile,
                    HeadphoneFeature.LEA_STATUS,
                    "GET LEA paired history $type",
                    bytes,
                ).copy(channel = lea.historyChannel)
            )
        }.orEmpty()
    }

    override fun buildRefreshPlaybackCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> {
        if (!profile.supports(HeadphoneFeature.PLAYBACK_CONTROL)) return emptyList()
        val codec = codecFor(profile, HeadphoneFeature.PLAYBACK_CONTROL)
        val type = profile.capabilities.playbackControlType
        return buildList {
            // Capability first: the volume step count lives only in this reply, and
            // the connection-time probe is skipped entirely on a capability-cache
            // hit — without this the volume row never appears on reconnect.
            codec.buildGetPlayCapability(type)?.let {
                add(command(profile, HeadphoneFeature.PLAYBACK_CONTROL, "GET playback capability", it))
            }
            codec.buildGetPlaybackStatus(type)?.let {
                add(command(profile, HeadphoneFeature.PLAYBACK_CONTROL, "GET playback status", it))
            }
            codec.buildGetPlaybackMetadata(type).forEachIndexed { index, bytes ->
                add(command(profile, HeadphoneFeature.PLAYBACK_CONTROL, "GET playback metadata #$index", bytes))
            }
            codec.buildGetPlaybackVolume(volumeType(profile))?.let {
                add(command(profile, HeadphoneFeature.PLAYBACK_CONTROL, "GET playback volume", it))
            }
        }
    }

    override fun buildSetPlaybackVolumeCommands(profile: ConnectedHeadphoneProfile, volume: Int): List<HeadphoneCommand> =
        if (!profile.supports(HeadphoneFeature.PLAYBACK_CONTROL)) {
            emptyList()
        } else {
            codecFor(profile, HeadphoneFeature.PLAYBACK_CONTROL)
                .buildSetPlaybackVolume(volume, volumeType(profile))
                ?.let { listOf(command(profile, HeadphoneFeature.PLAYBACK_CONTROL, "SET playback volume $volume", it)) }
                .orEmpty()
        }

    private fun volumeType(profile: ConnectedHeadphoneProfile): PlayInquiredType =
        if (profile.capabilities.playbackVolumeHasMute) {
            PlayInquiredType.MUSIC_VOLUME_WITH_MUTE
        } else {
            PlayInquiredType.MUSIC_VOLUME
        }

    private fun buildRefreshLeaCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> {
        val lea = profile.capabilities.lea ?: return emptyList()
        val historyCommands = if (lea.kind == LeaDeviceKind.PAS_CTKD) {
            listOf(
                command(profile, HeadphoneFeature.LEA_STATUS, "GET LEA status PAS",
                    SonyTandemV2Table2Protocol.buildGetLeaStatus(
                        dev.sonypods.protocol.LeaInquiredTypeTable2.PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
                    )).copy(channel = lea.historyChannel),
                command(profile, HeadphoneFeature.LEA_STATUS, "GET LEA paired history PAS",
                    SonyTandemV2Table2Protocol.buildGetLeaParam(
                        dev.sonypods.protocol.LeaInquiredTypeTable2.PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
                    )).copy(channel = lea.historyChannel),
            )
        } else {
            val type = LeaInquiredType.entries.firstOrNull {
                it.code.toInt().and(0xFF) == lea.historyInquiredTypeCode
            } ?: return emptyList()
            val codec = codecFor(profile, HeadphoneFeature.LEA_STATUS)
            listOf(
                codec.buildGetLeaStatus(type)?.let {
                    command(profile, HeadphoneFeature.LEA_STATUS, "GET LEA status $type", it)
                        .copy(channel = lea.historyChannel)
                },
                codec.buildGetLeaPairedHistory(type)?.let {
                    command(profile, HeadphoneFeature.LEA_STATUS, "GET LEA paired history $type", it)
                        .copy(channel = lea.historyChannel)
                },
            ).filterNotNull()
        }

        if (!lea.controlSupported) return historyCommands
        val controlChannel = lea.controlChannel
            ?: runCatching { profile.channelFor(HeadphoneFeature.DEVICE_INFO) }.getOrNull()
            ?: return historyCommands
        val controlCodec = TandemCodecRegistry.codecFor(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1)
        return historyCommands + listOf(
            HeadphoneCommand(
                "GET LE Audio setting availability",
                controlCodec.buildGetLeAudioSettingAvailability() ?: return historyCommands,
                controlChannel,
            ),
            HeadphoneCommand(
                "GET LE Audio setting",
                controlCodec.buildGetLeAudioSetting() ?: return historyCommands,
                controlChannel,
            ),
        )
    }

    override fun buildPlaybackCommands(profile: ConnectedHeadphoneProfile, control: PlaybackControl): List<HeadphoneCommand> =
        codecFor(profile, HeadphoneFeature.PLAYBACK_CONTROL)
            .buildPlayback(control, profile.capabilities.playbackControlType)
            ?.let { listOf(command(profile, HeadphoneFeature.PLAYBACK_CONTROL, "PLAYBACK ${control.name}", it)) }
            .orEmpty()

    override fun parse(
        profile: ConnectedHeadphoneProfile,
        channel: TandemChannel,
        raw: ByteArray,
    ): ParsedTandemResponse {
        if (raw.firstOrNull() == DATA_MDR_NO2) {
            val variant = table2VariantForResponse(profile, channel)
            return TandemCodecRegistry.codecFor(variant).parse(raw)
        }
        val normalized = if (raw.firstOrNull() == DATA_MDR) raw else byteArrayOf(DATA_MDR) + raw
        val command = normalized.getOrNull(1) ?: return ParsedTandemResponse.Unknown(null, null, byteArrayOf(), raw)
        val payload = normalized.drop(2).toByteArray()
        val binding = bindingForResponse(profile, channel, command, payload)
        return TandemCodecRegistry.codecFor(binding.variant).parse(raw).let { parsed ->
            if (parsed !is ParsedTandemResponse.Unknown) {
                parsed
            } else if (
                binding.feature == HeadphoneFeature.DEVICE_INFO &&
                profile.protocolFor(HeadphoneFeature.DEVICE_INFO) == HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1
            ) {
                codecFor(profile, HeadphoneFeature.DEVICE_INFO).parse(raw)
            } else {
                parsed
            }
        }
    }

    private fun table2VariantForResponse(
        profile: ConnectedHeadphoneProfile,
        channel: TandemChannel,
    ): HeadphoneProtocolVariant =
        when (channel) {
            TandemChannel.GATT_V2_MC -> HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2
            TandemChannel.GATT_V1_MC -> HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2
            TandemChannel.GATT_V2_HPC,
            TandemChannel.SPP_MDR -> {
                val profileVariants = profile.featureBindings.values.map { it.variant }.toSet()
                when {
                    profileVariants.any { it == HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1 || it == HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2 } ->
                        HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2
                    profileVariants.any { it == HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1 || it == HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2 } ->
                        HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2
                    else -> HeadphoneProtocolVariant.UNKNOWN
                }
            }
        }

    private fun bindingForResponse(
        profile: ConnectedHeadphoneProfile,
        channel: TandemChannel,
        command: Byte,
        payload: ByteArray,
    ): FeatureProtocolBinding {
        if (channel == TandemChannel.GATT_V2_MC) {
            return FeatureProtocolBinding(
                feature = HeadphoneFeature.DEVICE_INFO,
                variant = HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2,
                channel = channel,
            )
        }
        if (channel == TandemChannel.GATT_V1_MC && isV1Table2Command(command)) {
            return FeatureProtocolBinding(
                feature = HeadphoneFeature.DEVICE_INFO,
                variant = HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2,
                channel = channel,
            )
        }
        val feature = classifyCommand(command, payload)
        return profile.bindingFor(feature) ?: FeatureProtocolBinding(
            feature = feature,
            variant = variantForChannel(channel, command),
            channel = channel,
        )
    }

    private fun isV1Table2Command(command: Byte): Boolean {
        val code = command.toInt() and 0xFF
        return code in 0x30..0x49
    }

    private fun variantForChannel(channel: TandemChannel, command: Byte): HeadphoneProtocolVariant =
        when (channel) {
            TandemChannel.GATT_V2_HPC -> HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1
            TandemChannel.GATT_V2_MC -> HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2
            TandemChannel.GATT_V1_MC -> if (isV1Table2Command(command)) {
                HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2
            } else {
                HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1
            }
            TandemChannel.SPP_MDR -> HeadphoneProtocolVariant.UNKNOWN
        }

    private fun classifyCommand(command: Byte, payload: ByteArray = byteArrayOf()): HeadphoneFeature = when (command) {
        COMMON_RET_BATTERY_LEVEL -> HeadphoneFeature.BATTERY
        COMMON_NTFY_BATTERY_LEVEL -> classify0x13(payload)
        POWER_RET_STATUS, POWER_NTFY_STATUS -> HeadphoneFeature.BATTERY
        EQEBB_GET_STATUS, EQEBB_RET_STATUS, EQEBB_NTFY_STATUS,
        EQEBB_GET_PARAM, EQEBB_RET_PARAM, EQEBB_SET_PARAM,
        EQEBB_NTFY_PARAM, EQEBB_GET_EXTENDED_INFO,
        EQEBB_RET_EXTENDED_INFO -> HeadphoneFeature.EQ
        NCASM_GET_STATUS, NCASM_RET_STATUS, NCASM_NTFY_STATUS,
        NCASM_GET_PARAM, NCASM_RET_PARAM, NCASM_SET_PARAM,
        NCASM_NTFY_PARAM -> HeadphoneFeature.NOISE_CONTROL
        PLAY_RET_STATUS, PLAY_NTFY_STATUS -> HeadphoneFeature.PLAYBACK_CONTROL
        SYSTEM_RET_CAPABILITY,
        SYSTEM_RET_STATUS,
        SYSTEM_NTFY_STATUS,
        SYSTEM_RET_PARAM,
        SYSTEM_NTFY_PARAM,
        SYSTEM_RET_EXT_PARAM,
        SYSTEM_NTFY_EXT_PARAM -> classifySystemResponse(payload)
        else -> HeadphoneFeature.DEVICE_INFO
    }

    private fun classifySystemResponse(payload: ByteArray): HeadphoneFeature =
        when (payload.firstOrNull()?.toInt()?.and(0xFF)) {
            0x03, 0x0E -> HeadphoneFeature.GESTURE_OPERATIONS
            0x0D -> HeadphoneFeature.QUICK_ACCESS
            0x06 -> HeadphoneFeature.WEARING_STATUS
            else -> HeadphoneFeature.DEVICE_INFO
        }

    /**
     * 0x13 is overloaded: V2 COMMON_RET_STATUS and V1 COMMON_NTFY_BATTERY_LEVEL.
     * CommonInquiredType codes 0x00-0x06 overlap with PowerInquiredType codes.
     * Non-overlapping codes are routed directly; for overlapping codes we inspect
     * payload shape to decide whether it looks like a V1 battery response.
     */
    private fun classify0x13(payload: ByteArray): HeadphoneFeature {
        val firstPayloadByte = payload.firstOrNull() ?: return HeadphoneFeature.DEVICE_INFO
        val isCommonType = CommonInquiredType.entries.any { it.code == firstPayloadByte }
        val isPowerType = PowerInquiredType.entries.any { it.code == firstPayloadByte }

        return when {
            isCommonType && !isPowerType -> HeadphoneFeature.DEVICE_INFO
            isPowerType && !isCommonType -> HeadphoneFeature.BATTERY
            isPowerType && isCommonType -> {
                if (looksLikeV1BatteryPayload(payload)) HeadphoneFeature.BATTERY
                else HeadphoneFeature.DEVICE_INFO
            }
            else -> HeadphoneFeature.DEVICE_INFO
        }
    }

    private fun looksLikeV1BatteryPayload(payload: ByteArray): Boolean {
        val type = payload.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        return when (type) {
            0x00, 0x02 -> {
                payload.size == 2 && payload[1].isBatteryPercentage()
            }
            0x01 -> {
                payload.size == 4 &&
                    payload[1].isBatteryPercentage() &&
                    payload[2].toInt().and(0xFF) == 0x00 &&
                    payload[3].isBatteryPercentage()
            }
            0x04, 0x06 -> false
            else -> false
        }
    }

    private fun Byte.isBatteryPercentage(): Boolean {
        val v = this.toInt() and 0xFF
        return v in 0..100 || v == 0xFF
    }

}
