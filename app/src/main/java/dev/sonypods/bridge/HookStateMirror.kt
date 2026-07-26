package dev.sonypods.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Keeps a hooked system process in sync with the engine's state.
 *
 * Each hook creates one, registers it once it has a context, and reads
 * [snapshot] whenever the system asks it for headphone state.
 */
class HookStateMirror(private val onChanged: (SonyStateSnapshot) -> Unit = {}) {

    @Volatile
    var snapshot: SonyStateSnapshot = SonyStateSnapshot()
        private set

    private var registered = false

    fun register(context: Context?) {
        if (context == null || registered) return
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != SonyBridge.ACTION_STATE) return
                        val bundle = intent.getBundleExtra(SonyStateSnapshot.EXTRA_SNAPSHOT) ?: return
                        snapshot = SonyStateSnapshot.fromBundle(bundle)
                        onChanged(snapshot)
                    }
                },
                IntentFilter(SonyBridge.ACTION_STATE),
                Context.RECEIVER_EXPORTED,
            )
            registered = true
            // We may have started after the last state change; ask for a replay.
            SonyBridge.sendCommand(context, SonyBridge.CMD_REPUBLISH)
        }
    }
}
