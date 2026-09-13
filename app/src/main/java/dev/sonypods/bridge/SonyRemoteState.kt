package dev.sonypods.bridge

import android.content.Context
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
    private val _state = MutableStateFlow(SonyStateSnapshot())
    val state: StateFlow<SonyStateSnapshot> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    private val mirror = HookStateMirror { snapshot ->
        _state.value = snapshot
        appContext?.let { context ->
            CloudModelInfoSync.onState(context, snapshot)
            ModelImageSync.onState(context, snapshot)
        }
    }

    /** Binds the mirror to the engine's state bus; registering delivers the current state. */
    fun start(context: Context) {
        val ctx = context.applicationContext ?: context
        appContext = ctx
        mirror.register(ctx)
    }
}
