package dev.sonypods.config

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppConfig(
    val fakeDeviceId: String = ConfigManager.DEFAULT_FAKE_DEVICE_ID,
    val logLevel: Int = ConfigManager.LOG_LEVEL_BASIC,
    val islandMode: Int = ConfigManager.ISLAND_MODE_OFFICIAL,
    val islandShowTimings: Set<Int> = emptySet(),
    val notificationClickAction: Int = ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP,
    val moreClickAction: Int = ConfigManager.MORE_CLICK_MODULE,
    val adaptiveCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val spatialAudioCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val spatialSoundSwitchCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val ancImplementationCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val ancCycleModes: Set<String> = ConfigManager.DEFAULT_ANC_CYCLE_MODES,
    val startupTab: Int = ConfigManager.STARTUP_TAB_MODULE,
)

object ConfigManager {
    private const val TAG = "SonyPods-Config"
    const val PREFS_NAME = "sonypods_settings"
    const val PREF_KEY_CONFIG_JSON = "config_json"
    const val PREF_KEY_FAKE_DEVICE_ID = "fake_device_id"
    const val PREF_KEY_LOG_LEVEL = "log_level"
    const val PREF_KEY_ISLAND_MODE = "island_mode"
    const val PREF_KEY_ISLAND_SHOW_TIMINGS = "island_show_timings"
    const val PREF_KEY_NOTIFICATION_CLICK_ACTION = "notification_click_action"
    const val PREF_KEY_MORE_CLICK_ACTION = "more_click_action"
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
    const val ISLAND_SHOW_TIMING_CONNECTED = 0
    const val ISLAND_SHOW_TIMING_WEARING = 1
    const val ISLAND_SHOW_TIMING_REMOVED = 2
    const val ISLAND_SHOW_TIMING_IN_CASE = 3
    const val NOTIFICATION_CLICK_MODULE_POPUP = 0
    const val NOTIFICATION_CLICK_SYSTEM_SETTINGS = 1
    const val NOTIFICATION_CLICK_HEYTAP = 2
    const val MORE_CLICK_HEYTAP = 0
    const val MORE_CLICK_SYSTEM_SETTINGS = 1
    const val MORE_CLICK_MODULE = 2
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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedConfig: AppConfig = AppConfig()

    /**
     * Config awaiting a remote-prefs write because the LSPosed service was unavailable
     * at save time. Flushed by [flushPendingRemote] once the service (re)binds, so the
     * cross-process store stays authoritative and survives scope restarts even if the
     * first save raced the service connection. Only relevant in the app process; the
     * hook process always has the service and never buffers.
     */
    @Volatile
    private var pendingRemoteConfig: AppConfig? = null

    fun init(prefs: SharedPreferences) {
        val oldConfig = cachedConfig
        cachedConfig = readConfig(prefs, "init")
        logConfigChange("init", oldConfig, cachedConfig)
    }

    fun refreshFromPrefs(prefs: SharedPreferences): AppConfig {
        val oldConfig = cachedConfig
        return readConfig(prefs, "refreshFromPrefs").also {
            cachedConfig = it
            logConfigChange("refreshFromPrefs", oldConfig, it)
        }
    }

    /**
     * Apply a config pushed from the app process by value (serialized [AppConfig] JSON),
     * bypassing the remote-preferences store entirely. Used by the cross-process config
     * broadcast so a change made in the app takes effect in the engine immediately, even
     * when the remote-prefs write did not propagate (e.g. XposedService unavailable at save
     * time). Updates the shared [cachedConfig] without touching any SharedPreferences.
     * Returns true if the JSON was applied.
     */
    fun applyConfigJson(json: String): Boolean {
        val parsed = runCatching { this.json.decodeFromString(AppConfig.serializer(), json) }.getOrNull() ?: run {
            Log.w(TAG, "applyConfigJson: failed to decode config json, ignoring")
            return false
        }
        val oldConfig = cachedConfig
        cachedConfig = parsed.normalized()
        logConfigChange("applyConfigJson", oldConfig, cachedConfig)
        return true
    }

