package dev.sonypods.hook

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import dev.sonypods.config.ConfigManager
import dev.sonypods.hook.milink.MiLinkServiceHook

class HookEntry : XposedModule() {
    private val TAG = "SonyPods-HookEntry"
    private val configListeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        Log.d(TAG, "onPackageLoaded package=${param.packageName} firstPackage=${param.isFirstPackage} process=${runCatching { android.app.Application.getProcessName() }.getOrNull()}")
        if (!param.isFirstPackage) return

        when (param.packageName) {
            "com.android.bluetooth" -> {
                loadHook(HeadsetStateDispatcher, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
            //"com.android.settings" -> loadHook(SettingsHeadsetHook, param.defaultClassLoader, param.packageName)
            "com.milink.service" -> loadHook(MiLinkServiceHook, param.defaultClassLoader, param.packageName)
            "com.xiaomi.bluetooth" -> {
                loadHook(MiBluetoothToastHook, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
        }
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader, packageName: String) {
        Log.module = this
        hook.module = this
        hook.appClassLoader = classLoader
        hook.packageName = packageName
        hook.prefs = getRemotePreferences("sonypods_settings")
        Log.d(TAG, "loadHook package=$packageName hook=${hook.javaClass.simpleName}")
        ConfigManager.init(hook.prefs)
        val configListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == ConfigManager.PREF_KEY_CONFIG_JSON) {
                ConfigManager.refreshFromPrefs(sharedPreferences)
            }
        }
        configListeners.add(configListener)
        hook.prefs.registerOnSharedPreferenceChangeListener(configListener)
        hook.onHook()
    }
}
