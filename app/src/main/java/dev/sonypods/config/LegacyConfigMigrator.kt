package dev.sonypods.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService

/**
 * One-shot compatibility bridge from the pre-remote-pref layout to the framework-backed
 * remote-preference store.
 *
 * History: config was authored into the module's own local file ("sonypods_settings")
 * and mirrored into the remote store on every save. The remote store is now the ONLY
 * persistence for hook-consumed data (the module app keeps no local copy), so this
 * object moves whatever a legacy install still holds locally into the shared store and
 * then deletes the local file. Pure app-local appearance keys move to their own file so
 * the shared-name file can go away entirely.
 *
 * This is the single place where legacy key names are known; nothing else may read or
 * write them.
 */
object LegacyConfigMigrator {
    private const val TAG = "SonyPods-LegacyMigrate"

    /** App-local appearance prefs. Never consumed by hook processes; stay in a local file. */
    const val UI_PREFS_NAME = "sonypods_ui"
    private val UI_KEYS = listOf(
        "theme_mode",
        "accent_mode",
        "floating_bottom_bar",
        "blur_bottom_bar",
        "blur_top_bar",
        "app_language",
    )

    /**
     * Move the appearance keys into [UI_PREFS_NAME]. Runs from Application.onCreate,
     * before any activity reads them in attachBaseContext. The legacy file is kept here
     * on purpose: its config may still be needed by [migrateToRemote] once the LSPosed
     * service binds.
     */
    fun migrateUiPrefs(context: Context) {
        runCatching {
            val ui = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            if (ui.all.isNotEmpty()) return
            val legacy = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
            var moved = 0
            val editor = ui.edit()
            for (key in UI_KEYS) {
                if (!legacy.contains(key)) continue
                when (val value = legacy.all[key]) {
                    is Int -> editor.putInt(key, value).also { moved++ }
                    is Boolean -> editor.putBoolean(key, value).also { moved++ }
                    else -> null
                }
            }
            editor.apply()
            Log.d(TAG, "migrateUiPrefs: moved $moved key(s) into $UI_PREFS_NAME")
        }.onFailure { Log.w(TAG, "migrateUiPrefs failed", it) }
    }

    /**
     * Seed the remote store from the legacy local file for installs that predate
     * remote-only persistence, then delete the local file. Idempotent and cheap enough
     * to run on every service bind: once the remote store holds config_json the legacy
     * parse is skipped, and deleting an already-deleted file is a no-op.
     */
    fun migrateToRemote(context: Context, service: XposedService) {
        runCatching {
            val remote = service.getRemotePreferences(ConfigManager.PREFS_NAME)
            val legacy = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
            if (!remote.contains(ConfigManager.PREF_KEY_CONFIG_JSON)) {
                val config = readLegacyConfig(legacy)
                remote.edit()
                    .putString(ConfigManager.PREF_KEY_CONFIG_JSON, ConfigManager.encode(config))
                    .apply()
                Log.d(TAG, "seeded remote store from legacy file fakeDeviceId=${config.fakeDeviceId}")
            }
            if (!remote.contains(PodImagePrefs.PREF_KEY_EARPHONES)) {
                legacy.getString(PodImagePrefs.PREF_KEY_EARPHONES, null)?.let { earphonesJson ->
                    remote.edit().putString(PodImagePrefs.PREF_KEY_EARPHONES, earphonesJson).apply()
                    Log.d(TAG, "copied legacy earphone metadata (${earphonesJson.length} bytes)")
                }
            }
            // The remote store is authoritative from here on; the local copy is redundant
            // for migrated installs and empty for fresh ones.
            val deleted = context.deleteSharedPreferences(ConfigManager.PREFS_NAME)
            Log.d(TAG, "legacy local prefs ${ConfigManager.PREFS_NAME} deleted=$deleted")
        }.onFailure { Log.w(TAG, "migrateToRemote failed", it) }
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
            fakeDeviceId = prefs.getString(ConfigManager.PREF_KEY_FAKE_DEVICE_ID, null)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: base.fakeDeviceId,
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
