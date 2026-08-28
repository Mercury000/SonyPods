package dev.sonypods.config

import android.content.SharedPreferences
import android.util.Log
import com.mercury.sonypods.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-surface show/hide switches for the module's cards and badges. Every flag
 * defaults to true (current behaviour); a false value only ever removes a UI
 * element — it can never make one appear when its own support condition fails.
 */
@Serializable
data class VisibilityConfig(
    /** Sound-quality badges overlaid on the system Bluetooth settings page. */
    val bluetoothBadge: Boolean = true,
    /** Sound-quality badge row on the module's earphone detail page. */
    val detailBadge: Boolean = true,
    val speakToChat: Boolean = true,
    val eq: Boolean = true,
    val playback: Boolean = true,
    val connectionQuality: Boolean = true,
    val dsee: Boolean = true,
    val ldac: Boolean = true,
    /** LE Audio card as a whole. */
    val leAudioCard: Boolean = true,
    /** The low-power-audio switch inside the LE Audio card. */
    val leAudioToggle: Boolean = true,
    val gestures: Boolean = true,
    val multipoint: Boolean = true,
    val firmware: Boolean = true,
    /**
     * While LE Audio carries the audio these three cards degrade to a greyed-out
     * note; a false value hides them outright for the duration instead.
     */
    val leaRestrictedConnectionQuality: Boolean = true,
    val leaRestrictedLdac: Boolean = true,
    val leaRestrictedMultipoint: Boolean = true,
)

@Serializable
data class AppConfig(
    val fakeDeviceId: String = ConfigManager.DEFAULT_FAKE_DEVICE_ID,
    val logLevel: Int = ConfigManager.LOG_LEVEL_BASIC,
    /** Super Island renderer: none, official system island, or module island. */
    val superIslandMode: Int = ConfigManager.ISLAND_MODE_MODULE,
    val islandDurationSeconds: Int = ConfigManager.DEFAULT_ISLAND_DURATION_SECONDS,
    val notificationClickAction: Int = ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP,
    val notificationEnabled: Boolean = true,
    val popupOnConnect: Boolean = false,
    /** Connection dialog renderer: module-owned popup or Bluetooth Extension's PairingDialog. */
    val connectDialogMode: Int = ConfigManager.CONNECT_DIALOG_MODE_OFFICIAL,
    /**
     * Follow Bluetooth Extension's own connect-popup precondition: stay silent while a
     * game is running, and while a phone (not a tablet) is in landscape.
     */
    val suppressPopupInGameOrLandscape: Boolean = true,
    /** Packages exempt from [suppressPopupInGameOrLandscape] while in the foreground. */
    val popupAllowlist: Set<String> = ConfigManager.DEFAULT_POPUP_ALLOWLIST,
    /** Packages that suppress the popup while in the foreground, whatever else holds. */
    val popupDenylist: Set<String> = ConfigManager.DEFAULT_POPUP_DENYLIST,
    val moreClickAction: Int = ConfigManager.MORE_CLICK_MODULE,
    val fusionMoreClickAction: Int = ConfigManager.FUSION_MORE_CLICK_SYSTEM_SETTINGS,
    val adaptiveCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val spatialAudioCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val spatialSoundSwitchCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val ancImplementationCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val ancCycleModes: Set<String> = ConfigManager.DEFAULT_ANC_CYCLE_MODES,
    val startupTab: Int = ConfigManager.STARTUP_TAB_MODULE,
    val visibility: VisibilityConfig = VisibilityConfig(),
)

/**
 * Cross-process configuration authority.
 *
 * The single persistence layer is the framework-backed remote-preference store
 * ([PREFS_NAME] group), following the canonical libxposed pattern: the module app
 * writes the serialized [AppConfig] under [PREF_KEY_CONFIG_JSON] with `.apply()`;
 * hooked processes read it via `XposedModule.getRemotePreferences` and observe changes
 * through `registerOnSharedPreferenceChangeListener`. No side keeps a local
 * SharedPreferences copy of these keys — legacy local storage is handled exclusively
 * by [LegacyConfigMigrator], which is also the only consumer of the direct per-key
 * constants below.
 */
