package dev.sonypods.config

import android.content.Context
import android.util.Log
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.data.SonyModelImageCatalog
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Fetches artwork metadata into app-local storage when a connected model is unknown. */
object CloudModelInfoSync {
    private const val TAG = "SonyPods-CloudModelInfo"
    private const val RETRY_DELAY_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastFetchAt = ConcurrentHashMap<String, Long>()

    fun onState(context: Context, snapshot: SonyStateSnapshot) {
        if (!snapshot.connected) return
        val address = snapshot.deviceAddress?.takeIf { it.isNotBlank() } ?: return
        val model = normalize(snapshot.deviceName) ?: return
        val colorKey = snapshot.modelColorCode?.toString()
            ?: normalizeColor(snapshot.modelColor)
        val key = "$model|$colorKey"
        val now = System.currentTimeMillis()
        if (lastFetchAt[key]?.let { now - it < RETRY_DELAY_MS } == true) return
        if (!inFlight.add(key)) return

        val appContext = context.applicationContext ?: context
        scope.launch {
            try {
                val settingsPrefs = appContext.getSharedPreferences(
                    ConfigManager.PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
                // A cached device image is authoritative. Do not download the
                // model-info catalog merely because a later state has a new or
                // incomplete model URL.
                if (PodImagePrefs.hasCachedBoxImage(settingsPrefs, address)) {
                    return@launch
                }

                // A known image URL means the current model/colour already
                // matched the local catalog. No network refresh is necessary.
                if (snapshot.modelImageUrl != null) return@launch

                val prefs = CloudModelInfoStore.preferences(appContext)
                val raw = CloudModelInfoStore.readCachedJson(prefs)
                val catalog = SonyModelImageCatalog { raw }
                val catalogLoaded = catalog.refresh()
                if (catalogLoaded && catalog.resolve(
                        modelName = snapshot.deviceName,
                        modelColor = snapshot.modelColor,
                        colorCode = snapshot.modelColorCode,
                    ) != null
                ) {
                    // The repository may not have loaded this already-cached
                    // catalog yet. Ask it to resolve locally; this is not a
                    // network operation.
                    SonyBridge.sendCommand(appContext, SonyBridge.CMD_CLOUD_MODEL_INFO_READY)
                    return@launch
                }

                // A model entry with an unknown colour is not a miss. Wait for
                // the protocol's colour response instead of downloading the
                // entire catalog and risking a guessed image.
                val hasKnownColor = snapshot.modelColorCode != null ||
                    normalizeColor(snapshot.modelColor) != DEFAULT_COLOR
                if (catalogLoaded && !hasKnownColor && raw?.let { catalogContainsModel(it, model) } == true) {
                    return@launch
                }

                val fetched = runCatching { CloudModelInfoNetwork.fetchCatalog() }
                    .onFailure { Log.w(TAG, "model catalog fetch failed", it) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@launch
                lastFetchAt[key] = System.currentTimeMillis()
                CloudModelInfoStore.saveCached(prefs, fetched)
                SonyBridge.sendCommand(appContext, SonyBridge.CMD_CLOUD_MODEL_INFO_READY)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun normalize(value: String?): String? = value
        ?.removePrefix("LE_")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()

    private fun normalizeColor(value: String?): String = value
        ?.substringAfter("/", value)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.lowercase(Locale.ROOT)
        ?: DEFAULT_COLOR

    private fun catalogContainsModel(raw: String, model: String): Boolean =
        CloudModelInfoStore.parseRecords(raw).any { normalize(it.modelName) == model }

    private const val DEFAULT_COLOR = "default"
}
