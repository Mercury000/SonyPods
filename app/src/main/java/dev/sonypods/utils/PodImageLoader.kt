package dev.sonypods.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mercury.sonypods.R
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.PodImageResource

object PodImageLoader {
    private const val MODULE_PACKAGE = "com.mercury.sonypods"

    /**
     * Hook-only image reader backed by libxposed Remote Files. Set by [dev.sonypods.hook.HookEntry]
     * in each hooked process; null in the module app process (which reads images via its own
     * local files). When non-null, [loadCached] reads the image via `openRemoteFile` first —
     * this is what makes the notification (com.xiaomi.bluetooth) and the island
     * (com.android.bluetooth) show the catalog image instead of the stock fallback.
     */
    @Volatile
    var remoteImageReader: ((fileName: String) -> Bitmap?)? = null

    /** Temporary image fallback owned by the current Hook host process. */
    @Volatile
    var temporaryImageReader: ((address: String) -> Bitmap?)? = null

    fun loadBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        loadCached(prefs, address, listOf(resource))?.let { return it }
        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    fun loadBitmapWithFallback(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        loadCached(prefs, address, listOf(resource, fallbackResource))?.let { return it }
        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    /**
     * Resolve a cached catalog image for [address], trying each [resources] in order.
     * In a hooked process the Remote Files reader is tried first (cold-safe).
     * In the module app process it may read the module's local file. In a hooked process a
     * Remote File miss goes only to the host-local temporary cache; it never reads a module
     * private path. Returns null if no cached catalog image is available.
     */
    private fun loadCached(
        prefs: SharedPreferences,
        address: String,
        resources: List<PodImageResource>,
    ): Bitmap? {
        val reader = remoteImageReader
        if (reader != null) {
            for (res in resources) {
                val fileName = PodImagePrefs.remoteImageFileName(address, res)
                runCatching { reader(fileName) }.getOrNull()?.let { return it }
            }
        } else {
            // Only the module process may read its own private image files. A hooked
            // process must never try to decode the absolute path stored in prefs.
            val earphone = runCatching { PodImagePrefs.find(prefs, address) }.getOrNull()
            if (earphone != null) {
                for (res in resources) {
                    val path = earphone.imagePath(res) ?: continue
                    runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let { return it }
                }
            }
        }
        temporaryImageReader?.let { runCatching { it(address) }.getOrNull()?.let { return it } }
        return null
    }

    fun loadBoxBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmap(context, prefs, address, PodImageResource.BOX, R.drawable.img_box)
    }

}
