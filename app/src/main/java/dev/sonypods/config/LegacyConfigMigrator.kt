package dev.sonypods.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * One-shot compatibility bridge from the pre-remote-pref layout to the framework-backed
 * remote-preference store.
 *
 * History: config was authored into the module's own local file ("sonypods_settings")
 * and mirrored into the remote store on every save. The remote store is now the ONLY
 * persistence for hook-consumed data (the module app keeps no local copy), so this
 * object reads whatever a legacy install still holds locally as the seed value of the
 * store's binding transaction, and deletes the local file once both stores hold a
 * confirmed value. Pure app-local keys — appearance plus the module UI's own startup tab
 * and click actions — move to their own file so the shared-name file can go away entirely.
 *
 * This is the single place where legacy key names are known; nothing else may read or
 * write them.
 */
object LegacyConfigMigrator {
    private const val TAG = "SonyPods-App"

    /** App-local keys that live in [UI_PREFS_NAME]. */
    const val UI_PREFS_NAME = "sonypods_ui"

    /**
     * App-local keys, all stored in [UI_PREFS_NAME]. The appearance keys were always
     * local; the startup tab and the notification/more click actions used to ride inside
     * the shared remote config blob and are pulled out so the module UI never depends on
     * the LSPosed service. The [ConfigManager.PREF_KEY_*] names double as the key names
     * here, so a legacy direct key and the migrated value share one name.
     */
    private val UI_KEYS = listOf(
        "theme_mode",
        "accent_mode",
        "floating_bottom_bar",
        "blur_bottom_bar",
        "blur_top_bar",
        "app_language",
        ConfigManager.PREF_KEY_STARTUP_TAB,
        ConfigManager.PREF_KEY_NOTIFICATION_CLICK_ACTION,
        ConfigManager.PREF_KEY_MORE_CLICK_ACTION,
    )

    /**
     * Move the app-local keys into [UI_PREFS_NAME], per key and only when the target
     * does not already hold one. Runs from Application.onCreate, before any activity
     * reads them in attachBaseContext. The legacy file is kept here on purpose: its
     * config is still read as the seed of the store's binding transaction once the
     * LSPosed service binds ([readLegacySeed]).
     */
    fun migrateUiPrefs(context: Context) {
        runCatching {
            val ui = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            val legacy = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
            var moved = 0
            val editor = ui.edit()
            for (key in UI_KEYS) {
                if (!legacy.contains(key) || ui.contains(key)) continue
                when (val value = legacy.all[key]) {
                    is Int -> editor.putInt(key, value).also { moved++ }
                    is Boolean -> editor.putBoolean(key, value).also { moved++ }
                }
            }
            editor.apply()
            Log.d(TAG, "migrateUiPrefs: moved $moved key(s) into $UI_PREFS_NAME")
        }.onFailure { Log.w(TAG, "migrateUiPrefs failed", it) }
    }

    /**
     * The configuration a pre-remote-pref install still holds in its local file, or null when
     * that file carries no configuration at all.
     *
     * The null case matters: it separates "there is legacy data to migrate" from "the store
     * reads empty", so an empty store is never seeded from a legacy file that holds nothing.
     */
    fun readLegacySeed(context: Context): AppConfig? {
        val legacy = legacyPrefs(context)
        if (LEGACY_CONFIG_KEYS.none { legacy.contains(it) }) return null
        return readLegacyConfig(legacy)
    }

    /** The metadata blob a pre-remote-pref install still holds locally. */
    fun readLegacyEarphones(context: Context): String? =
        runCatching { legacyPrefs(context).getString(PodImagePrefs.PREF_KEY_EARPHONES, null) }.getOrNull()

    /**
     * Drop the legacy local file. Only called once both stores hold a confirmed value — the
     * file is the last place a pre-migration configuration exists.
     */
    fun deleteLegacyFile(context: Context) {
        val deleted = context.deleteSharedPreferences(ConfigManager.PREFS_NAME)
        Log.d(TAG, "legacy local prefs ${ConfigManager.PREFS_NAME} deleted=$deleted")
    }

