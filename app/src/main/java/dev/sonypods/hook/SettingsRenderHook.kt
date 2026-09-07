package dev.sonypods.hook

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.view.View
import android.widget.ImageView
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.config.PodImageResource
import dev.sonypods.utils.PodImageLoader
import java.lang.ref.WeakReference

/**
 * Replaces the Settings headset page's stock picture with the catalog image belonging
 * to that page's real Sony Bluetooth address. The activity is the animation context, so
 * the gate does not rely on a Xiaomi model id or on whichever headset connected last.
 */
class SettingsRenderHook : HookContext() {
    private val TAG = "SonyPods-Hook"
    private var reloadEpoch = 0L

    private val animClass = "com.android.settings.bluetooth.tws.MiuiHeadsetAnimation"

    override fun onBeforeReload() {
        reloadEpoch += 1L
    }

    override fun onHook() {
        runCatching {
            val m = findMethodByParamCount(animClass, "loadDefaultInternal", 0)
            hookBefore(m) {
                val instance = this.instance ?: return@hookBefore
                val ctx = runCatching {
                    (getObjectField(instance, "mContext") as? WeakReference<*>)?.get() as? Context
                }.getOrNull() ?: return@hookBefore
                val device = runCatching {
                    callMethod(ctx, "getDevice") as? BluetoothDevice
                }.getOrNull() ?: return@hookBefore
                if (!SettingsHeadsetHook.isSonyPod(device)) return@hookBefore
                val address = runCatching { device.address }.getOrNull() ?: return@hookBefore
                val rootView = runCatching {
                    (getObjectField(instance, "mRootView") as? WeakReference<*>)?.get() as? View
                }.getOrNull() ?: return@hookBefore
                val handler = runCatching {
                    (getObjectField(instance, "mHandler") as? WeakReference<*>)?.get() as? Handler
                }.getOrNull()

                val earphone = PodImagePrefs.find(prefsProvider(), address) ?: run {
                    Log.d(TAG, "no image metadata for $address, falling through to stock")
                    return@hookBefore
                }
                if (earphone.autoImageUrl == null && !earphone.boxManual) {
                    Log.d(TAG, "no box image metadata for $address, falling through to stock")
                    return@hookBefore
                }

                val fileName = PodImagePrefs.remoteImageFileName(
                    earphone.address,
                    PodImageResource.BOX,
                )
                val reader = PodImageLoader.remoteImageReader
                val bitmap = reader?.let { runCatching { it(fileName) }.getOrNull() }
                if (bitmap == null) {
                    Log.d(TAG, "remote reader returned null for $fileName, falling through")
                    return@hookBefore
                }

                // Skip the stock method so it never posts setImageResource.
                result = null

                val drawable = BitmapDrawable(ctx.resources, bitmap)
                val ticId = rootView.findViewById<ImageView>(
                    ctx.resources.getIdentifier("tic", "id", "com.android.settings")
                )
                if (ticId == null) {
                    Log.d(TAG, "tic ImageView not found")
                    return@hookBefore
                }

                // Post at the same 50ms delay the stock code uses.
                val epoch = reloadEpoch
                val action = Runnable {
                    if (epoch != reloadEpoch) return@Runnable
                    Log.d(TAG, "setting catalog box image")
                    ticId.setImageDrawable(drawable)
                }
                handler?.postDelayed(action, 50L) ?: action.run()
            }
            Log.d(TAG, "loadDefaultInternal hook installed")
        }.onFailure { Log.e(TAG, "Failed to hook loadDefaultInternal", it) }
    }
}
