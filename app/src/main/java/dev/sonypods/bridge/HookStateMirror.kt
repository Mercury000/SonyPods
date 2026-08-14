package dev.sonypods.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import dev.sonypods.device.SonyDeviceService

/**
 * Keeps a hooked system process in sync with the engine's state.
 *
 * Each hook creates one, registers it once it has a context, and reads
 * [snapshot] whenever the system asks it for headphone state.
 *
 * Registration races with the engine at boot: a consumer process can come up
 * before the bluetooth process has booted the engine, in which case its replay
 * request goes nowhere and — since state is only broadcast on change — it would
 * stay empty until the user next touched something. So the request is retried
 * until the first snapshot actually arrives.
 */
class HookStateMirror(private val onChanged: (SonyStateSnapshot) -> Unit = {}) {

    @Volatile
    var snapshot: SonyStateSnapshot = SonyStateSnapshot()
        private set

    @Volatile
    private var received = false

    private var registered = false
    private var registeredContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    fun register(context: Context?) {
        if (context == null || registered) return
        val appContext = context.applicationContext ?: context
        runCatching {
            val stateReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != SonyBridge.ACTION_STATE) return
                        val bundle = intent.getBundleExtra(SonyStateSnapshot.EXTRA_SNAPSHOT) ?: return
                        received = true
                        snapshot = SonyStateSnapshot.fromBundle(bundle)
                        SonyDeviceService.rememberAddress(snapshot.deviceAddress)
                        onChanged(snapshot)
                    }
                }
            appContext.registerReceiver(
                stateReceiver,
                IntentFilter(SonyBridge.ACTION_STATE),
                Context.RECEIVER_EXPORTED,
            )
            receiver = stateReceiver
            registeredContext = appContext
            registered = true
            requestReplay(appContext, attempt = 0)
        }
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        val ctx = registeredContext
        val stateReceiver = receiver
        if (ctx != null && stateReceiver != null) {
            try {
                ctx.unregisterReceiver(stateReceiver)
            } catch (_: IllegalArgumentException) {
                // The receiver was already unregistered; close remains idempotent.
            }
        }
        receiver = null
        registeredContext = null
        registered = false
        received = false
        snapshot = SonyStateSnapshot()
    }

    private fun requestReplay(context: Context, attempt: Int) {
        if (received || attempt >= REPLAY_ATTEMPTS) return
        SonyBridge.sendCommand(context, SonyBridge.CMD_REPUBLISH)
        handler.postDelayed({ requestReplay(context, attempt + 1) }, REPLAY_INTERVAL_MS)
    }

    private companion object {
        const val REPLAY_ATTEMPTS = 10
        const val REPLAY_INTERVAL_MS = 3_000L
    }
}
