package dev.sonypods.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-process mirror of the headphone state owned by the engine in the bluetooth
 * process. The UI observes [state] exactly like it used to observe the repository,
 * but the authority now lives elsewhere, so the UI can come and go freely.
 */
object SonyRemoteState {
    private const val TAG = "SonyPods-RemoteState"

    private val _state = MutableStateFlow(SonyStateSnapshot())
    val state: StateFlow<SonyStateSnapshot> = _state.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SonyBridge.ACTION_STATE) return
            val bundle = intent.bundleExtra(SonyStateSnapshot.EXTRA_SNAPSHOT) ?: return
            val snapshot = SonyStateSnapshot.fromBundle(bundle)
            _state.value = snapshot
            context?.let { ModelImageSync.onState(it, snapshot) }
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
        SonyBridge.sendCommand(appContext, SonyBridge.CMD_REPUBLISH)
    }

    @Suppress("DEPRECATION")
    private fun Intent.bundleExtra(key: String): Bundle? =
        runCatching { getBundleExtra(key) }.getOrNull()
}
