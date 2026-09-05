package dev.sonypods.headphones

import dev.sonypods.ble.DiscoveredSonyDevice
import dev.sonypods.protocol.AmbientSoundMode
import dev.sonypods.protocol.EqEbbInquiredType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NcAsmInquiredType
import dev.sonypods.protocol.NoiseAdaptiveSensitivity
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.PlaybackControl
import dev.sonypods.protocol.PlayInquiredType
import dev.sonypods.protocol.PowerInquiredType
import dev.sonypods.protocol.SonyV2FunctionType
import dev.sonypods.protocol.AssignableSettingsMapping
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.SystemInquiredType

enum class HeadphoneProtocolVariant {
    SONY_TANDEM_V1_TABLE1,
    SONY_TANDEM_V1_TABLE2,
    SONY_TANDEM_V2_TABLE1,
    SONY_TANDEM_V2_TABLE2,
    UNKNOWN,
}

enum class HeadphoneFormFactor {
    HEADSET,
    TRUE_WIRELESS,
    UNKNOWN,
}

enum class HeadphoneFeature {
    DEVICE_INFO,
    BATTERY,
    POWER_OFF,
    NOISE_CONTROL,
    AMBIENT_LEVEL,
    AMBIENT_VOICE_MODE,
    NOISE_ADAPTIVE,
    PLAYBACK_CONTROL,
    EQ,
    CLEAR_BASS,
    LEA_STATUS,
    UPSCALING,
    CONNECTION_QUALITY,
    QUICK_ACCESS,
    WEARING_STATUS,
    GESTURE_OPERATIONS,
    SPEAK_TO_CHAT,
    MULTIPOINT,
    /** Safe Listening: the current sound pressure (dB) readout polled from the
     * headset via SAFE_LISTENING_GET_EXTENDED_PARAM (V2 Table2). */
    SAFE_LISTENING,
}

enum class LeaDeviceKind {
    TWS_CTKD,
    HBS_CTKD,
    TWS_LE_ONLY,
    PAS_CTKD,
}

data class LeaProtocolCapability(
    val kind: LeaDeviceKind,
    val historyVariant: HeadphoneProtocolVariant,
    val historyChannel: TandemChannel,
    val historyInquiredTypeCode: Int,
    val controlSupported: Boolean,
    /** Table1 control endpoint, independent of the status/history query table. */
    val controlChannel: TandemChannel?,
)

enum class HeadphoneTransport {
    UNKNOWN,
    SPP,
    GATT_HPC,
    GATT_MC,
    UNSUPPORTED_LE_ENDPOINT,
}

enum class TandemChannel {
    SPP_MDR,
    GATT_V2_HPC,
    GATT_V2_MC,
    GATT_V1_MC,
    ;

    companion object {
        fun fromServiceUuid(uuid: java.util.UUID): TandemChannel? {
            // Lazy-init to avoid circular dependency with SonyGatt
            val v2Hpc = java.util.UUID.fromString("5b833e20-6bc7-4802-8e9a-723ceca4bd8f")
            val v2Mc = java.util.UUID.fromString("5b833e21-6bc7-4802-8e9a-723ceca4bd8f")
            val v1Mc = java.util.UUID.fromString("5b833e23-6bc7-4802-8e9a-723ceca4bd8f")
            return when (uuid) {
                v2Hpc -> GATT_V2_HPC
                v2Mc -> GATT_V2_MC
                v1Mc -> GATT_V1_MC
                else -> null
            }
        }
    }
}

enum class PlaybackDispatchStrategy {
    TANDEM_FIRST,
    ANDROID_MEDIA_FALLBACK,
    TANDEM_ONLY,
}

data class FeatureProtocolBinding(
    val feature: HeadphoneFeature,
    val variant: HeadphoneProtocolVariant,
    val channel: TandemChannel,
    val queryTypes: List<Any> = emptyList(),
    val writableTypes: Set<Any> = emptySet(),
)

data class HeadphoneCommand(
    val label: String,
    val bytes: ByteArray,
    val channel: TandemChannel,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HeadphoneCommand) return false
        return label == other.label && channel == other.channel && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * (31 * label.hashCode() + channel.hashCode()) + bytes.contentHashCode()
}

data class EqWriteContext(
    val rawBandSteps: List<Int> = emptyList(),
    val preset: EqPresetId? = null,
    /** Base EqPresetId behind a PRESET_EQ_AND_ULT_MODE display value: when the
     * current selection is ULT_1/ULT_2, `preset` is the ULT marker and this is
     * the preset the device pairs with the ULT mode byte (SC `hf0.d`). */
    val basePreset: EqPresetId? = null,
)

