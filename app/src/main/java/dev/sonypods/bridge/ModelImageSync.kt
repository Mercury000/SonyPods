package dev.sonypods.bridge

import android.content.Context
import android.util.Log
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.PodImageResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads the cloud model image for the module detail page and stores it in the
 * application-owned image cache used by the detail page.
 *
 * This stays in the app process: the image lives in our private files dir, which the
 * bluetooth process cannot write to. The engine only reports the URL.
 */
object ModelImageSync {
    private const val TAG = "SonyPods-ImageSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val failedAtMs = ConcurrentHashMap<String, Long>()
    private val connectionLock = Any()
    private var connectionActive = false
    private var activeAddress: String? = null

    fun onState(context: Context, snapshot: SonyStateSnapshot) {
        updateConnection(snapshot)
        if (!snapshot.connected) {
            return
        }
        val address = snapshot.deviceAddress ?: run {
            return
        }
        val url = snapshot.modelImageUrl ?: run {
            return
        }
        val key = "$address|$url"

        val failedAt = failedAtMs[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_DELAY_MS) {
            return
        }
        if (!inFlightKeys.add(key)) {
            return
        }

        val appContext = context.applicationContext ?: context
        scope.launch {
            try {
                // SharedPreferences JSON parsing and file checks are kept off
                // the state collector's main thread. State updates can be very
                // frequent while the headset is probing.
                val prefs = appContext.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
                // A device address owns its cached artwork. Once a valid image
                // exists, keep using it permanently and do not replace it just
                // because the catalog URL or a later state snapshot changed.
                // This also prevents a transient/incorrect colour resolution
                // from overwriting the known-good cached image.
                val hasCachedImage = PodImagePrefs.hasCachedBoxImage(prefs, address)
                if (hasCachedImage) {
                    failedAtMs.remove(key)
                    return@launch
                }

                val bytes = runCatching {
                    val connection = URL(url).openConnection() as? HttpURLConnection
                        ?: throw IOException("model image URL is not HTTP: $url")
                    try {
                        connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
                        connection.readTimeout = DOWNLOAD_TIMEOUT_MS
                        connection.instanceFollowRedirects = true
                        val status = connection.responseCode
                        check(status in 200..299) { "model image HTTP $status" }
                        connection.inputStream.use { it.readBytes() }
                    } finally {
                        connection.disconnect()
                    }
                }
                    .onFailure { Log.w(TAG, "model image download failed url=$url", it) }
                    .getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    failedAtMs[key] = System.currentTimeMillis()
                    return@launch
                }

                val stored = runCatching {
                    PodImagePrefs.saveImageBytes(
                        context = appContext,
                        prefs = prefs,
                        address = address,
                        name = snapshot.deviceName.orEmpty(),
                        images = mapOf(PodImageResource.BOX to bytes),
                        autoImageUrl = url,
                    )
                    Log.d(TAG, "model image stored address=$address bytes=${bytes.size}")
                    true
                }.onFailure { Log.w(TAG, "model image store failed", it) }.getOrDefault(false)

                if (stored) {
                    failedAtMs.remove(key)
                    Log.d(TAG, "application image cache ready address=$address")
                    // The download finishes after the connection snapshot that
                    // opened the detail page. Re-publish that snapshot so the UI
                    // reloads the completed cache even if it was already composed.
                    SonyBridge.sendCommand(appContext, SonyBridge.CMD_REPUBLISH)
                } else {
                    failedAtMs[key] = System.currentTimeMillis()
                }
            } finally {
                inFlightKeys.remove(key)
            }
        }
    }

    private fun updateConnection(snapshot: SonyStateSnapshot) {
        val address = snapshot.deviceAddress?.uppercase()
        synchronized(connectionLock) {
            if (!snapshot.connected) {
                connectionActive = false
                activeAddress = null
                failedAtMs.clear()
                return
            }
            if (!connectionActive || activeAddress != address) {
                failedAtMs.clear()
                connectionActive = true
                activeAddress = address
            }
        }
    }

    private const val DOWNLOAD_TIMEOUT_MS = 15_000
    private const val RETRY_DELAY_MS = 10_000L
}
