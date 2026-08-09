package dev.sonypods.headphones

import dev.sonypods.ble.DiscoveredSonyDevice
import dev.sonypods.protocol.AmbientSoundMode
import dev.sonypods.protocol.CommonInquiredType
import dev.sonypods.protocol.DeviceInfoType
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.LeaInquiredType
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.PlaybackControl
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.AssignableSettingsMapping
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.ConnectivityActionTypeTable2
import dev.sonypods.protocol.PeripheralBluetoothModeTable2
import dev.sonypods.protocol.PeripheralInquiredTypeTable2
import dev.sonypods.protocol.SonyTandemV2Table2Protocol
import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR
import dev.sonypods.protocol.SonyTandemConstants.DATA_MDR_NO2

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
        if (variant == profile.protocolFor(HeadphoneFeature.DEVICE_INFO)) return profile
        return rebindProfile(profile, variant)
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
    ): ConnectedHeadphoneProfile {
        val newMap = profile.featureProtocolMap.mapValues { variant }
        return profile.copy(
            featureProtocolMap = newMap,
            featureBindings = buildFeatureBindings(newMap, profile.capabilities),
        ).rebounded()
    }

    override fun buildRefreshCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        buildList {
            val deviceInfoCodec = codecFor(profile, HeadphoneFeature.DEVICE_INFO)
            // Unconditional first exchange (SC C29903d/C30916e): the runtime
            // protocol version from RET_PROTOCOL_INFO drives the whitelist check.
            deviceInfoCodec.buildGetProtocolInfo()?.let {
                add(command(profile, HeadphoneFeature.DEVICE_INFO, "GET protocol info", it))
            }
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
        }

    override fun canWrite(profile: ConnectedHeadphoneProfile, feature: HeadphoneFeature): Boolean =
        when (feature) {
            HeadphoneFeature.NOISE_CONTROL,
            HeadphoneFeature.AMBIENT_LEVEL,
            HeadphoneFeature.AMBIENT_VOICE_MODE ->
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
    ): List<HeadphoneCommand> {
        val level = ambientLevel.coerceIn(1, 20)
        val codec = codecFor(profile, HeadphoneFeature.NOISE_CONTROL)

        profile.capabilities.writableNoiseControlTypes.forEach { type ->
            val bytes = codec.buildSetNoiseControlMode(type, mode, level, ambientMode) ?: return@forEach
            return listOf(
                command(
                    profile,
                    HeadphoneFeature.NOISE_CONTROL,
                    noiseControlWriteLabel(type, mode, level, ambientMode),
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
    ): String {
        val voice = ambientMode == AmbientSoundMode.VOICE
        return when (type) {
            NcAsmInquiredType.V1_TABLE_SET1_NC_ASM ->
                "SET NC/ASM V1 table1 mode $mode level=$level voice=$voice"
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS ->
                "SET NC/ASM 0x14 mode $mode level=$level voice=$voice"
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
        )
    }

    private fun multipointCommand(
        profile: ConnectedHeadphoneProfile,
        label: String,
        bytes: ByteArray,
    ): HeadphoneCommand = HeadphoneCommand(label, bytes, TandemChannel.GATT_V2_MC)

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

    override fun buildRefreshPlaybackCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        codecFor(profile, HeadphoneFeature.PLAYBACK_CONTROL)
            .buildGetPlaybackStatus(profile.capabilities.playbackControlType)
            ?.let { listOf(command(profile, HeadphoneFeature.PLAYBACK_CONTROL, "GET playback status", it)) }
            .orEmpty()

    private fun buildRefreshLeaCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        LeaInquiredType.entries.flatMap { type ->
            val codec = codecFor(profile, HeadphoneFeature.LEA_STATUS)
            listOf(
                codec.buildGetLeaStatus(type)?.let {
                    command(profile, HeadphoneFeature.LEA_STATUS, "GET LEA status $type", it)
                },
                codec.buildGetLeaPairedHistory(type)?.let {
                    command(profile, HeadphoneFeature.LEA_STATUS, "GET LEA paired history $type", it)
                },
            ).filterNotNull()
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
