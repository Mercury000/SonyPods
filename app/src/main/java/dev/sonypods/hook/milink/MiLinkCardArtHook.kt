package dev.sonypods.hook.milink

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.PodImageResource
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.hook.Log
import dev.sonypods.utils.PodImageLoader
import java.util.Collections
import java.util.WeakHashMap

/**
 * Replaces the generic third-party headset art on the device-interconnect "big card" with
 * the module's catalog product shot — same delivery path the notification/island/settings
 * surfaces already use: image bytes come from the libxposed Remote Files reader
 * ([PodImageLoader.remoteImageReader]) and are injected as a BitmapDrawable immediately
 * after the stock `setImageResource(...)` call.
 *
 * Card classes and members around the art are obfuscated, so nothing here is looked up by
 * a method or field name:
 *  - the hook anchor is the framework `ImageView.setImageResource(int)` method (stable);
 *  - "is this the headset art ImageView" is inferred from the resource id alone: these art
 *    drawables are handed out only by HeadsetDeviceUtils, whose sole caller is the
 *    BluetoothCardView bind method, so the id set is never used for list rows or icons;
 *  - the target pod is the latest EarphonePref this process recognises as Sony.
 *
 * In addition, guards against asynchronous or re-binding overwrites (`setImageDrawable`)
 * by tracking identified headset art Views in memory and intercepting subsequent writes.
 */
internal class MiLinkCardArtHook(private val hook: MiLinkServiceHook) {

    private var installed = false
    private var artResourceIds: Set<Int> = emptySet()
    private val artViews = Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>())
    @Volatile
    private var isApplyingArt = false
    @Volatile
    private var cachedBoxBitmap: Pair<String, Bitmap>? = null

    fun hookCardArt() {
        runCatching { install() }
            .onFailure { Log.d(MiLinkServiceHook.TAG, "milink card art hook skipped", it) }
    }

    private fun install() {
        if (installed) return
        installed = true
        artResourceIds = resolveArtResourceIds()
        if (artResourceIds.isEmpty()) {
            Log.d(MiLinkServiceHook.TAG, "milink card art: no headset art resources found, hook idle")
            return
        }
        hook.hookAfter(
            hook.findMethod("android.widget.ImageView", "setImageResource", Int::class.javaPrimitiveType!!),
            logicalRole = "milink-card-art",
        ) {
            val view = instance as? ImageView ?: return@hookAfter
            val resId = args.getOrNull(0) as? Int ?: return@hookAfter
            if (resId in artResourceIds || isArtView(view)) {
                artViews.add(view)
                replaceCardArtIfSony(view)
            }
        }
        hook.hookBefore(
            hook.findMethod("android.widget.ImageView", "setImageDrawable", Drawable::class.java),
            logicalRole = "milink-card-art-drawable",
        ) {
            if (isApplyingArt) return@hookBefore
            val view = instance as? ImageView ?: return@hookBefore
            if (!isArtView(view)) return@hookBefore
            val address = targetSonyAddress() ?: return@hookBefore
            this.result = null
            applyCardArt(view, address)
            Log.d(MiLinkServiceHook.TAG, "milink card art guarded setImageDrawable with catalog image address=$address")
        }
        Log.d(MiLinkServiceHook.TAG, "milink card art hook installed art=${artResourceIds.size}")
    }

    private fun isArtView(view: ImageView): Boolean = artViews.contains(view)

    private fun resolveArtResourceIds(): Set<Int> {
        val names = listOf(
            "circulate_headset_icon",
            "circulate_single_battery_headset_icon",
            "circulate_device_headset_openwear",
            "circulate_airpods_headset_icon",
            "circulate_airpods_headphones_headset_icon",
            "circulate_device_headset_headphones",
            "circulate_headset_icon_clip",
            "circulate_headset_icon_sony",
            "circulate_headset_icon_sony_xf_xm6_b",
            "circulate_headset_icon_sony_xf_xm6_w",
            "circulate_headset_icon_edifier",
            "circulate_device_bt_headset",
        )
        val out = mutableSetOf<Int>()
        RESOURCE_CLASSES.forEach { resClass ->
            val clazz = runCatching { hook.findClass(resClass) }.getOrNull() ?: return@forEach
            names.forEach { field ->
                runCatching {
                    out += clazz.getField(field).getInt(null)
                }.onFailure { /* a field absent from one R class is normal */ }
            }
        }
        return out
    }

    private fun getOrLoadBoxBitmap(address: String): Bitmap? {
        val resolved = SonyDeviceService.resolveControlAddress(address) ?: address
        val cached = cachedBoxBitmap
        if (cached != null && cached.first == resolved && !cached.second.isRecycled) {
            return cached.second
        }
        val fileName = PodImagePrefs.remoteImageFileName(resolved, PodImageResource.BOX)
        val reader = PodImageLoader.remoteImageReader ?: return null
        val bitmap = runCatching { reader(fileName) }.getOrNull()
        if (bitmap != null) {
            cachedBoxBitmap = resolved to bitmap
        }
        return bitmap
    }

    private fun replaceCardArtIfSony(view: ImageView) {
        val address = targetSonyAddress() ?: return
        applyCardArt(view, address)
    }

    private fun applyCardArt(view: ImageView, address: String) {
        val bitmap = getOrLoadBoxBitmap(address)
        if (bitmap == null) {
            Log.d(MiLinkServiceHook.TAG, "milink card art: no catalog box image for $address, stock kept")
            return
        }
        val ctx = runCatching { view.context }.getOrNull() ?: return
        isApplyingArt = true
        try {
            view.setImageDrawable(BitmapDrawable(ctx.resources, bitmap))
            Log.d(MiLinkServiceHook.TAG, "milink card art replaced generic headset art with catalog image address=$address")
        } catch (t: Throwable) {
            Log.d(MiLinkServiceHook.TAG, "milink card art setImageDrawable failed", t)
        } finally {
            isApplyingArt = false
        }
    }

    /**
     * The Sony pod this process currently manages. Prefers the address of the connected
     * headset the module is feeding (mirrors the card the user actually sees); otherwise
     * the most recent EarphonePref that is recognised as Sony here. Unknown/blank falls
     * back to stock, never guessing at a stranger's card.
     */
    private fun targetSonyAddress(): String? {
        val current = hook.currentAddress
        val candidate = if (!current.isNullOrBlank() && hook.isSonyAddress(current)) {
            current
        } else {
            PodImagePrefs.load(hook.prefs)
                .filter { hook.isSonyAddress(it.address) }
                .maxByOrNull { it.lastConnectedAt }
                ?.address
        } ?: return null
        return SonyDeviceService.resolveControlAddress(candidate) ?: candidate
    }

    private companion object {
        /** Both R classes coexist in the APK; a resource may live in either. */
        val RESOURCE_CLASSES = listOf(
            "com.miui.circulate.device.service.R\$drawable",
            "com.miui.circulate.world.R\$drawable",
        )
    }
}
