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
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads the cloud model image for the module detail page and publishes it to Remote File
 * when the service is available. Notification/island rendering is owned by the Hook host and
 * has its own temporary cache path.
 *
 * This stays in the app process: the image lives in our private files dir, which the
 * bluetooth process cannot write to. The engine only reports the URL.
 */
object ModelImageSync {
    private const val TAG = "SonyPods-Cloud"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    private val connectionLock = Any()
    private var connectionActive = false
    private var activeAddress: String? = null

    @Volatile
    private var pendingSnapshot: SonyStateSnapshot? = null

    /**
     * The manifest receiver can deliver a state snapshot while the app process is
     * still waiting for the Xposed service. Metadata is remote-store backed, so
     * attempting a cache lookup before the store is attached would look like a
     * cache miss and download an image that is already present.
     */
    fun onServiceBound(context: Context) {
        val snapshot = pendingSnapshot ?: return
        pendingSnapshot = null
        onState(context, snapshot)
    }

    fun onState(
        context: Context,
        snapshot: SonyStateSnapshot,
        onComplete: () -> Unit = {},
    ) {
        updateConnection(snapshot)
        if (!PodImagePrefs.isStoreAttached()) {
            pendingSnapshot = snapshot
            onComplete()
            return
        }
        if (!snapshot.connected) {
            onComplete()
            return
        }
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
        val existing = PodImagePrefs.findCurrent(address)
        val upToDate = existing?.autoImageUrl == url &&
            existing.boxImagePath?.let { File(it).isFile && File(it).length() > 0L } == true
        if (upToDate) {
            failedKeys.remove(key)
            onComplete()
            return
        }

        if (failedKeys.contains(key) || !inFlightKeys.add(key)) {
            onComplete()
            return
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
                if (bytes == null || bytes.isEmpty()) {
                    failedKeys.add(key)
                    return@launch
                }

                val stored = runCatching {
                    PodImagePrefs.saveImageBytes(
                        context = appContext,
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
                    failedKeys.remove(key)
                    // Surface images are owned by the Hook host. Its temporary
                    // cache downloader is the only side that sends CMD_IMAGE_READY
                    // after it has actually produced a bitmap for notification/island
                    // rendering. The module-side download only updates the detail-page
                    // cache and publishes Remote File; notifying here would make opening
                    // the module re-submit an island that Hook already displayed.
                    Log.d(TAG, "module image cache ready; hook surfaces unchanged address=$address")
                } else {
                    failedKeys.add(key)
                }
            } finally {
                inFlightKeys.remove(key)
                onComplete()
            }
        }
    }

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

    private const val DOWNLOAD_TIMEOUT_MS = 15_000
}
