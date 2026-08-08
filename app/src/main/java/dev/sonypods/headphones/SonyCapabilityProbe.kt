package dev.sonypods.headphones

import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.PlayInquiredType
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.SonySupportedFunction
import dev.sonypods.protocol.SonyTable
import dev.sonypods.protocol.SonyV1FunctionType
import dev.sonypods.protocol.SonyV2FunctionType
import dev.sonypods.protocol.SystemInquiredType
import dev.sonypods.config.FunctionCode

/**
 * Connection-time dynamic capability probing, mirroring Sound Connect 13.2.1
 * (`C29903d` V1 / `C30916e` V2 sequencers): after RET_SUPPORT_FUNCTION lists the
 * model's authoritative FunctionTypes, each known type is mapped to its domain
 * GET_CAPABILITY probe (NCASM/EQEBB/PLAY/...), and the engine derives its
 * feature/query/writable sets from the FunctionType list that triggered the
 * probes. Unknown FunctionTypes are skipped, never failing.
 *
 * This is the replacement for per-model static profile decisions: a model is no
 * longer judged by its name, but by what it tells us it supports.
 */
object SonyCapabilityProbe {

    /** The single CONNECT_GET_SUPPORT_FUNCTION command that starts a probe. */
    fun buildGetSupportFunctionCommand(profile: ConnectedHeadphoneProfile): HeadphoneCommand =
        HeadphoneCommand(
            label = "GET support function",
            bytes = requireNotNull(
                TandemCodecRegistry.codecFor(profile.protocolFor(HeadphoneFeature.DEVICE_INFO))
                    .buildGetSupportFunction()
            ) { "Codec ${profile.protocolFor(HeadphoneFeature.DEVICE_INFO)} has no support-function probe" },
            channel = profile.channelFor(HeadphoneFeature.DEVICE_INFO),
        )

    fun buildGetSupportFunctionCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        listOf(buildGetSupportFunctionCommand(profile))

    /**
     * The single CONNECT_GET_CAPABILITY_INFO command. Its response
     * (CONNECT_RET_CAPABILITY_INFO 0x03) carries the device's capability counter
     * and identifier; a counter matching the persisted value means the cached
     * capability tableset is still valid and the per-domain probe below can be
     * omitted (SC `C29903d.m109368F` / `C30916e`).
     */
    fun buildGetCapabilityInfoCommand(profile: ConnectedHeadphoneProfile): HeadphoneCommand =
        HeadphoneCommand(
            label = "GET capability info",
            bytes = requireNotNull(
                TandemCodecRegistry.codecFor(profile.protocolFor(HeadphoneFeature.DEVICE_INFO))
                    .buildGetCapabilityInfo()
            ) { "Codec ${profile.protocolFor(HeadphoneFeature.DEVICE_INFO)} has no capability-info request" },
            channel = profile.channelFor(HeadphoneFeature.DEVICE_INFO),
        )

