package dev.sonypods.config

import android.content.Context
import android.util.Log
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyStateSnapshot
import io.github.libxposed.service.XposedService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Publishes the cloud model catalog from the module process.
 *
 * There is intentionally no daily full-catalog refresh. A request is made only when
 * the currently used model (or its image URL) is absent from the local cache. Hooked
 * processes may have already fetched a temporary fallback; this app process still
 * performs its own authoritative fetch and publishes the result to Remote Files.
 */
object CloudModelInfoSync {
    private const val TAG = "SonyPods-Cloud"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    private val connectionLock = Any()
    private var connectionActive = false
    private var activeAddress: String? = null

    @Volatile
    private var service: XposedService? = null

    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    fun onServiceBound(context: Context, xposedService: XposedService) {
        val app = context.applicationContext ?: context
        service = xposedService
        scope.launch {
            publishCached(app, CloudModelInfoStore.preferences(app))
        }
    }

    fun onServiceDied(xposedService: XposedService) {
        if (service === xposedService) service = null
    }

    fun onState(context: Context, snapshot: SonyStateSnapshot) {
        updateConnection(snapshot)
        ensureForDevice(context, snapshot.deviceName, snapshot.modelImageUrl)
    }

    /**
     * Ensure the current device is represented in the module-owned cache. The URL is
     * included when available so a Hook fallback can force refresh after a catalog
     * update without refreshing on every ordinary battery state.
     */
    fun ensureForDevice(context: Context, modelName: String?, imageUrl: String?) {
        if (!hasActiveConnection()) return
        val normalizedModel = normalizeModelName(modelName) ?: return
        val app = context.applicationContext ?: context
        val key = "$normalizedModel|${imageUrl.orEmpty()}"
        if (failedKeys.contains(key) || !inFlightKeys.add(key)) return
        scope.launch {
            try {
                ensureForDeviceNow(app, normalizedModel, imageUrl, key)
            } finally {
                inFlightKeys.remove(key)
            }
        }
    }

    private suspend fun ensureForDeviceNow(
        context: Context,
        normalizedModel: String,
        imageUrl: String?,
        attemptKey: String,
    ) {
        refreshMutex.withLock {
            val prefs = CloudModelInfoStore.preferences(context)
            val cached = CloudModelInfoStore.readCachedJson(prefs)
            var records = CloudModelInfoStore.parseRecords(cached)
            val present = records.any { record ->
                normalizeModelName(record.modelName) == normalizedModel &&
                    (imageUrl.isNullOrBlank() || record.imageUrl == imageUrl)
            }
            if (present) {
                failedKeys.remove(attemptKey)
                publishCached(context, prefs)
                return
            }

            // The Hook and app processes share Remote Files, but not ordinary
            // SharedPreferences. Adopt an already-published catalog before using
            // the network, otherwise a valid Hook-side cache looks absent here.
            val remoteRaw = CloudModelInfoStore.readRemoteJson(service)
            records = CloudModelInfoStore.parseRecords(remoteRaw)
            val remotePresent = records.any { record ->
                normalizeModelName(record.modelName) == normalizedModel &&
                    (imageUrl.isNullOrBlank() || record.imageUrl == imageUrl)
            }
            if (remotePresent) {
                CloudModelInfoStore.saveCachedJson(prefs, records)
                failedKeys.remove(attemptKey)
                return
            }

            val fetched = runCatching { CloudModelInfoNetwork.fetchCatalog() }
                .onFailure { Log.w(TAG, "on-demand cloud catalog fetch failed key=$attemptKey", it) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: run {
                    failedKeys.add(attemptKey)
                    return
                }
            val matchesCurrentDevice = fetched.any { record ->
                normalizeModelName(record.modelName) == normalizedModel &&
                    (imageUrl.isNullOrBlank() || record.imageUrl == imageUrl)
            }
            val raw = runCatching { CloudModelInfoStore.saveCachedJson(prefs, fetched) }
                .onFailure { Log.w(TAG, "saving on-demand cloud catalog failed", it) }
                .getOrNull()
                ?: run {
                    failedKeys.add(attemptKey)
                    return
                }
            if (matchesCurrentDevice) failedKeys.remove(attemptKey) else failedKeys.add(attemptKey)
            if (CloudModelInfoStore.publishJson(raw, service)) {
                SonyBridge.sendCommand(context, SonyBridge.CMD_CLOUD_MODEL_INFO_READY)
            }
            Log.i(TAG, "on-demand cloud model catalog updated records=${fetched.size} key=$attemptKey")
        }
    }

    private fun publishCached(context: Context, prefs: android.content.SharedPreferences) {
        if (CloudModelInfoStore.publishCached(prefs, service)) {
            SonyBridge.sendCommand(context, SonyBridge.CMD_CLOUD_MODEL_INFO_READY)
        }
    }

    private fun normalizeModelName(value: String?): String? =
        value
            ?.removePrefix("LE_")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()

    private fun updateConnection(snapshot: SonyStateSnapshot) {
        val address = snapshot.deviceAddress?.uppercase()
        synchronized(connectionLock) {
            if (!snapshot.connected) {
                connectionActive = false
                activeAddress = null
                failedKeys.clear()
                return
            }
            if (!connectionActive || activeAddress != address) {
                failedKeys.clear()
                connectionActive = true
                activeAddress = address
            }
        }
    }

    private fun hasActiveConnection(): Boolean = synchronized(connectionLock) { connectionActive }
}
