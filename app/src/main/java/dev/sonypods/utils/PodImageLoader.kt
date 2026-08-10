package dev.sonypods.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mercury.sonypods.R
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.PodImageResource
import java.io.File

object PodImageLoader {
    private const val MODULE_PACKAGE = "com.mercury.sonypods"

    /**
     * Hook-only image reader backed by libxposed Remote Files. Set by [dev.sonypods.hook.HookEntry]
     * in each hooked process; null in the module app process (which reads images via the
     * PodImageProvider ContentProvider / local files). When non-null, [loadCached] reads the
     * image via `openRemoteFile` first — this is what makes the notification (com.xiaomi.bluetooth)
     * and the island (com.android.bluetooth) show the catalog image instead of the stock fallback,
     * without depending on a cross-process ContentProvider query.
     */
    @Volatile
    var remoteImageReader: ((fileName: String) -> Bitmap?)? = null

    fun loadBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        loadCached(context, prefs, address, listOf(resource))?.let { return it }
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
        loadCached(context, prefs, address, listOf(resource, fallbackResource))?.let { return it }
        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    /**
     * Resolve a cached catalog image for [address], trying each [resources] in order.
     * In a hooked process the Remote Files reader is tried first (cold-safe).
     * In the module app process (or on a remote-file miss) it falls back to the local file
     * directly. Returns null if no cached catalog image is available.
     */
    private fun loadCached(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resources: List<PodImageResource>,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.find(prefs, address) }.getOrNull() ?: return null
        val reader = remoteImageReader
        for (res in resources) {
            val path = earphone.imagePath(res) ?: continue
            val file = File(path)
            if (reader != null) {
                runCatching { reader(file.name) }.getOrNull()?.let { return it }
            }
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()?.let { return it }
        }
        return null
    }

    fun loadBoxBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmap(context, prefs, address, PodImageResource.BOX, R.drawable.img_box)
    }

}