    /**
     * Map a FunctionType list to the ordered domain GET_CAPABILITY probes SC
     * would send. Each returns exactly the probes for FunctionTypes the engine
     * recognises; everything else is skipped.
     */
    fun buildCapabilityProbeCommands(
        profile: ConnectedHeadphoneProfile,
        functions: List<SonySupportedFunction>,
    ): List<HeadphoneCommand> {
        val codec = TandemCodecRegistry.codecFor(profile.protocolFor(HeadphoneFeature.NOISE_CONTROL))
        val deviceInfoCodec = TandemCodecRegistry.codecFor(profile.protocolFor(HeadphoneFeature.DEVICE_INFO))
        return buildList {
            for (function in functions) {
                when (function.domain(profile)) {
                    ProbeDomain.NCASM -> if (function.isV1(profile)) {
                        codec.buildGetNcAsmCapability(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM)?.let { bytes ->
                            add(
                                HeadphoneCommand(
                                    label = "GET NCASM capability V1",
                                    bytes = bytes,
                                    channel = profile.channelFor(HeadphoneFeature.NOISE_CONTROL),
                                )
                            )
                        }
                    } else {
                        function.v2Type()?.let { v2 ->
                            ncAsmInquiredFor(v2)?.let { inquired ->
                                codec.buildGetNcAsmCapability(inquired)?.let { bytes ->
                                    add(
                                        HeadphoneCommand(
                                            label = "GET NCASM capability ${v2.name}",
                                            bytes = bytes,
                                            channel = profile.channelFor(HeadphoneFeature.NOISE_CONTROL),
                                        )
                                    )
                                }
                            }
                        }
                    }

                    ProbeDomain.EQEBB -> function.eqEbbInquired(profile)?.let { inquired ->
                        codec.buildGetEqEbbCapability(inquired)?.let { bytes ->
                            add(
                                HeadphoneCommand(
                                    label = "GET EQEBB capability ${inquired.name}",
                                    bytes = bytes,
                                    channel = profile.channelFor(HeadphoneFeature.EQ),
                                )
                            )
                        }
                        // EQ band geometry only comes from extended info, not the
                        // capability blob; probe it alongside (SC `gf0` also reads
                        // extended info after capability in its EQ setup).
                        codec.buildGetEqEbbExtendedInfo(inquired)?.let { bytes ->
                            add(
                                HeadphoneCommand(
                                    label = "GET EQEBB extended ${inquired.name}",
                                    bytes = bytes,
                                    channel = profile.channelFor(HeadphoneFeature.EQ),
                                )
                            )
                        }
                    }

                    ProbeDomain.PLAY -> function.playInquired(profile)?.let { inquired ->
                        codec.buildGetPlayCapability(inquired)?.let { bytes ->
                            add(
                                HeadphoneCommand(
                                    label = "GET PLAY capability ${inquired.name}",
                                    bytes = bytes,
                                    channel = profile.channelFor(HeadphoneFeature.PLAYBACK_CONTROL),
                                )
                            )
                        }
                    }

                    ProbeDomain.BATTERY -> function.batteryInquired(profile)?.let { power ->
                        deviceInfoCodec.buildGetBatteryStatus(power)?.let { bytes ->
                            add(
                                HeadphoneCommand(
                                    label = "GET battery ${power.name}",
                                    bytes = bytes,
                                    channel = profile.channelFor(HeadphoneFeature.BATTERY),
                                )
                            )
                        }
                    }

                    ProbeDomain.SYSTEM -> function.systemInquired(profile)?.let { system ->
                        val systemCodec = codec as? SonyTandemV1Table1Codec
                        systemCodec?.buildGetSystemCapability(system)?.let { bytes ->
                            add(
                                HeadphoneCommand(
                                    label = "GET SYSTEM capability ${system.name}",
                                    bytes = bytes,
                                    channel = systemCodec.defaultChannel,
                                )
                            )
                        }
                    }

                    // LEA state is read via GET_STATUS in refresh, not via a
                    // capability probe, so no command is emitted here.
                    ProbeDomain.LEA -> Unit

                    ProbeDomain.NONE -> Unit
                }
            }
        }
    }

