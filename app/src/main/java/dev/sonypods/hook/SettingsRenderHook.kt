package dev.sonypods.hook

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.view.View
import android.widget.ImageView
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.EarphonePref
import dev.sonypods.config.PodImageResource
import dev.sonypods.utils.PodImageLoader
import dev.sonypods.hook.symbols.ResolvedSymbolBundle
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Replaces the Settings headset page's stock picture with the catalog image belonging
 * to that page's real Sony Bluetooth address. The activity is the animation context, so
 * the gate does not rely on a Xiaomi model id or on whichever headset connected last.
 */
class SettingsRenderHook : HookContext() {
    private val TAG = "SonyPods-Hook"
    private var reloadEpoch = 0L
    @Volatile private var imageExecutor: ExecutorService = newImageExecutor()
    @Volatile private var cachedMetadata: List<EarphonePref>? = null
    @Volatile private var metadataLoading = false
    private val bitmapCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val imageLoads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private lateinit var renderSymbols: ResolvedSymbolBundle

    override fun onBeforeReload() {
        reloadEpoch += 1L
        imageExecutor.shutdownNow()
    }

    override fun onReloadRejected(snapshot: dev.sonypods.bridge.SonyStateSnapshot) {
        ensureImageExecutor()
        loadMetadataInBackground()
    }

    private fun newImageExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "SonyPods-SettingsImage").apply { isDaemon = true }
        }

    @Synchronized
    private fun ensureImageExecutor(): ExecutorService {
        if (imageExecutor.isShutdown) imageExecutor = newImageExecutor()
        return imageExecutor
    }


    private fun cachedEarphone(address: String): EarphonePref? {
        val canonical = dev.sonypods.device.SonyDeviceService.resolveControlAddress(address) ?: return null
        return cachedMetadata?.firstOrNull { it.address.equals(canonical, ignoreCase = true) }
    }

    private fun loadMetadataInBackground() {
        if (cachedMetadata != null || metadataLoading) return
        synchronized(this) {
            if (cachedMetadata != null || metadataLoading) return
            metadataLoading = true
        }
        val task = Runnable {
            try {
                val metadata = runCatching { PodImagePrefs.load(prefsProvider()) }.getOrDefault(emptyList())
                cachedMetadata = metadata
                metadata.forEach { earphone ->
                    if (earphone.autoImageUrl != null || earphone.boxManual) preloadDrawable(earphone)
                }
            } finally {
                metadataLoading = false
            }
        }
        executeImageTask(task)
    }

    private fun preloadDrawable(earphone: EarphonePref) {
        val fileName = PodImagePrefs.remoteImageFileName(earphone.address, PodImageResource.BOX)
        if (bitmapCache.containsKey(fileName) || !imageLoads.add(fileName)) return
        // Capture the current generation's reader; a queued old-generation task must not
        // resolve the replacement generation's static reader after hot reload.
        val reader = PodImageLoader.remoteImageReader
        executeImageTask(Runnable {
            try {
                val bitmap = reader?.let { runCatching { it(fileName) }.getOrNull() }
                if (bitmap != null) bitmapCache[fileName] = bitmap
            } finally {
                imageLoads.remove(fileName)
            }
        })
    }

    private fun executeImageTask(task: Runnable) {
        try {
            ensureImageExecutor().execute(task)
        } catch (_: RejectedExecutionException) {
            ensureImageExecutor().execute(task)
        }
    }

    override fun onHook() {
        runCatching {
            renderSymbols = requireSymbols(SettingsRenderSymbols)
            hookBefore(renderSymbols.method("loadDefaultInternal")) {
                val instance = this.instance ?: return@hookBefore
                val ctx = runCatching {
                    (renderSymbols.field("contextField").get(instance) as? WeakReference<*>)?.get() as? Context
                }.getOrNull() ?: return@hookBefore
                val device = runCatching {
                    requireSymbols(SettingsActivitySymbols).method("getDevice").invoke(ctx) as? BluetoothDevice
                }.getOrNull() ?: return@hookBefore
                if (!SettingsHeadsetHook.isSonyPod(device)) return@hookBefore
                val address = runCatching { device.address }.getOrNull() ?: return@hookBefore
                val rootView = runCatching {
                    (renderSymbols.field("rootViewField").get(instance) as? WeakReference<*>)?.get() as? View
                }.getOrNull() ?: return@hookBefore
                val handler = runCatching {
                    (renderSymbols.field("handlerField").get(instance) as? WeakReference<*>)?.get() as? Handler
                }.getOrNull() ?: return@hookBefore
                val imageView = rootView.findViewById<ImageView>(
                    ctx.resources.getIdentifier("tic", "id", "com.android.settings")
                ) ?: return@hookBefore

                // Metadata is prefetched off the main thread. Suppress Xiaomi's competing
                // drawable only when the cache proves that a replacement exists; this preserves
                // the old no-flash behavior without a RemotePreferences read in the callback.
                val earphone = cachedEarphone(address)
                if (earphone == null || (earphone.autoImageUrl == null && !earphone.boxManual)) {
                    loadMetadataInBackground()
                    return@hookBefore
                }
                val fileName = PodImagePrefs.remoteImageFileName(
                    earphone.address,
                    PodImageResource.BOX,
                )
                val cachedBitmap = bitmapCache[fileName]
                // Never let Xiaomi post a competing stock drawable for a device with catalog
                // metadata. A cache miss continues loading off-thread and applies only the
                // replacement, preserving the original no-stock-flash behavior.
                result = null
                val epoch = reloadEpoch
                if (cachedBitmap != null) {
                    val drawable = BitmapDrawable(ctx.resources, cachedBitmap)
                    handler.postDelayed({
                        if (epoch != reloadEpoch || !imageView.isAttachedToWindow) return@postDelayed
                        Log.d(TAG, "setting cached catalog box image")
                        imageView.setImageDrawable(drawable)
                    }, 50L)
                } else if (imageLoads.add(fileName)) {
                    val reader = PodImageLoader.remoteImageReader
                    executeImageTask(Runnable {
                        try {
                            val bitmap = reader
                                ?.let { runCatching { it(fileName) }.getOrNull() }
                                ?: return@Runnable
                            bitmapCache[fileName] = bitmap
                            val drawable = BitmapDrawable(ctx.resources, bitmap)
                            handler.post {
                                if (epoch != reloadEpoch || !imageView.isAttachedToWindow) return@post
                                Log.d(TAG, "setting loaded catalog box image")
                                imageView.setImageDrawable(drawable)
                            }
                        } finally {
                            imageLoads.remove(fileName)
                        }
                    })
                }
            }
            loadMetadataInBackground()
            Log.d(TAG, "loadDefaultInternal hook installed")
        }.onFailure { Log.e(TAG, "Failed to hook loadDefaultInternal", it) }
    }
}