    fun current(): AppConfig = cachedConfig

    /** Serialize the current config to JSON for cross-process push via broadcast. */
    fun currentAsJson(): String = json.encodeToString(AppConfig.serializer(), cachedConfig)

    fun fakeDeviceId(): String = current().fakeDeviceId.normalizedFakeDeviceId()

    fun logLevel(): Int = current().logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG)

    fun islandMode(): Int = current().islandMode.coerceIn(ISLAND_MODE_NONE, ISLAND_MODE_MODULE)

    fun islandShowTimings(): Set<Int> = current().islandShowTimings.normalizedIslandShowTimings()

    fun notificationClickAction(): Int = current().notificationClickAction.coerceIn(NOTIFICATION_CLICK_MODULE_POPUP, NOTIFICATION_CLICK_HEYTAP)

    fun moreClickAction(): Int = current().moreClickAction.coerceIn(MORE_CLICK_HEYTAP, MORE_CLICK_MODULE)

    fun adaptiveCapabilityOverride(): Int = current().adaptiveCapabilityOverride.normalizedCapabilityOverride()

    fun spatialAudioCapabilityOverride(): Int = current().spatialAudioCapabilityOverride.normalizedCapabilityOverride()

    fun spatialSoundSwitchCapabilityOverride(): Int = current().spatialSoundSwitchCapabilityOverride.normalizedCapabilityOverride()

    fun ancImplementationCapabilityOverride(): Int = current().ancImplementationCapabilityOverride.normalizedCapabilityOverride()

    fun ancCycleModes(): Set<String> = current().ancCycleModes.normalizedAncCycleModes()

    fun startupTab(): Int = current().startupTab.coerceIn(STARTUP_TAB_MODULE, STARTUP_TAB_EARPHONES)

    fun fakeSupport(): String = "${fakeDeviceId()},000000000000000010000000"

    fun updateFakeDeviceId(prefs: SharedPreferences, fakeDeviceId: String) {
        val config = current().copy(fakeDeviceId = fakeDeviceId.normalizedFakeDeviceId())
        save(prefs, config)
    }

    fun updateFakeDeviceId(prefs: SharedPreferences, service: XposedService?, fakeDeviceId: String) {
        val config = current().copy(fakeDeviceId = fakeDeviceId.normalizedFakeDeviceId())
        save(prefs, service, config)
    }

    fun updateLogLevel(prefs: SharedPreferences, service: XposedService?, logLevel: Int) {
        val config = current().copy(logLevel = logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG))
        save(prefs, service, config)
    }

    fun updateIslandMode(prefs: SharedPreferences, service: XposedService?, islandMode: Int) {
        val config = current().copy(islandMode = islandMode.coerceIn(ISLAND_MODE_NONE, ISLAND_MODE_MODULE))
        save(prefs, service, config)
    }

    fun updateIslandShowTimings(prefs: SharedPreferences, service: XposedService?, timings: Set<Int>) {
        val config = current().copy(islandShowTimings = timings.normalizedIslandShowTimings())
        save(prefs, service, config)
    }

    fun updateNotificationClickAction(prefs: SharedPreferences, service: XposedService?, action: Int) {
        val config = current().copy(notificationClickAction = action.coerceIn(NOTIFICATION_CLICK_MODULE_POPUP, NOTIFICATION_CLICK_HEYTAP))
        save(prefs, service, config)
    }

    fun updateMoreClickAction(prefs: SharedPreferences, service: XposedService?, action: Int) {
        val config = current().copy(moreClickAction = action.coerceIn(MORE_CLICK_HEYTAP, MORE_CLICK_MODULE))
        save(prefs, service, config)
    }

    fun updateAdaptiveCapabilityOverride(prefs: SharedPreferences, service: XposedService?, override: Int) {
        val config = current().copy(adaptiveCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, service, config)
    }

    fun updateSpatialAudioCapabilityOverride(prefs: SharedPreferences, service: XposedService?, override: Int) {
        val config = current().copy(spatialAudioCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, service, config)
    }

    fun updateSpatialSoundSwitchCapabilityOverride(prefs: SharedPreferences, service: XposedService?, override: Int) {
        val config = current().copy(spatialSoundSwitchCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, service, config)
    }

    fun updateAncImplementationCapabilityOverride(prefs: SharedPreferences, service: XposedService?, override: Int) {
        val config = current().copy(ancImplementationCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, service, config)
    }

    fun updateAncCycleModes(prefs: SharedPreferences, service: XposedService?, modes: Set<String>) {
        val config = current().copy(ancCycleModes = modes.normalizedAncCycleModes())
        save(prefs, service, config)
    }

    fun updateStartupTab(prefs: SharedPreferences, service: XposedService?, tab: Int) {
        val config = current().copy(startupTab = tab.coerceIn(STARTUP_TAB_MODULE, STARTUP_TAB_EARPHONES))
        save(prefs, service, config)
    }

    fun save(prefs: SharedPreferences, config: AppConfig) {
        val oldConfig = cachedConfig
        val normalized = config.copy(fakeDeviceId = config.fakeDeviceId.normalizedFakeDeviceId())
        cachedConfig = normalized
        writePrefs(prefs, normalized)
        logConfigChange("save", oldConfig, normalized)
    }

    fun save(prefs: SharedPreferences, service: XposedService?, config: AppConfig) {
        val oldConfig = cachedConfig
        val normalized = config.copy(fakeDeviceId = config.fakeDeviceId.normalizedFakeDeviceId())
        cachedConfig = normalized
        writePrefs(prefs, normalized)
        if (service != null) {
            // getRemotePreferences() is non-null in libxposed 101/102; a non-null service
            // always yields a usable cross-process store. This (app-side) store is WRITABLE,
            // unlike the engine-side one, so it is the durable authority the engine reads at
            // startup via ConfigManager.init.
            val remotePrefs = service.getRemotePreferences(PREFS_NAME)
            writeRemoteConfig(remotePrefs, normalized)
            val hasConfigJson = runCatching { remotePrefs.contains(PREF_KEY_CONFIG_JSON) }.getOrDefault(false)
            Log.d(TAG, "save remote prefs class=${remotePrefs.javaClass.name} hasConfigJson=$hasConfigJson fakeDeviceId=${normalized.fakeDeviceId}")
            pendingRemoteConfig = null
        } else {
            // The engine reads remote prefs at startup (after a scope restart), so a
            // missing write here would make the persisted config revert to defaults.
            // Buffer it and flush when the service binds.
            pendingRemoteConfig = normalized
            Log.w(TAG, "save remote prefs skipped: LSPosed service is null; buffering for flush on bind")
        }
        logConfigChange("save", oldConfig, normalized)
    }

    /**
     * Write any config buffered because the LSPosed service was null at save time.
     * Call from [dev.sonypods.SonyPodsApp.onServiceBind] so the cross-process store is
     * always current, even if a save raced the service connection.
     */
    fun flushPendingRemote(service: XposedService?) {
        val pending = pendingRemoteConfig ?: return
        service ?: return
        val remotePrefs = service.getRemotePreferences(PREFS_NAME)
        writeRemoteConfig(remotePrefs, pending)
        pendingRemoteConfig = null
        Log.d(TAG, "flushed buffered remote config fakeDeviceId=${pending.fakeDeviceId}")
    }

    /**
     * Unconditionally write the current [cachedConfig] to the remote-preference store.
     *
     * This repairs a remote-prefs store that is stale or missing [PREF_KEY_CONFIG_JSON] —
     * for example after an older build of the module wrote unrelated data into remote prefs,
     * evicting config_json, leaving the engine with no config on startup.
     * Unlike [flushPendingRemote], which only acts when there is a buffered write, this always
     * writes and is therefore safe to call on every [onServiceBind].
     */
    fun syncToRemote(service: XposedService?) {
        service ?: return
        runCatching {
            val remotePrefs = service.getRemotePreferences(PREFS_NAME)
            val hasConfig = runCatching { remotePrefs.contains(PREF_KEY_CONFIG_JSON) }.getOrDefault(false)
            if (!hasConfig) {
                writeRemoteConfig(remotePrefs, cachedConfig)
                Log.d(TAG, "syncToRemote: config_json was absent; wrote cachedConfig ancCycleModes=${cachedConfig.ancCycleModes}")
            } else {
                Log.d(TAG, "syncToRemote: config_json already present, skipping")
            }
        }.onFailure { Log.w(TAG, "syncToRemote failed", it) }
    }

    private fun writePrefs(prefs: SharedPreferences, config: AppConfig) {
        prefs.edit()
            .putString(PREF_KEY_CONFIG_JSON, json.encodeToString(AppConfig.serializer(), config))
            .putString(PREF_KEY_FAKE_DEVICE_ID, config.fakeDeviceId)
            .putInt(PREF_KEY_LOG_LEVEL, config.logLevel)
            .putInt(PREF_KEY_ISLAND_MODE, config.islandMode)
            .putStringSet(PREF_KEY_ISLAND_SHOW_TIMINGS, config.islandShowTimings.map { it.toString() }.toSet())
            .putInt(PREF_KEY_NOTIFICATION_CLICK_ACTION, config.notificationClickAction)
            .putInt(PREF_KEY_MORE_CLICK_ACTION, config.moreClickAction)
            .putInt(PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE, config.adaptiveCapabilityOverride)
            .putInt(PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE, config.spatialAudioCapabilityOverride)
            .putInt(PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE, config.spatialSoundSwitchCapabilityOverride)
            .putInt(PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE, config.ancImplementationCapabilityOverride)
            .putStringSet(PREF_KEY_ANC_CYCLE_MODES, config.ancCycleModes)
            .putInt(PREF_KEY_STARTUP_TAB, config.startupTab)
            .commit()
    }

    /**
     * Write the config to the framework-backed remote-preference store, following the
     * canonical libxposed pattern (see libxposed/example): a single authoritative key — the
     * serialized [AppConfig] — written with `.apply()` (the framework's async remote write).
     * The hooked app reads it back via `getRemotePreferences` and observes changes through
     * `registerOnSharedPreferenceChangeListener`. Direct/legacy keys are NOT mirrored to
     * remote prefs; [readConfig] falls back to config_json when they are absent, so this
     * single key is sufficient. Using `.apply()` (not `.commit()`) matches the reference
     * implementation and avoids a synchronous IPC write that can fail silently.
     */
    private fun writeRemoteConfig(remotePrefs: SharedPreferences, config: AppConfig) {
        remotePrefs.edit()
            .putString(PREF_KEY_CONFIG_JSON, json.encodeToString(AppConfig.serializer(), config))
            .apply()
    }

    private fun readConfig(prefs: SharedPreferences, source: String): AppConfig {
        val directFakeDeviceId = prefs.getString(PREF_KEY_FAKE_DEVICE_ID, null)
        val directLogLevel = prefs.getInt(PREF_KEY_LOG_LEVEL, Int.MIN_VALUE)
        val directIslandMode = prefs.getInt(PREF_KEY_ISLAND_MODE, Int.MIN_VALUE)
        val directIslandShowTimings = prefs.getStringSet(PREF_KEY_ISLAND_SHOW_TIMINGS, null)?.mapNotNull { it.toIntOrNull() }?.toSet()
        val directNotificationClickAction = prefs.getInt(PREF_KEY_NOTIFICATION_CLICK_ACTION, Int.MIN_VALUE)
        val directMoreClickAction = prefs.getInt(PREF_KEY_MORE_CLICK_ACTION, Int.MIN_VALUE)
        val directAdaptiveCapabilityOverride = prefs.getInt(PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directSpatialAudioCapabilityOverride = prefs.getInt(PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directSpatialSoundSwitchCapabilityOverride = prefs.getInt(PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directAncImplementationCapabilityOverride = prefs.getInt(PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directAncCycleModes = prefs.getStringSet(PREF_KEY_ANC_CYCLE_MODES, null)?.toSet()
        val directStartupTab = prefs.getInt(PREF_KEY_STARTUP_TAB, Int.MIN_VALUE)
        val raw = prefs.getString(PREF_KEY_CONFIG_JSON, null)
        logPrefsSnapshot(source, prefs, directFakeDeviceId, raw)
        val config = raw?.let {
            runCatching { json.decodeFromString(AppConfig.serializer(), it) }.getOrNull()
        } ?: AppConfig()
        val migratedMoreClickAction = if (prefs.getBoolean("open_heytap", false)) MORE_CLICK_HEYTAP else config.moreClickAction
        if (!directFakeDeviceId.isNullOrBlank()) {
            return config.copy(
                fakeDeviceId = directFakeDeviceId.normalizedFakeDeviceId(),
                logLevel = directLogLevel.takeIf { it != Int.MIN_VALUE } ?: config.logLevel,
                islandMode = directIslandMode.takeIf { it != Int.MIN_VALUE } ?: config.islandMode,
                islandShowTimings = directIslandShowTimings ?: config.islandShowTimings,
                notificationClickAction = directNotificationClickAction.takeIf { it != Int.MIN_VALUE } ?: config.notificationClickAction,
                moreClickAction = directMoreClickAction.takeIf { it != Int.MIN_VALUE } ?: migratedMoreClickAction,
                adaptiveCapabilityOverride = directAdaptiveCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.adaptiveCapabilityOverride,
                spatialAudioCapabilityOverride = directSpatialAudioCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialAudioCapabilityOverride,
                spatialSoundSwitchCapabilityOverride = directSpatialSoundSwitchCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialSoundSwitchCapabilityOverride,
                ancImplementationCapabilityOverride = directAncImplementationCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.ancImplementationCapabilityOverride,
                ancCycleModes = directAncCycleModes ?: config.ancCycleModes,
                startupTab = directStartupTab.takeIf { it != Int.MIN_VALUE } ?: config.startupTab,
            ).normalized()
        }
        return config.copy(
            fakeDeviceId = config.fakeDeviceId.normalizedFakeDeviceId(),
            logLevel = directLogLevel.takeIf { it != Int.MIN_VALUE } ?: config.logLevel,
            islandMode = directIslandMode.takeIf { it != Int.MIN_VALUE } ?: config.islandMode,
            islandShowTimings = directIslandShowTimings ?: config.islandShowTimings,
            notificationClickAction = directNotificationClickAction.takeIf { it != Int.MIN_VALUE } ?: config.notificationClickAction,
            moreClickAction = directMoreClickAction.takeIf { it != Int.MIN_VALUE } ?: migratedMoreClickAction,
            adaptiveCapabilityOverride = directAdaptiveCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.adaptiveCapabilityOverride,
            spatialAudioCapabilityOverride = directSpatialAudioCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialAudioCapabilityOverride,
            spatialSoundSwitchCapabilityOverride = directSpatialSoundSwitchCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialSoundSwitchCapabilityOverride,
            ancImplementationCapabilityOverride = directAncImplementationCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.ancImplementationCapabilityOverride,
            ancCycleModes = directAncCycleModes ?: config.ancCycleModes,
            startupTab = directStartupTab.takeIf { it != Int.MIN_VALUE } ?: config.startupTab,
        ).normalized()
    }

    private fun AppConfig.normalized(): AppConfig = copy(
        fakeDeviceId = fakeDeviceId.normalizedFakeDeviceId(),
        logLevel = logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG),
        islandMode = islandMode.coerceIn(ISLAND_MODE_NONE, ISLAND_MODE_MODULE),
        islandShowTimings = islandShowTimings.normalizedIslandShowTimings(),
        notificationClickAction = notificationClickAction.coerceIn(NOTIFICATION_CLICK_MODULE_POPUP, NOTIFICATION_CLICK_HEYTAP),
        moreClickAction = moreClickAction.coerceIn(MORE_CLICK_HEYTAP, MORE_CLICK_MODULE),
        adaptiveCapabilityOverride = adaptiveCapabilityOverride.normalizedCapabilityOverride(),
        spatialAudioCapabilityOverride = spatialAudioCapabilityOverride.normalizedCapabilityOverride(),
        spatialSoundSwitchCapabilityOverride = spatialSoundSwitchCapabilityOverride.normalizedCapabilityOverride(),
        ancImplementationCapabilityOverride = ancImplementationCapabilityOverride.normalizedCapabilityOverride(),
        ancCycleModes = ancCycleModes.normalizedAncCycleModes(),
        startupTab = startupTab.coerceIn(STARTUP_TAB_MODULE, STARTUP_TAB_EARPHONES),
    )

    private fun String.normalizedFakeDeviceId(): String = trim().takeIf { it.isNotEmpty() } ?: DEFAULT_FAKE_DEVICE_ID

    private fun Int.normalizedCapabilityOverride(): Int = coerceIn(CAPABILITY_OVERRIDE_AUTO, CAPABILITY_OVERRIDE_FORCE_DISABLED)

    private fun Set<Int>.normalizedIslandShowTimings(): Set<Int> = filterTo(mutableSetOf()) {
        it in ISLAND_SHOW_TIMING_CONNECTED..ISLAND_SHOW_TIMING_IN_CASE
    }

    /**
     * Filters out any values that are not valid [ANC_CYCLE_MODE_ORDER] names.
     *
     * Intentionally does NOT fall back to [DEFAULT_ANC_CYCLE_MODES] when the result is empty:
     * that would silently override a user who deliberately deselected all modes (edge case) or,
     * more critically, would expand a valid two-mode subset (e.g. NC+ASM) back to all three
     * modes whenever this function is called on a freshly-read SharedPreferences that happens
     * to be empty (LSPosed remote-prefs bridge not yet ready at package-load time). The
     * empty-set fallback belongs at the point where the cycle list is actually consumed
     * (SonyEngineHost.CMD_CYCLE_NOISE_CONTROL), not here.
     */
    private fun Set<String>.normalizedAncCycleModes(): Set<String> =
        filterTo(mutableSetOf()) { it in ANC_CYCLE_MODE_ORDER }

    private fun logConfigChange(source: String, oldConfig: AppConfig, newConfig: AppConfig) {
        val changes = changedFields(oldConfig, newConfig)
        if (changes.isEmpty()) {
            Log.d(TAG, "$source config unchanged: $newConfig")
        } else {
            Log.d(TAG, "$source config changed: ${changes.joinToString()}")
        }
    }

    private fun logPrefsSnapshot(source: String, prefs: SharedPreferences, directFakeDeviceId: String?, rawConfig: String?) {
        val all = runCatching { prefs.all }.getOrElse { error -> mapOf("<getAllError>" to error.message) }
        Log.d(
            TAG,
            "$source prefs snapshot class=${prefs.javaClass.name} keys=${all.keys.sorted()} " +
                "$PREF_KEY_FAKE_DEVICE_ID=$directFakeDeviceId $PREF_KEY_CONFIG_JSON=$rawConfig all=$all"
        )
    }

    private fun changedFields(oldConfig: AppConfig, newConfig: AppConfig): List<String> {
        return buildList {
            if (oldConfig.fakeDeviceId != newConfig.fakeDeviceId) {
                add("fakeDeviceId=${oldConfig.fakeDeviceId}->${newConfig.fakeDeviceId}")
            }
            if (oldConfig.logLevel != newConfig.logLevel) {
                add("logLevel=${oldConfig.logLevel}->${newConfig.logLevel}")
            }
            if (oldConfig.islandMode != newConfig.islandMode) {
                add("islandMode=${oldConfig.islandMode}->${newConfig.islandMode}")
            }
            if (oldConfig.islandShowTimings != newConfig.islandShowTimings) {
                add("islandShowTimings=${oldConfig.islandShowTimings}->${newConfig.islandShowTimings}")
            }
            if (oldConfig.notificationClickAction != newConfig.notificationClickAction) {
                add("notificationClickAction=${oldConfig.notificationClickAction}->${newConfig.notificationClickAction}")
            }
            if (oldConfig.moreClickAction != newConfig.moreClickAction) {
                add("moreClickAction=${oldConfig.moreClickAction}->${newConfig.moreClickAction}")
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
        }
    }
}