    /**
     * Derive a capability set purely from the FunctionType list. This is the
     * authoritative, per-model answer; the RET_CAPABILITY blobs recorded during
     * the probe are kept as evidence but the feature/query/writable sets here
     * drive the UI and the write paths.
     */
    fun capabilitiesFromFunctions(
        functions: List<SonySupportedFunction>,
        fallback: HeadphoneCapabilities,
        transport: HeadphoneTransport = HeadphoneTransport.UNKNOWN,
        profile: ConnectedHeadphoneProfile? = null,
    ): HeadphoneCapabilities {
        val features = mutableSetOf(HeadphoneFeature.DEVICE_INFO)
        val batteryQueries = mutableListOf<PowerInquiredType>()
        val noiseQueries = mutableListOf<NcAsmInquiredType>()
        val writableNoise = mutableSetOf<NcAsmInquiredType>()
        val eqTypes = mutableSetOf<EqEbbInquiredType>()
        val playTypes = mutableSetOf<PlayInquiredType>()

        for (function in functions) {
            when (function.domain(profile)) {
                ProbeDomain.NCASM -> if (function.isV1(profile)) {
                    noiseQueries.add(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM)
                    writableNoise.add(NcAsmInquiredType.V1_TABLE_SET1_NC_ASM)
                } else {
                    function.v2Type()?.let { v2 ->
                        ncAsmInquiredFor(v2)?.let { inquired ->
                            noiseQueries.add(inquired)
                            writableNoise.add(inquired)
                        }
                    }
                }

                ProbeDomain.EQEBB -> {
                    function.eqEbbInquired(profile)?.let {
                        eqTypes.add(it)
                        features.add(HeadphoneFeature.EQ)
                        if (it == EqEbbInquiredType.EBB) {
                            features.add(HeadphoneFeature.CLEAR_BASS)
                        }
                    }
                }

                ProbeDomain.PLAY -> function.playInquired(profile)?.let {
                    playTypes.add(it)
                    features.add(HeadphoneFeature.PLAYBACK_CONTROL)
                }

                ProbeDomain.BATTERY -> function.batteryInquired(profile)?.let {
                    batteryQueries.add(it)
                    features.add(HeadphoneFeature.BATTERY)
                }

                ProbeDomain.SYSTEM -> {
                    when (function.systemInquired(profile)) {
                        SystemInquiredType.WEARING_STATUS_DETECTOR ->
                            features.add(HeadphoneFeature.WEARING_STATUS)
                        SystemInquiredType.QUICK_ACCESS ->
                            features.add(HeadphoneFeature.QUICK_ACCESS)
                        else -> Unit
                    }
                }

                ProbeDomain.LEA -> {
                    features.add(HeadphoneFeature.LEA_STATUS)
                }

                ProbeDomain.NONE -> Unit
            }
        }

        if (noiseQueries.isNotEmpty()) {
            features.add(HeadphoneFeature.NOISE_CONTROL)
            // V1's single NC/ASM type (0x02) carries both the ambient level and
            // the focus-on-voice flag in its SET_PARAM layout.
            if (NcAsmInquiredType.V1_TABLE_SET1_NC_ASM in noiseQueries) {
                features.add(HeadphoneFeature.AMBIENT_LEVEL)
                features.add(HeadphoneFeature.AMBIENT_VOICE_MODE)
            } else {
                if (noiseQueries.any { it in AMBIENT_LEVEL_TYPES }) {
                    features.add(HeadphoneFeature.AMBIENT_LEVEL)
                }
                // V2 seamless types whose SET_PARAM layout carries the
                // focus-on-voice byte (0x14/0x17/0x19) expose voice mode too.
                if (noiseQueries.any { it in AMBIENT_VOICE_TYPES }) {
                    features.add(HeadphoneFeature.AMBIENT_VOICE_MODE)
                }
            }
        }

        val eqConfig = if (eqTypes.isNotEmpty()) {
            val writeType = preferredEqWriteType(eqTypes)
            EqDeviceConfig(
                availablePresets = fallback.eqConfig.availablePresets
                    .ifEmpty { DEFAULT_PRESETS },
                writeInquiredType = writeType,
                statusQueryTypes = eqTypes.toList(),
                paramQueryTypes = eqTypes.toList(),
                extendedInfoQueryTypes = listOf(writeType),
                bandCount = fallback.eqConfig.bandCount,
                hasClearBass = fallback.eqConfig.hasClearBass || eqTypes.contains(EqEbbInquiredType.EBB),
                clearBassWriteMode = if (EqEbbInquiredType.PRESET_EQ in eqTypes) {
                    // Capture evidence (LinkBuds S / WH-1000XM4): the standard EQ
                    // path writes Clear Bass by resending the PRESET_EQ bands,
                    // even when a standalone EBB type is advertised.
                    ClearBassWriteMode.PRESET_EQ_BANDS
                } else if (eqTypes.contains(EqEbbInquiredType.EBB)) {
                    ClearBassWriteMode.EBB_PARAM
                } else {
                    ClearBassWriteMode.PRESET_EQ_BANDS
                },
            )
        } else {
            fallback.eqConfig
        }

        return fallback.copy(
            features = features + (fallback.features - fallbackOnlyFeatures) ,
            formFactor = formFactorFromBattery(batteryQueries),
            batteryQueries = batteryQueries.ifEmpty { fallback.batteryQueries },
            noiseControlQueryTypes = noiseQueries.distinct().ifEmpty { fallback.noiseControlQueryTypes },
            writableNoiseControlTypes = preferDualWriteTypes(writableNoise)
                .ifEmpty { fallback.writableNoiseControlTypes },
            eqConfig = eqConfig,
            playbackControlType = playTypes.firstOrNull() ?: fallback.playbackControlType,
        )
    }

