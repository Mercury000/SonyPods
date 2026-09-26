package dev.sonypods

import android.app.Application
import android.util.Log
import dev.sonypods.config.CloudModelInfoSync
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.LegacyConfigMigrator
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.bridge.ModelImageSync
import dev.sonypods.device.HeadsetRegistry
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class SonyPodsApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        // App-local appearance keys (theme/accent/language) move out of the legacy shared
        // prefs file into their own file before any activity reads them. The legacy file
        // itself survives until the store binding transaction has drained it below.
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
        // App-local appearance keys already moved out in onCreate. The shared store binds
        // through an explicit read-then-adopt: the store is read once here (the libxposed
        // example reads its value the same way from the bound service), and only ever written
        // by mutating that adopted value. A store that reads empty adopts defaults in memory
        // without writing, so a store that actually holds a config is left intact; the
        // historical default-overwrite (writing before the read) can no longer happen.
        ConfigManager.attachWritableStore(service) { LegacyConfigMigrator.readLegacySeed(this) }
        PodImagePrefs.attachWritableStore(service) { LegacyConfigMigrator.readLegacyEarphones(this) }
        // App-only fields (startup tab, click actions) move out of the shared blob into
        // local UI prefs; runs after adoption so it reads the real config, not defaults.
        LegacyConfigMigrator.migrateAppOnlyPrefsToUi(this)
        // The store is authoritative from here on; drop the legacy local file only once both
        // stores hold a confirmed value, since that file is the last copy of a pre-migration
        // configuration.
        if (ConfigManager.isConfigReady() && PodImagePrefs.isMetadataReady()) {
            LegacyConfigMigrator.deleteLegacyFile(this)
        }
        ModelImageSync.onServiceBound(this)
        // No store here: this process holds no Tandem session, so it can learn nothing itself.
        // The engine identifies a headset from its own replies and ships the records in every
        // state snapshot, so mirror those broadcasts and feed the records in.
        HeadsetRegistry.initializeForEngine(null)
        identityMirror.register(this)
        // Migrate model images saved before the Remote Files path was introduced so
        // hooked system surfaces can continue to read the automatic catalog image.
        PodImagePrefs.migrateImagesToRemote(service)
        notifyListeners(service)
    }

    /** Engine -> app identity feed; see [HeadsetRegistry.ingest]. */
    private val identityMirror = dev.sonypods.bridge.HookStateMirror { snapshot ->
        HeadsetRegistry.ingest(snapshot.headsetRecords)
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
