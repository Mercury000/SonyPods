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
 * Downloads the cloud model image and stores it where [dev.sonypods.config.PodImageProvider]
 * can serve it to the system notification and focus island.
 *
 * This stays in the app process: the image lives in our private files dir, which the
 * bluetooth process cannot write to. The engine only reports the URL.
 */
object ModelImageSync {
    private const val TAG = "SonyPods-ImageSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastHandledKey: String? = null

    fun onState(context: Context, snapshot: SonyStateSnapshot) {
        val address = snapshot.deviceAddress ?: return
        val url = snapshot.modelImageUrl ?: return
        val key = "$address|$url"
        if (key == lastHandledKey) return

        val appContext = context.applicationContext ?: context
        val prefs = appContext.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = PodImagePrefs.find(prefs, address)
        val userProvided = existing?.boxImagePath != null && existing.autoImageUrl == null
        val upToDate = existing?.autoImageUrl == url &&
            existing.boxImagePath?.let { File(it).exists() } == true
        if (userProvided || upToDate) {
            lastHandledKey = key
            return
        }
        lastHandledKey = key

        scope.launch {
            val bytes = runCatching { URL(url).openStream().use { it.readBytes() } }
                .onFailure { Log.w(TAG, "model image download failed url=$url", it) }
                .getOrNull() ?: return@launch
            if (bytes.isEmpty()) return@launch
            runCatching {
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
            }.onFailure { Log.w(TAG, "model image store failed", it) }
        }
    }
}