    /**
     * Form factor mirrors Sound Connect's `DeviceCapabilityTableset.t()`
     * (BatterySupportType): the sole discriminator is whether the device advertises
     * a LEFT/RIGHT (or crate/bud capsule) battery function. Any model without one
     * is a single-battery over-ear/neck headset — SC's unconditional `SINGLE_BATTERY`
     * default. There is no "UNKNOWN" fallback: the absence of a dual-bud battery
     * evidence is itself the headset signal.
     */
    private fun formFactorFromBattery(batteryQueries: List<PowerInquiredType>): HeadphoneFormFactor = when {
        PowerInquiredType.LEFT_RIGHT_BATTERY in batteryQueries -> HeadphoneFormFactor.TRUE_WIRELESS
        PowerInquiredType.CRADLE_BATTERY in batteryQueries -> HeadphoneFormFactor.TRUE_WIRELESS
        else -> HeadphoneFormFactor.HEADSET
    }

    /** A probe-derived profile, or null when the probe supplied nothing new. */
    fun applyToProfile(
        profile: ConnectedHeadphoneProfile,
        functions: List<SonySupportedFunction>,
        transport: HeadphoneTransport,
    ): ConnectedHeadphoneProfile {
        val capabilities = capabilitiesFromFunctions(functions, profile.capabilities, transport, profile)
        return profile.copy(
            capabilities = capabilities,
            playbackDispatchStrategy = if (HeadphoneFeature.PLAYBACK_CONTROL in capabilities.features) {
                PlaybackDispatchStrategy.TANDEM_FIRST
            } else {
                profile.playbackDispatchStrategy
            },
            protocolEvidence = profile.protocolEvidence +
                listOf("probe:ret-support-function(${functions.size})") +
                functions.map { "probe:${it.domain(profile).name}:0x%02X".format(it.code.toInt() and 0xFF) },
        )
    }

    /**
     * Reconstruct the ordered [SonySupportedFunction] list from persisted
     * [FunctionCode]s for a given profile. Codes are interpreted against the
     * profile's protocol generation exactly as the live probe does (V1 and V2
     * byte codes collide, so the generation decides the enum table). Unknown
     * codes are dropped. Used to restore a probe-derived profile from the
     * capability-probe cache without re-running the probe.
     */
    fun restoreFunctions(
        profile: ConnectedHeadphoneProfile,
        codes: List<FunctionCode>,
    ): List<SonySupportedFunction> {
        if (codes.isEmpty()) return emptyList()
        val v1 = listOf(
            profile.protocolFor(HeadphoneFeature.NOISE_CONTROL),
            profile.protocolFor(HeadphoneFeature.EQ),
            profile.protocolFor(HeadphoneFeature.BATTERY),
        ).any { it.name.startsWith("SONY_TANDEM_V1") }
        return codes.mapNotNull { fc ->
            val byte = fc.code.toByte()
            if (v1) {
                SonyV1FunctionType.fromByteCode(byte).takeIf { it != SonyV1FunctionType.OUT_OF_RANGE }
                    ?.let { SonySupportedFunction(it.code, fc.order) }
            } else {
                SonyV2FunctionType.fromByteCode(SonyTable.NO_1, byte)
                    .takeIf { it != SonyV2FunctionType.OUT_OF_RANGE }
                    ?.let { SonySupportedFunction(it.code, fc.order) }
            }
        }
    }

    // ── FunctionType → domain & inquired-type mapping (SC §9.3–9.5) ──────────

    enum class ProbeDomain {
        NCASM, EQEBB, PLAY, BATTERY, SYSTEM, LEA, NONE,
    }

    private val V1_NC_ASM_TYPES = setOf(
        SonyV1FunctionType.NOISE_CANCELLING,
        SonyV1FunctionType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE,
        SonyV1FunctionType.AMBIENT_SOUND_MODE,
    )

    private val AMBIENT_LEVEL_TYPES = setOf(
        NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS,
        NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        NcAsmInquiredType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
        NcAsmInquiredType.ASM_SEAMLESS,
    )

    /** V2 seamless inquired types whose SET_PARAM layout has a voice byte. */
    private val AMBIENT_VOICE_TYPES = setOf(
        NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
    )