    private fun legacyPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Every key [readLegacyConfig] can read a configuration out of. A file holding only
     * app-local appearance keys has nothing to migrate.
     */
    private val LEGACY_CONFIG_KEYS = listOf(
        ConfigManager.PREF_KEY_CONFIG_JSON,
        ConfigManager.PREF_KEY_LOG_LEVEL,
        ConfigManager.PREF_KEY_SUPER_ISLAND_MODE,
        ConfigManager.PREF_KEY_ISLAND_DURATION_SECONDS,
        ConfigManager.PREF_KEY_NOTIFICATION_CLICK_ACTION,
        ConfigManager.PREF_KEY_POPUP_ON_CONNECT,
        ConfigManager.PREF_KEY_CONNECT_DIALOG_MODE,
        ConfigManager.PREF_KEY_SUPPRESS_POPUP_IN_GAME_OR_LANDSCAPE,
        ConfigManager.PREF_KEY_POPUP_ALLOWLIST,
        ConfigManager.PREF_KEY_POPUP_DENYLIST,
        ConfigManager.PREF_KEY_SUPPRESS_POPUP_ON_CONNECT_WHEN_FOREGROUND,
        ConfigManager.PREF_KEY_MORE_CLICK_ACTION,
        "open_heytap",
        ConfigManager.PREF_KEY_FUSION_MORE_CLICK_ACTION,
        ConfigManager.PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE,
        ConfigManager.PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE,
        ConfigManager.PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE,
        ConfigManager.PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE,
        ConfigManager.PREF_KEY_ANC_CYCLE_MODES,
        ConfigManager.PREF_KEY_STARTUP_TAB,
        PodImagePrefs.PREF_KEY_EARPHONES,
    )

