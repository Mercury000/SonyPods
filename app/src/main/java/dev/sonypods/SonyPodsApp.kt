package dev.sonypods

import android.app.Application
import dev.sonypods.config.ConfigManager
import dev.sonypods.engine.AppEngineHost

class SonyPodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load the app-local configuration before the service callback can rewrite
        // the remote store. Otherwise an early bind would publish AppConfig's
        // in-memory defaults and overwrite the user's persisted settings.
        ConfigManager.init(getSharedPreferences(ConfigManager.PREFS_NAME, MODE_PRIVATE))
        // Clear notifications left by older builds. This build intentionally has
        // no notification publisher or foreground service.
        getSystemService(android.app.NotificationManager::class.java)?.cancelAll()
        AppEngineHost.start(this)
    }

    companion object {
        const val TAG = "SonyPods-App"
    }
}
