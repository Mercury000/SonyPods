package dev.sonypods.bridge

import android.content.Context
import android.util.Log
import dev.sonypods.SonyPodsApp
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.PodImageResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

/**
 * Downloads the cloud model image and stores it where the system notification and focus island
 * can serve it through the module's image cache.
 *
 * This stays in the app process: the image lives in our private files dir, which the
 * bluetooth process cannot write to. The engine only reports the URL.
 */
object ModelImageSync {
    private const val TAG = "SonyPods-ImageSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var inFlightKey: String? = null

    fun onState(
        context: Context,
        snapshot: SonyStateSnapshot,
        onComplete: () -> Unit = {},
    ) {
        val address = snapshot.deviceAddress ?: run {
            onComplete()
            return
        }
        val url = snapshot.modelImageUrl ?: run {
            onComplete()
            return
        }
        val key = "$address|$url"

        val appContext = context.applicationContext ?: context
        val prefs = appContext.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = PodImagePrefs.find(prefs, address)
        val upToDate = existing?.autoImageUrl == url &&
            existing.boxImagePath?.let { File(it).isFile && File(it).length() > 0L } == true
        if (upToDate) {
            onComplete()
            return
        }

        synchronized(this) {
            // Do not cache a successful key independently of the file check
            // above: the file can be removed/corrupted while this process stays
            // alive and must then be downloaded again for the same URL.
            if (key == inFlightKey) {
                onComplete()
                return
            }
            inFlightKey = key
        }

        scope.launch {
            try {
                val bytes = runCatching {
                    URL(url).openConnection().apply {
                        connectTimeout = DOWNLOAD_TIMEOUT_MS
                        readTimeout = DOWNLOAD_TIMEOUT_MS
                    }.getInputStream().use { it.readBytes() }
                }
                    .onFailure { Log.w(TAG, "model image download failed url=$url", it) }
                    .getOrNull()
                if (bytes == null || bytes.isEmpty()) return@launch

                val stored = runCatching {
                    PodImagePrefs.saveImageBytes(
                        context = appContext,
                        prefs = prefs,
                        service = SonyPodsApp.xposedService,
                        address = address,
                        name = snapshot.deviceName.orEmpty(),
                        images = mapOf(PodImageResource.BOX to bytes),
                        autoImageUrl = url,
                    )
                    Log.d(TAG, "model image stored address=$address bytes=${bytes.size}")
                    true
                }.onFailure { Log.w(TAG, "model image store failed", it) }.getOrDefault(false)

                if (stored) {
                    // The notification and island embed Bitmap data at render time;
                    // tell the engine to rebuild them after the file is complete.
                    SonyBridge.imageReady(appContext, address)
                }
            } finally {
                synchronized(ModelImageSync) {
                    if (inFlightKey == key) inFlightKey = null
                }
                onComplete()
            }
        }
    }

    private const val DOWNLOAD_TIMEOUT_MS = 15_000
}