data class HeadphoneCapabilities(
    val features: Set<HeadphoneFeature>,
    val formFactor: HeadphoneFormFactor,
    val batteryQueries: List<PowerInquiredType>,
    val noiseControlQueryTypes: List<NcAsmInquiredType>,
    val writableNoiseControlTypes: Set<NcAsmInquiredType>,
    val eqConfig: EqDeviceConfig = EqDeviceConfig(
        availablePresets = listOf(EqPresetId.OFF),
        writeInquiredType = EqEbbInquiredType.PRESET_EQ,
        statusQueryTypes = emptyList(),
        paramQueryTypes = emptyList(),
        bandCount = 0,
        hasClearBass = false,
    ),
    val playbackControlType: PlayInquiredType = PlayInquiredType.PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT,
    /** PLAY function is the AND_MUTE variant: volume rides type 0x30, not 0x20. */
    val playbackVolumeHasMute: Boolean = false,
    /** System parameter family used by the assignable-settings feature. */
    val gestureSettingsType: SystemInquiredType = SystemInquiredType.ASSIGNABLE_SETTINGS,
    val queryProtocolInfo: Boolean = true,
    val queryNoiseControlParams: Boolean = true,
    val lea: LeaProtocolCapability? = null,
    /** AUDIO inquired type this device's DSEE/upscaling toggle uses:
     * 0x01 (UPSCALING) for the first generation, 0x0B
     * (UPSCALING_AUTO_OFF_WITH_STATUS_DISABLE_REASON) for the newer one.
     * Null = the support-function list did not advertise upscaling. */
    val upscalingInquiredTypeCode: Int? = null,
    /** AUDIO inquired type the Bluetooth 连接质量 toggle uses, picked with SC's
     * card-branch priority: 0x05 (CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO) over
     * 0x00 (CONNECTION_MODE) over 0x02 (…_WITH_LDAC_STATUS). Null = the
     * support-function list advertised none of them. */
    val connectionQualityInquiredTypeCode: Int? = null,
    /** 支持列表宣告 0x4D（连接质量在 LE Audio 下不可用）：卡片保留但置灰，
     * 右侧显示「不可用」。会话级状态——随当前传输的能力表变化。 */
    val connectionQualityRestrictedByLea: Boolean = false,
    /**
     * SC 的 `FunctionCantBeUsedWithLEAConnectionType` 对应的 FunctionType 集合。
     * 来自能力表 support-function list 中所有 `*_CANT_BE_USED_WITH_LEA_CONNECTION`
     * 条目，决定哪些功能在 LE Audio 连接下不可用。
     *
     * 对照 SC `DeviceCapabilityTableset2.x1()`：每个 FunctionCantBeUsedWithLEAConnectionType
     * 映射到一个 FunctionType，能力表的 support-function list 包含该 FunctionType
     * 时，对应功能在 LEA 下不可用。
     */
    val leaRestrictedFunctionTypes: Set<SonyV2FunctionType> = emptySet(),
    /** UpscalingType byte from AUDIO_RET_CAPABILITY (`cf0.e0`) — the DSEE
     * generation: DSEE_HX=0, DSEE=1, DSEE_HX_AI/Extreme=2, DSEE_ULTIMATE=3.
     * The official title/description strings are picked from this, not from
     * which FunctionType the support list carried. Null = not probed yet. */
    val upscalingTypeCode: Int? = null,
    /** FunctionType CODEC_INDICATOR advertised — enables the live codec badge
     * (COMMON_GET_STATUS AUDIO_CODEC + NTFY), mirroring SC `mo58633n()`. */
    val codecIndicatorSupported: Boolean = false,
    /** FunctionType UPSCALING_INDICATOR advertised — enables the live DSEE badge
     * (COMMON_GET_STATUS UPSCALING_EFFECT + NTFY), mirroring SC `mo58463A1()`. */
    val upscalingIndicatorSupported: Boolean = false,
    /** Whether the device declared any ALERT / FIXED_MESSAGE notification support. */
    val alertSupported: Boolean = false,
    val supportsAutoWindNoiseReduction: Boolean = false,
    val supportsWindNoiseReduction: Boolean = false,
    val supportsSpeakToChat: Boolean = false,
    val speakToChatType: dev.sonypods.protocol.SystemInquiredType? = null,
    /** Safe Listening inquired type this device's current-sound-pressure query
     * uses, from the advertised SAFE_LISTENING_* FunctionType (V2 Table2).
     * Null = the support-function list advertised none of them. */
    val safeListeningInquiredType: dev.sonypods.protocol.SafeListeningInquiredTypeTable2? = null,
    /** SC `mo58509L0()`: the merged Table1+Table2 support-function list contains a
     *  PAIRING_DEVICE_MANAGEMENT FunctionType (0x30/0x32/0x33). Verified against the
     *  decompiled chain (`u4()` → the merged RET_SUPPORT_FUNCTION list the tableset
     *  was built from) — a raw declaration test. GS-only devices (this LinkBuds S
     *  declares none of the three) reach the multipoint card through SC's
     *  `mo58500J()` instead — a GS slot whose capability reply names
     *  MULTIPOINT_SETTING — mirrored by [ConnectedHeadphoneProfile.multipointGsSlot]. */
    val supportsMultipointViaFunction: Boolean = false,
    /** SC `u70.C29444f` registers the 0x0D EXECUTE_TANDEM_TARGET_CHANGE handler
     *  only when the device declares `TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD`
     *  (0x40 — this headset declares it and sends 0x0D). Without the declaration
     *  the notification has no defined meaning. */
    val declaresTandemTargetChange: Boolean = false,
    /** SC `C14319c.mo61835c` registers the 0x0E CHANGE_TANDEM_CONNECTION_PROFILE_
     *  FOR_ANDROID observer only when the device declares 0x44. */
    val declaresChangeTandemConnectionProfile: Boolean = false,
    /** SC registers the 0x0F NOTIFY_DISCONNECTING_TANDEM observer for devices
     *  declaring any LEA-unicast-broadcast-with-CTKD FunctionType (TWS 0x40 /
     *  HBS 0x41 / PAS 0x64 Table2). */
    val declaresTandemDisconnectingNotification: Boolean = false,
    /**
     * SC `C14319c.mo61835c` registers `V2GetIdentityResolvingKeyRepository` only when the device
     * declares `GET_IDENTITY_RESOLVING_KEY` (0x63, Table2). Without it SC's LE-address matching
     * degrades to exact comparison against the bonded set (`C12272j.m52768g` answers an empty key
     * array), so the same gate decides whether we ask at all.
     */
    val declaresIdentityResolvingKey: Boolean = false,
) {
    /** SC `FunctionCantBeUsedWithLEAConnectionType.PAIRING_DEVICE_MANAGEMENT` → 多点连接 */
    val multipointLeaRestricted: Boolean
        get() = SonyV2FunctionType.PAIRING_DEVICE_MANAGEMENT_CANT_BE_USED_WITH_LEA_CONNECTION in leaRestrictedFunctionTypes
    /** SC `FunctionCantBeUsedWithLEAConnectionType.QUICK_ACCESS` → Quick Access */
    val quickAccessLeaRestricted: Boolean
        get() = SonyV2FunctionType.QUICK_ACCESS_CANT_BE_USED_WITH_LEA_CONNECTION in leaRestrictedFunctionTypes
    /** SC `FunctionCantBeUsedWithLEAConnectionType.SOUND_AR` → Sound AR */
    val soundArLeaRestricted: Boolean
        get() = SonyV2FunctionType.SOUND_AR_CANT_BE_USED_WITH_LEA_CONNECTION in leaRestrictedFunctionTypes
    /** SC `FunctionCantBeUsedWithLEAConnectionType.BGM_MODE` → BGM 模式 */
    val bgmModeLeaRestricted: Boolean
        get() = SonyV2FunctionType.BGM_MODE_CANT_BE_USED_WITH_LEA_CONNECTION in leaRestrictedFunctionTypes
    /** SC `FunctionCantBeUsedWithLEAConnectionType.VOICE_ASSISTANT_SETTINGS` → 语音助手设置 */
    val voiceAssistantLeaRestricted: Boolean
        get() = SonyV2FunctionType.VOICE_ASSISTANT_SETTINGS_CANT_BE_USED_WITH_LEA_CONNECTION in leaRestrictedFunctionTypes
    /** SC `FunctionCantBeUsedWithLEAConnectionType.SOUND_AR_OPTIMIZATION` → Sound AR 优化 */
    val soundArOptimizationLeaRestricted: Boolean
        get() = SonyV2FunctionType.SOUND_AR_OPTIMIZATION_CANT_BE_USED_WITH_LEA_CONNECTION in leaRestrictedFunctionTypes
    /** SC `…SOUND_AR_OPTIMIZATION_CANT_BE_USED_WITH_LEA_CONNECTION_WITH_HEAD_TRACKING`,
     *  whose FunctionType is `HEAD_TRACKER_CANT_BE_USED_WITH_LEA_CONNECTION` (0x46) —
     *  a different declaration from plain Sound AR optimization (0x4B) above. */
    val headTrackerLeaRestricted: Boolean
        get() = SonyV2FunctionType.HEAD_TRACKER_CANT_BE_USED_WITH_LEA_CONNECTION in leaRestrictedFunctionTypes
    /** SC `FunctionCantBeUsedWithLEAConnectionType.LINK_AUTO_SWITCH` → 自动切换 */
    val linkAutoSwitchLeaRestricted: Boolean
        get() = SonyV2FunctionType.LINK_AUTO_SWITCH_CANT_BE_USED_WITH_LEA_CONNECTION in leaRestrictedFunctionTypes
}

