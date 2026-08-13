package dev.sonypods.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mercury.sonypods.R
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.PodImageResource

object PodImageLoader {
    fun loadBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        loadCached(prefs, address, listOf(resource))?.let { return it }
        return BitmapFactory.decodeResource(context.resources, fallbackResId)
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
        return BitmapFactory.decodeResource(context.resources, fallbackResId)
    }

    /**
     * Resolve a cached catalog image for [address], trying each [resources] in order.
     * Returns null if no cached catalog image is available.
     */
    private fun loadCached(
        prefs: SharedPreferences,
        address: String,
        resources: List<PodImageResource>,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.find(prefs, address) }.getOrNull()
        if (earphone != null) {
            for (res in resources) {
                val path = earphone.imagePath(res) ?: continue
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    fun loadBoxBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmap(context, prefs, address, PodImageResource.BOX, R.drawable.img_box)
    }

}
