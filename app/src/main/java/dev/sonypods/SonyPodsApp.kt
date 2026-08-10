package dev.sonypods

import android.app.Application
import android.util.Log
import dev.sonypods.config.CapabilityProbeCache
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.PodImagePrefs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class SonyPodsApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        // Load the app-local configuration before the service callback can rewrite
        // the remote store. Otherwise an early bind would publish AppConfig's
        // in-memory defaults and overwrite the user's persisted settings.
        ConfigManager.init(getSharedPreferences(ConfigManager.PREFS_NAME, MODE_PRIVATE))
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Log.d(TAG, "LSPosed service bound api=${service.apiVersion} framework=${service.frameworkName}/${service.frameworkVersionCode}")
        xposedService = service
        notifyListeners(service)
        // Publish the initialized local config using the current schema. Must run before
        // flushPendingRemote so a pending write always lands on the current schema.
        ConfigManager.syncToRemote(service)
        // Flush any config that was saved while the service was unavailable, so the
        // engine's remote-prefs store is authoritative and survives a scope restart.
        ConfigManager.flushPendingRemote(service)
        // Migrate model images saved before the Remote Files path was introduced so
        // hooked system surfaces can continue to read the automatic catalog image.
        PodImagePrefs.migrateImagesToRemote(this, service)
        // Flush any capability-probe cache the engine pushed while the service was
        // unavailable, so the shared remote-prefs store is authoritative across a scope
        // restart (the engine reads it back on the next connection).
        CapabilityProbeCache.flushPending(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            Log.d(TAG, "LSPosed service died")
            xposedService = null
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