data class ConnectedHeadphoneProfile(
    val adapterId: String,
    val brand: String,
    val modelName: String,
    val displayName: String,
    val protocolName: String,
    val series: String? = null,
    val transport: HeadphoneTransport = HeadphoneTransport.UNKNOWN,
    val capabilities: HeadphoneCapabilities,
    val featureProtocolMap: Map<HeadphoneFeature, HeadphoneProtocolVariant> = emptyMap(),
    val featureBindings: Map<HeadphoneFeature, FeatureProtocolBinding> = emptyMap(),
    val protocolEvidence: List<String> = emptyList(),
    val playbackDispatchStrategy: PlaybackDispatchStrategy = PlaybackDispatchStrategy.TANDEM_FIRST,
    /** Peripheral pairing domain selected by the headset (0x00 or 0x02). */
    val multipointTypeCode: Int? = null,
    /** V2 Table1 General Setting slot (0xD1..0xD4) whose title matched
     * "MULTIPOINT_SETTING" — the "同时连接2台设备" toggle slot. Discovered via
     * GS GET_CAPABILITY, mirroring SC `DeviceCapabilityTableset2.E1()`. */
    val multipointGsSlot: Int? = null,
    /** Runtime protocol version reported by CONNECT_RET_PROTOCOL_INFO (2 bytes BE),
     * validated against [dev.sonypods.protocol.SonyTandemConstants.PROTOCOL_VERSIONS]. */
    val protocolVersion: Int? = null,
    /** Bumped whenever the profile is rebound to a different protocol generation
     * or transport; stale probe-derived evidence is discarded on a rebind. */
    val rebindGeneration: Int = 0,
    /**
     * The device's own capability table has been applied — either the live
     * RET_SUPPORT_FUNCTION reply or the counter-matched cache restore.
     *
     * Everything model-shaped comes from that table: the form factor, the battery query
     * set, which noise-control types are writable, EQ. Until it lands the profile is the
     * neutral fallback, which asks a single BATTERY question and reports UNKNOWN form
     * factor — render on that and a pair of buds appears as a single-battery headband.
     * Every surface gates on this, which is why it is a fact about the profile rather than
     * about the probe: a probe that stopped without a table must not read as ready.
     */
    val capabilitiesKnown: Boolean = false,
) {
    fun supports(feature: HeadphoneFeature): Boolean = feature in capabilities.features
    val isV1: Boolean
        get() = featureBindings.values.any { it.variant == HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1 || it.variant == HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2 } ||
            featureProtocolMap.values.any { it == HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1 || it == HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2 }
    fun protocolFor(feature: HeadphoneFeature): HeadphoneProtocolVariant =
        featureBindings[feature]?.variant ?: featureProtocolMap[feature] ?: HeadphoneProtocolVariant.UNKNOWN
    fun bindingFor(feature: HeadphoneFeature): FeatureProtocolBinding? = featureBindings[feature]
    fun channelFor(feature: HeadphoneFeature): TandemChannel =
        featureBindings[feature]?.channel
            ?: protocolFor(feature)
                .takeIf { it != HeadphoneProtocolVariant.UNKNOWN }
                ?.let(::defaultChannelFor)
            ?: error("No protocol channel binding for $feature on $modelName")

    fun defaultResponseChannel(): TandemChannel =
        featureBindings.values
            .firstOrNull { it.channel == TandemChannel.GATT_V2_HPC }
            ?.channel
            ?: featureBindings.values.firstOrNull()?.channel
            ?: TandemChannel.SPP_MDR

    /** Record the validated runtime protocol version from RET_PROTOCOL_INFO. */
    fun withProtocolVersion(version: Int): ConnectedHeadphoneProfile =
        copy(protocolVersion = version)

    /** Mark this profile as rebound (new generation/transport); clears the
     * protocol version so a stale whitelist verdict is never reused, and the
     * capability table with it — its function codes are specific to the generation
     * that answered, so it says nothing about the one now bound. */
    fun rebounded(): ConnectedHeadphoneProfile =
        copy(rebindGeneration = rebindGeneration + 1, protocolVersion = null, capabilitiesKnown = false)
}

