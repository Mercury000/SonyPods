package dev.sonypods.bridge

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.sonypods.SonyPodsApp
import dev.sonypods.config.CloudModelInfoNetwork
import dev.sonypods.config.CloudModelInfoStore
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
        // A user-picked BOX image owns the slot; the automatic catalog must not replace it.
        if (existing?.boxManual == true) {
            failedKeys.remove(key)
            onComplete()
            return
        }
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
                val bytes = downloadImage(url)
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "model image download failed url=$url")
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

    private fun downloadImage(url: String): ByteArray? = runCatching {
        URL(url).openConnection().apply {
            connectTimeout = DOWNLOAD_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
        }.getInputStream().use { it.readBytes() }
    }.getOrNull()

    /**
     * On-demand "sync cloud box image" for the custom-image dialog: re-downloads the
     * device's catalog image and applies it, clearing any manual override. Unlike the
     * connect-time auto flow this always downloads — the user asked for it, and it also
     * doubles as a manual retry when the automatic download failed (e.g. offline at
     * connect): the auto-failure keys are bypassed and the currently announced URL is
     * used even when no catalog image was ever cached. When no URL is known the local
     * cloud catalog is absent or stale, so a fresh one is pulled before concluding that
     * the model has no cloud image. Blocking; call from [Dispatchers.IO].
     */
    fun syncBoxImageBlocking(
        context: Context,
        address: String,
        urlHint: String? = null,
    ): BoxSyncResult {
        if (!PodImagePrefs.isStoreAttached()) return BoxSyncResult.Unavailable
        if (SonyPodsApp.xposedService == null) return BoxSyncResult.Unavailable
        if (address.isBlank()) return BoxSyncResult.NoUrl
        val existing = PodImagePrefs.findCurrent(address)
        val appContext = context.applicationContext ?: context
        // Prefer the URL the engine is announcing right now (covers a download that
        // failed before any catalog image was cached, and refreshes a stale one), then
        // fall back to the last URL we actually applied. With neither known, pull a
        // fresh catalog and let the engine (which owns model + colour) re-resolve before
        // declaring that the model has no cloud image.
        var url = urlHint?.takeIf { it.isNotBlank() }
            ?: existing?.autoImageUrl?.takeIf { it.isNotBlank() }
        if (url.isNullOrBlank()) {
            url = fetchCatalogAndResolve(appContext, address)
        }
        if (url.isNullOrBlank()) return BoxSyncResult.NoUrl
        val bytes = downloadImage(url)
        if (bytes == null || bytes.isEmpty()) return BoxSyncResult.Failed
        val stored = runCatching {
            PodImagePrefs.applyCloudBox(
                context = appContext,
                service = SonyPodsApp.xposedService,
                address = address,
                name = existing?.name.orEmpty(),
                url = url,
                bytes = bytes,
            )
        }.getOrNull()
        if (stored == null) return BoxSyncResult.Failed
        Log.d(TAG, "cloud box image synced address=$address bytes=${bytes.size}")
        return BoxSyncResult.Ok
    }

    /**
     * Download the latest cloud catalog, publish it, then ask the engine to re-resolve
     * the connected device's image URL (only it knows model + colour) and wait for the
     * refreshed URL on the mirrored state. Returns null when the catalog is unavailable
     * or the model still has no entry in it.
     */
    private fun fetchCatalogAndResolve(context: Context, address: String): String? {
        val service = SonyPodsApp.xposedService ?: return null
        val fetched = runCatching { CloudModelInfoNetwork.fetchCatalog() }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val prefs = CloudModelInfoStore.preferences(context)
        val raw = runCatching { CloudModelInfoStore.saveCachedJson(prefs, fetched) }.getOrNull()
            ?: return null
        CloudModelInfoStore.publishJson(raw, service)
        SonyBridge.sendCommand(context, SonyBridge.CMD_CLOUD_MODEL_INFO_READY)
        return awaitResolvedImageUrl(address)
    }

    /** Bounded wait for the engine to publish a resolved model image URL for [address]. */
    private fun awaitResolvedImageUrl(address: String): String? {
        val target = address.trim().uppercase()
        val deadline = SystemClock.elapsedRealtime() + RESOLVE_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = SonyRemoteState.state.value
            val url = snapshot.modelImageUrl?.takeIf { it.isNotBlank() }
            if (url != null && snapshot.deviceAddress?.equals(target, ignoreCase = true) == true) {
                return url
            }
            Thread.sleep(RESOLVE_POLL_MS)
        }
        return null
    }

    private const val DOWNLOAD_TIMEOUT_MS = 15_000
    private const val RESOLVE_WAIT_MS = 5_000L
    private const val RESOLVE_POLL_MS = 120L
}

/**
 * Outcome of an explicit in-dialog "sync from cloud" / "restore from cloud" request.
 * Restore is download-only (no offline copy), so any failure leaves the current
 * manual override untouched.
 */
sealed interface BoxSyncResult {
    /** Cloud image downloaded and applied; manual override cleared. */
    data object Ok : BoxSyncResult
    /** No cloud URL for this device — nothing to restore. */
    data object NoUrl : BoxSyncResult
    /** Download or persist failed; the manual image is kept. */
    data object Failed : BoxSyncResult
    /** The framework-backed store is not attached yet; retry once the service binds. */
    data object Unavailable : BoxSyncResult
}
