package dev.sonypods.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.sonypods.SonyPodsApp
import dev.sonypods.bridge.SonyBridge

/**
 * Receives the capability-probe cache pushed by the engine (hosted in the
 * `com.android.bluetooth` hook process). The app process is the only side where
 * `XposedService.getRemotePreferences` is writable, so it persists the value
 * into the shared remote-prefs store (buffering if the LSPosed service is not
 * bound yet) and echoes it back to the engine so its in-process overlay stays
 * current even when the hook-side remote-prefs read is not yet reliable.
 *
 * Declared exported in the manifest so the engine can reach it even when the
 * module app process is not running (`FLAG_INCLUDE_STOPPED_PACKAGES`).
 */
class CapabilityCacheReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SonyBridge.ACTION_CAPABILITY_CACHE) return
        val json = intent.getStringExtra(SonyBridge.EXTRA_CAPABILITY_JSON) ?: return
        val ctx = context.applicationContext ?: context
        android.util.Log.d(TAG, "received ${json.length} bytes; service=${SonyPodsApp.xposedService != null}")
        CapabilityProbeCache.persistFromApp(json, SonyPodsApp.xposedService)
        SonyBridge.sendCapabilityCache(ctx, json)
    }

    private companion object {
        const val TAG = "SonyPods-CapabilityCache"
    }
}