data class ProfileTemplate(
    val modelName: String,
    val series: String?,
    val capabilities: HeadphoneCapabilities,
    val featureProtocolMap: Map<HeadphoneFeature, HeadphoneProtocolVariant>,
    val knownStaticProfile: Boolean = true,
) {
    init {
        if (knownStaticProfile) {
            val missingFeatures = capabilities.features - featureProtocolMap.keys
            require(missingFeatures.isEmpty()) {
                "Static profile $modelName is missing protocol bindings for $missingFeatures"
            }
        }
    }

    val featureBindings: Map<HeadphoneFeature, FeatureProtocolBinding> by lazy {
        buildFeatureBindings(featureProtocolMap, capabilities)
    }

    fun toProfile(adapterId: String, brand: String, protocolName: String, displayName: String): ConnectedHeadphoneProfile =
        ConnectedHeadphoneProfile(
            adapterId = adapterId,
            brand = brand,
            modelName = modelName,
            displayName = displayName.removePrefix("LE_").takeIf { it.isNotBlank() } ?: modelName,
            protocolName = protocolName,
            series = series,
            capabilities = capabilities,
            featureProtocolMap = featureProtocolMap,
            featureBindings = featureBindings,
            protocolEvidence = if (knownStaticProfile) {
                listOf(
                    "static-profile:$modelName",
                    "reverse:C11518x DeviceCapabilityTableset1/2 dispatch",
                    "reverse:MdlSeries table-set mapping",
                )
            } else {
                listOf(
                    "probe-only:unknown-sony-device",
                    "reverse:C11518x DeviceCapabilityTableset1/2 dispatch",
                )
            },
            playbackDispatchStrategy = if (knownStaticProfile) {
                PlaybackDispatchStrategy.TANDEM_FIRST
            } else {
                PlaybackDispatchStrategy.ANDROID_MEDIA_FALLBACK
            },
        )
}

