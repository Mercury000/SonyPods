package dev.sonypods.bridge

import android.os.Bundle
import dev.sonypods.data.GestureOperationAction
import dev.sonypods.data.GestureOperationKey
import dev.sonypods.data.QuickAccessActionState
import dev.sonypods.data.SonyHeadphoneUiState
import dev.sonypods.protocol.AssignableSettingsAction
import dev.sonypods.protocol.AssignableSettingsFunction
import dev.sonypods.protocol.AssignableSettingsKey
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.AssignableSettingsType
import dev.sonypods.protocol.DseeGeneration
import dev.sonypods.protocol.SoundQualityCodec
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.PlaybackStatus

/**
 * The full headphone state as carried across processes.
 *
 * The Sony Tandem engine lives in the `com.android.bluetooth` hook process so that
 * system-surface controls keep working when the module app is not running. Every
 * other process (module UI, com.xiaomi.bluetooth, com.milink.service,
 * com.android.settings) is a consumer that learns the state from this snapshot.
 *
 * Kept as a flat [Bundle] rather than a Parcelable: the receiving processes load our
 * classes through LSPosed, and a Parcelable would have to be unmarshalled by a class
 * loader that may not be the one that wrote it.
 */
data class SonyStateSnapshot(
    val connected: Boolean = false,
    val protocolReady: Boolean = false,
    /**
     * The device's own capability table has been applied: the live RET_SUPPORT_FUNCTION
     * replies, or the cached table restored on a capability-counter match.
     *
     * Every surface gates on this. Until the table lands the profile is the neutral fallback
     * — UNKNOWN form factor, a single battery query, nothing writable — and rendering it
     * shows a pair of buds as a single-battery headband and greys out controls the device
     * does have. Everything model-shaped (form factor, battery slots, which noise-control
     * types are writable, EQ) comes from that table, so nothing may be drawn before it lands.
     */
    val capabilitiesKnown: Boolean = false,
    /**
     * True once the connection-time value burst is done: every domain the adapter expects
     * has answered, nothing is left to transmit and the channel has settled (or the wait
     * timed out).
     *
     * [capabilitiesKnown] only says which features exist — over LE it is true seconds before
     * the first value reply, so a UI opened on it renders untappable defaults. One reply per
     * domain is not enough either: a domain is several queries. The app UI waits for this
     * instead.
     */
    val initialValuesReady: Boolean = false,
    /**
     * The same gate reduced to what a surface outside the app renders: battery and the
     * noise-control mode. The island, the connection notification and the official
     * fast-connect dialog wait for this rather than for the whole burst.
     */
    val essentialValuesReady: Boolean = false,
    /**
     * Whether the system has an audio profile (A2DP or LE Audio) connected to this headset.
     *
     * Not the same fact as [connected], which is the Tandem control transport: audio connects first
     * and the Tandem session is built on top of it, over LE after seconds of GATT discovery. It is
     * also not the same as an ACL probe — the LE identity's link outlives audio by seconds.
     *
     * This is what separates "the headset is here and the session is still coming up" from "the
     * headset is leaving", which no other field in this snapshot can express.
     */
    val audioLinkConnected: Boolean = false,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val firmwareVersion: String? = null,
    /** Physical form: "HEADSET" (over-ear, single battery) / "TRUE_WIRELESS" /
     * "UNKNOWN". Carried from the connected profile so system surfaces (fusion
     * device center, settings injection) can present a single-battery over-ear
     * headphone instead of projecting it onto a TWS case/left/right layout. */
    val formFactor: String? = null,
    /** Cloud catalog image URL for this model and colour. */
    val modelImageUrl: String? = null,
    /** Catalog accent colour (ARGB hex) for system surfaces. */
    val modelImageSourceColor: String? = null,
    val supportsPowerOff: Boolean = false,
    val supportsGestureOperations: Boolean = false,
    val supportsMultipoint: Boolean = false,
    /** SC `mo58509L0()`: the device's support-function list declares multipoint
     *  via Table2 FunctionType, independent of the runtime GS-slot discovery. */
    val supportsMultipointViaFunction: Boolean = false,
    /**
     * Every headset the engine has identified, one serialized [dev.sonypods.device.HeadsetRecord]
     * per line.
     *
     * Only the process holding the Tandem session learns any of this — the identity comes from the
     * headset's own `LEA_RET_CAPABILITY` reply and its LEA streaming status, not from anything a
     * consumer process could read. So this is the sole feed: nothing downstream re-derives identity
     * from names, `BluetoothDevice.type` or a UUID cache. Those disagree with each other, and the
     * UUID one flips outright once the LE Audio pairing flow puts ASCS into the control identity's
     * cache.
     */
    val headsetRecords: List<String> = emptyList(),
    val supportsNoiseControl: Boolean = false,
    val supportsEq: Boolean = false,
    val supportsPlaybackControl: Boolean = false,
    val batterySingle: Int? = null,
    val batteryLeft: Int? = null,
    val batteryRight: Int? = null,
    val batteryCradle: Int? = null,

    val noiseControlMode: NoiseControlMode? = null,
    val ambientLevel: Int? = null,
    val ambientVoiceMode: Boolean = false,
    val supportsAutoWindNoiseReduction: Boolean = false,
    val supportsWindNoiseReduction: Boolean = false,
    val windNoiseReduction: Boolean = false,
    val supportsSpeakToChat: Boolean = false,
    val speakToChatEnabled: Boolean = false,
    val speakToChatSensitivity: String? = null,
    val speakToChatModeOutTime: String? = null,
    val supportsNoiseAdaptive: Boolean = false,
    val noiseAdaptiveEnabled: Boolean = false,
    /** [dev.sonypods.protocol.NoiseAdaptiveSensitivity] name, null when unknown. */
    val noiseAdaptiveSensitivity: String? = null,

    val eqPreset: EqPresetId? = null,
    val eqAvailablePresets: List<EqPresetId> = emptyList(),
    val eqClearBass: Int? = null,
    val eqHasClearBass: Boolean = true,
    val eqBandSteps: List<Int> = emptyList(),
    val eqBandLabels: List<String> = emptyList(),
    val eqBandMin: Int = -10,
    val eqBandMax: Int = 10,
    val eqClearBassMin: Int = -10,
    val eqClearBassMax: Int = 10,

    val supportsLeAudio: Boolean = false,
    /**
     * Whether the *phone* itself supports LE Audio (Xiaomi's own gate for the LE
     * Audio / Auracast UI: `BluetoothAdapter.isLeAudioSupported() == 10`). False
     * hides the LE Audio card and the LC3-only behaviors — the headset's
     * capability says nothing about the phone being able to carry LC3.
     */
    val phoneSupportsLeAudio: Boolean = true,
    val leaStatus: String? = null,
    /** DSEE / DSEE Extreme (AUDIO-domain upscaling): supported + current state.
     * [upscalingTypeCode] is the AUDIO_RET_CAPABILITY generation byte
     * (DSEE_HX=0, DSEE=1, DSEE_Extreme=2, DSEE_Ultimate=3; -1 = not probed yet)
     * that the official title/description strings are picked from. */
    val supportsUpscaling: Boolean = false,
    val upscalingEnabled: Boolean? = null,
    val upscalingTypeCode: Int = -1,
    /** Bluetooth 连接质量（AUDIO 域 CONNECTION_MODE 系）：当前 PriorMode 名、
     * 可用性（null=未应答，按可用对待），以及能力表宣告的 inquired 类型码。 */
    val connectionQualityModeName: String? = null,
    val connectionQualityEnabled: Boolean? = null,
    val supportsConnectionQuality: Boolean = false,
    val connectionQualityRestrictedByLea: Boolean = false,
    /**
     * SC 的 FunctionCantBeUsedWithLEAConnectionType 对应的 FunctionType 集合。
     * 来自能力表 support-function list，决定哪些功能在 LE Audio 连接下不可用。
     */
    val leaRestrictedFunctionTypes: Set<dev.sonypods.protocol.SonyV2FunctionType> = emptySet(),
    val connectionQualitySwitching: Boolean = false,
    val leaStreamingStatusL: String? = null,
    val leaStreamingStatusR: String? = null,
    val leAudioPending: Boolean = false,
    val leAudioPendingTargetEnabled: Boolean = false,
    val leAudioPendingMessageType: Int? = null,
    val leAudioPendingInquiredType: Int? = null,
    val leAudioPendingItemCodes: List<Int> = emptyList(),
    val leAudioSwitchPending: Boolean = false,
    /** [dev.sonypods.leaudio.LeAudioBond.Stage] name for the phone-side bond. */
    val leAudioDevicePairStage: String = "IDLE",
    /**
     * What the last run has to report beyond its stage: progress while it works, the reason it
     * failed, or the set being one earbud short. Empty when it has nothing to add.
     */
    val leAudioDevicePairMessage: String = "",
    /**
     * The identity the last run bonded — a hint for finding the bond again, not a claim that it
     * still exists. Whether the phone holds an LE Audio identity is [leAudioIdentityAddress], read
     * from the stack; this only tells the bluetooth process which address to look at when no
     * session names the headset, which is the state the pairing flow always ends in.
     */
    val leAudioDevicePairedAddress: String? = null,
    /**
     * The bonded identity the system's LE Audio permission applies to, or null when the phone
     * holds none for this headset.
     *
     * That is the headset's LE-only bond for models whose control identity advertises no ASCS, and
     * the control identity itself for the dual-mode ones — which is how the permission stays
     * readable for a headset switched over from Sound Connect, with no module-created bond.
     *
     * Filled in by the bluetooth-process host from the current bonds, not from a pairing session:
     * the module app is restarted far more often than a bond changes, so the pairing stage above
     * says nothing about whether an identity exists right now. This is the only source for that
     * question — every surface that shows "paired" reads it here, so none of them can disagree.
     */
    val leAudioIdentityAddress: String? = null,
    /**
     * `BluetoothProfile.CONNECTION_POLICY_ALLOWED` on [leAudioIdentityAddress] — the state behind
     * the system's per-device "低功耗音频" switch. Null only when no read succeeded; a device the
     * stack holds no record for reads as CONNECTION_POLICY_UNKNOWN, which shows off.
     */
    val leAudioPolicyAllowed: Boolean? = null,
    /**
     * `LeAudioService` holds a CONNECTED state machine for one of this headset's identities.
     *
     * The permission above only says the system *may* connect LE Audio; this says it has. Null
     * when the profile service could not be read — it is absent until the profile starts.
     */
    val leAudioSystemConnected: Boolean? = null,
    /**
     * That LE Audio group is the stack's active audio route, i.e. media really is carried as LC3.
     *
     * The headset's own streaming status answers the same question, but only while it happens to
     * push a LEA status — this one is readable at any moment and is what the system acts on.
     */
    val leAudioSystemActive: Boolean? = null,
    val quickAccessLeftRight: String? = null,
    val quickAccessNcAmb: String? = null,
    val quickAccessKeyCode: Int? = null,
    val quickAccessTypeCode: Int? = null,
    val quickAccessEnabled: Boolean? = null,
    val quickAccessFunctionCodes: List<Int> = emptyList(),
    val quickAccessActions: List<QuickAccessActionSnapshot> = emptyList(),
    val gestureOperationKeys: List<GestureOperationKey> = emptyList(),
    val multipoint: MultipointSnapshot = MultipointSnapshot(),
    val wearingStatus: String? = null,
    val playbackStatus: PlaybackStatus = PlaybackStatus.UNKNOWN,
    /** null = unknown/UNSETTLED; "" = NOTHING (UI shows an "unknown" placeholder). */
    val playbackTrack: String? = null,
    val playbackArtist: String? = null,
    val playbackAlbum: String? = null,
    val playbackMusicVolume: Int? = null,
    /** 0 = no volume control on this device. */
    val playbackMusicVolumeStep: Int = 0,
    /** Live sound-quality badge values (COMMON domain); codec null or OTHER/UNSETTLED hides it. */
    val soundQualityCodec: SoundQualityCodec? = null,
    /**
     * The system's per-device LDAC switch, read from and written to the A2DP profile service.
     *
     * Supported means this bond's A2DP selectable capabilities actually list LDAC — the same test
     * the stack applies before accepting a codec preference. Enabled means LDAC is the codec
     * currently carrying media; null when nothing could be read, which is also the state while
     * A2DP is down (under LC3 there is no A2DP codec to switch). Switching covers the stack's
     * settling window after a write, during which the row is held so it cannot bounce.
     *
     * Filled in by the bluetooth-process host as it publishes, like the LE Audio facts above: the
     * repository never sees them.
     */
    val ldacSupported: Boolean = false,
    val ldacEnabled: Boolean? = null,
    val ldacSwitching: Boolean = false,
    val dseeGeneration: DseeGeneration? = null,
    val dseeActive: Boolean = false,
    val scanState: String? = null,
    /** Safe Listening current-sound-pressure readout. The row only draws when
     * [supportsSafeListening] (probe-derived); [safeListeningStatus] is a
     * [dev.sonypods.data.SafeListeningStatus] name. */
    val supportsSafeListening: Boolean = false,
    val safeListeningLevelDb: Int? = null,
    val safeListeningStatus: String? = null,
) {
    /** Aggregated level fed to the system bluetooth stack and the Xiaomi surfaces. */
    val systemBatteryLevel: Int?
        get() = listOfNotNull(batterySingle, batteryLeft, batteryRight).minOrNull()

    /**
     * Whether the phone-side bond is still being worked on.
     *
     * Stated as "not one of the resting stages" so that a stage added to
     * [dev.sonypods.leaudio.LeAudioBond.Stage] counts as busy without every UI that shows
     * progress having to learn its name.
     */
    val leAudioDevicePairing: Boolean
        get() = leAudioDevicePairStage !in RESTING_PAIR_STAGES

    /**
     * True when audio for this headset really is being carried over LE Audio right now.
     *
     * Two independent witnesses, either of which is conclusive: the stack has made the headset's
     * LE Audio group the active route, or the headset itself reports a bud streaming
     * `VIA_LE_AUDIO_UNICAST`. The stack's own view settles first and stays readable, while the
     * headset's arrives only with a LEA status notification — so neither alone is enough.
     */
    val usingLeAudio: Boolean
        get() = leAudioSystemActive == true ||
            leaStreamingStatusL == LEA_STREAMING_UNICAST ||
            leaStreamingStatusR == LEA_STREAMING_UNICAST

    /**
     * True when this phone holds the headset over LE Audio rather than classic Bluetooth.
     *
     * Sony's LC3 links do not carry every function of a classic one — multipoint above all: a
     * headset connected this way cannot hold a second device. The profile being connected is
     * enough, whether or not the stack has already handed the audio route over.
     */
    val connectedViaLeAudio: Boolean
        get() = leAudioSystemConnected == true || usingLeAudio

    /**
     * SC `mo58685x1(type)` narrowed to the current connection: the headset declares the
     * function unusable while LE Audio carries the audio, and LE Audio is in fact carrying
     * it. A headset that never declares the entry has no restriction concept at all, so
     * these stay false however the connection is routed.
     */
    private fun leaRestricted(type: dev.sonypods.protocol.SonyV2FunctionType): Boolean =
        connectedViaLeAudio && type in leaRestrictedFunctionTypes

    val quickAccessLeaRestricted: Boolean
        get() = leaRestricted(dev.sonypods.protocol.SonyV2FunctionType.QUICK_ACCESS_CANT_BE_USED_WITH_LEA_CONNECTION)

    val multipointLeaRestricted: Boolean
        get() = leaRestricted(
            dev.sonypods.protocol.SonyV2FunctionType.PAIRING_DEVICE_MANAGEMENT_CANT_BE_USED_WITH_LEA_CONNECTION,
        )

    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(KEY_CONNECTED, connected)
        putBoolean(KEY_PROTOCOL_READY, protocolReady)
        putBoolean(KEY_CAPABILITIES_KNOWN, capabilitiesKnown)
        putBoolean(KEY_INITIAL_VALUES_READY, initialValuesReady)
        putBoolean(KEY_ESSENTIAL_VALUES_READY, essentialValuesReady)
        putBoolean(KEY_AUDIO_LINK_CONNECTED, audioLinkConnected)
        deviceName?.let { putString(KEY_DEVICE_NAME, it) }
        deviceAddress?.let { putString(KEY_DEVICE_ADDRESS, it) }
        firmwareVersion?.let { putString(KEY_FIRMWARE, it) }
        formFactor?.let { putString(KEY_FORM_FACTOR, it) }
        modelImageUrl?.let { putString(KEY_MODEL_IMAGE, it) }
        modelImageSourceColor?.let { putString(KEY_MODEL_IMAGE_COLOR, it) }
        putBoolean(KEY_SUPPORTS_POWER_OFF, supportsPowerOff)
        putBoolean(KEY_SUPPORTS_GESTURES, supportsGestureOperations)
        putBoolean(KEY_SUPPORTS_MULTIPOINT, supportsMultipoint)
        putBoolean(KEY_SUPPORTS_MULTIPOINT_VIA_FUNCTION, supportsMultipointViaFunction)
        putStringArrayList(KEY_HEADSET_RECORDS, ArrayList(headsetRecords))
        putBoolean(KEY_SUPPORTS_NOISE_CONTROL, supportsNoiseControl)
        putBoolean(KEY_SUPPORTS_EQ, supportsEq)
        putBoolean(KEY_SUPPORTS_PLAYBACK, supportsPlaybackControl)
        putParcelableArrayList(KEY_GESTURES, ArrayList(gestureOperationKeys.map { it.toBundle() }))
        putBundle(KEY_MULTIPOINT, multipoint.toBundle())

        batterySingle?.let { putInt(KEY_BATTERY_SINGLE, it) }
        batteryLeft?.let { putInt(KEY_BATTERY_LEFT, it) }
        batteryRight?.let { putInt(KEY_BATTERY_RIGHT, it) }
        batteryCradle?.let { putInt(KEY_BATTERY_CRADLE, it) }

        noiseControlMode?.let { putString(KEY_NC_MODE, it.name) }
        ambientLevel?.let { putInt(KEY_AMBIENT_LEVEL, it) }
        putBoolean(KEY_AMBIENT_VOICE, ambientVoiceMode)
        putBoolean(KEY_SUPPORTS_AUTO_WIND_NOISE, supportsAutoWindNoiseReduction)
        putBoolean(KEY_SUPPORTS_WIND_NOISE, supportsWindNoiseReduction)
        putBoolean(KEY_WIND_NOISE, windNoiseReduction)
        putBoolean(KEY_SUPPORTS_SPEAK_TO_CHAT, supportsSpeakToChat)
        putBoolean(KEY_SPEAK_TO_CHAT_ENABLED, speakToChatEnabled)
        speakToChatSensitivity?.let { putString(KEY_SPEAK_TO_CHAT_SENSITIVITY, it) }
        speakToChatModeOutTime?.let { putString(KEY_SPEAK_TO_CHAT_MODE_OUT_TIME, it) }
        putBoolean(KEY_SUPPORTS_NOISE_ADAPTIVE, supportsNoiseAdaptive)
        putBoolean(KEY_NOISE_ADAPTIVE, noiseAdaptiveEnabled)
        noiseAdaptiveSensitivity?.let { putString(KEY_NOISE_ADAPTIVE_SENSITIVITY, it) }

        eqPreset?.let { putString(KEY_EQ_PRESET, it.name) }
        putStringArray(KEY_EQ_PRESETS, eqAvailablePresets.map { it.name }.toTypedArray())
        eqClearBass?.let { putInt(KEY_EQ_CLEAR_BASS, it) }
        putBoolean(KEY_EQ_HAS_CLEAR_BASS, eqHasClearBass)
        putIntArray(KEY_EQ_BANDS, eqBandSteps.toIntArray())
        putStringArray(KEY_EQ_BAND_LABELS, eqBandLabels.toTypedArray())
        putInt(KEY_EQ_BAND_MIN, eqBandMin)
        putInt(KEY_EQ_BAND_MAX, eqBandMax)
        putInt(KEY_EQ_CB_MIN, eqClearBassMin)
        putInt(KEY_EQ_CB_MAX, eqClearBassMax)

        putBoolean(KEY_SUPPORTS_LEA, supportsLeAudio)
        putBoolean(KEY_PHONE_SUPPORTS_LEA, phoneSupportsLeAudio)
        putBoolean(KEY_SUPPORTS_UPSCALING, supportsUpscaling)
        upscalingEnabled?.let { putBoolean(KEY_UPSCALING_ENABLED, it) }
        putInt(KEY_UPSCALING_TYPE, upscalingTypeCode)
        connectionQualityModeName?.let { putString(KEY_CONNECTION_QUALITY_MODE, it) }
        connectionQualityEnabled?.let { putBoolean(KEY_CONNECTION_QUALITY_ENABLED, it) }
        putBoolean(KEY_SUPPORTS_CONNECTION_QUALITY, supportsConnectionQuality)
        putBoolean(KEY_CONNECTION_QUALITY_RESTRICTED, connectionQualityRestrictedByLea)
        putIntegerArrayList(KEY_LEA_RESTRICTED_TYPES, ArrayList(leaRestrictedFunctionTypes.map { it.code.toInt() and 0xFF }))
        putBoolean(KEY_CONNECTION_QUALITY_SWITCHING, connectionQualitySwitching)
        leaStatus?.let { putString(KEY_LEA, it) }
        leaStreamingStatusL?.let { putString(KEY_LEA_STREAMING_L, it) }
        leaStreamingStatusR?.let { putString(KEY_LEA_STREAMING_R, it) }
        putBoolean(KEY_LEA_PENDING, leAudioPending)
        putBoolean(KEY_LEA_PENDING_TARGET, leAudioPendingTargetEnabled)
        leAudioPendingMessageType?.let { putInt(KEY_LEA_PENDING_MESSAGE, it) }
        leAudioPendingInquiredType?.let { putInt(KEY_LEA_PENDING_INQUIRED, it) }
        putIntArray(KEY_LEA_PENDING_ITEMS, leAudioPendingItemCodes.toIntArray())
        putBoolean(KEY_LEA_SWITCH_PENDING, leAudioSwitchPending)
        putString(KEY_LEA_PAIR_STAGE, leAudioDevicePairStage)
        putString(KEY_LEA_PAIR_MESSAGE, leAudioDevicePairMessage)
        leAudioDevicePairedAddress?.let { putString(KEY_LEA_PAIRED_ADDRESS, it) }
        leAudioIdentityAddress?.let { putString(KEY_LEA_IDENTITY_ADDRESS, it) }
        leAudioPolicyAllowed?.let {
            putBoolean(KEY_LEA_POLICY_KNOWN, true)
            putBoolean(KEY_LEA_POLICY_ALLOWED, it)
        }
        leAudioSystemConnected?.let {
            putBoolean(KEY_LEA_SYS_CONNECTED_KNOWN, true)
            putBoolean(KEY_LEA_SYS_CONNECTED, it)
        }
        leAudioSystemActive?.let {
            putBoolean(KEY_LEA_SYS_ACTIVE_KNOWN, true)
            putBoolean(KEY_LEA_SYS_ACTIVE, it)
        }
        putBoolean(KEY_LDAC_SUPPORTED, ldacSupported)
        ldacEnabled?.let {
            putBoolean(KEY_LDAC_ENABLED_KNOWN, true)
            putBoolean(KEY_LDAC_ENABLED, it)
        }
        putBoolean(KEY_LDAC_SWITCHING, ldacSwitching)
        quickAccessLeftRight?.let { putString(KEY_QA_LR, it) }
        quickAccessNcAmb?.let { putString(KEY_QA_NC, it) }
        quickAccessKeyCode?.let { putInt(KEY_QA_KEY, it) }
        quickAccessTypeCode?.let { putInt(KEY_QA_TYPE, it) }
        quickAccessEnabled?.let {
            putBoolean(KEY_QA_ENABLED_KNOWN, true)
            putBoolean(KEY_QA_ENABLED, it)
        }
        putIntArray(KEY_QA_FUNCTION_CODES, quickAccessFunctionCodes.toIntArray())
        putParcelableArrayList(KEY_QA_ACTIONS, ArrayList(quickAccessActions.map { it.toBundle() }))
        wearingStatus?.let { putString(KEY_WEARING, it) }
        putString(KEY_PLAYBACK, playbackStatus.name)
        playbackTrack?.let { putString(KEY_PLAY_TRACK, it) }
        playbackArtist?.let { putString(KEY_PLAY_ARTIST, it) }
        playbackAlbum?.let { putString(KEY_PLAY_ALBUM, it) }
        playbackMusicVolume?.let { putInt(KEY_PLAY_VOLUME, it) }
        putInt(KEY_PLAY_VOLUME_STEP, playbackMusicVolumeStep)
        soundQualityCodec?.let { putString(KEY_SQ_CODEC, it.name) }
        dseeGeneration?.let { putString(KEY_DSEE_GENERATION, it.name) }
        putBoolean(KEY_DSEE_ACTIVE, dseeActive)
        scanState?.let { putString(KEY_SCAN_STATE, it) }
        putBoolean(KEY_SUPPORTS_SAFE_LISTENING, supportsSafeListening)
        safeListeningLevelDb?.let { putInt(KEY_SL_LEVEL, it) }
        safeListeningStatus?.let { putString(KEY_SL_STATUS, it) }
    }

    companion object {
        const val EXTRA_SNAPSHOT = "sony_state"

        /**
         * [dev.sonypods.leaudio.LeAudioBond.Stage] names that mean nothing is in flight.
         *
         * Listing the resting stages rather than the busy ones keeps [leAudioDevicePairing] correct
         * when a stage is added: a new step is work, and work is the default.
         */
        private val RESTING_PAIR_STAGES = setOf("IDLE", "SUCCESS", "FAILED")

        private const val KEY_CONNECTED = "connected"
        private const val KEY_PROTOCOL_READY = "protocol_ready"
        private const val KEY_CAPABILITIES_KNOWN = "capabilities_known"
        private const val KEY_INITIAL_VALUES_READY = "initial_values_ready"
        private const val KEY_ESSENTIAL_VALUES_READY = "essential_values_ready"
        private const val KEY_AUDIO_LINK_CONNECTED = "audio_link_connected"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_FIRMWARE = "firmware"
        private const val KEY_FORM_FACTOR = "form_factor"
        private const val KEY_MODEL_IMAGE = "model_image_url"
        private const val KEY_MODEL_IMAGE_COLOR = "model_image_source_color"
        private const val KEY_SUPPORTS_POWER_OFF = "supports_power_off"
        private const val KEY_SUPPORTS_GESTURES = "supports_gesture_operations"
        private const val KEY_SUPPORTS_MULTIPOINT = "supports_multipoint"
        private const val KEY_SUPPORTS_MULTIPOINT_VIA_FUNCTION = "supports_multipoint_via_function"
        private const val KEY_HEADSET_RECORDS = "headset_records"
        private const val KEY_SUPPORTS_NOISE_CONTROL = "supports_noise_control"
        private const val KEY_SUPPORTS_EQ = "supports_eq"
        private const val KEY_SUPPORTS_PLAYBACK = "supports_playback"
        private const val KEY_GESTURES = "gesture_operations"
        private const val KEY_MULTIPOINT = "multipoint"
        private const val KEY_ACTIONS = "actions"
        private const val KEY_KEY = "key"
        private const val KEY_TYPE = "type"
        private const val KEY_ENABLED_KNOWN = "enabled_known"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset"
        private const val KEY_PRESETS = "presets"
        private const val KEY_ACTION = "action"
        private const val KEY_FUNCTION = "function"
        private const val KEY_FUNCTIONS = "functions"
        private const val KEY_BATTERY_SINGLE = "battery_single"
        private const val KEY_BATTERY_LEFT = "battery_left"
        private const val KEY_BATTERY_RIGHT = "battery_right"
        private const val KEY_BATTERY_CRADLE = "battery_cradle"
        private const val KEY_NC_MODE = "nc_mode"
        private const val KEY_AMBIENT_LEVEL = "ambient_level"
        private const val KEY_AMBIENT_VOICE = "ambient_voice"
        private const val KEY_SUPPORTS_AUTO_WIND_NOISE = "supports_auto_wind_noise"
        private const val KEY_SUPPORTS_WIND_NOISE = "supports_wind_noise"
        private const val KEY_WIND_NOISE = "wind_noise"
        private const val KEY_SUPPORTS_SPEAK_TO_CHAT = "supports_speak_to_chat"
        private const val KEY_SPEAK_TO_CHAT_ENABLED = "speak_to_chat_enabled"
        private const val KEY_SPEAK_TO_CHAT_SENSITIVITY = "speak_to_chat_sensitivity"
        private const val KEY_SPEAK_TO_CHAT_MODE_OUT_TIME = "speak_to_chat_mode_out_time"
        private const val KEY_SUPPORTS_NOISE_ADAPTIVE = "supports_noise_adaptive"
        private const val KEY_NOISE_ADAPTIVE = "noise_adaptive"
        private const val KEY_NOISE_ADAPTIVE_SENSITIVITY = "noise_adaptive_sensitivity"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val KEY_EQ_PRESETS = "eq_presets"
        private const val KEY_EQ_CLEAR_BASS = "eq_clear_bass"
        private const val KEY_EQ_HAS_CLEAR_BASS = "eq_has_clear_bass"
        private const val KEY_EQ_BANDS = "eq_bands"
        private const val KEY_EQ_BAND_LABELS = "eq_band_labels"
        private const val KEY_EQ_BAND_MIN = "eq_band_min"
        private const val KEY_EQ_BAND_MAX = "eq_band_max"
        private const val KEY_EQ_CB_MIN = "eq_cb_min"
        private const val KEY_EQ_CB_MAX = "eq_cb_max"
        private const val KEY_SUPPORTS_LEA = "supports_lea"
        private const val KEY_PHONE_SUPPORTS_LEA = "phone_supports_lea"
        private const val KEY_SUPPORTS_UPSCALING = "supports_upscaling"
        private const val KEY_UPSCALING_ENABLED = "upscaling_enabled"
        private const val KEY_UPSCALING_TYPE = "upscaling_type"
        private const val KEY_CONNECTION_QUALITY_MODE = "connection_quality_mode"
        private const val KEY_CONNECTION_QUALITY_ENABLED = "connection_quality_enabled"
        private const val KEY_SUPPORTS_CONNECTION_QUALITY = "supports_connection_quality"
        private const val KEY_CONNECTION_QUALITY_RESTRICTED = "connection_quality_restricted"
        private const val KEY_LEA_RESTRICTED_TYPES = "lea_restricted_types"
        private const val KEY_CONNECTION_QUALITY_SWITCHING = "connection_quality_switching"
        private const val KEY_LEA = "lea"
        private const val KEY_LEA_STREAMING_L = "lea_streaming_l"
        private const val KEY_LEA_STREAMING_R = "lea_streaming_r"
        private const val KEY_LEA_PENDING = "lea_pending"
        private const val KEY_LEA_PENDING_TARGET = "lea_pending_target"
        private const val KEY_LEA_PENDING_MESSAGE = "lea_pending_message"
        private const val KEY_LEA_PENDING_INQUIRED = "lea_pending_inquired"
        private const val KEY_LEA_PENDING_ITEMS = "lea_pending_items"
        private const val KEY_LEA_SWITCH_PENDING = "lea_switch_pending"
        private const val KEY_LEA_PAIR_STAGE = "lea_pair_stage"
        private const val KEY_LEA_PAIR_MESSAGE = "lea_pair_message"
        private const val KEY_LEA_PAIRED_ADDRESS = "lea_paired_address"
        private const val KEY_LEA_IDENTITY_ADDRESS = "lea_identity_address"
        private const val KEY_LEA_POLICY_KNOWN = "lea_policy_known"
        private const val KEY_LEA_POLICY_ALLOWED = "lea_policy_allowed"
        private const val KEY_LEA_SYS_CONNECTED_KNOWN = "lea_sys_connected_known"
        private const val KEY_LEA_SYS_CONNECTED = "lea_sys_connected"
        private const val KEY_LEA_SYS_ACTIVE_KNOWN = "lea_sys_active_known"
        private const val KEY_LEA_SYS_ACTIVE = "lea_sys_active"
        private const val KEY_LDAC_SUPPORTED = "ldac_supported"
        private const val KEY_LDAC_ENABLED_KNOWN = "ldac_enabled_known"
        private const val KEY_LDAC_ENABLED = "ldac_enabled"
        private const val KEY_LDAC_SWITCHING = "ldac_switching"

        /** `LeaTargetStreamingStatus.VIA_LE_AUDIO_UNICAST` as the headset reports it. */
        const val LEA_STREAMING_UNICAST = "VIA_LE_AUDIO_UNICAST"

        private const val KEY_QA_LR = "qa_lr"
        private const val KEY_QA_NC = "qa_nc"
        private const val KEY_QA_KEY = "qa_key"
        private const val KEY_QA_TYPE = "qa_type"
        private const val KEY_QA_ENABLED_KNOWN = "qa_enabled_known"
        private const val KEY_QA_ENABLED = "qa_enabled"
        private const val KEY_QA_FUNCTION_CODES = "qa_function_codes"
        private const val KEY_QA_ACTIONS = "qa_actions"
        private const val KEY_QA_ACTION = "qa_action"
        private const val KEY_QA_FUNCTION = "qa_function"
        private const val KEY_QA_DEFAULT = "qa_default"
        private const val KEY_QA_FUNCTIONS = "qa_functions"
        private const val KEY_WEARING = "wearing"
        private const val KEY_PLAYBACK = "playback"
        private const val KEY_PLAY_TRACK = "play_track"
        private const val KEY_PLAY_ARTIST = "play_artist"
        private const val KEY_PLAY_ALBUM = "play_album"
        private const val KEY_PLAY_VOLUME = "play_volume"
        private const val KEY_PLAY_VOLUME_STEP = "play_volume_step"
        private const val KEY_SQ_CODEC = "sq_codec"
        private const val KEY_DSEE_GENERATION = "dsee_generation"
        private const val KEY_DSEE_ACTIVE = "dsee_active"
        private const val KEY_SCAN_STATE = "scan_state"
        private const val KEY_SUPPORTS_SAFE_LISTENING = "supports_safe_listening"
        private const val KEY_SL_LEVEL = "sl_level"
        private const val KEY_SL_STATUS = "sl_status"

        /**
         * Decode a snapshot and adopt the headset records it carries.
         *
         * Only the engine holds a Tandem session, so only it can learn an identity; every consumer
         * process takes the answer here rather than deriving it locally, which is what kept the two
         * identities of one headset from being read in opposite directions in different processes.
         */
        fun fromBundle(bundle: Bundle): SonyStateSnapshot = decodeBundle(bundle).also { snapshot ->
            if (snapshot.headsetRecords.isNotEmpty()) {
                runCatching {
                    dev.sonypods.device.HeadsetRegistry.ingest(snapshot.headsetRecords)
                }
            }
        }

        private fun decodeBundle(bundle: Bundle): SonyStateSnapshot = SonyStateSnapshot(
            connected = bundle.getBoolean(KEY_CONNECTED, false),            protocolReady = bundle.getBoolean(KEY_PROTOCOL_READY, false),
            capabilitiesKnown = bundle.getBoolean(KEY_CAPABILITIES_KNOWN, false),
            initialValuesReady = bundle.getBoolean(KEY_INITIAL_VALUES_READY, false),
            essentialValuesReady = bundle.getBoolean(KEY_ESSENTIAL_VALUES_READY, false),
            audioLinkConnected = bundle.getBoolean(KEY_AUDIO_LINK_CONNECTED, false),
            deviceName = bundle.getString(KEY_DEVICE_NAME),
            deviceAddress = bundle.getString(KEY_DEVICE_ADDRESS),
            firmwareVersion = bundle.getString(KEY_FIRMWARE),
            formFactor = bundle.getString(KEY_FORM_FACTOR),
            modelImageUrl = bundle.getString(KEY_MODEL_IMAGE),
            modelImageSourceColor = bundle.getString(KEY_MODEL_IMAGE_COLOR),
            supportsPowerOff = bundle.getBoolean(KEY_SUPPORTS_POWER_OFF, false),
            supportsGestureOperations = bundle.getBoolean(KEY_SUPPORTS_GESTURES, false),
            supportsMultipoint = bundle.getBoolean(KEY_SUPPORTS_MULTIPOINT, false),
            supportsMultipointViaFunction = bundle.getBoolean(KEY_SUPPORTS_MULTIPOINT_VIA_FUNCTION, false),
            headsetRecords = bundle.getStringArrayList(KEY_HEADSET_RECORDS)?.toList().orEmpty(),
            supportsNoiseControl = bundle.getBoolean(KEY_SUPPORTS_NOISE_CONTROL, false),
            supportsEq = bundle.getBoolean(KEY_SUPPORTS_EQ, false),
            supportsPlaybackControl = bundle.getBoolean(KEY_SUPPORTS_PLAYBACK, false),
            batterySingle = bundle.optInt(KEY_BATTERY_SINGLE),
            batteryLeft = bundle.optInt(KEY_BATTERY_LEFT),
            batteryRight = bundle.optInt(KEY_BATTERY_RIGHT),
            batteryCradle = bundle.optInt(KEY_BATTERY_CRADLE),
            noiseControlMode = bundle.getString(KEY_NC_MODE)?.let { name ->
                NoiseControlMode.entries.firstOrNull { it.name == name }
            },
            ambientLevel = bundle.optInt(KEY_AMBIENT_LEVEL),
            ambientVoiceMode = bundle.getBoolean(KEY_AMBIENT_VOICE, false),
            supportsAutoWindNoiseReduction = bundle.getBoolean(KEY_SUPPORTS_AUTO_WIND_NOISE, false),
            supportsWindNoiseReduction = bundle.getBoolean(KEY_SUPPORTS_WIND_NOISE, false),
            windNoiseReduction = bundle.getBoolean(KEY_WIND_NOISE, false),
            supportsSpeakToChat = bundle.getBoolean(KEY_SUPPORTS_SPEAK_TO_CHAT, false),
            speakToChatEnabled = bundle.getBoolean(KEY_SPEAK_TO_CHAT_ENABLED, false),
            speakToChatSensitivity = bundle.getString(KEY_SPEAK_TO_CHAT_SENSITIVITY),
            speakToChatModeOutTime = bundle.getString(KEY_SPEAK_TO_CHAT_MODE_OUT_TIME),
            supportsNoiseAdaptive = bundle.getBoolean(KEY_SUPPORTS_NOISE_ADAPTIVE, false),
            noiseAdaptiveEnabled = bundle.getBoolean(KEY_NOISE_ADAPTIVE, false),
            noiseAdaptiveSensitivity = bundle.getString(KEY_NOISE_ADAPTIVE_SENSITIVITY),
            eqPreset = bundle.getString(KEY_EQ_PRESET)?.let { name ->
                EqPresetId.entries.firstOrNull { it.name == name }
            },
            eqAvailablePresets = bundle.getStringArray(KEY_EQ_PRESETS).orEmpty().mapNotNull { name ->
                EqPresetId.entries.firstOrNull { it.name == name }
            },
            eqClearBass = bundle.optInt(KEY_EQ_CLEAR_BASS),
            eqHasClearBass = bundle.getBoolean(KEY_EQ_HAS_CLEAR_BASS, true),
            eqBandSteps = bundle.getIntArray(KEY_EQ_BANDS)?.toList().orEmpty(),
            eqBandLabels = bundle.getStringArray(KEY_EQ_BAND_LABELS)?.toList().orEmpty(),
            eqBandMin = bundle.getInt(KEY_EQ_BAND_MIN, -10),
            eqBandMax = bundle.getInt(KEY_EQ_BAND_MAX, 10),
            eqClearBassMin = bundle.getInt(KEY_EQ_CB_MIN, -10),
            eqClearBassMax = bundle.getInt(KEY_EQ_CB_MAX, 10),
            supportsLeAudio = bundle.getBoolean(KEY_SUPPORTS_LEA, false),
            phoneSupportsLeAudio = bundle.getBoolean(KEY_PHONE_SUPPORTS_LEA, true),
            supportsUpscaling = bundle.getBoolean(KEY_SUPPORTS_UPSCALING, false),
            upscalingEnabled = if (bundle.containsKey(KEY_UPSCALING_ENABLED)) {
                bundle.getBoolean(KEY_UPSCALING_ENABLED)
            } else null,
            upscalingTypeCode = bundle.getInt(KEY_UPSCALING_TYPE, -1),
            connectionQualityModeName = bundle.getString(KEY_CONNECTION_QUALITY_MODE),
            connectionQualityEnabled = if (bundle.containsKey(KEY_CONNECTION_QUALITY_ENABLED)) {
                bundle.getBoolean(KEY_CONNECTION_QUALITY_ENABLED)
            } else null,
            supportsConnectionQuality = bundle.getBoolean(KEY_SUPPORTS_CONNECTION_QUALITY, false),
            connectionQualityRestrictedByLea = bundle.getBoolean(KEY_CONNECTION_QUALITY_RESTRICTED, false),
            leaRestrictedFunctionTypes = bundle.getIntegerArrayList(KEY_LEA_RESTRICTED_TYPES)
                ?.mapNotNullTo(mutableSetOf()) {
                    dev.sonypods.protocol.SonyV2FunctionType.leaRestrictionFromCode(it)
                } ?: emptySet(),
            connectionQualitySwitching = bundle.getBoolean(KEY_CONNECTION_QUALITY_SWITCHING, false),
            leaStatus = bundle.getString(KEY_LEA),
            leaStreamingStatusL = bundle.getString(KEY_LEA_STREAMING_L),
            leaStreamingStatusR = bundle.getString(KEY_LEA_STREAMING_R),
            leAudioPending = bundle.getBoolean(KEY_LEA_PENDING, false),
            leAudioPendingTargetEnabled = bundle.getBoolean(KEY_LEA_PENDING_TARGET, false),
            leAudioPendingMessageType = bundle.optInt(KEY_LEA_PENDING_MESSAGE),
            leAudioPendingInquiredType = bundle.optInt(KEY_LEA_PENDING_INQUIRED),
            leAudioPendingItemCodes = bundle.getIntArray(KEY_LEA_PENDING_ITEMS)?.toList().orEmpty(),
            leAudioSwitchPending = bundle.getBoolean(KEY_LEA_SWITCH_PENDING, false),
            leAudioDevicePairStage = bundle.getString(KEY_LEA_PAIR_STAGE) ?: "IDLE",
            leAudioDevicePairMessage = bundle.getString(KEY_LEA_PAIR_MESSAGE).orEmpty(),
            leAudioDevicePairedAddress = bundle.getString(KEY_LEA_PAIRED_ADDRESS),
            leAudioIdentityAddress = bundle.getString(KEY_LEA_IDENTITY_ADDRESS),
            leAudioPolicyAllowed = if (bundle.getBoolean(KEY_LEA_POLICY_KNOWN, false)) {
                bundle.getBoolean(KEY_LEA_POLICY_ALLOWED, false)
            } else {
                null
            },
            leAudioSystemConnected = if (bundle.getBoolean(KEY_LEA_SYS_CONNECTED_KNOWN, false)) {
                bundle.getBoolean(KEY_LEA_SYS_CONNECTED, false)
            } else {
                null
            },
            leAudioSystemActive = if (bundle.getBoolean(KEY_LEA_SYS_ACTIVE_KNOWN, false)) {
                bundle.getBoolean(KEY_LEA_SYS_ACTIVE, false)
            } else {
                null
            },
            ldacSupported = bundle.getBoolean(KEY_LDAC_SUPPORTED, false),
            ldacEnabled = if (bundle.getBoolean(KEY_LDAC_ENABLED_KNOWN, false)) {
                bundle.getBoolean(KEY_LDAC_ENABLED, false)
            } else {
                null
            },
            ldacSwitching = bundle.getBoolean(KEY_LDAC_SWITCHING, false),
            quickAccessLeftRight = bundle.getString(KEY_QA_LR),
            quickAccessNcAmb = bundle.getString(KEY_QA_NC),
            quickAccessKeyCode = bundle.optInt(KEY_QA_KEY),
            quickAccessTypeCode = bundle.optInt(KEY_QA_TYPE),
            quickAccessEnabled = if (bundle.getBoolean(KEY_QA_ENABLED_KNOWN, false)) {
                bundle.getBoolean(KEY_QA_ENABLED)
            } else null,
            quickAccessFunctionCodes = bundle.getIntArray(KEY_QA_FUNCTION_CODES)?.toList().orEmpty(),
            quickAccessActions = bundle.quickAccessActions(),
            gestureOperationKeys = bundle.gestureKeys(),
            multipoint = bundle.getBundle(KEY_MULTIPOINT)?.multipointSnapshot() ?: MultipointSnapshot(),
            wearingStatus = bundle.getString(KEY_WEARING),
            playbackStatus = bundle.getString(KEY_PLAYBACK)?.let { name ->
                PlaybackStatus.entries.firstOrNull { it.name == name }
            } ?: PlaybackStatus.UNKNOWN,
            playbackTrack = bundle.getString(KEY_PLAY_TRACK),
            playbackArtist = bundle.getString(KEY_PLAY_ARTIST),
            playbackAlbum = bundle.getString(KEY_PLAY_ALBUM),
            playbackMusicVolume = bundle.optInt(KEY_PLAY_VOLUME),
            playbackMusicVolumeStep = bundle.getInt(KEY_PLAY_VOLUME_STEP, 0),
            soundQualityCodec = bundle.getString(KEY_SQ_CODEC)?.let { name ->
                SoundQualityCodec.entries.firstOrNull { it.name == name }
            },
            dseeGeneration = bundle.getString(KEY_DSEE_GENERATION)?.let { name ->
                DseeGeneration.entries.firstOrNull { it.name == name }
            },
            dseeActive = bundle.getBoolean(KEY_DSEE_ACTIVE, false),
            scanState = bundle.getString(KEY_SCAN_STATE),
            supportsSafeListening = bundle.getBoolean(KEY_SUPPORTS_SAFE_LISTENING, false),
            safeListeningLevelDb = bundle.optInt(KEY_SL_LEVEL),
            safeListeningStatus = bundle.getString(KEY_SL_STATUS),
        )

        fun fromUiState(state: SonyHeadphoneUiState): SonyStateSnapshot {
            val capability = state.eqUiCapability
            return SonyStateSnapshot(
                connected = state.connectedDevice != null,
                protocolReady = state.deviceInfo.protocolReady,
                capabilitiesKnown = state.capabilitiesKnown,
                initialValuesReady = state.initialValuesReady,
                essentialValuesReady = state.essentialValuesReady,
                // Blank as well as null: the neutral profile now leaves the model name empty rather
                // than inventing a placeholder, and a blank would otherwise win over the name the
                // discovered device already carries — which is what the surfaces render.
                deviceName = state.deviceInfo.modelName?.takeIf { it.isNotBlank() }
                    ?: state.connectedDevice?.name,
                deviceAddress = state.connectedDevice?.address,
                firmwareVersion = state.deviceInfo.firmwareVersion,
                formFactor = state.connectedProfile?.capabilities?.formFactor?.name,
                modelImageUrl = state.deviceInfo.modelImageUrl,
                modelImageSourceColor = state.deviceInfo.modelImageSourceColor,
                supportsPowerOff = state.connectedProfile?.supports(dev.sonypods.headphones.HeadphoneFeature.POWER_OFF) == true,
                supportsGestureOperations = state.connectedProfile?.supports(dev.sonypods.headphones.HeadphoneFeature.GESTURE_OPERATIONS) == true,
                supportsMultipoint = state.multipointState.supported,
                supportsMultipointViaFunction = state.connectedProfile
                    ?.capabilities?.supportsMultipointViaFunction == true,
                headsetRecords = dev.sonypods.device.HeadsetRegistry.snapshotLines(),
                supportsNoiseControl = state.connectedProfile?.supports(dev.sonypods.headphones.HeadphoneFeature.NOISE_CONTROL) == true,
                supportsEq = state.connectedProfile?.supports(dev.sonypods.headphones.HeadphoneFeature.EQ) == true,
                supportsPlaybackControl = state.connectedProfile?.supports(dev.sonypods.headphones.HeadphoneFeature.PLAYBACK_CONTROL) == true,
                batterySingle = state.batteryState.single,
                batteryLeft = state.batteryState.left,
                batteryRight = state.batteryState.right,
                batteryCradle = state.batteryState.cradle,
                noiseControlMode = state.noiseControlState.controlMode,
                ambientLevel = state.noiseControlState.ambientLevel,
                ambientVoiceMode = state.noiseControlState.ambientVoiceMode,
                supportsAutoWindNoiseReduction = state.connectedProfile
                    ?.capabilities?.supportsAutoWindNoiseReduction == true,
                supportsWindNoiseReduction = state.connectedProfile
                    ?.capabilities?.supportsWindNoiseReduction == true,
                windNoiseReduction = state.noiseControlState.windNoiseReduction,
                supportsSpeakToChat = state.connectedProfile?.supports(dev.sonypods.headphones.HeadphoneFeature.SPEAK_TO_CHAT) == true ||
                    state.connectedProfile?.capabilities?.supportsSpeakToChat == true,
                speakToChatEnabled = state.speakToChatState.enabled,
                speakToChatSensitivity = state.speakToChatState.sensitivity.name,
                speakToChatModeOutTime = state.speakToChatState.modeOutTime.name,
                supportsNoiseAdaptive = state.connectedProfile?.supports(dev.sonypods.headphones.HeadphoneFeature.NOISE_ADAPTIVE) == true,
                noiseAdaptiveEnabled = state.noiseControlState.noiseAdaptiveEnabled,
                noiseAdaptiveSensitivity = state.noiseControlState.noiseAdaptiveSensitivity.name,
                eqPreset = state.eqState.preset,
                eqAvailablePresets = capability?.availablePresets.orEmpty(),
                eqClearBass = state.eqState.clearBass,
                eqHasClearBass = capability?.hasClearBass ?: true,
                eqBandSteps = state.eqState.bandSteps,
                eqBandLabels = capability?.bandLabels.orEmpty(),
                eqBandMin = capability?.bandDisplayRange?.first ?: -10,
                eqBandMax = capability?.bandDisplayRange?.last ?: 10,
                eqClearBassMin = capability?.clearBassDisplayRange?.first ?: -10,
                eqClearBassMax = capability?.clearBassDisplayRange?.last ?: 10,
                supportsLeAudio = state.connectedProfile
                    ?.supports(dev.sonypods.headphones.HeadphoneFeature.LEA_STATUS) == true,
                leaStatus = state.leaState.enabled,
                supportsUpscaling = state.connectedProfile
                    ?.supports(dev.sonypods.headphones.HeadphoneFeature.UPSCALING) == true,
                upscalingEnabled = state.upscalingEnabled,
                upscalingTypeCode = state.connectedProfile
                    ?.capabilities?.upscalingTypeCode ?: -1,
                connectionQualityModeName = state.connectionQualityMode?.name,
                connectionQualityEnabled = state.connectionQualityEnabled,
                supportsConnectionQuality = state.connectedProfile
                    ?.supports(dev.sonypods.headphones.HeadphoneFeature.CONNECTION_QUALITY) == true,
                connectionQualityRestrictedByLea = state.connectedProfile
                    ?.capabilities?.connectionQualityRestrictedByLea == true,
                leaRestrictedFunctionTypes = state.connectedProfile
                    ?.capabilities?.leaRestrictedFunctionTypes.orEmpty(),
                connectionQualitySwitching = state.connectionQualitySwitching,
                leaStreamingStatusL = state.leaState.streamingStatusL,
                leaStreamingStatusR = state.leaState.streamingStatusR,
                leAudioPending = state.leAudioPendingAlert != null,
                leAudioPendingTargetEnabled = state.leAudioPendingAlert?.targetEnabled == true,
                leAudioPendingMessageType = state.leAudioPendingAlert?.messageType,
                leAudioPendingInquiredType = state.leAudioPendingAlert?.inquiredType,
                leAudioPendingItemCodes = state.leAudioPendingAlert?.itemCodes.orEmpty(),
                leAudioSwitchPending = state.leAudioSwitchPending,
                leAudioDevicePairStage = state.leAudioDevicePairState.stage.name,
                leAudioDevicePairMessage = state.leAudioDevicePairState.message,
                leAudioDevicePairedAddress = state.leAudioDevicePairState.bondedAddress,
                // leAudioIdentityAddress, leAudioPolicyAllowed, leAudioSystem* and the ldac* fields
                // are the stack's own facts, not the repository's: the bluetooth-process host fills
                // them in as it publishes, since only it can read the profile services.
                quickAccessLeftRight = state.quickAccessState.lrKeyFunction,
                quickAccessNcAmb = state.quickAccessState.ncAmbKeyFunction,
                quickAccessKeyCode = state.quickAccessState.key?.code?.toInt()?.and(0xFF),
                quickAccessTypeCode = state.quickAccessState.type?.code?.toInt()?.and(0xFF),
                quickAccessEnabled = state.quickAccessState.enabled,
                quickAccessFunctionCodes = state.quickAccessState.functionCodes,
                quickAccessActions = state.quickAccessState.actions.map { it.toSnapshot() },
                gestureOperationKeys = state.gestureOperationsState.uiKeys(),
                multipoint = MultipointSnapshot(
                    enabled = state.multipointState.enabled,
                    pairingMode = state.multipointState.pairingMode,
                    maxPairedDevices = state.multipointState.maxPairedDevices,
                    maxConnectedDevices = state.multipointState.maxConnectedDevices,
                    supportsFileTransfer = state.multipointState.supportsFileTransfer,
                    playbackRight = state.multipointState.playbackRight,
                    activeSourceAddress = state.multipointState.activeSourceAddress,
                    resultCode = state.multipointState.resultCode,
                    resultAddress = state.multipointState.resultAddress,
                    connectedDevices = state.multipointState.connectedDevices.map { device ->
                        MultipointDeviceSnapshot(device.address, device.name, device.deviceClass, device.connectedStatus)
                    },
                    historyDevices = state.multipointState.historyDevices.map { device ->
                        MultipointDeviceSnapshot(device.address, device.name, device.deviceClass, device.connectedStatus)
                    },
                    sourceSwitchEnabled = state.multipointState.sourceSwitchEnabled,
                    fixedSourceAddress = state.multipointState.fixedSourceAddress,
                    sourceSwitchResultCode = state.multipointState.sourceSwitchResultCode,
                    musicHandOverEnabled = state.multipointState.musicHandOverEnabled,
                    multipointEnabled = state.multipointState.multipointEnabled,
                    multipointTogglePending = state.multipointState.pendingMultipointToggle != null,
                    pendingAlertMessageType = state.multipointState.pendingAlertMessageType,
                ),
                wearingStatus = state.wearingState.status,
                playbackStatus = state.playbackStatus,
                playbackTrack = state.playbackState.track,
                playbackArtist = state.playbackState.artist,
                playbackAlbum = state.playbackState.album,
                playbackMusicVolume = state.playbackState.musicVolume,
                playbackMusicVolumeStep = state.playbackState.musicVolumeStep,
                soundQualityCodec = state.soundQualityState.codec,
                dseeGeneration = state.soundQualityState.dseeGeneration,
                dseeActive = state.soundQualityState.dseeActive,
                scanState = state.scanState,
                supportsSafeListening = state.connectedProfile
                    ?.supports(dev.sonypods.headphones.HeadphoneFeature.SAFE_LISTENING) == true,
                safeListeningLevelDb = state.safeListeningState.levelDb,
                safeListeningStatus = state.safeListeningState.status.name,
            )
        }

        @Suppress("DEPRECATION")
        private fun Bundle.gestureKeys(): List<GestureOperationKey> =
            getParcelableArrayList<Bundle>(KEY_GESTURES).orEmpty().map { keyBundle ->
                val actions = keyBundle.getParcelableArrayList<Bundle>(KEY_ACTIONS).orEmpty().map { actionBundle ->
                    GestureOperationAction(
                        action = AssignableSettingsAction.entries.firstOrNull {
                            it.code.toInt() and 0xFF == actionBundle.getInt(KEY_ACTION)
                        } ?: AssignableSettingsAction.OUT_OF_RANGE,
                        function = AssignableSettingsFunction.entries.firstOrNull {
                            it.code.toInt() and 0xFF == actionBundle.getInt(KEY_FUNCTION)
                        } ?: AssignableSettingsFunction.OUT_OF_RANGE,
                        availableFunctions = (actionBundle.getIntArray(KEY_FUNCTIONS) ?: intArrayOf()).toList().mapNotNull { code ->
                            AssignableSettingsFunction.entries.firstOrNull {
                                it.code.toInt() and 0xFF == code
                            }?.takeIf { it != AssignableSettingsFunction.OUT_OF_RANGE }
                        },
                    )
                }
                GestureOperationKey(
                    key = AssignableSettingsKey.entries.firstOrNull {
                        it.code.toInt() and 0xFF == keyBundle.getInt(KEY_KEY)
                    } ?: AssignableSettingsKey.OUT_OF_RANGE,
                    type = AssignableSettingsType.entries.firstOrNull {
                        it.code.toInt() and 0xFF == keyBundle.getInt(KEY_TYPE)
                    } ?: AssignableSettingsType.OUT_OF_RANGE,
                    enabled = if (keyBundle.getBoolean(KEY_ENABLED_KNOWN, false)) {
                        keyBundle.getBoolean(KEY_ENABLED)
                    } else null,
                    currentPreset = AssignableSettingsPreset.entries.firstOrNull {
                        it.code.toInt() and 0xFF == keyBundle.getInt(KEY_PRESET)
                    } ?: AssignableSettingsPreset.NO_FUNCTION,
                    availablePresets = (keyBundle.getIntArray(KEY_PRESETS) ?: intArrayOf()).toList().mapNotNull { code ->
                        AssignableSettingsPreset.entries.firstOrNull {
                            it.code.toInt() and 0xFF == code
                        }?.takeIf { it != AssignableSettingsPreset.OUT_OF_RANGE }
                    },
                    actions = actions,
                )
            }

        private fun GestureOperationKey.toBundle(): Bundle = Bundle().apply {
            putInt(KEY_KEY, key.code.toInt() and 0xFF)
            putInt(KEY_TYPE, type.code.toInt() and 0xFF)
            enabled?.let {
                putBoolean(KEY_ENABLED_KNOWN, true)
                putBoolean(KEY_ENABLED, it)
            }
            putInt(KEY_PRESET, currentPreset.code.toInt() and 0xFF)
            putIntArray(KEY_PRESETS, availablePresets.map { it.code.toInt() and 0xFF }.toIntArray())
            putParcelableArrayList(KEY_ACTIONS, ArrayList(actions.map { it.toBundle() }))
        }

        private fun GestureOperationAction.toBundle(): Bundle = Bundle().apply {
            putInt(KEY_ACTION, action.code.toInt() and 0xFF)
            putInt(KEY_FUNCTION, function.code.toInt() and 0xFF)
            putIntArray(KEY_FUNCTIONS, availableFunctions.map { it.code.toInt() and 0xFF }.toIntArray())
        }

        @Suppress("DEPRECATION")
        private fun Bundle.quickAccessActions(): List<QuickAccessActionSnapshot> =
            getParcelableArrayList<Bundle>(KEY_QA_ACTIONS).orEmpty().map { actionBundle ->
                QuickAccessActionSnapshot(
                    actionCode = actionBundle.getInt(KEY_QA_ACTION),
                    currentFunctionCode = if (actionBundle.getBoolean("${KEY_QA_FUNCTION}_known", false)) {
                        actionBundle.getInt(KEY_QA_FUNCTION)
                    } else null,
                    defaultFunctionCode = actionBundle.getInt(KEY_QA_DEFAULT),
                    availableFunctionCodes = (actionBundle.getIntArray(KEY_QA_FUNCTIONS) ?: intArrayOf()).toList(),
                )
            }

        private fun QuickAccessActionState.toSnapshot(): QuickAccessActionSnapshot =
            QuickAccessActionSnapshot(
                actionCode = action.code.toInt() and 0xFF,
                currentFunctionCode = currentFunctionCode,
                defaultFunctionCode = defaultFunctionCode,
                availableFunctionCodes = availableFunctionCodes,
            )

        private fun QuickAccessActionSnapshot.toBundle(): Bundle = Bundle().apply {
            putInt(KEY_QA_ACTION, actionCode)
            currentFunctionCode?.let {
                putBoolean("${KEY_QA_FUNCTION}_known", true)
                putInt(KEY_QA_FUNCTION, it)
            }
            putInt(KEY_QA_DEFAULT, defaultFunctionCode)
            putIntArray(KEY_QA_FUNCTIONS, availableFunctionCodes.toIntArray())
        }

        @Suppress("DEPRECATION")
        private fun Bundle.multipointSnapshot(): MultipointSnapshot = MultipointSnapshot(
            enabled = if (getBoolean("enabled_known", false)) getBoolean("enabled") else null,
            pairingMode = getBoolean("pairing_mode", false),
            maxPairedDevices = getInt("max_paired", 0),
            maxConnectedDevices = getInt("max_connected", 0),
            supportsFileTransfer = if (getBoolean("file_transfer_known", false)) getBoolean("file_transfer") else null,
            playbackRight = getInt("playback_right", 0),
            activeSourceAddress = getString("active_source_address"),
            resultAddress = getString("result_address"),
            connectedDevices = getParcelableArrayList<Bundle>("connected_devices").orEmpty().map { device ->
                MultipointDeviceSnapshot(
                    address = device.getString("address").orEmpty(),
                    name = device.getString("name").orEmpty(),
                    deviceClass = device.getInt("class", 0),
                    connectedStatus = device.getInt("status", 0),
                )
            },
            historyDevices = getParcelableArrayList<Bundle>("history_devices").orEmpty().map { device ->
                MultipointDeviceSnapshot(
                    address = device.getString("address").orEmpty(),
                    name = device.getString("name").orEmpty(),
                    deviceClass = device.getInt("class", 0),
                    connectedStatus = device.getInt("status", 0),
                )
            },
            sourceSwitchEnabled = if (getBoolean("source_switch_known", false)) getBoolean("source_switch_enabled") else null,
            fixedSourceAddress = getString("fixed_source_address"),
            resultCode = if (getBoolean("mp_result_known", false)) getInt("mp_result_code") else null,
            musicHandOverEnabled = if (getBoolean("music_hand_over_known", false)) getBoolean("music_hand_over_enabled") else null,
            multipointEnabled = if (getBoolean("multipoint_enabled_known", false)) getBoolean("multipoint_enabled") else null,
            multipointTogglePending = getBoolean("multipoint_toggle_pending", false),
            pendingAlertMessageType = if (getBoolean("pending_alert_known", false)) getInt("pending_alert_message_type") else null,
        )

        private fun MultipointSnapshot.toBundle(): Bundle = Bundle().apply {
            enabled?.let {
                putBoolean("enabled_known", true)
                putBoolean("enabled", it)
            }
            putBoolean("pairing_mode", pairingMode)
            putInt("max_paired", maxPairedDevices)
            putInt("max_connected", maxConnectedDevices)
            supportsFileTransfer?.let {
                putBoolean("file_transfer_known", true)
                putBoolean("file_transfer", it)
            }
            putInt("playback_right", playbackRight)
            activeSourceAddress?.let { putString("active_source_address", it) }
            resultCode?.let {
                putBoolean("mp_result_known", true)
                putInt("mp_result_code", it)
            }
            resultAddress?.let { putString("result_address", it) }
            putParcelableArrayList("connected_devices", ArrayList(connectedDevices.map { it.toBundle() }))
            putParcelableArrayList("history_devices", ArrayList(historyDevices.map { it.toBundle() }))
            sourceSwitchEnabled?.let {
                putBoolean("source_switch_known", true)
                putBoolean("source_switch_enabled", it)
            }
            fixedSourceAddress?.let { putString("fixed_source_address", it) }
            sourceSwitchResultCode?.let { putInt("source_switch_result_code", it) }
            musicHandOverEnabled?.let {
                putBoolean("music_hand_over_known", true)
                putBoolean("music_hand_over_enabled", it)
            }
            multipointEnabled?.let {
                putBoolean("multipoint_enabled_known", true)
                putBoolean("multipoint_enabled", it)
            }
            putBoolean("multipoint_toggle_pending", multipointTogglePending)
            pendingAlertMessageType?.let {
                putBoolean("pending_alert_known", true)
                putInt("pending_alert_message_type", it)
            }
        }

        private fun MultipointDeviceSnapshot.toBundle(): Bundle = Bundle().apply {
            putString("address", address)
            putString("name", name)
            putInt("class", deviceClass)
            putInt("status", connectedStatus)
        }

        private fun Bundle.optInt(key: String): Int? = if (containsKey(key)) getInt(key) else null
    }
}

