package dev.sonypods.hook

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import dev.sonypods.config.ConfigManager
import dev.sonypods.hook.milink.MiLinkServiceHook
import dev.sonypods.utils.PodImageLoader

class HookEntry : XposedModule() {
    private val TAG = "SonyPods-HookEntry"

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        Log.d(TAG, "onPackageLoaded package=${param.packageName} firstPackage=${param.isFirstPackage} process=${runCatching { android.app.Application.getProcessName() }.getOrNull()}")
        if (!param.isFirstPackage) return

        when (param.packageName) {
            "com.android.bluetooth" -> {
                loadHook(HeadsetStateDispatcher, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
            "com.android.settings" -> {
                loadHook(SettingsRenderHook(), param.defaultClassLoader, param.packageName)
                loadHook(SettingsHeadsetHook, param.defaultClassLoader, param.packageName)
            }
            "com.milink.service" -> loadHook(MiLinkServiceHook, param.defaultClassLoader, param.packageName)
            "com.xiaomi.bluetooth" -> {
                loadHook(MiBluetoothToastHook, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
            "com.sony.songpal.mdr" ->
                loadHook(SoundConnectHandoverHook, param.defaultClassLoader, param.packageName)
        }
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader, packageName: String) {
        Log.module = this
        hook.module = this
        hook.appClassLoader = classLoader
        hook.packageName = packageName
        // LSPosed's remote preference (libxposed 101) is the cross-process config store.
        // The app persists config here on every change, so the engine reads the authoritative
        // value at startup (surviving scope restarts) and on demand. Live updates while running
        // are still delivered by the config broadcast (applyConfigJson).
        hook.prefs = getRemotePreferences("sonypods_settings")
        // Re-fetchable provider: every call re-reads the framework-backed store so the
        // engine can correct a startup read that raced the remote-prefs bridge.
        hook.prefsProvider = { getRemotePreferences("sonypods_settings") }
        Log.d(TAG, "loadHook package=$packageName hook=${hook.javaClass.simpleName}")
        ConfigManager.init(hook.prefs)
        hook.onHook()
        PodImageLoader.remoteImageReader = { name ->
            runCatching {
                hook.module.openRemoteFile(name).use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }
            }.getOrNull()
        }
    }
}
