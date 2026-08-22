package dev.sonypods.config

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
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
    /** GS capability metadata; the matched slot remains in [multipointGsSlot]. */
    val generalSettingCapability: GeneralSettingCapabilityCache? = null,
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
 * Serialization and persistence of the capability-probe cache.
 *
 * The engine runs in the `com.android.bluetooth` hook process, where
 * `XposedModule.getRemotePreferences(...)` is **read-only**, so durable writes
 * must go through the module app process: the engine broadcasts the encoded
 * map to the app, and the app persists it into the shared remote-prefs store
 * via the writable `XposedService.getRemotePreferences` (buffering if the
 * LSPosed service is not yet bound, flushed from `SonyPodsApp.onServiceBind`).
 * The engine reads the same key back through its read-only remote prefs, which
 * reflects the app-persisted data across a scope restart.
 */
object CapabilityProbeCache {
    const val PREFS_KEY = "capability_probe_cache"

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

    /** Read the whole map from a SharedPreferences store (engine-side, read-only). */
    fun readAll(prefs: SharedPreferences?): Map<String, CapabilityCacheEntry> {
        if (prefs == null) return emptyMap()
        val encoded = runCatching { prefs.getString(PREFS_KEY, null) }.getOrNull()
        return decode(encoded)
    }

    // ── App-process persistence (the only side where the remote-prefs store is writable) ──

    @Volatile
    private var pendingJson: String? = null

    /**
     * Persist the engine-sent cache map into the shared remote-prefs store.
     * Returns true when written; when the LSPosed service is not bound yet the
     * value is buffered and flushed from [flushPending] once it binds.
     */
    fun persistFromApp(encoded: String, service: XposedService?): Boolean {
        if (service != null) {
            val ok = runCatching {
                val remotePrefs = service.getRemotePreferences(ConfigManager.PREFS_NAME)
                remotePrefs.edit().putString(PREFS_KEY, encoded).apply()
                true
            }.getOrElse {
                android.util.Log.w(TAG, "persistFromApp failed", it)
                false
            }
            if (ok) {
                pendingJson = null
                android.util.Log.d(TAG, "persisted capability cache (${encoded.length} bytes) into ${ConfigManager.PREFS_NAME}")
            }
            return ok
        }
        android.util.Log.d(TAG, "persistFromApp buffered (${encoded.length} bytes), XposedService not bound")
        pendingJson = encoded
        return false
    }

    /** Write any cache buffered because the LSPosed service was null. */
    fun flushPending(service: XposedService?): Boolean {
        val encoded = pendingJson ?: return false
        return persistFromApp(encoded, service)
    }

    private const val TAG = "SonyPods-CapabilityCache"
}