/** Flat cross-process representation of a Quick Access capability action. */
data class QuickAccessActionSnapshot(
    val actionCode: Int,
    val currentFunctionCode: Int?,
    val defaultFunctionCode: Int,
    val availableFunctionCodes: List<Int>,
)

data class MultipointSnapshot(
    val enabled: Boolean? = null,
    val pairingMode: Boolean = false,
    val maxPairedDevices: Int = 0,
    val maxConnectedDevices: Int = 0,
    val supportsFileTransfer: Boolean? = null,
    /** connectedStatus value of the playback-right holder, 0 = none. */
    val playbackRight: Int = 0,
    val activeSourceAddress: String? = null,
    /** Raw multipoint action result code; the UI layer maps it to localized copy. */
    val resultCode: Int? = null,
    val resultAddress: String? = null,
    val connectedDevices: List<MultipointDeviceSnapshot> = emptyList(),
    val historyDevices: List<MultipointDeviceSnapshot> = emptyList(),
    val sourceSwitchEnabled: Boolean? = null,
    val fixedSourceAddress: String? = null,
    /** Raw SourceSwitchControlResult code; localized by the UI layer if rendered. */
    val sourceSwitchResultCode: Int? = null,
    val musicHandOverEnabled: Boolean? = null,
    /** "同时连接2台设备" — V2 Table1 GS multipoint toggle; null = unknown. */
    val multipointEnabled: Boolean? = null,
    /** A write is waiting for the device to settle; ignore repeated taps. */
    val multipointTogglePending: Boolean = false,
    /** Pending device alert msgType (6/7) awaiting reconnection confirmation; null = none. */
    val pendingAlertMessageType: Int? = null,
)

data class MultipointDeviceSnapshot(
    val address: String,
    val name: String,
    val deviceClass: Int,
    /** SC connectedStatus: 1-based slot number, 0 = paired but not connected. */
    val connectedStatus: Int = 0,
)
