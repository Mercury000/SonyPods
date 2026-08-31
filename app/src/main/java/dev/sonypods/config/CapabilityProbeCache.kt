package dev.sonypods.config

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One persisted capability-probe result, keyed by device address. Mirrors Sound
 * Connect's `exchanged_capabilities` row (identifier/store_group/command_table
 * number/capability_counter/capabilities): the [counter] returned by
 * CONNECT_RET_CAPABILITY_INFO (0x03) gates whether the per-domain capability
 * probe may be omitted on reconnection, and the ordered [functions] list is
 * enough to re-derive the probe-derived profile via
 * `SonyCapabilityProbe.applyToProfile` without re-running the probe.
 */
@Serializable
data class CapabilityCacheEntry(
    val counter: Int,
    /** Bumped whenever the probe's completeness changes (e.g. a new table is
     * queried). Entries written by an older probe lack the field and decode to 0,
     * so they fail the current-version check and trigger one fresh probe. */
    val version: Int = 0,
    val identifier: String = "",
    /** Protocol variant name, e.g. "V1_TABLE_1" / "V2_TABLE_1" (informational). */
    val variant: String = "",
    /** Transport the probe ran on, e.g. "GATT_MC" / "SPP" (informational). */
    val transport: String = "",
    /** Ordered FunctionType (code, order) list from RET_SUPPORT_FUNCTION. */
    val functions: List<FunctionCode> = emptyList(),
    /** PLAY_RET_CAPABILITY volume step count, mirroring the official capability
     * DB: -1 = never learned, 0 = device has no volume control, >0 = step count.
     * Restored on a cache hit so the volume row appears without waiting for the
     * capability round-trip. */
    val playVolumeStep: Int = -1,
    /** Safe Listening minimum poll interval (seconds) from SL_RET_CAPABILITY;
     * SC refreshes the current-sound-pressure readout every `1000 * it`.
     * Null until the SL capability is probed. */
    val safeListeningMinimumInterval: Int? = null,
    /** GS slot for the "同时连接2台设备" setting; -1 = not discovered yet. */
    val multipointGsSlot: Int = -1,
    /** Last device-confirmed value of the GS multipoint setting. */
    val multipointEnabled: Boolean? = null,
    /** Raw values returned by typed/generic capability responses, keyed by domain/type. */
    val capabilityValues: List<CapabilityValueCache> = emptyList(),
    /** EQ extended-info geometry, including the raw band information type/value pairs. */
    val eqBandInfo: List<EqBandInfoCache> = emptyList(),
    /** The preset IDs and Clear Bass support used to build the EQ UI. */
    val eqAvailablePresetCodes: List<Int> = emptyList(),
    val eqHasClearBass: Boolean? = null,
    /** PLAY capability bits not derivable from RET_SUPPORT_FUNCTION. */
    val playbackSupportsButtons: Boolean? = null,
    val playbackSupportsMetadata: Boolean? = null,
    /** Quick Access capability grammar used by the editor. */
    val quickAccessCapability: QuickAccessCapabilityCache? = null,
    /** ASSIGNABLE_SETTINGS capability grammar used by the gesture editor. */
    val gestureCapabilities: List<GestureKeyCapabilityCache> = emptyList(),
    /** PERIPHERAL capability values used by the multipoint page. */
    val multipointTypeCode: Int? = null,
    val maxPairedDevices: Int = 0,
    val maxConnectedDevices: Int = 0,
    val supportsFileTransfer: Boolean? = null,
    /** AUDIO-domain DSEE/upscaling: the inquired type the support list chose
     * (0x01 / 0x0B) and the UpscalingType generation byte from
     * AUDIO_RET_CAPABILITY (`cf0.e0`) that titles the row. Neither is derivable
     * from the function list alone, so both persist with the entry. */
    val upscalingInquiredTypeCode: Int? = null,
    val upscalingTypeCode: Int? = null,
    /** GS capability metadata; the matched slot remains in [multipointGsSlot]. */
    val generalSettingCapability: GeneralSettingCapabilityCache? = null,
    /** V1 NCASM_RET_CAPABILITY's NcAsmSettingType was DUAL_SINGLE_OFF, i.e. the
     * device has the single-mic (wind-noise) NC option. Not derivable from the
     * function list, so it persists for cache-hit reconnects. */
    val supportsV1WindNoise: Boolean? = null,
    val savedAtMs: Long = 0L,
)

@Serializable
data class FunctionCode(
    val code: Int,
    val order: Int,
    /** Empty keeps old cache entries readable. */
    val table: String = "",
)

@Serializable
data class CapabilityValueCache(
    val domain: String,
    val inquiredTypeCode: Int? = null,
    val values: List<Int> = emptyList(),
)

@Serializable
data class EqBandInfoCache(
    val typeCode: Int,
    val value: Int,
)

@Serializable
data class QuickAccessActionCapabilityCache(
    val actionCode: Int,
    val defaultFunctionCode: Int,
    val availableFunctionCodes: List<Int> = emptyList(),
)

@Serializable
data class QuickAccessCapabilityCache(
    val keyCode: Int,
    val typeCode: Int,
    val actions: List<QuickAccessActionCapabilityCache> = emptyList(),
)

@Serializable
data class GestureActionCapabilityCache(
    val actionCode: Int,
    val defaultFunctionCode: Int,
    val availableFunctionCodes: List<Int> = emptyList(),
)

@Serializable
data class GesturePresetCapabilityCache(
    val presetCode: Int,
    val actions: List<GestureActionCapabilityCache> = emptyList(),
)

@Serializable
data class GestureKeyCapabilityCache(
    val keyCode: Int,
    val typeCode: Int,
    val defaultPresetCode: Int,
    val presets: List<Int> = emptyList(),
    val actionsByPreset: List<GesturePresetCapabilityCache> = emptyList(),
)

@Serializable
data class GeneralSettingCapabilityCache(
    val settingType: Int? = null,
    val stringFormat: Int? = null,
    val title: String = "",
    val description: String = "",
)

/**
 * Serialization of the capability-probe cache.
 *
 * The engine (com.android.bluetooth process) is this cache's only consumer, so it
 * persists into the host process's OWN data directory as a plain JSON file — no
 * module-app detour and no broadcast. [dev.sonypods.hook.SonyEngineHost] wires a
 * writer over [encode] and restores at startup through [decode]; a plain file in
 * the hooked app's filesDir survives scope restarts just like the former shared
 * remote-prefs round trip did.
 */
object CapabilityProbeCache {
    const val PREFS_KEY = "capability_probe_cache"

    /** Current capability-cache schema. Bump when a probe change makes older
     * entries incomplete (see [CapabilityCacheEntry.version]); stale entries are
     * treated as cache misses so a fresh probe rebuilds them once. */
    const val CURRENT_CACHE_VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(entries: Map<String, CapabilityCacheEntry>): String =
        json.encodeToString(entries)

    fun decode(encoded: String?): Map<String, CapabilityCacheEntry> {
        if (encoded.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, CapabilityCacheEntry>>(encoded)
        }.getOrDefault(emptyMap())
    }

    /** Read the whole map from a SharedPreferences store (legacy remote-store reads). */
    fun readAll(prefs: SharedPreferences?): Map<String, CapabilityCacheEntry> {
        if (prefs == null) return emptyMap()
        val encoded = runCatching { prefs.getString(PREFS_KEY, null) }.getOrNull()
        return decode(encoded)
    }
}
