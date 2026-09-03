package dev.sonypods

import android.app.Application
import android.util.Log
import dev.sonypods.config.CloudModelInfoSync
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.LegacyConfigMigrator
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.bridge.ModelImageSync
import dev.sonypods.device.UnifiedDeviceIdentityService
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class SonyPodsApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        // App-local appearance keys (theme/accent/language) move out of the legacy shared
        // prefs file into their own file before any activity reads them. The legacy file
        // itself survives until migrateToRemote has drained it below.
        LegacyConfigMigrator.migrateUiPrefs(this)
        // Fetch/update the app-owned cloud cache independently of the Hook process.
        // Hooked processes consume the published Remote File, never this app Pref.
        CloudModelInfoSync.initialize(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Log.d(TAG, "LSPosed service bound api=${service.apiVersion} framework=${service.frameworkName}/${service.frameworkVersionCode}")
        xposedService = service
        CloudModelInfoSync.onServiceBound(this, service)
        // Migration must run before anything reads or writes the shared store: for
        // installs predating remote-only persistence it seeds config/earphone metadata
        // from the legacy local file and then deletes that file, so no local prefs
        // copy of hook-consumed data survives.
        LegacyConfigMigrator.migrateToRemote(this, service)
        val remotePrefs = runCatching { service.getRemotePreferences(ConfigManager.PREFS_NAME) }
            .onFailure { Log.w(TAG, "getRemotePreferences failed", it) }
            .getOrNull()
        // Adopt the store (reads the persisted config into the cache) and flush any
        // saves buffered while the service was unavailable. Config is fully loaded
        // BEFORE listeners fire so the UI never renders defaults over real values.
        ConfigManager.attachStore(remotePrefs)
        // App-only fields (startup tab, click actions) move out of the shared blob into
        // local UI prefs; pull any value older builds saved remotely in on the first bind.
        LegacyConfigMigrator.migrateAppOnlyPrefsToUi(this)
        PodImagePrefs.attachStore(remotePrefs)
        ModelImageSync.onServiceBound(this)
        // No store and no bt_config here: this process cannot classify, it only consumes.
        // The engine is the one that resolves LE/control direction and it ships the result in
        // every state snapshot, so mirror those broadcasts and feed the pairs in.
        UnifiedDeviceIdentityService.initializeForEngine(null)
        identityMirror.register(this)
        // Migrate model images saved before the Remote Files path was introduced so
        // hooked system surfaces can continue to read the automatic catalog image.
        PodImagePrefs.migrateImagesToRemote(service)
        notifyListeners(service)
    }

    /** Engine -> app identity feed; see [UnifiedDeviceIdentityService.ingestPairs]. */
    private val identityMirror = dev.sonypods.bridge.HookStateMirror { snapshot ->
        UnifiedDeviceIdentityService.ingestPairs(snapshot.identityPairs)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            Log.d(TAG, "LSPosed service died")
            xposedService = null
            CloudModelInfoSync.onServiceDied(service)
            notifyListeners(null)
        }
    }

    private fun notifyListeners(service: XposedService?) {
        listeners.forEach { it(service) }
    }

    companion object {
        private const val TAG = "SonyPods-App"

        @Volatile
        var xposedService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }
    }
}
