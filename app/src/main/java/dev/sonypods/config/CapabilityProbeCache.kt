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
    val savedAtMs: Long = 0L,
)

@Serializable
data class FunctionCode(val code: Int, val order: Int)

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