val ConnectedHeadphoneProfile.eqUiCapability: EqUiCapability
    get() = EqProtocolEngine.uiCapability(capabilities.eqConfig)

/** Build the per-feature protocol/channel/query bindings for a feature map.
 * Shared by [ProfileTemplate] and dynamic rebinding, so a profile rebound to a
 * different protocol generation keeps every domain addressable. */
/**
 * @param endpoints the transport endpoints this connection actually exposes. Sound Connect has
 * no per-table channel concept at all — a session is `C22925e(CommandTableSet, transport)` and
 * sending picks a DataType from the table alone — so a table must not imply an endpoint. When
 * the endpoints are known, commands go to one that exists; [defaultChannelFor] is only the
 * answer for a profile built before any transport is up.
 */
fun buildFeatureBindings(
    featureProtocolMap: Map<HeadphoneFeature, HeadphoneProtocolVariant>,
    capabilities: HeadphoneCapabilities,
    endpoints: Set<TandemChannel> = emptySet(),
): Map<HeadphoneFeature, FeatureProtocolBinding> =
    featureProtocolMap.mapValues { (feature, variant) ->
        val bindingVariant = if (feature == HeadphoneFeature.LEA_STATUS) {
            capabilities.lea?.historyVariant ?: variant
        } else {
            variant
        }
        FeatureProtocolBinding(
            feature = feature,
            variant = bindingVariant,
            // Multipoint is a V2 Table2 peripheral domain even when the
            // main profile is negotiated as Table1 on the HPC channel.
            channel = if (feature == HeadphoneFeature.LEA_STATUS && capabilities.lea != null) {
                capabilities.lea.historyChannel
            } else if (
                feature == HeadphoneFeature.MULTIPOINT &&
                    feature in capabilities.features &&
                    variant == HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1
            ) TandemChannel.GATT_V2_MC else defaultChannelFor(bindingVariant),
            queryTypes = queryTypesForFeature(feature, capabilities),
            writableTypes = writableTypesForFeature(feature, capabilities),
        ).onEndpoints(endpoints)
    }

/**
 * Moves a binding onto an endpoint this connection actually has.
 *
 * The endpoint a variant defaults to may simply not exist: on LE Audio the headset exposes only
 * TANDEM_V2_HPC_SERVICE, and Sound Connect runs table 2 over exactly that service. Keeping the
 * default would address an endpoint that was never discovered and the frame would be dropped.
 */
private fun FeatureProtocolBinding.onEndpoints(
    endpoints: Set<TandemChannel>,
): FeatureProtocolBinding {
    if (endpoints.isEmpty() || channel in endpoints) return this
    val substitute = endpoints.singleOrNull() ?: return this
    return copy(channel = substitute)
}

private fun queryTypesForFeature(
    feature: HeadphoneFeature,
    capabilities: HeadphoneCapabilities,
): List<Any> = when (feature) {
    HeadphoneFeature.BATTERY -> capabilities.batteryQueries
    HeadphoneFeature.NOISE_CONTROL,
    HeadphoneFeature.AMBIENT_LEVEL,
    HeadphoneFeature.AMBIENT_VOICE_MODE,
    HeadphoneFeature.NOISE_ADAPTIVE -> capabilities.noiseControlQueryTypes
    HeadphoneFeature.EQ,
    HeadphoneFeature.CLEAR_BASS -> capabilities.eqConfig.statusQueryTypes + capabilities.eqConfig.paramQueryTypes
    HeadphoneFeature.PLAYBACK_CONTROL -> listOf(capabilities.playbackControlType)
    else -> emptyList()
}

private fun writableTypesForFeature(
    feature: HeadphoneFeature,
    capabilities: HeadphoneCapabilities,
): Set<Any> = when (feature) {
    HeadphoneFeature.NOISE_CONTROL,
    HeadphoneFeature.AMBIENT_LEVEL,
    HeadphoneFeature.AMBIENT_VOICE_MODE,
    HeadphoneFeature.NOISE_ADAPTIVE -> capabilities.writableNoiseControlTypes
    HeadphoneFeature.PLAYBACK_CONTROL -> setOf(capabilities.playbackControlType)
    else -> emptySet()
}

interface HeadphoneAdapter {
    val id: String
    val brand: String
    val protocolName: String

    fun match(device: DiscoveredSonyDevice, reportedModelName: String? = null): ConnectedHeadphoneProfile?
    fun fallbackProfile(device: DiscoveredSonyDevice): ConnectedHeadphoneProfile
    fun withTransport(profile: ConnectedHeadphoneProfile, transport: HeadphoneTransport): ConnectedHeadphoneProfile =
        profile.copy(transport = transport)

