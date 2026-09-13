package dev.sonypods.bridge

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.hook.Log

/**
 * Consumer-side mirror of the engine's state, fed by the [SonyStateBus] settings entry.
 *
 * Registering attaches a content observer and immediately reads the current value; every
 * later engine write notifies exactly the subscribed processes. Nothing is polled and
 * nothing is replayed, so a stopped engine simply publishes no further changes.
 */
class HookStateMirror(
    private val onEnvelope: ((SonyStateSnapshot, Bundle) -> Unit)? = null,
    private val onChanged: (SonyStateSnapshot) -> Unit = {},
) {

    @Volatile
    var snapshot: SonyStateSnapshot = SonyStateSnapshot()
        private set

    private var registered = false
    private var registeredContext: Context? = null
    private var observer: ContentObserver? = null
    private val main = Handler(Looper.getMainLooper())

    fun register(context: Context?) {
        if (context == null || registered) return
        val appContext = context.applicationContext ?: context
        registeredContext = appContext
        registered = true
        val resolver = appContext.contentResolver
        val watcher = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                readAndDispatch(resolver)
            }
        }
        runCatching {
            resolver.registerContentObserver(SonyStateBus.uri(), false, watcher)
            observer = watcher
        }.onFailure { Log.w(TAG, "state observer registration failed", it) }
        readAndDispatch(resolver)
    }

    fun close() {
        val watcher = observer
        observer = null
        if (watcher != null) {
            registeredContext?.let { context ->
                runCatching { context.contentResolver.unregisterContentObserver(watcher) }
            }
        }
        registered = false
        registeredContext = null
    }

    /** Re-reads the current value. A pull, not a replay: the bus holds one value. */
    fun refresh() {
        registeredContext?.let { readAndDispatch(it.contentResolver) }
    }

    private fun readAndDispatch(resolver: ContentResolver) {
        SonyStateBus.read(resolver)?.let(::dispatch)
    }

    private fun dispatch(envelope: Bundle) {
        val bundle = envelope.getBundle(SonyStateSnapshot.EXTRA_SNAPSHOT) ?: return
        val state = SonyStateSnapshot.fromBundle(bundle)
        // Delivery already runs on the main handler; keep the explicit check so a
        // caller-invoked refresh cannot bypass the main-thread contract.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(state, envelope)
        } else {
            main.post { apply(state, envelope) }
        }
    }

    private fun apply(state: SonyStateSnapshot, envelope: Bundle) {
        if (!registered) return
        snapshot = state
        SonyDeviceService.rememberAddress(state.deviceAddress)
        onChanged(state)
        onEnvelope?.invoke(state, envelope)
    }

    private companion object {
        const val TAG = "SonyPods-Bridge"
    }
}