    /** App-local prefs handle for the module UI's own appearance/startup settings. */
    fun appOnlyPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)

    fun readStartupTab(context: Context): Int =
        appOnlyPrefs(context)
            .getInt(ConfigManager.PREF_KEY_STARTUP_TAB, ConfigManager.STARTUP_TAB_MODULE)
            .coerceIn(ConfigManager.STARTUP_TAB_MODULE, ConfigManager.STARTUP_TAB_EARPHONES)

    fun writeStartupTab(context: Context, value: Int) {
        appOnlyPrefs(context).edit().putInt(ConfigManager.PREF_KEY_STARTUP_TAB, value).apply()
    }

    fun readNotificationClickAction(context: Context): Int =
        appOnlyPrefs(context)
            .getInt(ConfigManager.PREF_KEY_NOTIFICATION_CLICK_ACTION, ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP)
            .coerceIn(ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP, ConfigManager.NOTIFICATION_CLICK_HEYTAP)

    fun writeNotificationClickAction(context: Context, value: Int) {
        appOnlyPrefs(context).edit().putInt(ConfigManager.PREF_KEY_NOTIFICATION_CLICK_ACTION, value).apply()
    }

    fun readMoreClickAction(context: Context): Int =
        appOnlyPrefs(context)
            .getInt(ConfigManager.PREF_KEY_MORE_CLICK_ACTION, ConfigManager.MORE_CLICK_MODULE)
            .coerceIn(ConfigManager.MORE_CLICK_HEYTAP, ConfigManager.MORE_CLICK_MODULE)

    fun writeMoreClickAction(context: Context, value: Int) {
        appOnlyPrefs(context).edit().putInt(ConfigManager.PREF_KEY_MORE_CLICK_ACTION, value).apply()
    }

    /**
     * Pull the app-only fields from the freshly attached remote config into the local
     * [UI_PREFS_NAME] file. These fields previously lived inside the shared config_json
     * blob; the module UI now reads them locally so its startup never depends on the
     * LSPosed service. This one-shot copy preserves the value older builds saved
     * remotely — once a key exists locally it is authoritative and never overwritten.
     */
    fun migrateAppOnlyPrefsToUi(context: Context) {
        runCatching {
            val ui = appOnlyPrefs(context)
            val config = ConfigManager.current()
            val editor = ui.edit()
            var moved = 0
            fun moveIfAbsent(key: String, value: Int) {
                if (!ui.contains(key)) {
                    editor.putInt(key, value)
                    moved++
                }
            }
            moveIfAbsent(ConfigManager.PREF_KEY_STARTUP_TAB, config.startupTab)
            moveIfAbsent(ConfigManager.PREF_KEY_NOTIFICATION_CLICK_ACTION, config.notificationClickAction)
            moveIfAbsent(ConfigManager.PREF_KEY_MORE_CLICK_ACTION, config.moreClickAction)
            editor.apply()
            if (moved > 0) {
                Log.d(TAG, "migrateAppOnlyPrefsToUi: moved $moved key(s) from remote config into $UI_PREFS_NAME")
            }
        }.onFailure { Log.w(TAG, "migrateAppOnlyPrefsToUi failed", it) }
    }

    /**
     * Parse the pre-remote layout: direct per-key values layered over an optional
     * config_json blob (the blob wins only where no direct key exists, mirroring the
     * precedence the old dual-write reader used).
     */
    private fun readLegacyConfig(prefs: SharedPreferences): AppConfig {
        val base = prefs.getString(ConfigManager.PREF_KEY_CONFIG_JSON, null)
            ?.let { raw -> runCatching { ConfigManager.decode(raw) }.getOrNull() }
            ?: AppConfig()

        fun Int.orNull(): Int? = takeIf { it != Int.MIN_VALUE }

        // One-time migration off the "suppress popup while the module is open" switch,
        // whose meaning became "the module's own package sits on the deny list". Only a
        // user who explicitly turned it OFF needs carrying over; everyone else lands on
        // DEFAULT_POPUP_DENYLIST, which already holds that package.
        val denylist = prefs.getStringSet(ConfigManager.PREF_KEY_POPUP_DENYLIST, null)?.toSet()
            ?: if (prefs.contains(ConfigManager.PREF_KEY_SUPPRESS_POPUP_ON_CONNECT_WHEN_FOREGROUND) &&
                !prefs.getBoolean(ConfigManager.PREF_KEY_SUPPRESS_POPUP_ON_CONNECT_WHEN_FOREGROUND, true)
            ) {
                emptySet()
            } else {
                null
            }

        return base.copy(
            logLevel = prefs.getInt(ConfigManager.PREF_KEY_LOG_LEVEL, Int.MIN_VALUE).orNull() ?: base.logLevel,
            superIslandMode = prefs.getInt(ConfigManager.PREF_KEY_SUPER_ISLAND_MODE, Int.MIN_VALUE).orNull()
                ?: base.superIslandMode,
            islandDurationSeconds = prefs.getInt(ConfigManager.PREF_KEY_ISLAND_DURATION_SECONDS, Int.MIN_VALUE).orNull()
                ?: base.islandDurationSeconds,
            notificationClickAction = prefs.getInt(ConfigManager.PREF_KEY_NOTIFICATION_CLICK_ACTION, Int.MIN_VALUE).orNull()
                ?: base.notificationClickAction,
            popupOnConnect = if (prefs.contains(ConfigManager.PREF_KEY_POPUP_ON_CONNECT)) {
                prefs.getBoolean(ConfigManager.PREF_KEY_POPUP_ON_CONNECT, false)
            } else {
                base.popupOnConnect
            },
            connectDialogMode = prefs.getInt(ConfigManager.PREF_KEY_CONNECT_DIALOG_MODE, Int.MIN_VALUE).orNull()
                ?: base.connectDialogMode,
            suppressPopupInGameOrLandscape =
                if (prefs.contains(ConfigManager.PREF_KEY_SUPPRESS_POPUP_IN_GAME_OR_LANDSCAPE)) {
                    prefs.getBoolean(ConfigManager.PREF_KEY_SUPPRESS_POPUP_IN_GAME_OR_LANDSCAPE, true)
                } else {
                    base.suppressPopupInGameOrLandscape
                },
            popupAllowlist = prefs.getStringSet(ConfigManager.PREF_KEY_POPUP_ALLOWLIST, null)?.toSet()
                ?: base.popupAllowlist,
            popupDenylist = denylist ?: base.popupDenylist,
            moreClickAction = prefs.getInt(ConfigManager.PREF_KEY_MORE_CLICK_ACTION, Int.MIN_VALUE).orNull()
                ?: if (prefs.getBoolean("open_heytap", false)) {
                    ConfigManager.MORE_CLICK_HEYTAP
                } else {
                    base.moreClickAction
                },
            fusionMoreClickAction = prefs.getInt(ConfigManager.PREF_KEY_FUSION_MORE_CLICK_ACTION, Int.MIN_VALUE).orNull()
                ?: base.fusionMoreClickAction,
            adaptiveCapabilityOverride = prefs.getInt(ConfigManager.PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
                .orNull() ?: base.adaptiveCapabilityOverride,
            spatialAudioCapabilityOverride = prefs.getInt(
                ConfigManager.PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE, Int.MIN_VALUE,
            ).orNull() ?: base.spatialAudioCapabilityOverride,
            spatialSoundSwitchCapabilityOverride = prefs.getInt(
                ConfigManager.PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE, Int.MIN_VALUE,
            ).orNull() ?: base.spatialSoundSwitchCapabilityOverride,
            ancImplementationCapabilityOverride = prefs.getInt(
                ConfigManager.PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE, Int.MIN_VALUE,
            ).orNull() ?: base.ancImplementationCapabilityOverride,
            ancCycleModes = prefs.getStringSet(ConfigManager.PREF_KEY_ANC_CYCLE_MODES, null)?.toSet()
                ?: base.ancCycleModes,
            startupTab = prefs.getInt(ConfigManager.PREF_KEY_STARTUP_TAB, Int.MIN_VALUE).orNull() ?: base.startupTab,
        )
    }
}