object ConfigManager {
    private const val TAG = "SonyPods-Config"
    const val PREFS_NAME = "sonypods_settings"
    const val PREF_KEY_CONFIG_JSON = "config_json"

    // Legacy direct keys. Never written anymore; parsed only by LegacyConfigMigrator
    // when seeding the remote store from a pre-remote-pref install.
    const val PREF_KEY_FAKE_DEVICE_ID = "fake_device_id"
    const val PREF_KEY_LOG_LEVEL = "log_level"
    // Deliberately uses a new key. The old island_mode/island_show_timings keys
    // are not read, so an upgrade starts with the new defaults instead of
    // carrying the previous renderer/timing selection forward.
    const val PREF_KEY_SUPER_ISLAND_MODE = "super_island_mode"
    const val PREF_KEY_ISLAND_DURATION_SECONDS = "island_duration_seconds"
    const val PREF_KEY_NOTIFICATION_CLICK_ACTION = "notification_click_action"
    const val PREF_KEY_POPUP_ON_CONNECT = "popup_on_connect"
    const val PREF_KEY_CONNECT_DIALOG_MODE = "connect_dialog_mode"
    /**
     * Read only by [LegacyConfigMigrator]. The switch this key backed became
     * "the module's own package sits in [PREF_KEY_POPUP_DENYLIST]".
     */
    const val PREF_KEY_SUPPRESS_POPUP_ON_CONNECT_WHEN_FOREGROUND = "suppress_popup_on_connect_when_foreground"
    const val PREF_KEY_SUPPRESS_POPUP_IN_GAME_OR_LANDSCAPE = "suppress_popup_in_game_or_landscape"
    const val PREF_KEY_POPUP_ALLOWLIST = "popup_allowlist"
    const val PREF_KEY_POPUP_DENYLIST = "popup_denylist"
    const val PREF_KEY_MORE_CLICK_ACTION = "more_click_action"
    const val PREF_KEY_FUSION_MORE_CLICK_ACTION = "fusion_more_click_action"
    const val PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE = "adaptive_capability_override"
    const val PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE = "spatial_audio_capability_override"
    const val PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE = "spatial_sound_switch_capability_override"
    const val PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE = "anc_implementation_capability_override"
    const val PREF_KEY_ANC_CYCLE_MODES = "anc_cycle_modes"
    const val PREF_KEY_STARTUP_TAB = "startup_tab"
    const val DEFAULT_FAKE_DEVICE_ID = "01010607"
    const val LOG_LEVEL_OFF = 0
    const val LOG_LEVEL_BASIC = 1
    const val LOG_LEVEL_DEBUG = 2
    const val ISLAND_MODE_NONE = 0
    const val ISLAND_MODE_OFFICIAL = 1
    const val ISLAND_MODE_MODULE = 2
    const val CONNECT_DIALOG_MODE_MODULE = 0
    const val CONNECT_DIALOG_MODE_OFFICIAL = 1
    const val DEFAULT_ISLAND_DURATION_SECONDS = 10
    /** islandTimeout is specified in seconds by the system; cap at 24h. */
    const val MAX_ISLAND_DURATION_SECONDS = 24 * 60 * 60
    const val NOTIFICATION_CLICK_MODULE_POPUP = 0
    const val NOTIFICATION_CLICK_SYSTEM_SETTINGS = 1
    const val NOTIFICATION_CLICK_HEYTAP = 2
    const val MORE_CLICK_HEYTAP = 0
    const val MORE_CLICK_SYSTEM_SETTINGS = 1
    const val MORE_CLICK_MODULE = 2
    const val FUSION_MORE_CLICK_SYSTEM_SETTINGS = 0
    const val FUSION_MORE_CLICK_MODULE = 1
    const val SPATIAL_AUDIO_OFF = 0
    const val SPATIAL_AUDIO_FIXED = 1
    const val SPATIAL_AUDIO_HEAD_TRACKING = 2
    const val CAPABILITY_OVERRIDE_AUTO = 0
    const val CAPABILITY_OVERRIDE_FORCE_ENABLED = 1
    const val CAPABILITY_OVERRIDE_FORCE_DISABLED = 2

