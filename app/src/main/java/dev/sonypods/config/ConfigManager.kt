package dev.sonypods.config

import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppConfig(
    val logLevel: Int = ConfigManager.LOG_LEVEL_BASIC,
    val adaptiveCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val spatialAudioCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val spatialSoundSwitchCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val ancImplementationCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
)

object ConfigManager {
    private const val TAG = "SonyPods-Config"
    const val PREFS_NAME = "sonypods_settings"
    const val PREF_KEY_CONFIG_JSON = "config_json"
    const val PREF_KEY_LOG_LEVEL = "log_level"
    const val PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE = "adaptive_capability_override"
    const val PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE = "spatial_audio_capability_override"
    const val PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE = "spatial_sound_switch_capability_override"
    const val PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE = "anc_implementation_capability_override"
    const val LOG_LEVEL_OFF = 0
    const val LOG_LEVEL_BASIC = 1
    const val LOG_LEVEL_DEBUG = 2
    const val SPATIAL_AUDIO_OFF = 0
    const val SPATIAL_AUDIO_FIXED = 1
    const val SPATIAL_AUDIO_HEAD_TRACKING = 2
    const val CAPABILITY_OVERRIDE_AUTO = 0
    const val CAPABILITY_OVERRIDE_FORCE_ENABLED = 1
    const val CAPABILITY_OVERRIDE_FORCE_DISABLED = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedConfig: AppConfig = AppConfig()

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

    /** Apply a serialized configuration received from the application host. */
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

    fun logLevel(): Int = current().logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG)

    fun adaptiveCapabilityOverride(): Int = current().adaptiveCapabilityOverride.normalizedCapabilityOverride()

    fun spatialAudioCapabilityOverride(): Int = current().spatialAudioCapabilityOverride.normalizedCapabilityOverride()

    fun spatialSoundSwitchCapabilityOverride(): Int = current().spatialSoundSwitchCapabilityOverride.normalizedCapabilityOverride()

    fun ancImplementationCapabilityOverride(): Int = current().ancImplementationCapabilityOverride.normalizedCapabilityOverride()

    fun updateLogLevel(prefs: SharedPreferences, logLevel: Int) {
        val config = current().copy(logLevel = logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG))
        save(prefs, config)
    }

    fun updateAdaptiveCapabilityOverride(prefs: SharedPreferences, override: Int) {
        val config = current().copy(adaptiveCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, config)
    }

    fun updateSpatialAudioCapabilityOverride(prefs: SharedPreferences, override: Int) {
        val config = current().copy(spatialAudioCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, config)
    }

    fun updateSpatialSoundSwitchCapabilityOverride(prefs: SharedPreferences, override: Int) {
        val config = current().copy(spatialSoundSwitchCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, config)
    }

    fun updateAncImplementationCapabilityOverride(prefs: SharedPreferences, override: Int) {
        val config = current().copy(ancImplementationCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, config)
    }

    fun save(prefs: SharedPreferences, config: AppConfig) {
        val oldConfig = cachedConfig
        val normalized = config.normalized()
        cachedConfig = normalized
        writePrefs(prefs, normalized)
        logConfigChange("save", oldConfig, normalized)
    }

    private fun writePrefs(prefs: SharedPreferences, config: AppConfig) {
        prefs.edit()
            .putString(PREF_KEY_CONFIG_JSON, json.encodeToString(AppConfig.serializer(), config))
            .putInt(PREF_KEY_LOG_LEVEL, config.logLevel)
            // Remove settings from older builds; these features no longer exist.
            .remove("super_island_mode")
            .remove("island_duration_seconds")
            .remove("popup_on_connect")
            .remove("suppress_popup_on_connect_when_foreground")
            .remove("startup_tab")
            .remove("island_mode")
            .remove("island_show_timings")
            .putInt(PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE, config.adaptiveCapabilityOverride)
            .putInt(PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE, config.spatialAudioCapabilityOverride)
            .putInt(PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE, config.spatialSoundSwitchCapabilityOverride)
            .putInt(PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE, config.ancImplementationCapabilityOverride)
            .remove("anc_cycle_modes")
            .commit()
    }

    private fun readConfig(prefs: SharedPreferences, source: String): AppConfig {
        val directLogLevel = prefs.getInt(PREF_KEY_LOG_LEVEL, Int.MIN_VALUE)
        val directAdaptiveCapabilityOverride = prefs.getInt(PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directSpatialAudioCapabilityOverride = prefs.getInt(PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directSpatialSoundSwitchCapabilityOverride = prefs.getInt(PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directAncImplementationCapabilityOverride = prefs.getInt(PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val raw = prefs.getString(PREF_KEY_CONFIG_JSON, null)
        logPrefsSnapshot(source, prefs, raw)
        val config = raw?.let {
            runCatching { json.decodeFromString(AppConfig.serializer(), it) }.getOrNull()
        } ?: AppConfig()
        return config.copy(
            logLevel = directLogLevel.takeIf { it != Int.MIN_VALUE } ?: config.logLevel,
            adaptiveCapabilityOverride = directAdaptiveCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.adaptiveCapabilityOverride,
            spatialAudioCapabilityOverride = directSpatialAudioCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialAudioCapabilityOverride,
            spatialSoundSwitchCapabilityOverride = directSpatialSoundSwitchCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialSoundSwitchCapabilityOverride,
            ancImplementationCapabilityOverride = directAncImplementationCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.ancImplementationCapabilityOverride,
        ).normalized()
    }

    private fun AppConfig.normalized(): AppConfig = copy(
        logLevel = logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG),
        adaptiveCapabilityOverride = adaptiveCapabilityOverride.normalizedCapabilityOverride(),
        spatialAudioCapabilityOverride = spatialAudioCapabilityOverride.normalizedCapabilityOverride(),
        spatialSoundSwitchCapabilityOverride = spatialSoundSwitchCapabilityOverride.normalizedCapabilityOverride(),
        ancImplementationCapabilityOverride = ancImplementationCapabilityOverride.normalizedCapabilityOverride(),
    )

    private fun Int.normalizedCapabilityOverride(): Int = coerceIn(CAPABILITY_OVERRIDE_AUTO, CAPABILITY_OVERRIDE_FORCE_DISABLED)

    private fun logConfigChange(source: String, oldConfig: AppConfig, newConfig: AppConfig) {
        val changes = changedFields(oldConfig, newConfig)
        if (changes.isEmpty()) {
            Log.d(TAG, "$source config unchanged: $newConfig")
        } else {
            Log.d(TAG, "$source config changed: ${changes.joinToString()}")
        }
    }

    private fun logPrefsSnapshot(source: String, prefs: SharedPreferences, rawConfig: String?) {
        val all = runCatching { prefs.all }.getOrElse { error -> mapOf("<getAllError>" to error.message) }
        Log.d(
            TAG,
            "$source prefs snapshot class=${prefs.javaClass.name} keys=${all.keys.sorted()} " +
                "$PREF_KEY_CONFIG_JSON=$rawConfig all=$all"
        )
    }

    private fun changedFields(oldConfig: AppConfig, newConfig: AppConfig): List<String> {
        return buildList {
            if (oldConfig.logLevel != newConfig.logLevel) {
                add("logLevel=${oldConfig.logLevel}->${newConfig.logLevel}")
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
        }
    }
}