    /**
     * Prefer the DUAL (MODE_NC_ASM_DUAL_...) seamless write type over the AUTO
     * one when a device advertises both. Devices like LinkBuds S / WF-1000XM5
     * report AUTO_NCASM in the support-function list (so AUTO maps to 0x15 and
     * gets inserted first), but the device only accepts the level/focus-on-voice
     * bytes through the DUAL layout — its RET_NCASM echoes 0x17 regardless of
     * the queried type, and mode toggles survive an AUTO write only because the
     * mode byte sits at the same offset. Writing AUTO silently drops level and
     * voice (observed: LinkBuds S 14:46). Keep AUTO when DUAL is absent.
     */
    private fun preferDualWriteTypes(types: Set<NcAsmInquiredType>): Set<NcAsmInquiredType> {
        if (types.isEmpty()) return types
        val dual = types.firstOrNull { it in AMBIENT_VOICE_TYPES }
        return if (dual != null) setOf(dual) else types
    }

    private val fallbackOnlyFeatures = setOf(
        HeadphoneFeature.NOISE_CONTROL,
        HeadphoneFeature.AMBIENT_LEVEL,
        HeadphoneFeature.AMBIENT_VOICE_MODE,
        HeadphoneFeature.EQ,
        HeadphoneFeature.CLEAR_BASS,
        HeadphoneFeature.PLAYBACK_CONTROL,
        HeadphoneFeature.BATTERY,
        HeadphoneFeature.LEA_STATUS,
        HeadphoneFeature.QUICK_ACCESS,
        HeadphoneFeature.WEARING_STATUS,
    )

    private fun SonySupportedFunction.v2Type(): SonyV2FunctionType? =
        SonyV2FunctionType.fromByteCode(SonyTable.NO_1, code).takeIf { it != SonyV2FunctionType.OUT_OF_RANGE }

    private fun SonySupportedFunction.v1Type(): SonyV1FunctionType? =
        SonyV1FunctionType.fromByteCode(code).takeIf { it != SonyV1FunctionType.OUT_OF_RANGE }

    /**
     * V1 and V2 FunctionType byte codes collide (V1 NOISE_CANCELLING_AND_ASM=0x62
     * == V2 NOISE_CANCELLING_ONOFF_AND_ASM_ONOFF=0x62, V1 EBB=0x52 == V2
     * PRESET_EQ_NON_CUSTOMIZABLE=0x52, ...), so interpretation must follow the
     * model's protocol generation. Unknown-generation profiles (null) default to
     * the V2 interpretation and fall back to V1 on a code V2 does not know.
     */
    private fun SonySupportedFunction.isV1(profile: ConnectedHeadphoneProfile?): Boolean {
        if (profile == null) return false
        return listOf(
            profile.protocolFor(HeadphoneFeature.NOISE_CONTROL),
            profile.protocolFor(HeadphoneFeature.EQ),
            profile.protocolFor(HeadphoneFeature.BATTERY),
        ).any { it.name.startsWith("SONY_TANDEM_V1") }
    }

    private fun SonySupportedFunction.domain(profile: ConnectedHeadphoneProfile?): ProbeDomain {
        if (isV1(profile)) {
            return v1Type()?.let { domainForV1(it) } ?: ProbeDomain.NONE
        }
        return v2Type()?.let { domainForV2(it) } ?: v1Type()?.let { domainForV1(it) } ?: ProbeDomain.NONE
    }

