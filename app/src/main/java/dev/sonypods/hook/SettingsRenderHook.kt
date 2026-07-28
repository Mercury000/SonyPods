package dev.sonypods.hook

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.view.View
import android.widget.ImageView
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.utils.PodImageLoader
import java.io.File
import java.lang.ref.WeakReference

/**
 * Hooks MiuiHeadsetAnimation in com.android.settings so that the Bluetooth settings page
 * (MiuiHeadsetActivity) shows the user's box image for Sony devices instead of the default
 * headset silhouette.
 *
 * fakeDeviceId 01010607 matches HeadsetIDConstants.isK73WhiteHeadset, so loadDefaultInternal
 * takes the K73 white branch and calls setImageResource(R.drawable.headset_default_k73_white)
 * on a delayed Handler post — it never reaches the checkLocalCached/getDrawableFromFile path.
 * We hook loadDefaultInternal's after and post our own replacement on the same Handler.
 */
class SettingsRenderHook : HookContext() {
    private val TAG = "SonyPods-SettingsRender"

    private val animClass = "com.android.settings.bluetooth.tws.MiuiHeadsetAnimation"

    override fun onHook() {
        // loadDefaultInternal() -> void: after it schedules the stock drawable, post our
        // replacement to override the ImageView.
        runCatching {
            val m = findMethodByParamCount(animClass, "loadDefaultInternal", 0)
            hookAfter(m) {
                val instance = this.instance ?: return@hookAfter
                val deviceId = runCatching {
                    getObjectField(instance, "mDeviceId") as? String
                }.getOrNull()
                if (deviceId != fakeDeviceId()) return@hookAfter

                val ctx = runCatching {
                    (getObjectField(instance, "mContext") as? WeakReference<*>)?.get() as? Context
                }.getOrNull() ?: return@hookAfter
                val rootView = runCatching {
                    (getObjectField(instance, "mRootView") as? WeakReference<*>)?.get() as? View
                }.getOrNull() ?: return@hookAfter
                val handler = runCatching {
                    (getObjectField(instance, "mHandler") as? WeakReference<*>)?.get() as? Handler
                }.getOrNull()

                val earphone = PodImagePrefs.load(prefsProvider())
                    .filter { it.boxImagePath != null }
                    .maxByOrNull { it.lastConnectedAt }
                if (earphone == null) {
                    Log.i(TAG, "loadDefaultInternal after: no earphone with box image")
                    return@hookAfter
                }

                val fileName = File(earphone.boxImagePath!!).name
                val reader = PodImageLoader.remoteImageReader
                val bitmap = reader?.let { runCatching { it(fileName) }.getOrNull() }
                if (bitmap == null) {
                    Log.i(TAG, "loadDefaultInternal after: remote reader returned null for $fileName")
                    return@hookAfter
                }

                val drawable = BitmapDrawable(ctx.resources, bitmap)
                val ticId = rootView.findViewById<ImageView>(
                    ctx.resources.getIdentifier("tic", "id", "com.android.settings")
                ) ?: run {
                    Log.i(TAG, "loadDefaultInternal after: tic ImageView not found")
                    return@hookAfter
                }

                // Post after the stock 50ms delay so we override the setImageResource call.
                val action = Runnable {
                    Log.i(TAG, "loadDefaultInternal after: setting custom box image")
                    ticId.setImageDrawable(drawable)
                }
                handler?.postDelayed(action, 100L) ?: action.run()
            }
            Log.i(TAG, "loadDefaultInternal hook installed")
        }.onFailure { Log.e(TAG, "Failed to hook loadDefaultInternal", it) }
    }
}