    const val STARTUP_TAB_MODULE = 0
    const val STARTUP_TAB_EARPHONES = 1

    /** Cycle order of the notification/island ANC button; values are [dev.sonypods.protocol.NoiseControlMode] names. */
    val ANC_CYCLE_MODE_ORDER = listOf("NOISE_CANCELLING", "AMBIENT_SOUND", "OFF")
    /**
     * Default cycle set (all three modes). Used only when the persisted value is absent or
     * contains no valid mode names — i.e. never to override a valid subset the user chose.
     */
    val DEFAULT_ANC_CYCLE_MODES: Set<String> = ANC_CYCLE_MODE_ORDER.toSet()

    val DEFAULT_POPUP_ALLOWLIST: Set<String> = setOf("com.miui.home")

    /**
     * The module's own UI starts out on the deny list: an auto popup over the app the
     * user is already reading the headphone state in has never been wanted. This
     * replaces a dedicated switch, so it must stay the default for existing installs.
     */
    val DEFAULT_POPUP_DENYLIST: Set<String> = setOf(BuildConfig.APPLICATION_ID)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedConfig: AppConfig = AppConfig()

    /** The bound cross-process store. Writable app-side, read-only hook-side. */
    @Volatile
    private var store: SharedPreferences? = null

    /**
     * Config awaiting a remote-prefs write because the LSPosed service was unavailable
     * at save time (app process only). Flushed by [attachStore] once the service
     * (re)binds, so the cross-process store stays authoritative even if a save raced
     * the service connection. Memory-only by design: no local prefs file may reappear.
     */
    @Volatile
    private var pendingConfig: AppConfig? = null

    internal fun encode(config: AppConfig): String = json.encodeToString(AppConfig.serializer(), config)

    internal fun decode(raw: String): AppConfig? =
        runCatching { json.decodeFromString(AppConfig.serializer(), raw) }.getOrNull()

    /**
     * Bind the cross-process store and adopt whatever it holds.
     *
     * Hook processes pass their read-only `XposedModule.getRemotePreferences` handle;
     * the app passes the writable `XposedService` one after running
     * [LegacyConfigMigrator.migrateToRemote]. A failed bind leaves the cache untouched.
     */
    @Synchronized
    fun attachStore(prefs: SharedPreferences?) {
        if (prefs == null) {
            Log.w(TAG, "attachStore skipped: no remote-pref store available")
            return
        }
        store = prefs
        refreshFromPrefs(prefs)
        pendingConfig?.let { pending ->
            pendingConfig = null
            writeToStore(prefs, pending)
            Log.d(TAG, "flushed buffered config fakeDeviceId=${pending.fakeDeviceId}")
        }
    }

    fun refreshFromPrefs(prefs: SharedPreferences): AppConfig {
        val oldConfig = cachedConfig
        val raw = runCatching { prefs.getString(PREF_KEY_CONFIG_JSON, null) }.getOrNull()
        val loaded = raw?.let(::decode) ?: AppConfig().also {
            if (raw != null) Log.w(TAG, "config_json failed to decode; using defaults")
        }
        cachedConfig = loaded.normalized()
        logConfigChange("refreshFromPrefs", oldConfig, cachedConfig)
        return cachedConfig
    }

    fun current(): AppConfig = cachedConfig

