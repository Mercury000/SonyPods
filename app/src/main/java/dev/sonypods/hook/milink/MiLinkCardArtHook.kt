package dev.sonypods.hook.milink

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.PodImageResource
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.hook.Log
import dev.sonypods.utils.PodImageLoader
import java.util.Collections
import java.util.WeakHashMap

/**
 * Replaces the generic third-party headset art on the device-interconnect card with
 * the module's catalog product shot — per-card: walks up the [ImageView] hierarchy to
 * find the enclosing BluetoothCardView, reads its [CirculateDeviceInfo] to identify the
 * Bluetooth address, and only replaces art when that specific card's device is Sony.
 */
internal class MiLinkCardArtHook(private val hook: MiLinkServiceHook) {

    private var installed = false
    private var artResourceIds: Set<Int> = emptySet()
    private val artViews = Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>())
    @Volatile
    private var isApplyingArt = false
    @Volatile
    private var cachedBoxBitmap: Pair<String, Bitmap>? = null
    private val cardAddressCache = WeakHashMap<ImageView, String?>()

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
                cardAddressCache.remove(view)
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
            val address = resolveCardSonyAddress(view) ?: return@hookBefore
            this.result = null
            applyCardArt(view, address)
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

    /**
     * Per-card: walk up the view hierarchy to find the enclosing BluetoothCardView,
     * read its CirculateDeviceInfo → circulateServices → deviceId (the BT address),
     * and return the resolved Sony address only if that card's device is Sony.
     */
    private fun resolveCardSonyAddress(view: ImageView): String? {
        cardAddressCache[view]?.let { return it }
        val address = extractBluetoothAddressFromCard(view) ?: return null
        if (!SonyDeviceService.isKnownSonyAddress(address)) return null
        val resolved = SonyDeviceService.resolveControlAddress(address) ?: address
        cardAddressCache[view] = resolved
        return resolved
    }

    private fun replaceCardArtIfSony(view: ImageView) {
        val address = resolveCardSonyAddress(view) ?: return
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
            Log.d(MiLinkServiceHook.TAG, "milink card art replaced art with catalog image address=$address")
        } catch (t: Throwable) {
            Log.d(MiLinkServiceHook.TAG, "milink card art setImageDrawable failed", t)
        } finally {
            isApplyingArt = false
        }
    }

    /**
     * Walk up from [view] to find the enclosing BluetoothCardView that holds a
     * CirculateDeviceInfo field, then extract the BT address from its service info.
     */
    private fun extractBluetoothAddressFromCard(view: View): String? {
        var current: View? = view.parent as? View
        while (current != null) {
            val deviceInfo = findCirculateDeviceInfo(current)
            if (deviceInfo != null) {
                return extractBluetoothMac(deviceInfo)
            }
            current = current.parent as? View
        }
        return null
    }

    private fun findCirculateDeviceInfo(view: View): Any? {
        var clazz: Class<*>? = view.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (field.type.name == CIRCULATE_DEVICE_INFO_CLASS) {
                    field.isAccessible = true
                    return field.get(view)
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    /**
     * Extract the Bluetooth MAC address from [deviceInfo] by reading
     * `CirculateServiceInfo.deviceId` from its `circulateServices` set.
     *
     * The circulate headset flow stores the real BT address in `CirculateServiceInfo.deviceId`
     * (set by `HeadsetDeviceManager.convertToBluetoothService` → `headsetInfo.getAddress()`).
     * The `CirculateDeviceInfo.f20363id` is a host-level ID, and `deviceProperties` only
     * contains `MIPLAY_ID` — neither is the BT MAC.
     */
    private fun extractBluetoothMac(deviceInfo: Any): String? {
        return runCatching {
            val servicesField = deviceInfo.javaClass
                .getDeclaredField("circulateServices")
                .apply { isAccessible = true }
            val services = servicesField.get(deviceInfo) as? Set<*> ?: return@runCatching null
            for (svc in services) {
                if (svc == null) continue
                val deviceIdField = svc.javaClass
                    .getDeclaredField("deviceId")
                    .apply { isAccessible = true }
                val deviceId = deviceIdField.get(svc) as? String
                if (!deviceId.isNullOrBlank()) return@runCatching deviceId
            }
            null
        }.getOrNull()
    }

    private companion object {
        val RESOURCE_CLASSES = listOf(
            "com.miui.circulate.device.service.R\$drawable",
            "com.miui.circulate.world.R\$drawable",
        )
        private const val CIRCULATE_DEVICE_INFO_CLASS =
            "com.miui.circulate.api.service.CirculateDeviceInfo"
    }
}
