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
 * Hooks MiuiHeadsetAnimation in com.android.settings so that the Bluetooth settings page
 * (MiuiHeadsetActivity) shows the catalog box image for Sony devices instead of the default
 * headset silhouette.
 *
 * fakeDeviceId 01010607 matches HeadsetIDConstants.isK73WhiteHeadset, so loadDefaultInternal
 * takes the K73 white branch and posts setImageResource(R.drawable.headset_default_k73_white)
 * at 50ms. We hook loadDefaultInternal *before* to skip the stock drawable entirely and post
 * our own image at the same 50ms delay, avoiding any flicker.
 */
class SettingsRenderHook : HookContext() {
    private val TAG = "SonyPods-SettingsRender"

    private val animClass = "com.android.settings.bluetooth.tws.MiuiHeadsetAnimation"

    override fun onHook() {
        runCatching {
            val m = findMethodByParamCount(animClass, "loadDefaultInternal", 0)
            hookBefore(m) {
                val instance = this.instance ?: return@hookBefore
                val device = runCatching {
                    getObjectField(instance, "mDevice") as? BluetoothDevice
                }.getOrNull() ?: runCatching {
                    getObjectField(instance, "mBluetoothDevice") as? BluetoothDevice
                }.getOrNull()
                if (device == null || !SettingsHeadsetHook.isSonyPod(device)) return@hookBefore

                val ctx = runCatching {
                    (getObjectField(instance, "mContext") as? WeakReference<*>)?.get() as? Context
                }.getOrNull() ?: return@hookBefore
                val rootView = runCatching {
                    (getObjectField(instance, "mRootView") as? WeakReference<*>)?.get() as? View
                }.getOrNull() ?: return@hookBefore
                val handler = runCatching {
                    (getObjectField(instance, "mHandler") as? WeakReference<*>)?.get() as? Handler
                }.getOrNull()

                val earphone = PodImagePrefs.load(prefsProvider())
                    .filter { it.autoImageUrl != null }
                    .maxByOrNull { it.lastConnectedAt }
                if (earphone == null) {
                    Log.i(TAG, "no earphone with box image, falling through to stock")
                    return@hookBefore
                }

                val fileName = PodImagePrefs.remoteImageFileName(
                    earphone.address,
                    PodImageResource.BOX,
                )
                val reader = PodImageLoader.remoteImageReader
                val bitmap = reader?.let { runCatching { it(fileName) }.getOrNull() }
                if (bitmap == null) {
                    Log.i(TAG, "remote reader returned null for $fileName, falling through")
                    return@hookBefore
                }

                // Skip the stock method so it never posts setImageResource.
                result = null

                val drawable = BitmapDrawable(ctx.resources, bitmap)
                val ticId = rootView.findViewById<ImageView>(
                    ctx.resources.getIdentifier("tic", "id", "com.android.settings")
                )
                if (ticId == null) {
                    Log.i(TAG, "tic ImageView not found")
                    return@hookBefore
                }

                // Post at the same 50ms delay the stock code uses.
                val action = Runnable {
                    Log.i(TAG, "setting catalog box image")
                    ticId.setImageDrawable(drawable)
                }
                handler?.postDelayed(action, 50L) ?: action.run()
            }
            Log.i(TAG, "loadDefaultInternal hook installed")
        }.onFailure { Log.e(TAG, "Failed to hook loadDefaultInternal", it) }
    }
}