    private fun domainForV2(type: SonyV2FunctionType): ProbeDomain = when (type) {
        SonyV2FunctionType.PRESET_EQ,
        SonyV2FunctionType.EBB,
        SonyV2FunctionType.PRESET_EQ_NON_CUSTOMIZABLE,
        SonyV2FunctionType.PRESET_EQ_AND_ULT_MODE,
        SonyV2FunctionType.SOUND_EFFECT,
        SonyV2FunctionType.CUSTOM_EQ,
        SonyV2FunctionType.TURN_KEY_EQ,
        SonyV2FunctionType.PRESET_EQ_AND_ERRORCODE,
        SonyV2FunctionType.ULT_SOUND_EFFECT_ASSIGN,
        SonyV2FunctionType.CUSTOMIZABLE_SOUND_EFFECT -> ProbeDomain.EQEBB

        SonyV2FunctionType.NOISE_CANCELLING_ONOFF,
        SonyV2FunctionType.NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_ONOFF,
        SonyV2FunctionType.NOISE_CANCELLING_DUAL_SINGLE_OFF_AND_AMBIENT_SOUND_MODE_ONOFF,
        SonyV2FunctionType.NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT,
        SonyV2FunctionType.NOISE_CANCELLING_DUAL_SINGLE_OFF_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT,
        SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AUTO_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT,
        SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_SINGLE_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT,
        SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT,
        SonyV2FunctionType.MODE_NC_NCSS_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_WITH_TEST_MODE,
        SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_NOISE_ADAPTATION,
        SonyV2FunctionType.AMBIENT_SOUND_MODE_ONOFF,
        SonyV2FunctionType.AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT,
        SonyV2FunctionType.AMBIENT_SOUND_CONTROL_MODE_SELECT,
        SonyV2FunctionType.AUTO_NCASM,
        // SC registers AUTO_NCASM and adaptive control under the same NCASM
        // domain (`pq/i.java`); both read the auto NC/ASM param.
        SonyV2FunctionType.ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION -> ProbeDomain.NCASM

        SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT,
        SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT_AND_MUTE,
        SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT_AND_FUNCTION_CHANGE,
        SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_FUNCTION_CHANGE,
        SonyV2FunctionType.SAR -> ProbeDomain.PLAY

        SonyV2FunctionType.BATTERY_LEVEL_INDICATOR,
        SonyV2FunctionType.LEFT_RIGHT_BATTERY_LEVEL_INDICATOR,
        SonyV2FunctionType.CRADLE_BATTERY_LEVEL_INDICATOR,
        SonyV2FunctionType.BATTERY_LEVEL_WITH_THRESHOLD,
        SonyV2FunctionType.LR_BATTERY_LEVEL_WITH_THRESHOLD,
        SonyV2FunctionType.CRADLE_BATTERY_LEVEL_WITH_THRESHOLD -> ProbeDomain.BATTERY

        SonyV2FunctionType.WEARING_STATUS_DETECTOR,
        SonyV2FunctionType.QUICK_ACCESS -> ProbeDomain.SYSTEM

        SonyV2FunctionType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
        SonyV2FunctionType.HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
        SonyV2FunctionType.CLASSIC_ONLY_LE_CLASSIC_SETTING,
        SonyV2FunctionType.TWS_SUPPORTS_LEA_UNI_LEA_BROAD -> ProbeDomain.LEA

        else -> ProbeDomain.NONE
    }

    private fun domainForV1(type: SonyV1FunctionType): ProbeDomain = when (type) {
        SonyV1FunctionType.PRESET_EQ,
        SonyV1FunctionType.EBB,
        SonyV1FunctionType.PRESET_EQ_NONCUSTOMIZABLE -> ProbeDomain.EQEBB

        SonyV1FunctionType.NOISE_CANCELLING,
        SonyV1FunctionType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE,
        SonyV1FunctionType.AMBIENT_SOUND_MODE -> ProbeDomain.NCASM

        SonyV1FunctionType.PLAYBACK_CONTROLLER -> ProbeDomain.PLAY
        SonyV1FunctionType.BATTERY_LEVEL,
        SonyV1FunctionType.LEFT_RIGHT_BATTERY_LEVEL,
        SonyV1FunctionType.CRADLE_BATTERY_LEVEL -> ProbeDomain.BATTERY

        SonyV1FunctionType.CONTROL_BY_WEARING -> ProbeDomain.SYSTEM
        else -> ProbeDomain.NONE
    }