    fun matchTemplate(
        template: ProfileTemplate,
        device: DiscoveredSonyDevice,
        reportedModelName: String? = null,
    ): ConnectedHeadphoneProfile? {
        val candidates = listOfNotNull(reportedModelName, device.name.removePrefix("LE_"))
        val matched = candidates.any { candidate ->
            candidate.normalizedModelName().contains(template.modelName.normalizedModelName())
        }
        return if (matched) template.toProfile(id, brand, protocolName, device.name) else null
    }

    fun buildRefreshCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand>

    /**
     * The domains whose connection-time replies a caller should wait for before
     * treating the session as operable.
     *
     * [buildRefreshCommands] fires its whole burst at once and returns nothing about
     * what will answer it, so a caller cannot tell "asked" from "answered": every
     * control still reads its default value while the replies are in flight. This is
     * the subset of that burst the headset is expected to answer for this profile —
     * one reply in each listed domain means the values behind the UI have arrived.
     */
    fun initialValueDomains(profile: ConnectedHeadphoneProfile): Set<HeadphoneFeature> = emptySet()

    fun buildSetNoiseControlModeCommands(
        profile: ConnectedHeadphoneProfile,
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
        noiseAdaptive: Boolean = false,
        noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.STANDARD,
        windNoiseReduction: Boolean = false,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetEqPresetCommands(
        profile: ConnectedHeadphoneProfile,
        preset: EqPresetId,
        context: EqWriteContext,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetEqBandCommands(
        profile: ConnectedHeadphoneProfile,
        rawSteps: List<Int>,
        preset: EqPresetId?,
        context: EqWriteContext,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetClearBassCommands(
        profile: ConnectedHeadphoneProfile,
        level: Int,
        context: EqWriteContext,
    ): List<HeadphoneCommand> =
        emptyList()

    fun buildRefreshNoiseControlCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> = emptyList()

    fun buildRefreshEqCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> = emptyList()

    fun buildRefreshBatteryCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> = emptyList()

    fun buildPowerOffCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> = emptyList()

    fun buildSetLeAudioEnabledCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
        changeConnectionMethod: Boolean = true,
    ): List<HeadphoneCommand> = emptyList()

    fun buildRefreshLeaPairedHistoryCommands(
        profile: ConnectedHeadphoneProfile,
    ): List<HeadphoneCommand> = emptyList()

    fun buildRefreshPlaybackCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> = emptyList()

    fun buildRefreshGestureOperationsCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> = emptyList()

    fun buildSetQuickAccessFunction(
        profile: ConnectedHeadphoneProfile,
        functionCodes: List<Int>,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetGesturePresetsCommands(
        profile: ConnectedHeadphoneProfile,
        presets: List<AssignableSettingsPreset>,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetGestureMappingsCommands(
        profile: ConnectedHeadphoneProfile,
        mappings: List<AssignableSettingsMapping>,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetSpeakToChatEnabledCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetSpeakToChatParamsCommands(
        profile: ConnectedHeadphoneProfile,
        sensitivity: dev.sonypods.protocol.SmartTalkingDetectionSensitivity,
        modeOutTime: dev.sonypods.protocol.SmartTalkingModeOutTime,
        voiceFocus: Boolean = false,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetMultipointPairingModeCommands(
        profile: ConnectedHeadphoneProfile,
        inquiry: Boolean,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetMultipointDeviceCommand(
        profile: ConnectedHeadphoneProfile,
        address: String,
        action: MultipointDeviceAction,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetSourceSwitchCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
    ): List<HeadphoneCommand> = emptyList()

    /** Toggle the V2 Table1 General Setting "同时连接2台设备" slot. */
    fun buildSetMultipointEnabledCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
    ): List<HeadphoneCommand> = emptyList()

    /** Reply to a pending FIXED_MESSAGE alert (V2 Table1 ALERT_SET_PARAM 0x98),
     * echoing [messageType] with POSITIVE/NEGATIVE action. */
    fun buildReplyAlertCommand(
        profile: ConnectedHeadphoneProfile,
        messageType: Int,
        positive: Boolean,
    ): List<HeadphoneCommand> = emptyList()

    fun buildReplyAlertCommand(
        profile: ConnectedHeadphoneProfile,
        alert: ParsedTandemResponse,
        positive: Boolean,
    ): List<HeadphoneCommand> = when (alert) {
        is ParsedTandemResponse.AlertFixedMessage -> buildReplyAlertCommand(profile, alert.messageType, positive)
        is ParsedTandemResponse.AlertForegroundMessage -> buildReplyAlertCommand(profile, alert.messageType, positive)
        is ParsedTandemResponse.AlertFixedMessageWithLeftRightSelection -> buildReplyAlertCommand(profile, alert.messageType, positive)
        is ParsedTandemResponse.AlertFlexibleMessage -> buildReplyAlertCommand(profile, alert.messageType, positive)
        else -> emptyList()
    }

    fun buildSetFixedSourceCommand(
        profile: ConnectedHeadphoneProfile,
        address: String,
    ): List<HeadphoneCommand> = emptyList()

    fun buildSetMusicHandOverCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
    ): List<HeadphoneCommand> = emptyList()

    fun buildPlaybackCommands(profile: ConnectedHeadphoneProfile, control: PlaybackControl): List<HeadphoneCommand> =
        emptyList()

    fun buildSetPlaybackVolumeCommands(profile: ConnectedHeadphoneProfile, volume: Int): List<HeadphoneCommand> =
        emptyList()

    fun parse(profile: ConnectedHeadphoneProfile, channel: TandemChannel, raw: ByteArray): ParsedTandemResponse

    fun parse(profile: ConnectedHeadphoneProfile, raw: ByteArray): ParsedTandemResponse =
        parse(profile, profile.defaultResponseChannel(), raw)

    fun canWrite(profile: ConnectedHeadphoneProfile, feature: HeadphoneFeature): Boolean =
        profile.supports(feature)
}

enum class MultipointDeviceAction {
    CONNECT,
    DISCONNECT,
    UNPAIR,
}

object HeadphoneAdapterRegistry {
    private val adapters: List<HeadphoneAdapter> = listOf(SonyTandemHeadphoneAdapter)

    fun resolve(device: DiscoveredSonyDevice, reportedModelName: String? = null): ConnectedHeadphoneProfile {
        adapters.forEach { adapter ->
            adapter.match(device, reportedModelName)?.let { return it }
        }
        return SonyTandemHeadphoneAdapter.fallbackProfile(device)
    }

    fun buildRefreshCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        adapterFor(profile).buildRefreshCommands(profile)

    fun initialValueDomains(profile: ConnectedHeadphoneProfile): Set<HeadphoneFeature> =
        adapterFor(profile).initialValueDomains(profile)

    fun canWrite(profile: ConnectedHeadphoneProfile, feature: HeadphoneFeature): Boolean =
        adapterFor(profile).canWrite(profile, feature)

    fun buildSetNoiseControlModeCommands(
        profile: ConnectedHeadphoneProfile,
        mode: NoiseControlMode,
        ambientLevel: Int,
        ambientMode: AmbientSoundMode,
        noiseAdaptive: Boolean = false,
        noiseAdaptiveSensitivity: NoiseAdaptiveSensitivity = NoiseAdaptiveSensitivity.STANDARD,
        windNoiseReduction: Boolean = false,
    ): List<HeadphoneCommand> =
        adapterFor(profile).buildSetNoiseControlModeCommands(
            profile, mode, ambientLevel, ambientMode, noiseAdaptive, noiseAdaptiveSensitivity, windNoiseReduction,
        )

    fun buildSetEqPresetCommands(
        profile: ConnectedHeadphoneProfile,
        preset: EqPresetId,
        context: EqWriteContext,
    ): List<HeadphoneCommand> =
        adapterFor(profile).buildSetEqPresetCommands(profile, preset, context)

    fun buildSetEqBandCommands(
        profile: ConnectedHeadphoneProfile,
        rawSteps: List<Int>,
        preset: EqPresetId?,
        context: EqWriteContext,
    ): List<HeadphoneCommand> =
        adapterFor(profile).buildSetEqBandCommands(profile, rawSteps, preset, context)

    fun buildSetClearBassCommands(
        profile: ConnectedHeadphoneProfile,
        level: Int,
        context: EqWriteContext,
    ): List<HeadphoneCommand> =
        adapterFor(profile).buildSetClearBassCommands(profile, level, context)

    fun buildRefreshNoiseControlCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        adapterFor(profile).buildRefreshNoiseControlCommands(profile)

    fun buildRefreshEqCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        adapterFor(profile).buildRefreshEqCommands(profile)

    fun buildRefreshBatteryCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        adapterFor(profile).buildRefreshBatteryCommands(profile)

    fun buildPowerOffCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        adapterFor(profile).buildPowerOffCommands(profile)

    fun buildSetLeAudioEnabledCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
        changeConnectionMethod: Boolean = true,
    ): List<HeadphoneCommand> =
        adapterFor(profile).buildSetLeAudioEnabledCommands(profile, enabled, changeConnectionMethod)

    fun buildRefreshLeaPairedHistoryCommands(
        profile: ConnectedHeadphoneProfile,
    ): List<HeadphoneCommand> =
        adapterFor(profile).buildRefreshLeaPairedHistoryCommands(profile)

    fun buildRefreshPlaybackCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        adapterFor(profile).buildRefreshPlaybackCommands(profile)

    fun buildPlaybackCommands(profile: ConnectedHeadphoneProfile, control: PlaybackControl): List<HeadphoneCommand> =
        adapterFor(profile).buildPlaybackCommands(profile, control)

    fun buildSetPlaybackVolumeCommands(profile: ConnectedHeadphoneProfile, volume: Int): List<HeadphoneCommand> =
        adapterFor(profile).buildSetPlaybackVolumeCommands(profile, volume)

    fun buildRefreshGestureOperationsCommands(profile: ConnectedHeadphoneProfile): List<HeadphoneCommand> =
        adapterFor(profile).buildRefreshGestureOperationsCommands(profile)

    fun buildSetQuickAccessFunction(
        profile: ConnectedHeadphoneProfile,
        functionCodes: List<Int>,
    ): List<HeadphoneCommand> = adapterFor(profile).buildSetQuickAccessFunction(profile, functionCodes)

    fun buildSetGesturePresetsCommands(
        profile: ConnectedHeadphoneProfile,
        presets: List<AssignableSettingsPreset>,
    ): List<HeadphoneCommand> = adapterFor(profile).buildSetGesturePresetsCommands(profile, presets)

    fun buildSetGestureMappingsCommands(
        profile: ConnectedHeadphoneProfile,
        mappings: List<AssignableSettingsMapping>,
    ): List<HeadphoneCommand> = adapterFor(profile).buildSetGestureMappingsCommands(profile, mappings)

    fun buildSetSpeakToChatEnabledCommands(
        profile: ConnectedHeadphoneProfile,
        enabled: Boolean,
    ): List<HeadphoneCommand> = adapterFor(profile).buildSetSpeakToChatEnabledCommands(profile, enabled)

    fun buildSetSpeakToChatParamsCommands(
        profile: ConnectedHeadphoneProfile,
        sensitivity: dev.sonypods.protocol.SmartTalkingDetectionSensitivity,
        modeOutTime: dev.sonypods.protocol.SmartTalkingModeOutTime,
        voiceFocus: Boolean = false,
    ): List<HeadphoneCommand> = adapterFor(profile).buildSetSpeakToChatParamsCommands(profile, sensitivity, modeOutTime, voiceFocus)

    fun buildSetMultipointPairingModeCommands(
        profile: ConnectedHeadphoneProfile,
        inquiry: Boolean,
    ): List<HeadphoneCommand> = adapterFor(profile).buildSetMultipointPairingModeCommands(profile, inquiry)

    fun buildSetMultipointDeviceCommand(
        profile: ConnectedHeadphoneProfile,
        address: String,
        action: MultipointDeviceAction,
    ): List<HeadphoneCommand> = adapterFor(profile).buildSetMultipointDeviceCommand(profile, address, action)

    fun buildSetSourceSwitchCommands(profile: ConnectedHeadphoneProfile, enabled: Boolean): List<HeadphoneCommand> =
        adapterFor(profile).buildSetSourceSwitchCommands(profile, enabled)

    fun buildSetMultipointEnabledCommands(profile: ConnectedHeadphoneProfile, enabled: Boolean): List<HeadphoneCommand> =
        adapterFor(profile).buildSetMultipointEnabledCommands(profile, enabled)

    fun buildReplyAlertCommand(profile: ConnectedHeadphoneProfile, messageType: Int, positive: Boolean): List<HeadphoneCommand> =
        adapterFor(profile).buildReplyAlertCommand(profile, messageType, positive)

    fun buildReplyAlertCommand(profile: ConnectedHeadphoneProfile, alert: ParsedTandemResponse, positive: Boolean): List<HeadphoneCommand> =
        adapterFor(profile).buildReplyAlertCommand(profile, alert, positive)

    fun buildSetFixedSourceCommand(profile: ConnectedHeadphoneProfile, address: String): List<HeadphoneCommand> =
        adapterFor(profile).buildSetFixedSourceCommand(profile, address)

    fun buildSetMusicHandOverCommands(profile: ConnectedHeadphoneProfile, enabled: Boolean): List<HeadphoneCommand> =
        adapterFor(profile).buildSetMusicHandOverCommands(profile, enabled)

    fun parse(profile: ConnectedHeadphoneProfile, channel: TandemChannel, raw: ByteArray): ParsedTandemResponse =
        adapterFor(profile).parse(profile, channel, raw)

    fun parse(profile: ConnectedHeadphoneProfile, raw: ByteArray): ParsedTandemResponse =
        adapterFor(profile).parse(profile, raw)

    private fun adapterFor(profile: ConnectedHeadphoneProfile): HeadphoneAdapter =
        adapters.firstOrNull { it.id == profile.adapterId }
            ?: adapters.firstOrNull { adapter ->
                adapter is SonyTandemHeadphoneAdapter && profile.adapterId in adapter.legacyIds
            }
            ?: SonyTandemHeadphoneAdapter
}

fun String.normalizedModelName(): String =
    uppercase()
        .removePrefix("LE_")
        .replace(Regex("[\\s\\-_.]+"), "")

fun defaultChannelFor(variant: HeadphoneProtocolVariant): TandemChannel =
    when (variant) {
        HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1,
        HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2 -> TandemChannel.GATT_V1_MC
        HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2 -> TandemChannel.GATT_V2_MC
        HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1 -> TandemChannel.GATT_V2_HPC
        HeadphoneProtocolVariant.UNKNOWN -> error("Unknown protocol variant has no default channel")
    }
