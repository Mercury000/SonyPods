package dev.sonypods.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.sonypods.hook.Log
import dev.sonypods.config.CloudModelInfoSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-process mirror of the headphone state owned by the engine in the bluetooth
 * process. The UI observes [state] exactly like it used to observe the repository,
 * but the authority now lives elsewhere, so the UI can come and go freely.
 */
object SonyRemoteState {
    private const val TAG = "SonyPods-App"

    private val _state = MutableStateFlow(SonyStateSnapshot())
    val state: StateFlow<SonyStateSnapshot> = _state.asStateFlow()

    private var registered = false

    @Volatile
    private var received = false

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SonyBridge.ACTION_STATE) return
            val bundle = intent.bundleExtra(SonyStateSnapshot.EXTRA_SNAPSHOT) ?: return
            val snapshot = SonyStateSnapshot.fromBundle(bundle)
            received = true
            _state.value = snapshot
            val appContext = context ?: return
            CloudModelInfoSync.onState(appContext, snapshot)
            ModelImageSync.onState(appContext, snapshot)
        }
    }

    /** Starts mirroring and asks the engine for the current state. */
    fun start(context: Context) {
        val appContext = context.applicationContext ?: context
        if (!registered) {
            runCatching {
                appContext.registerReceiver(
                    receiver,
                    IntentFilter(SonyBridge.ACTION_STATE),
                    Context.RECEIVER_EXPORTED,
                )
                registered = true
            }.onFailure { Log.w(TAG, "state receiver registration failed", it) }
        }
        // The engine may still be booting; keep asking until it answers.
        requestReplay(appContext, attempt = 0)
    }

    private fun requestReplay(context: Context, attempt: Int) {
        if (received || attempt >= REPLAY_ATTEMPTS) return
        SonyBridge.sendCommand(context, SonyBridge.CMD_REPUBLISH)
        handler.postDelayed({ requestReplay(context, attempt + 1) }, REPLAY_INTERVAL_MS)
    }

    private const val REPLAY_ATTEMPTS = 10
    private const val REPLAY_INTERVAL_MS = 2_000L

    @Suppress("DEPRECATION")
    private fun Intent.bundleExtra(key: String): Bundle? =
        runCatching { getBundleExtra(key) }.getOrNull()
}