    /** V2 FunctionType → NcAsmInquiredType (SC §9.5). */
    private fun ncAsmInquiredFor(type: SonyV2FunctionType): NcAsmInquiredType? = when (type) {
        SonyV2FunctionType.NOISE_CANCELLING_ONOFF -> NcAsmInquiredType.NC_ON_OFF
        SonyV2FunctionType.NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_ONOFF ->
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_ON_OFF
        SonyV2FunctionType.NOISE_CANCELLING_DUAL_SINGLE_OFF_AND_AMBIENT_SOUND_MODE_ONOFF ->
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_ON_OFF
        SonyV2FunctionType.NOISE_CANCELLING_ONOFF_AND_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT ->
            NcAsmInquiredType.NC_ON_OFF_AND_ASM_SEAMLESS
        SonyV2FunctionType.NOISE_CANCELLING_DUAL_SINGLE_OFF_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT ->
            NcAsmInquiredType.NC_MODE_SWITCH_AND_ASM_SEAMLESS
        SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AUTO_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT ->
            NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS
        SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_SINGLE_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT ->
            NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS
        SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT ->
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS
        SonyV2FunctionType.MODE_NC_NCSS_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_WITH_TEST_MODE ->
            NcAsmInquiredType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS
        SonyV2FunctionType.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_NOISE_ADAPTATION ->
            NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA
        SonyV2FunctionType.AMBIENT_SOUND_MODE_ONOFF -> NcAsmInquiredType.ASM_ON_OFF
        SonyV2FunctionType.AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT -> NcAsmInquiredType.ASM_SEAMLESS
        SonyV2FunctionType.AMBIENT_SOUND_CONTROL_MODE_SELECT -> NcAsmInquiredType.NC_AMB_TOGGLE
        // AUTO_NCASM and ADAPTIVE_CONTROL both read the auto NC/ASM param
        // (SC `pq/i.java` groups them under one NCASM domain registration).
        SonyV2FunctionType.AUTO_NCASM,
        SonyV2FunctionType.ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION ->
            NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS
        else -> null
    }

    private fun SonySupportedFunction.eqEbbInquired(profile: ConnectedHeadphoneProfile?): EqEbbInquiredType? {
        if (isV1(profile)) {
            return when (v1Type()) {
                SonyV1FunctionType.PRESET_EQ -> EqEbbInquiredType.PRESET_EQ
                SonyV1FunctionType.EBB -> EqEbbInquiredType.EBB
                SonyV1FunctionType.PRESET_EQ_NONCUSTOMIZABLE -> EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE
                else -> null
            }
        }
        return when (val v2 = v2Type()) {
            SonyV2FunctionType.PRESET_EQ -> EqEbbInquiredType.PRESET_EQ
            SonyV2FunctionType.EBB -> EqEbbInquiredType.EBB
            SonyV2FunctionType.PRESET_EQ_NON_CUSTOMIZABLE -> EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE
            SonyV2FunctionType.PRESET_EQ_AND_ULT_MODE -> EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE
            SonyV2FunctionType.SOUND_EFFECT -> EqEbbInquiredType.SOUND_EFFECT
            SonyV2FunctionType.CUSTOM_EQ -> EqEbbInquiredType.CUSTOM_EQ
            SonyV2FunctionType.TURN_KEY_EQ -> EqEbbInquiredType.TURN_KEY_EQ
            SonyV2FunctionType.PRESET_EQ_AND_ERRORCODE -> EqEbbInquiredType.PRESET_EQ_AND_ERRORCODE
            SonyV2FunctionType.ULT_SOUND_EFFECT_ASSIGN -> EqEbbInquiredType.ULT_BTN_SOUND_EFFECT_ASSIGN
            SonyV2FunctionType.CUSTOMIZABLE_SOUND_EFFECT -> EqEbbInquiredType.CUSTOMIZABLE_SOUND_EFFECT_SELECT
            null -> when (v1Type()) {
                SonyV1FunctionType.PRESET_EQ -> EqEbbInquiredType.PRESET_EQ
                SonyV1FunctionType.EBB -> EqEbbInquiredType.EBB
                SonyV1FunctionType.PRESET_EQ_NONCUSTOMIZABLE -> EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE
                else -> null
            }
            else -> null
        }
    }

    private fun SonySupportedFunction.playInquired(profile: ConnectedHeadphoneProfile?): PlayInquiredType? {
        if (isV1(profile)) {
            return if (v1Type() == SonyV1FunctionType.PLAYBACK_CONTROLLER) {
                PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT
            } else {
                null
            }
        }
        return when (v2Type()) {
            SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT ->
                PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT
            SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT_AND_FUNCTION_CHANGE ->
                PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT_AND_FUNCTION_CHANGE
            SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_FUNCTION_CHANGE ->
                PlayInquiredType.PLAYBACK_CONTROL_WITH_FUNCTION_CHANGE
            null -> if (v1Type() == SonyV1FunctionType.PLAYBACK_CONTROLLER) {
                PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT
            } else {
                null
            }
            else -> null
        }
    }

