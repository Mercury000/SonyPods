package dev.sonypods.headphones

import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.PlayInquiredType
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.SafeListeningInquiredTypeTable2
import dev.sonypods.protocol.SonySupportedFunction
import dev.sonypods.protocol.SonyTable
import dev.sonypods.protocol.SonyV1FunctionType
import dev.sonypods.protocol.SonyV2FunctionType
import dev.sonypods.protocol.SystemInquiredType
import dev.sonypods.protocol.SonyTandemV2Table2Protocol

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

    fun buildGetSupportFunctionCommands(
        profile: ConnectedHeadphoneProfile,
        availableChannels: Set<TandemChannel> = emptySet(),
    ): List<HeadphoneCommand> = buildList {
        val deviceInfoChannel = profile.channelFor(HeadphoneFeature.DEVICE_INFO)
        add(buildGetSupportFunctionCommand(profile))
        // The Table2 support function carries the Table2 FunctionTypes (Safe
        // Listening, LEA, peripheral, ...). Prefer the MC endpoint, but under LE
        // Audio only the HPC service is up at probe time — the device answers
        // Table2 queries on it too (the inbound parser routes by dataType, and
        // a 0x0F frame on HPC resolves to the V2 Table2 codec).
        if (
            profile.protocolFor(HeadphoneFeature.DEVICE_INFO) == HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1
        ) {
            add(
                HeadphoneCommand(
                    label = "GET support function Table2",
                    bytes = SonyTandemV2Table2Protocol.buildGetSupportFunction(),
                    channel = if (TandemChannel.GATT_V2_MC in availableChannels) {
                        TandemChannel.GATT_V2_MC
                    } else {
                        deviceInfoChannel
                    },
                )
            )
        }
    }

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
        // SC picks the upscaling inquired type the same way (`u70.p1`): plain
        // UPSCALING_AUTO_OFF wins over WITH_STATUS_DISABLE_REASON. The chosen
        // type's GET_CAPABILITY then returns the DSEE generation byte.
        var upscalingPlain = false
        var upscalingWithStatusReason = false
        return buildList {
            for (function in functions) {
                when (function.v2Type()) {
                    SonyV2FunctionType.UPSCALING_AUTO_OFF -> upscalingPlain = true
                    SonyV2FunctionType.UPSCALING_AUTO_OFF_WITH_STATUS_DISABLE_REASON ->
                        upscalingWithStatusReason = true
                    else -> Unit
                }
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
                        deviceInfoCodec.buildGetSystemCapability(system)?.let { bytes ->
                            add(
                                HeadphoneCommand(
                                    label = "GET SYSTEM capability ${system.name}",
                                    bytes = bytes,
                                    // All Table1 SYSTEM requests share the connection
                                    // channel. Use DEVICE_INFO here instead of requiring
                                    // a synthetic GESTURE_OPERATIONS binding; this also
                                    // keeps the probe valid for older test/V1 profiles.
                                    channel = profile.channelFor(HeadphoneFeature.DEVICE_INFO),
                                )
                            )
                        }
                    }

                    // LEA state is read via GET_STATUS in refresh, not via a
                    // capability probe, so no command is emitted here.
                    ProbeDomain.LEA -> Unit

                    // The Safe Listening capability reply names the device's
                    // minimum poll interval (SC polls sound pressure at it); the
                    // inquired type itself comes from the FunctionType. The
                    // extended-param readout needs no probe — it just works.
                    ProbeDomain.SAFE_LISTENING ->
                        function.safeListeningInquired(profile)?.let { type ->
                            add(
                                HeadphoneCommand(
                                    label = "GET SAFE_LISTENING capability ${type.name}",
                                    bytes = SonyTandemV2Table2Protocol.buildGetSafeListeningCapability(type),
                                    channel = profile.channelFor(HeadphoneFeature.SAFE_LISTENING),
                                )
                            )
                        }

                    // General Setting slots ride the Table1 connection channel. The
                    // device advertises them as FunctionTypes (0xD1..0xD4) and the
                    // code is itself the slot; probing every advertised one mirrors
                    // SC (it enumerates the function list and probes any GS type).
                    ProbeDomain.GENERAL_SETTING ->
                        deviceInfoCodec.buildGetGeneralSettingCapability(function.code)?.let { bytes ->
                            add(
                                HeadphoneCommand(
                                    label = "GET GS capability 0x%02X".format(function.code.toInt() and 0xFF),
                                    bytes = bytes,
                                    channel = profile.channelFor(HeadphoneFeature.DEVICE_INFO),
                                )
                            )
                        }

                    ProbeDomain.NONE -> Unit
                }
            }
            if (upscalingPlain || upscalingWithStatusReason) {
                val inquiredTypeCode = (if (upscalingPlain) 0x01 else 0x0B).toByte()
                deviceInfoCodec.buildGetUpscalingCapability(inquiredTypeCode)?.let { bytes ->
                    add(
                        HeadphoneCommand(
                            label = "GET AUDIO capability upscaling",
                            bytes = bytes,
                            channel = profile.channelFor(HeadphoneFeature.UPSCALING),
                        )
                    )
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
        var playbackHasMute = false
        var gestureSettingsType: SystemInquiredType? = null
        // The SAFE_LISTENING_* FunctionType the device advertises names the
        // inquired type for the current-sound-pressure query (1:1, SC's
        // DeviceCapabilityTableset2 gates on these same four types).
        var safeListeningType: SafeListeningInquiredTypeTable2? = null
        val leaKind = functions.firstNotNullOfOrNull { it.leaDeviceKind() }
        // SC picks the upscaling inquired type the same way (`u70.p1`): the plain
        // UPSCALING_AUTO_OFF generation wins over the WITH_STATUS_DISABLE_REASON
        // one, and neither means the device has no DSEE toggle at all.
        var upscalingPlain = false
        var upscalingWithStatusReason = false
        // SC's card-branch priority (`d0.mo24713c`): the LE-era dual-mode variant
        // wins over the classic one over the LDAC-status one; none means no
        // Bluetooth 连接质量 setting at all.
        var qualityLeDual = false
        var qualityPlain = false
        var qualityLdacStatus = false
        // 官方分支③的判定来源：耳机宣告「连接质量在 LE Audio 下不可用」（0x4D）。
        // 此时卡片保留但置灰——与「直接隐藏」不同，这是会话级运行时状态。
        var qualityLeaRestricted = false
        // SC 的 FunctionCantBeUsedWithLEAConnectionType：能力表中所有
        // *_CANT_BE_USED_WITH_LEA_CONNECTION 条目，决定哪些功能在 LEA 下不可用。
        // 对照 SC DeviceCapabilityTableset2.x1()：检查 support-function list 是否
        // 包含对应的 FunctionType。
        val leaRestrictedTypes = mutableSetOf<SonyV2FunctionType>()
        val leaControlSupported = functions.any {
            it.v2Type() == SonyV2FunctionType.CLASSIC_ONLY_LE_CLASSIC_SETTING
        }
        // SC mo58509L0(): the support-function list contains a Table2 multipoint
        // FunctionType — the device *declares* multipoint support regardless of
        // whether a runtime GS slot has been discovered yet.
        var supportsMultipointViaFunction = false
        // SC registers the tandem-target instruction handlers only for devices
        // that declare the corresponding FunctionType (u70.C29444f / C14319c).
        var declaresTandemTargetChange = false
        var declaresChangeTandemConnectionProfile = false
        // SC registers the 0x0F NOTIFY_DISCONNECTING_TANDEM observer for devices
        // declaring any of the LEA-unicast-broadcast-with-CTKD types (TWS 0x40 /
        // HBS 0x41 / PAS 0x64 Table2 — C14319c.mo61835c).
        var declaresTandemDisconnectingNotification = false

        for (function in functions) {
            if (function.isPowerOff(profile)) {
                features.add(HeadphoneFeature.POWER_OFF)
            }
            function.v2Type()?.takeIf { it in SonyV2FunctionType.LEA_RESTRICTION_TYPES }
                ?.let { leaRestrictedTypes.add(it) }
            when (function.v2Type()) {
                SonyV2FunctionType.UPSCALING_AUTO_OFF -> upscalingPlain = true
                SonyV2FunctionType.UPSCALING_AUTO_OFF_WITH_STATUS_DISABLE_REASON ->
                    upscalingWithStatusReason = true
                SonyV2FunctionType.CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO -> qualityLeDual = true
                SonyV2FunctionType.CONNECTION_MODE_SOUND_QUALITY_CONNECTION_QUALITY -> qualityPlain = true
                SonyV2FunctionType.CONNECTION_MODE_SOUND_QUALITY_SOUND_WITH_LDAC_STATUS_QUALITY_CONNECTION_QUALITY ->
                    qualityLdacStatus = true
                SonyV2FunctionType.CONNECTION_MODE_CANT_BE_USED_WITH_LEA_CONNECTION ->
                    qualityLeaRestricted = true
                SonyV2FunctionType.PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT,
                SonyV2FunctionType.PAIRING_DEVICE_MANAGEMENT_WITH_BLUETOOTH_CLASS_OF_DEVICE,
                SonyV2FunctionType.PAIRING_DEVICE_MANAGEMENT_WITH_BLUETOOTH_CLASS_OF_DEVICE_CLASSIC_LE ->
                    supportsMultipointViaFunction = true
                SonyV2FunctionType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD -> {
                    declaresTandemTargetChange = true
                    declaresTandemDisconnectingNotification = true
                }
                SonyV2FunctionType.HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
                SonyV2FunctionType.PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD ->
                    declaresTandemDisconnectingNotification = true
                SonyV2FunctionType.CHANGE_TANDEM_CONNECTION_PROFILE_FOR_ANDROID ->
                    declaresChangeTandemConnectionProfile = true
                else -> Unit
            }
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
                    if (function.v2Type() == SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT_AND_MUTE) {
                        playbackHasMute = true
                    }
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
                        SystemInquiredType.ASSIGNABLE_SETTINGS,
                        SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION -> {
                            features.add(HeadphoneFeature.GESTURE_OPERATIONS)
                            if (gestureSettingsType == null) {
                                gestureSettingsType = function.systemInquired(profile)
                            }
                        }
                        else -> Unit
                    }
                }

                ProbeDomain.LEA -> {
                    if (function.leaDeviceKind() != null) {
                        features.add(HeadphoneFeature.LEA_STATUS)
                    }
                }

                ProbeDomain.SAFE_LISTENING -> {
                    function.safeListeningInquired(profile)?.let { type ->
                        features.add(HeadphoneFeature.SAFE_LISTENING)
                        if (safeListeningType == null) {
                            safeListeningType = type
                        }
                    }
                }

                // General Setting slots carry no feature flag of their own; the
                // GS capability reply (e.g. "MULTIPOINT_SETTING") is what the
                // engine matches to enable the 2-device switch.
                ProbeDomain.GENERAL_SETTING -> Unit

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
                // The _NA layout (0x19, FunctionType 0x6D) is the only carrier of
                // the noise-adaptive (Auto Ambient Sound) toggle + sensitivity.
                if (noiseQueries.any { it in NOISE_ADAPTIVE_TYPES }) {
                    features.add(HeadphoneFeature.NOISE_ADAPTIVE)
                }
            }
        }

        val eqConfig = if (eqTypes.isNotEmpty()) {
            val writeType = preferredEqWriteType(eqTypes)
            val basePresets = fallback.eqConfig.availablePresets.ifEmpty { DEFAULT_PRESETS }
            EqDeviceConfig(
                // The dropdown vocabulary follows the write type: sound-effect
                // devices speak SC `SoundEffectType` (not EqPresetId), and
                // PRESET_EQ_AND_ULT_MODE devices add the ULT mode entries.
                availablePresets = when (writeType) {
                    EqEbbInquiredType.SOUND_EFFECT,
                    EqEbbInquiredType.CUSTOMIZABLE_SOUND_EFFECT_SELECT -> SOUND_EFFECT_PRESETS
                    EqEbbInquiredType.PRESET_EQ_AND_ULT_MODE ->
                        basePresets + listOf(EqPresetId.ULT_1, EqPresetId.ULT_2)
                    else -> basePresets
                },
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

        // Sound Connect constructs the Classic-only setting sender from the
        // main V2 tableset when CLASSIC_ONLY_LE_CLASSIC_SETTING is advertised.
        // It is independent from the TWS/HBS/PAS paired-history reader. Bind it
        // to the main DEVICE_INFO command endpoint instead of deriving it from
        // the LEA history kind (PAS history, for example, is Table2/MC).
        val leaControlChannel = profile?.let {
            runCatching { it.channelFor(HeadphoneFeature.DEVICE_INFO) }.getOrNull()
        } ?: when (transport) {
            HeadphoneTransport.SPP -> TandemChannel.SPP_MDR
            HeadphoneTransport.GATT_HPC -> TandemChannel.GATT_V2_HPC
            HeadphoneTransport.GATT_MC -> TandemChannel.GATT_V2_MC
            HeadphoneTransport.UNSUPPORTED_LE_ENDPOINT,
            HeadphoneTransport.UNKNOWN -> null
        }
        val lea = leaKind?.let { kind ->
            when (kind) {
                LeaDeviceKind.TWS_CTKD -> LeaProtocolCapability(
                    kind, HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
                    TandemChannel.GATT_V2_HPC, 0x00, leaControlSupported,
                    leaControlChannel.takeIf { leaControlSupported },
                )
                LeaDeviceKind.HBS_CTKD -> LeaProtocolCapability(
                    kind, HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
                    TandemChannel.GATT_V2_HPC, 0x01, leaControlSupported,
                    leaControlChannel.takeIf { leaControlSupported },
                )
                LeaDeviceKind.TWS_LE_ONLY -> LeaProtocolCapability(
                    kind, HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
                    TandemChannel.GATT_V2_HPC, 0x02, leaControlSupported,
                    leaControlChannel.takeIf { leaControlSupported },
                )
                LeaDeviceKind.PAS_CTKD -> LeaProtocolCapability(
                    kind, HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2,
                    TandemChannel.GATT_V2_MC, 0x04, leaControlSupported,
                    leaControlChannel.takeIf { leaControlSupported },
                )
            }
        }

        val upscalingInquiredTypeCode = when {
            upscalingPlain -> 0x01
            upscalingWithStatusReason -> 0x0B
            else -> null
        }
        if (upscalingInquiredTypeCode != null) {
            features.add(HeadphoneFeature.UPSCALING)
        }

        val connectionQualityInquiredTypeCode = when {
            qualityLeDual -> 0x05
            qualityPlain -> 0x00
            qualityLdacStatus -> 0x02
            else -> null
        }
        if (connectionQualityInquiredTypeCode != null || qualityLeaRestricted) {
            features.add(HeadphoneFeature.CONNECTION_QUALITY)
        }

        // Live sound-quality badges: SC registers both indicator features on
        // their FunctionType presence alone (`u70.p1` / `t70.d`), so a model
        // without the entry simply never shows that badge. V1 and V2 byte codes
        // collide, so the check follows the profile's protocol generation.
        val codecIndicatorSupported = functions.any { function ->
            if (function.isV1(profile)) {
                function.v1Type() == SonyV1FunctionType.CODEC_INDICATOR
            } else {
                function.v2Type() == SonyV2FunctionType.CODEC_INDICATOR
            }
        }
        val upscalingIndicatorSupported = functions.any { function ->
            if (function.isV1(profile)) {
                function.v1Type() == SonyV1FunctionType.UPSCALING_INDICATOR
            } else {
                function.v2Type() == SonyV2FunctionType.UPSCALING_INDICATOR
            }
        }
        val alertSupported = functions.any { function ->
            if (function.isV1(profile)) {
                function.v1Type() == SonyV1FunctionType.VIBRATOR_ALERT_NOTIFICATION
            } else {
                function.v2Type() in setOf(
                    SonyV2FunctionType.FIXED_MESSAGE,
                    SonyV2FunctionType.FIXED_MESSAGE_WITH_LR_SELECTION,
                    SonyV2FunctionType.VIBRATOR_ALERT_NOTIFICATION,
                    SonyV2FunctionType.VOICE_ASSISTANT_ALERT_NOTIFICATION,
                    SonyV2FunctionType.LE_AUDIO_ALERT_NOTIFICATION,
                )
            }
        }

        val supportsAutoWindNoiseReduction = noiseQueries.any {
            it == NcAsmInquiredType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS
        }
        // V1 single-mic wind noise is NOT derivable from the function list: it
        // depends on the NCASM_RET_CAPABILITY NcAsmSettingType being
        // DUAL_SINGLE_OFF, which the repository learns when that capability
        // response arrives (SC `qe0.d2$c` / NoiseCancellingType.DUAL_SINGLE).
        val supportsWindNoiseReduction = noiseQueries.any {
            it == NcAsmInquiredType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS
        }

        val speakToChatType = functions.firstNotNullOfOrNull { function ->
            if (function.isV1(profile)) {
                if (function.v1Type() == SonyV1FunctionType.SMART_TALKING_MODE) {
                    SystemInquiredType.SMART_TALKING_MODE_TYPE1
                } else null
            } else {
                when (function.v2Type()) {
                    SonyV2FunctionType.SMART_TALKING_MODE_TYPE1 -> SystemInquiredType.SMART_TALKING_MODE_TYPE1
                    SonyV2FunctionType.SMART_TALKING_MODE_TYPE2 -> SystemInquiredType.SMART_TALKING_MODE_TYPE2
                    else -> null
                }
            }
        }
        val supportsSpeakToChat = speakToChatType != null
        if (supportsSpeakToChat) {
            features.add(HeadphoneFeature.SPEAK_TO_CHAT)
        }

        return fallback.copy(
            features = features + (fallback.features - fallbackOnlyFeatures) ,
            formFactor = formFactorFromBattery(batteryQueries),
            batteryQueries = batteryQueries.ifEmpty { fallback.batteryQueries },
            noiseControlQueryTypes = noiseQueries.distinct().ifEmpty { fallback.noiseControlQueryTypes },
            writableNoiseControlTypes = preferDualWriteTypes(writableNoise)
                .ifEmpty { fallback.writableNoiseControlTypes },
            supportsAutoWindNoiseReduction = supportsAutoWindNoiseReduction || fallback.supportsAutoWindNoiseReduction,
            supportsWindNoiseReduction = supportsWindNoiseReduction || fallback.supportsWindNoiseReduction,
            supportsSpeakToChat = supportsSpeakToChat || fallback.supportsSpeakToChat,
            speakToChatType = speakToChatType ?: fallback.speakToChatType,
            upscalingInquiredTypeCode = upscalingInquiredTypeCode
                ?: fallback.upscalingInquiredTypeCode,
            // The DSEE generation byte only arrives via the AUDIO_RET_CAPABILITY
            // probe; a restored/fallback tableset keeps whatever was cached.
            upscalingTypeCode = fallback.upscalingTypeCode,
            connectionQualityInquiredTypeCode = connectionQualityInquiredTypeCode
                ?: fallback.connectionQualityInquiredTypeCode,
            // 限制标记是会话级的（随当前传输的能力表变化），不从 fallback 继承。
            connectionQualityRestrictedByLea = qualityLeaRestricted,
            leaRestrictedFunctionTypes = leaRestrictedTypes,
            codecIndicatorSupported = codecIndicatorSupported,
            upscalingIndicatorSupported = upscalingIndicatorSupported,
            alertSupported = alertSupported,
            eqConfig = eqConfig,
            playbackControlType = playTypes.firstOrNull() ?: fallback.playbackControlType,
            playbackVolumeHasMute = playbackHasMute,
            gestureSettingsType = gestureSettingsType ?: fallback.gestureSettingsType,
            safeListeningInquiredType = safeListeningType ?: fallback.safeListeningInquiredType,
            supportsMultipointViaFunction = supportsMultipointViaFunction,
            // Device declarations — stable across sessions, so a partial rebuild
            // keeps what the fallback knew.
            declaresTandemTargetChange = declaresTandemTargetChange || fallback.declaresTandemTargetChange,
            declaresChangeTandemConnectionProfile =
                declaresChangeTandemConnectionProfile || fallback.declaresChangeTandemConnectionProfile,
            declaresTandemDisconnectingNotification =
                declaresTandemDisconnectingNotification || fallback.declaresTandemDisconnectingNotification,
            lea = lea,
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

    /**
     * A probe-derived profile, or null when the probe supplied nothing new.
     *
     * [markProbed] records whether a *live* support-function probe produced this
     * table: only the live probe may stamp `probe:ret-support-function` into the
     * evidence. Cache restores re-derive the same profile from persisted
     * FunctionCodes and must not claim it — the engine gates its one-shot
     * per-domain probe burst on that stamp, so a restore claiming it would
     * suppress every future genuine probe (observed: DSEE generation byte never
     * fetched because a cache hit always ran first on dual-identity headsets).
     */
    fun applyToProfile(
        profile: ConnectedHeadphoneProfile,
        functions: List<SonySupportedFunction>,
        transport: HeadphoneTransport,
        markProbed: Boolean = true,
    ): ConnectedHeadphoneProfile {
        val capabilities = capabilitiesFromFunctions(functions, profile.capabilities, transport, profile)
        return profile.copy(
            capabilities = capabilities,
            // The one funnel a real capability table passes through — the live probe, the
            // cache restore and the connection-time restore all land here.
            capabilitiesKnown = true,
            featureBindings = buildFeatureBindings(profile.featureProtocolMap, capabilities),
            playbackDispatchStrategy = if (HeadphoneFeature.PLAYBACK_CONTROL in capabilities.features) {
                PlaybackDispatchStrategy.TANDEM_FIRST
            } else {
                profile.playbackDispatchStrategy
            },
            protocolEvidence = profile.protocolEvidence +
                (if (markProbed) listOf("probe:ret-support-function(${functions.size})") else emptyList()) +
                functions.map {
                    val table = it.table.takeIf { table -> table != SonyTable.INVALID }
                        ?: SonyTable.NO_1
                    "probe:${table.name}:${it.domain(profile).name}:0x%02X".format(it.code.toInt() and 0xFF)
                },
        )
    }

    // ── FunctionType → domain & inquired-type mapping (SC §9.3–9.5) ──────────

    enum class ProbeDomain {
        NCASM, EQEBB, PLAY, BATTERY, SYSTEM, LEA, SAFE_LISTENING, GENERAL_SETTING, NONE,
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

    /** Inquired types whose SET_PARAM layout carries the noise-adaptive pair. */
    private val NOISE_ADAPTIVE_TYPES = setOf(
        NcAsmInquiredType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
    )

    /**
     * Prefer the DUAL (MODE_NC_ASM_DUAL_...) seamless write type over the AUTO
     * one when a device advertises both. A device advertising both 0x68
     * (DUAL_AUTO → 0x15) and a DUAL layout only honors the level/focus-on-voice
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
        HeadphoneFeature.NOISE_ADAPTIVE,
        HeadphoneFeature.EQ,
        HeadphoneFeature.CLEAR_BASS,
        HeadphoneFeature.PLAYBACK_CONTROL,
        HeadphoneFeature.BATTERY,
        HeadphoneFeature.POWER_OFF,
        HeadphoneFeature.LEA_STATUS,
        HeadphoneFeature.UPSCALING,
        HeadphoneFeature.CONNECTION_QUALITY,
        HeadphoneFeature.QUICK_ACCESS,
        HeadphoneFeature.WEARING_STATUS,
        HeadphoneFeature.GESTURE_OPERATIONS,
    )

    private fun SonySupportedFunction.v2Type(): SonyV2FunctionType? =
        SonyV2FunctionType.fromByteCode(
            table.takeIf { it != SonyTable.INVALID } ?: SonyTable.NO_1,
            code,
        ).takeIf { it != SonyV2FunctionType.OUT_OF_RANGE }

    private fun SonySupportedFunction.leaDeviceKind(): LeaDeviceKind? = when (v2Type()) {
        SonyV2FunctionType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD -> LeaDeviceKind.TWS_CTKD
        SonyV2FunctionType.HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD -> LeaDeviceKind.HBS_CTKD
        SonyV2FunctionType.TWS_SUPPORTS_LEA_UNI_LEA_BROAD -> LeaDeviceKind.TWS_LE_ONLY
        SonyV2FunctionType.PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD -> LeaDeviceKind.PAS_CTKD
        else -> null
    }

    private fun SonySupportedFunction.v1Type(): SonyV1FunctionType? =
        SonyV1FunctionType.fromByteCode(code).takeIf { it != SonyV1FunctionType.OUT_OF_RANGE }

    private fun SonySupportedFunction.isPowerOff(profile: ConnectedHeadphoneProfile?): Boolean =
        if (profile != null && profile.protocolFor(HeadphoneFeature.DEVICE_INFO) !in setOf(
                HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
                HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1,
            )
        ) {
            false
        } else if (isV1(profile)) {
            v1Type() == SonyV1FunctionType.POWER_OFF
        } else {
            v2Type() == SonyV2FunctionType.POWER_OFF ||
                (v2Type() == null && v1Type() == SonyV1FunctionType.POWER_OFF)
        }

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
        SonyV2FunctionType.AMBIENT_SOUND_CONTROL_MODE_SELECT -> ProbeDomain.NCASM

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

        SonyV2FunctionType.SMART_TALKING_MODE_TYPE1,
        SonyV2FunctionType.SMART_TALKING_MODE_TYPE2,
        SonyV2FunctionType.WEARING_STATUS_DETECTOR,
        SonyV2FunctionType.QUICK_ACCESS,
        SonyV2FunctionType.ASSIGNABLE_SETTING,
        SonyV2FunctionType.ASSIGNABLE_SETTING_WITH_LIMITATION -> ProbeDomain.SYSTEM

        SonyV2FunctionType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
        SonyV2FunctionType.HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD,
        SonyV2FunctionType.CLASSIC_ONLY_LE_CLASSIC_SETTING,
        SonyV2FunctionType.TWS_SUPPORTS_LEA_UNI_LEA_BROAD,
        SonyV2FunctionType.PAS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD -> ProbeDomain.LEA

        SonyV2FunctionType.SAFE_LISTENING_HBS_1,
        SonyV2FunctionType.SAFE_LISTENING_TWS_1,
        SonyV2FunctionType.SAFE_LISTENING_HBS_2,
        SonyV2FunctionType.SAFE_LISTENING_TWS_2,
        SonyV2FunctionType.SAFE_VOLUME_CONTROL,
        SonyV2FunctionType.MAX_VOLUME_LEVEL_LIMIT -> ProbeDomain.SAFE_LISTENING

        // SC probes every GENERAL_SETTING the device advertises, unconditionally:
        // the slot's GET_CAPABILITY reply names the setting it carries (e.g.
        // "MULTIPOINT_SETTING"), which is what establishes the 2-device switch.
        SonyV2FunctionType.GENERAL_SETTING_1,
        SonyV2FunctionType.GENERAL_SETTING_2,
        SonyV2FunctionType.GENERAL_SETTING_3,
        SonyV2FunctionType.GENERAL_SETTING_4 -> ProbeDomain.GENERAL_SETTING

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

        SonyV1FunctionType.SMART_TALKING_MODE -> ProbeDomain.SYSTEM

        SonyV1FunctionType.GENERAL_SETTING1,
        SonyV1FunctionType.GENERAL_SETTING2,
        SonyV1FunctionType.GENERAL_SETTING3 -> ProbeDomain.GENERAL_SETTING
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
        // AUTO_NCASM (0x70) / ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION
        // (0x71) are NOT 0x15 writers: SC registers them under the SENSE /
        // adaptive-sound-control domain (SenseInquiredType.ADAPTIVE_CONTROL),
        // not NCASM. Mapping them to 0x15 turned every ASC-capable device into
        // a phantom "auto wind noise reduction" device.
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
            // The AND_MUTE variant (0xA2) shares inquiredType 0x01 for STATUS and
            // metadata; only its volume channel differs (0x30, tracked separately).
            SonyV2FunctionType.PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT_AND_MUTE ->
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

    /**
     * V1 has no wearing-status detector: its `CONTROL_BY_WEARING` (SystemInquiredType
     * 0x03) is a playback-control on/off setting — official V1 RET_STATUS returns a
     * bare CommonStatus for it (`qe0.w2` falls through to `se0.m0`), and SET_PARAM
     * takes `[ControlByWearingSettingType][SettingValue]` (`se0.m`). It is V2's
     * PLAYBACK_CONTROL_BY_WEARING, not V2's WEARING_STATUS_DETECTOR, so it maps to no
     * inquired type here — the shared enum only carries V2 codes, where 0x06 means
     * ASSIGNABLE_SETTINGS on the V1 wire.
     */
    private fun SonySupportedFunction.systemInquired(profile: ConnectedHeadphoneProfile?): SystemInquiredType? {
        if (isV1(profile)) {
            return when (v1Type()) {
                SonyV1FunctionType.SMART_TALKING_MODE -> SystemInquiredType.SMART_TALKING_MODE_TYPE1
                // V1 assignable settings (0xF6) is preset-only on its own wire
                // type 0x06; the shared enum's ASSIGNABLE_SETTINGS spelling is
                // what the V1 codec translates back to 0x06.
                SonyV1FunctionType.ASSIGNABLE_SETTINGS -> SystemInquiredType.ASSIGNABLE_SETTINGS
                else -> null
            }
        }
        return when (v2Type()) {
            SonyV2FunctionType.SMART_TALKING_MODE_TYPE1 -> SystemInquiredType.SMART_TALKING_MODE_TYPE1
            SonyV2FunctionType.SMART_TALKING_MODE_TYPE2 -> SystemInquiredType.SMART_TALKING_MODE_TYPE2
            SonyV2FunctionType.ASSIGNABLE_SETTING -> SystemInquiredType.ASSIGNABLE_SETTINGS
            SonyV2FunctionType.ASSIGNABLE_SETTING_WITH_LIMITATION ->
                SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION
            SonyV2FunctionType.WEARING_STATUS_DETECTOR -> SystemInquiredType.WEARING_STATUS_DETECTOR
            SonyV2FunctionType.QUICK_ACCESS -> SystemInquiredType.QUICK_ACCESS
            null -> when (v1Type()) {
                SonyV1FunctionType.SMART_TALKING_MODE -> SystemInquiredType.SMART_TALKING_MODE_TYPE1
                SonyV1FunctionType.ASSIGNABLE_SETTINGS -> SystemInquiredType.ASSIGNABLE_SETTINGS
                else -> null
            }
            else -> null
        }
    }

    /** V2 SAFE_LISTENING_* FunctionType → the inquired type the
     * GET_EXTENDED_PARAM query uses. Only the HBS/TWS generation types carry the
     * current-sound-pressure readout (and the capability's minimum poll
     * interval); SAFE_VOLUME_CONTROL / MAX_VOLUME_LEVEL_LIMIT are separate
     * safe-listening subsystems with their own layouts. Table2 only. */
    private fun SonySupportedFunction.safeListeningInquired(
        profile: ConnectedHeadphoneProfile?,
    ): SafeListeningInquiredTypeTable2? {
        if (isV1(profile)) return null
        return when (v2Type()) {
            SonyV2FunctionType.SAFE_LISTENING_HBS_1 -> SafeListeningInquiredTypeTable2.SAFE_LISTENING_HBS_1
            SonyV2FunctionType.SAFE_LISTENING_TWS_1 -> SafeListeningInquiredTypeTable2.SAFE_LISTENING_TWS_1
            SonyV2FunctionType.SAFE_LISTENING_HBS_2 -> SafeListeningInquiredTypeTable2.SAFE_LISTENING_HBS_2
            SonyV2FunctionType.SAFE_LISTENING_TWS_2 -> SafeListeningInquiredTypeTable2.SAFE_LISTENING_TWS_2
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
            EqEbbInquiredType.CUSTOMIZABLE_SOUND_EFFECT_SELECT in types -> EqEbbInquiredType.CUSTOMIZABLE_SOUND_EFFECT_SELECT
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

    /** Dropdown vocabulary for SOUND_EFFECT / CUSTOMIZABLE_SOUND_EFFECT_SELECT
     * writers — mirrors SC `SoundEffectType` 0x00-0x06, not EqPresetId. */
    private val SOUND_EFFECT_PRESETS = listOf(
        EqPresetId.OFF,
        EqPresetId.ULT,
        EqPresetId.ULT_1,
        EqPresetId.ULT_2,
        EqPresetId.CUSTOM,
        EqPresetId.FLAT,
        EqPresetId.LIVE_SOUND,
    )
}
