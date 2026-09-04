package dev.sonypods.hook.milink

import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.PodImageResource
import dev.sonypods.hook.Log
import dev.sonypods.utils.PodImageLoader

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
 * Every gate failing simply keeps the stock art.
 */
internal class MiLinkCardArtHook(private val hook: MiLinkServiceHook) {

    private var installed = false
    private var artResourceIds: Set<Int> = emptySet()

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
            replaceCardArtIfSony(view, resId)
        }
        Log.d(MiLinkServiceHook.TAG, "milink card art hook installed art=${artResourceIds.size}")
    }

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

    private fun replaceCardArtIfSony(view: ImageView, resId: Int) {
        if (resId !in artResourceIds) return
        val address = targetSonyAddress() ?: return
        val fileName = PodImagePrefs.remoteImageFileName(address, PodImageResource.BOX)
        val reader = PodImageLoader.remoteImageReader ?: return
        val bitmap = runCatching { reader(fileName) }.getOrNull()
        if (bitmap == null) {
            Log.d(MiLinkServiceHook.TAG, "milink card art: no catalog box image for $address, stock kept")
            return
        }
        val ctx = runCatching { view.context }.getOrNull() ?: return
        runCatching {
            view.setImageDrawable(BitmapDrawable(ctx.resources, bitmap))
            Log.d(MiLinkServiceHook.TAG, "milink card art replaced generic headset art with catalog image address=$address")
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "milink card art setImageDrawable failed", it)
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
        if (!current.isNullOrBlank() && hook.isSonyAddress(current)) return current
        return PodImagePrefs.load(hook.prefs)
            .filter { hook.isSonyAddress(it.address) }
            .maxByOrNull { it.lastConnectedAt }
            ?.address
    }

    private companion object {
        /** Both R classes coexist in the APK; a resource may live in either. */
        val RESOURCE_CLASSES = listOf(
            "com.miui.circulate.device.service.R\$drawable",
            "com.miui.circulate.world.R\$drawable",
        )
    }
}