    private fun SonySupportedFunction.batteryInquired(profile: ConnectedHeadphoneProfile?): PowerInquiredType? {
        if (isV1(profile)) {
            return when (v1Type()) {
                SonyV1FunctionType.BATTERY_LEVEL -> PowerInquiredType.BATTERY
                SonyV1FunctionType.LEFT_RIGHT_BATTERY_LEVEL -> PowerInquiredType.LEFT_RIGHT_BATTERY
                SonyV1FunctionType.CRADLE_BATTERY_LEVEL -> PowerInquiredType.CRADLE_BATTERY
                else -> null
            }
        }
        return when (v2Type()) {
            SonyV2FunctionType.BATTERY_LEVEL_INDICATOR,
            SonyV2FunctionType.BATTERY_LEVEL_WITH_THRESHOLD -> PowerInquiredType.BATTERY
            SonyV2FunctionType.LEFT_RIGHT_BATTERY_LEVEL_INDICATOR,
            SonyV2FunctionType.LR_BATTERY_LEVEL_WITH_THRESHOLD -> PowerInquiredType.LEFT_RIGHT_BATTERY
            SonyV2FunctionType.CRADLE_BATTERY_LEVEL_INDICATOR,
            SonyV2FunctionType.CRADLE_BATTERY_LEVEL_WITH_THRESHOLD -> PowerInquiredType.CRADLE_BATTERY
            null -> when (v1Type()) {
                SonyV1FunctionType.BATTERY_LEVEL -> PowerInquiredType.BATTERY
                SonyV1FunctionType.LEFT_RIGHT_BATTERY_LEVEL -> PowerInquiredType.LEFT_RIGHT_BATTERY
                SonyV1FunctionType.CRADLE_BATTERY_LEVEL -> PowerInquiredType.CRADLE_BATTERY
                else -> null
            }
            else -> null
        }
    }

    private fun SonySupportedFunction.systemInquired(profile: ConnectedHeadphoneProfile?): SystemInquiredType? {
        if (isV1(profile)) {
            return if (v1Type() == SonyV1FunctionType.CONTROL_BY_WEARING) {
                SystemInquiredType.WEARING_STATUS_DETECTOR
            } else {
                null
            }
        }
        return when (v2Type()) {
            SonyV2FunctionType.WEARING_STATUS_DETECTOR -> SystemInquiredType.WEARING_STATUS_DETECTOR
            SonyV2FunctionType.QUICK_ACCESS -> SystemInquiredType.QUICK_ACCESS
            null -> if (v1Type() == SonyV1FunctionType.CONTROL_BY_WEARING) {
                SystemInquiredType.WEARING_STATUS_DETECTOR
            } else {
                null
            }
            else -> null
        }
    }

    private fun preferredEqWriteType(types: Set<EqEbbInquiredType>): EqEbbInquiredType =
        when {
            EqEbbInquiredType.PRESET_EQ in types -> EqEbbInquiredType.PRESET_EQ
            EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE in types -> EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE
            EqEbbInquiredType.PRESET_EQ_AND_ERRORCODE in types -> EqEbbInquiredType.PRESET_EQ_AND_ERRORCODE
            EqEbbInquiredType.CUSTOM_EQ in types -> EqEbbInquiredType.CUSTOM_EQ
            EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE in types -> EqEbbInquiredType.PRESET_EQ_NONCUSTOMIZABLE
            EqEbbInquiredType.SOUND_EFFECT in types -> EqEbbInquiredType.SOUND_EFFECT
            EqEbbInquiredType.TURN_KEY_EQ in types -> EqEbbInquiredType.TURN_KEY_EQ
            else -> types.firstOrNull() ?: EqEbbInquiredType.PRESET_EQ
        }

    private val DEFAULT_PRESETS = listOf(
        EqPresetId.OFF,
        EqPresetId.BRIGHT,
        EqPresetId.EXCITED,
        EqPresetId.MELLOW,
        EqPresetId.RELAXED,
        EqPresetId.VOCAL,
        EqPresetId.TREBLE,
        EqPresetId.BASS,
        EqPresetId.SPEECH,
        EqPresetId.CUSTOM,
        EqPresetId.USER_SETTING1,
        EqPresetId.USER_SETTING2,
    )
}
