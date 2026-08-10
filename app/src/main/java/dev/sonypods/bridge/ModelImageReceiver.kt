package dev.sonypods.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.sonypods.config.CloudModelInfoSync

/**
 * Keeps model-image synchronization independent from the module UI lifecycle.
 * The engine broadcasts snapshots when the device connects even if the app has
 * no activity, so this receiver is declared in the manifest rather than being
 * registered only by [SonyRemoteState].
 */
class ModelImageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SonyBridge.ACTION_STATE) return
        val bundle = intent.getBundleExtra(SonyStateSnapshot.EXTRA_SNAPSHOT) ?: return
        val pendingResult = goAsync()
        val snapshot = SonyStateSnapshot.fromBundle(bundle)
        CloudModelInfoSync.onState(context, snapshot)
        ModelImageSync.onState(context, snapshot) {
            pendingResult.finish()
        }
    }
}