    /** True after this process has adopted the framework-backed remote store. */
    fun isStoreAttached(): Boolean = store != null

    fun fakeDeviceId(): String = current().fakeDeviceId.normalizedFakeDeviceId()

    fun logLevel(): Int = current().logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG)

    fun islandMode(): Int = current().superIslandMode.coerceIn(ISLAND_MODE_NONE, ISLAND_MODE_MODULE)

    fun islandDurationSeconds(): Int = current().islandDurationSeconds.normalizedIslandDuration()

    fun notificationClickAction(): Int = current().notificationClickAction.coerceIn(NOTIFICATION_CLICK_MODULE_POPUP, NOTIFICATION_CLICK_HEYTAP)

    fun popupOnConnect(): Boolean = current().popupOnConnect

    fun connectDialogMode(): Int = current().connectDialogMode.coerceIn(
        CONNECT_DIALOG_MODE_MODULE,
        CONNECT_DIALOG_MODE_OFFICIAL,
    )

    fun suppressPopupInGameOrLandscape(): Boolean = current().suppressPopupInGameOrLandscape

    fun notificationEnabled(): Boolean = current().notificationEnabled

    fun popupAllowlist(): Set<String> = current().popupAllowlist

    fun popupDenylist(): Set<String> = current().popupDenylist

    fun moreClickAction(): Int = current().moreClickAction.coerceIn(MORE_CLICK_HEYTAP, MORE_CLICK_MODULE)

    fun fusionMoreClickAction(): Int = current().fusionMoreClickAction.coerceIn(
        FUSION_MORE_CLICK_SYSTEM_SETTINGS,
        FUSION_MORE_CLICK_MODULE,
    )

    fun adaptiveCapabilityOverride(): Int = current().adaptiveCapabilityOverride.normalizedCapabilityOverride()

    fun spatialAudioCapabilityOverride(): Int = current().spatialAudioCapabilityOverride.normalizedCapabilityOverride()

    fun spatialSoundSwitchCapabilityOverride(): Int = current().spatialSoundSwitchCapabilityOverride.normalizedCapabilityOverride()

    fun ancImplementationCapabilityOverride(): Int = current().ancImplementationCapabilityOverride.normalizedCapabilityOverride()

    fun ancCycleModes(): Set<String> = current().ancCycleModes.normalizedAncCycleModes()

    fun startupTab(): Int = current().startupTab.coerceIn(STARTUP_TAB_MODULE, STARTUP_TAB_EARPHONES)

    fun visibility(): VisibilityConfig = current().visibility

    fun fakeSupport(): String = "${fakeDeviceId()},000000000000000010000000"

    fun updateFakeDeviceId(fakeDeviceId: String) = save { it.copy(fakeDeviceId = fakeDeviceId.normalizedFakeDeviceId()) }

    fun updateLogLevel(logLevel: Int) = save { it.copy(logLevel = logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG)) }

    fun updateIslandMode(islandMode: Int) = save { it.copy(superIslandMode = islandMode.coerceIn(ISLAND_MODE_NONE, ISLAND_MODE_MODULE)) }

    fun updateIslandDurationSeconds(seconds: Int) = save { it.copy(islandDurationSeconds = seconds.normalizedIslandDuration()) }

    fun updateNotificationClickAction(action: Int) = save {
        it.copy(notificationClickAction = action.coerceIn(NOTIFICATION_CLICK_MODULE_POPUP, NOTIFICATION_CLICK_HEYTAP))
    }

    fun updateNotificationEnabled(enabled: Boolean) = save { it.copy(notificationEnabled = enabled) }

    fun updatePopupOnConnect(enabled: Boolean) = save { it.copy(popupOnConnect = enabled) }

    fun updateConnectDialogMode(mode: Int) = save {
        it.copy(connectDialogMode = mode.coerceIn(CONNECT_DIALOG_MODE_MODULE, CONNECT_DIALOG_MODE_OFFICIAL))
    }

    fun updateSuppressPopupInGameOrLandscape(enabled: Boolean) = save { it.copy(suppressPopupInGameOrLandscape = enabled) }

    fun updatePopupAllowlist(packages: Set<String>) = save { it.copy(popupAllowlist = packages.normalizedPackageSet()) }

    fun updatePopupDenylist(packages: Set<String>) = save { it.copy(popupDenylist = packages.normalizedPackageSet()) }

    fun updateMoreClickAction(action: Int) = save { it.copy(moreClickAction = action.coerceIn(MORE_CLICK_HEYTAP, MORE_CLICK_MODULE)) }

    fun updateFusionMoreClickAction(action: Int) = save {
        it.copy(fusionMoreClickAction = action.coerceIn(FUSION_MORE_CLICK_SYSTEM_SETTINGS, FUSION_MORE_CLICK_MODULE))
    }

    fun updateAdaptiveCapabilityOverride(override: Int) =
        save { it.copy(adaptiveCapabilityOverride = override.normalizedCapabilityOverride()) }

    fun updateSpatialAudioCapabilityOverride(override: Int) =
        save { it.copy(spatialAudioCapabilityOverride = override.normalizedCapabilityOverride()) }

    fun updateSpatialSoundSwitchCapabilityOverride(override: Int) =
        save { it.copy(spatialSoundSwitchCapabilityOverride = override.normalizedCapabilityOverride()) }

    fun updateAncImplementationCapabilityOverride(override: Int) =
        save { it.copy(ancImplementationCapabilityOverride = override.normalizedCapabilityOverride()) }

    fun updateAncCycleModes(modes: Set<String>) = save { it.copy(ancCycleModes = modes.normalizedAncCycleModes()) }

    fun updateStartupTab(tab: Int) = save { it.copy(startupTab = tab.coerceIn(STARTUP_TAB_MODULE, STARTUP_TAB_EARPHONES)) }

    fun updateVisibility(visibility: VisibilityConfig) = save { it.copy(visibility = visibility) }

    /** Mutate, normalize, cache, and persist the config to the cross-process store. */
    private fun save(mutate: (AppConfig) -> AppConfig) {
        val oldConfig = cachedConfig
        val normalized = mutate(cachedConfig).normalized()
        cachedConfig = normalized
        logConfigChange("save", oldConfig, normalized)
        val target = store
        if (target != null) {
            writeToStore(target, normalized)
        } else {
            pendingConfig = normalized
            Log.w(TAG, "save before store bind; buffering until LSPosed service connects")
        }
    }

    /**
     * Single-key async write, matching the libxposed example: the serialized [AppConfig]
     * under [PREF_KEY_CONFIG_JSON] with `.apply()` (the framework's async remote write).
     */
    private fun writeToStore(target: SharedPreferences, config: AppConfig) {
        runCatching {
            target.edit()
                .putString(PREF_KEY_CONFIG_JSON, encode(config))
                .apply()
        }.onFailure { Log.e(TAG, "remote config write failed", it) }
    }

    private fun AppConfig.normalized(): AppConfig = copy(
        fakeDeviceId = fakeDeviceId.normalizedFakeDeviceId(),
        logLevel = logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG),
        superIslandMode = superIslandMode.coerceIn(ISLAND_MODE_NONE, ISLAND_MODE_MODULE),
        islandDurationSeconds = islandDurationSeconds.normalizedIslandDuration(),
        notificationClickAction = notificationClickAction.coerceIn(NOTIFICATION_CLICK_MODULE_POPUP, NOTIFICATION_CLICK_HEYTAP),
        connectDialogMode = connectDialogMode.coerceIn(CONNECT_DIALOG_MODE_MODULE, CONNECT_DIALOG_MODE_OFFICIAL),
        moreClickAction = moreClickAction.coerceIn(MORE_CLICK_HEYTAP, MORE_CLICK_MODULE),
        fusionMoreClickAction = fusionMoreClickAction.coerceIn(FUSION_MORE_CLICK_SYSTEM_SETTINGS, FUSION_MORE_CLICK_MODULE),
        adaptiveCapabilityOverride = adaptiveCapabilityOverride.normalizedCapabilityOverride(),
        spatialAudioCapabilityOverride = spatialAudioCapabilityOverride.normalizedCapabilityOverride(),
        spatialSoundSwitchCapabilityOverride = spatialSoundSwitchCapabilityOverride.normalizedCapabilityOverride(),
        ancImplementationCapabilityOverride = ancImplementationCapabilityOverride.normalizedCapabilityOverride(),
        ancCycleModes = ancCycleModes.normalizedAncCycleModes(),
        popupAllowlist = popupAllowlist.normalizedPackageSet(),
        popupDenylist = popupDenylist.normalizedPackageSet(),
        startupTab = startupTab.coerceIn(STARTUP_TAB_MODULE, STARTUP_TAB_EARPHONES),
    )

    private fun String.normalizedFakeDeviceId(): String = trim().takeIf { it.isNotEmpty() } ?: DEFAULT_FAKE_DEVICE_ID

    private fun Int.normalizedCapabilityOverride(): Int = coerceIn(CAPABILITY_OVERRIDE_AUTO, CAPABILITY_OVERRIDE_FORCE_DISABLED)

    private fun Int.normalizedIslandDuration(): Int =
        takeIf { it in 1..MAX_ISLAND_DURATION_SECONDS } ?: DEFAULT_ISLAND_DURATION_SECONDS

    /**
     * Filters out any values that are not valid [ANC_CYCLE_MODE_ORDER] names.
     *
     * Intentionally does NOT fall back to [DEFAULT_ANC_CYCLE_MODES] when the result is empty:
     * that would silently override a user who deliberately deselected all modes (edge case) or,
     * more critically, would expand a valid two-mode subset (e.g. NC+ASM) back to all three
     * modes whenever this function is called on a freshly-read store that happens to be empty
     * (LSPosed remote-prefs bridge not yet ready at package-load time). The empty-set fallback
     * belongs at the point where the cycle list is actually consumed
     * (SonyEngineHost.CMD_CYCLE_NOISE_CONTROL), not here.
     */
    private fun Set<String>.normalizedAncCycleModes(): Set<String> =
        filterTo(mutableSetOf()) { it in ANC_CYCLE_MODE_ORDER }

    /**
     * Trims blanks out of a package-name list and nothing else.
     *
     * Deliberately does not check the names against PackageManager: an entry for an app
     * the user has uninstalled — or has not installed yet on a restored config — must
     * survive rather than be silently dropped, and the hooked processes that read these
     * lists cannot see the full package list anyway.
     */
    private fun Set<String>.normalizedPackageSet(): Set<String> =
        mapNotNullTo(mutableSetOf()) { it.trim().takeIf(String::isNotEmpty) }

    private fun logConfigChange(source: String, oldConfig: AppConfig, newConfig: AppConfig) {
        val changes = changedFields(oldConfig, newConfig)
        if (changes.isEmpty()) {
            Log.d(TAG, "$source config unchanged: $newConfig")
        } else {
            Log.d(TAG, "$source config changed: ${changes.joinToString()}")
        }
    }

    private fun changedFields(oldConfig: AppConfig, newConfig: AppConfig): List<String> {
        return buildList {
            if (oldConfig.fakeDeviceId != newConfig.fakeDeviceId) {
                add("fakeDeviceId=${oldConfig.fakeDeviceId}->${newConfig.fakeDeviceId}")
            }
            if (oldConfig.logLevel != newConfig.logLevel) {
                add("logLevel=${oldConfig.logLevel}->${newConfig.logLevel}")
            }
            if (oldConfig.superIslandMode != newConfig.superIslandMode) {
                add("superIslandMode=${oldConfig.superIslandMode}->${newConfig.superIslandMode}")
            }
            if (oldConfig.islandDurationSeconds != newConfig.islandDurationSeconds) {
                add("islandDurationSeconds=${oldConfig.islandDurationSeconds}->${newConfig.islandDurationSeconds}")
            }
            if (oldConfig.notificationClickAction != newConfig.notificationClickAction) {
                add("notificationClickAction=${oldConfig.notificationClickAction}->${newConfig.notificationClickAction}")
            }
            if (oldConfig.popupOnConnect != newConfig.popupOnConnect) {
                add("popupOnConnect=${oldConfig.popupOnConnect}->${newConfig.popupOnConnect}")
            }
            if (oldConfig.notificationEnabled != newConfig.notificationEnabled) {
                add("notificationEnabled=${oldConfig.notificationEnabled}->${newConfig.notificationEnabled}")
            }
            if (oldConfig.connectDialogMode != newConfig.connectDialogMode) {
                add("connectDialogMode=${oldConfig.connectDialogMode}->${newConfig.connectDialogMode}")
            }
            if (oldConfig.suppressPopupInGameOrLandscape != newConfig.suppressPopupInGameOrLandscape) {
                add("suppressPopupInGameOrLandscape=${oldConfig.suppressPopupInGameOrLandscape}->${newConfig.suppressPopupInGameOrLandscape}")
            }
            if (oldConfig.popupAllowlist != newConfig.popupAllowlist) {
                add("popupAllowlist=${oldConfig.popupAllowlist}->${newConfig.popupAllowlist}")
            }
            if (oldConfig.popupDenylist != newConfig.popupDenylist) {
                add("popupDenylist=${oldConfig.popupDenylist}->${newConfig.popupDenylist}")
            }
            if (oldConfig.moreClickAction != newConfig.moreClickAction) {
                add("moreClickAction=${oldConfig.moreClickAction}->${newConfig.moreClickAction}")
            }
            if (oldConfig.fusionMoreClickAction != newConfig.fusionMoreClickAction) {
                add("fusionMoreClickAction=${oldConfig.fusionMoreClickAction}->${newConfig.fusionMoreClickAction}")
            }
            if (oldConfig.adaptiveCapabilityOverride != newConfig.adaptiveCapabilityOverride) {
                add("adaptiveCapabilityOverride=${oldConfig.adaptiveCapabilityOverride}->${newConfig.adaptiveCapabilityOverride}")
            }
            if (oldConfig.spatialAudioCapabilityOverride != newConfig.spatialAudioCapabilityOverride) {
                add("spatialAudioCapabilityOverride=${oldConfig.spatialAudioCapabilityOverride}->${newConfig.spatialAudioCapabilityOverride}")
            }
            if (oldConfig.spatialSoundSwitchCapabilityOverride != newConfig.spatialSoundSwitchCapabilityOverride) {
                add("spatialSoundSwitchCapabilityOverride=${oldConfig.spatialSoundSwitchCapabilityOverride}->${newConfig.spatialSoundSwitchCapabilityOverride}")
            }
            if (oldConfig.ancImplementationCapabilityOverride != newConfig.ancImplementationCapabilityOverride) {
                add("ancImplementationCapabilityOverride=${oldConfig.ancImplementationCapabilityOverride}->${newConfig.ancImplementationCapabilityOverride}")
            }
            if (oldConfig.ancCycleModes != newConfig.ancCycleModes) {
                add("ancCycleModes=${oldConfig.ancCycleModes}->${newConfig.ancCycleModes}")
            }
            if (oldConfig.startupTab != newConfig.startupTab) {
                add("startupTab=${oldConfig.startupTab}->${newConfig.startupTab}")
            }
            if (oldConfig.visibility != newConfig.visibility) {
                add("visibility")
            }
        }
    }
}
