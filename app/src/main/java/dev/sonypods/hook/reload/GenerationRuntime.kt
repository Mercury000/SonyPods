package dev.sonypods.hook.reload

import android.os.Bundle
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.hook.HookContext
import dev.sonypods.hook.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicLong

/** One owned module generation in one target process. */
class GenerationRuntime(
    val module: XposedModule,
    val processName: String,
    val scopePackage: String,
    oldHandles: List<XposedInterface.HookHandle> = emptyList(),
    val reloadExtras: Bundle? = null,
    val savedState: Bundle? = null,
) {
    companion object {
        const val STATE_SCHEMA = 1
        const val KEY_SCHEMA = "schema"
        const val KEY_PROCESS = "process_name"
        const val KEY_PRIMARY_PACKAGE = "primary_package"
        const val KEY_SCOPE_PACKAGES = "scope_packages"
        const val KEY_OLD_GENERATION = "old_generation"
        const val KEY_RELOAD_EPOCH = "reload_epoch"
        const val KEY_CONNECTION_PRESENT = "connection_present"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_A2DP_DEVICE_ADDRESS = "a2dp_device_address"
        const val KEY_PHYSICAL_DISCONNECT_ADDRESS = "physical_disconnect_address"
        const val KEY_TRANSPORT = "selected_transport"
        const val KEY_DYNAMIC_BINDER_CLASSES = "dynamic_binder_classes"
        const val KEY_CONFIG_REVISION = "config_revision"

        private val nextGeneration = AtomicLong(1L)
        private val SCOPE_PACKAGES = arrayOf(
            "com.android.bluetooth",
            "com.android.settings",
            "com.milink.service",
            "com.xiaomi.bluetooth",
            "com.sony.songpal.mdr",
        )
    }

    // Each hot reload runs in a fresh classloader, so the static counter alone
    // would restart at 1; continue from the old generation carried in savedState.
    val generationId: Long = savedState?.getLong(KEY_OLD_GENERATION, 0L)
        ?.takeIf { it > 0L }?.plus(1L)
        ?: nextGeneration.getAndIncrement()
    val resources = ResourceRegistry()
    val hooks = HookRegistry(module, scopePackage, oldHandles)
    @Volatile
    var acceptingEvents: Boolean = true
        private set
    private val contexts = linkedSetOf<HookContext>()
    private var quiesced = false
    private var reloadSnapshot: SonyStateSnapshot? = null
    private var reloadState: Bundle? = null

    fun attach(context: HookContext) {
        contexts += context
        context.attachRuntime(this)
        savedState?.let { context.restoreReloadState(it) }
    }

    fun updateClassLoader(classLoader: ClassLoader) {
        contexts.forEach { it.appClassLoader = classLoader }
    }

    fun contexts(): List<HookContext> = contexts.toList()

    fun registerResource(key: String, disposer: () -> Unit) = resources.register(key, disposer)
    fun commitHooks() = hooks.commit()

    fun dynamicBinderClasses(): List<String> = (contexts.flatMap { it.dynamicTargetClasses() } + hooks.dynamicTargets()).distinct().sorted()

    /**
     * Stop all module-owned activity before the framework captures old handles.
     * Hook handles intentionally remain installed: API 102 transfers them to the
     * new callback, where they are atomically replaced or explicitly removed.
     */
    fun prepareForReload(): Bundle? {
        if (quiesced) return null
        quiesced = true
        acceptingEvents = false
        val snapshot = runCatching { dev.sonypods.hook.SonyEngineHost.snapshot() }.getOrDefault(SonyStateSnapshot())
        val reloadAddress = runCatching {
            dev.sonypods.hook.SonyEngineHost.reloadDeviceAddress()
        }.getOrNull()
        val physicalDisconnect = runCatching {
            dev.sonypods.hook.SonyEngineHost.reloadPhysicallyDisconnected()
        }.getOrNull()
        reloadSnapshot = snapshot
        val dynamicClasses = dynamicBinderClasses()
        val failures = mutableListOf<String>()
        val reloadState = Bundle()
        contexts.forEach { context ->
            runCatching { context.saveReloadState(reloadState) }
                .onFailure { failures += "${context.javaClass.name}: ${it.message}" }
        }
        this.reloadState = reloadState
        contexts.forEach { context ->
            runCatching { context.onBeforeReload() }
                .onFailure { failures += "${context.javaClass.name}: ${it.message}" }
        }
        failures += resources.closeAll()
        if (failures.isNotEmpty()) {
            val recoveryFailures = restoreAfterReloadRejection(failures)
            Log.e(
                "SonyPods-Hook",
                "generation=$generationId refused reload: ${failures.joinToString()}" +
                    if (recoveryFailures.isEmpty()) "; old generation restored" else
                        "; old generation recovery failed: ${recoveryFailures.joinToString()}",
            )
            return null
        }
        return Bundle().apply {
            putInt(KEY_SCHEMA, STATE_SCHEMA)
            putString(KEY_PROCESS, processName)
            putString(KEY_PRIMARY_PACKAGE, scopePackage)
            putStringArray(KEY_SCOPE_PACKAGES, SCOPE_PACKAGES)
            putLong(KEY_OLD_GENERATION, generationId)
            putLong(KEY_RELOAD_EPOCH, generationId)
            putBoolean(
                KEY_CONNECTION_PRESENT,
                snapshot.connected || !reloadAddress.isNullOrBlank(),
            )
            // Keep the old key for consumers which already use it, but prefer the
            // A2DP-derived address in the new dispatcher. Tandem may be false while
            // the classic audio link is still alive.
            putString(KEY_DEVICE_ADDRESS, reloadAddress ?: snapshot.deviceAddress)
            putString(KEY_DEVICE_NAME, snapshot.deviceName)
            putString(KEY_A2DP_DEVICE_ADDRESS, reloadAddress)
            putString(KEY_PHYSICAL_DISCONNECT_ADDRESS, physicalDisconnect.toString())
            putAll(reloadState)
            putString(KEY_TRANSPORT, "unknown")
            putStringArrayList(KEY_DYNAMIC_BINDER_CLASSES, ArrayList(dynamicClasses))
            putLong(KEY_CONFIG_REVISION, 0L)
        }
    }

    fun validateSavedState(): Boolean = savedState?.getInt(KEY_SCHEMA, -1) == STATE_SCHEMA

    fun finishReplacement(): Boolean {
        val unresolved = hooks.unresolvedOldHookIds()
        if (unresolved.isNotEmpty()) {
            Log.e("SonyPods-Hook", "generation=$generationId unresolved old hooks=${unresolved.joinToString()}")
            return false
        }
        return true
    }

    fun abortReplacement() {
        hooks.abort()
        resources.closeAll()
    }

    /**
     * Restores an old generation after a reload request is rejected before the
     * framework captures its hook handles. Returning false from onHotReloading
     * is a cancellation, so the old generation must become usable again.
     */
    fun restoreAfterReloadRejection(preexistingFailures: List<String> = emptyList()): List<String> {
        val snapshot = reloadSnapshot ?: SonyStateSnapshot()
        val failures = preexistingFailures.toMutableList()
        reloadState?.let { state ->
            contexts.forEach { context ->
                runCatching { context.restoreReloadState(state) }
                    .onFailure { failures += "${context.javaClass.name}: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
        contexts.toList().asReversed().forEach { context ->
            runCatching { context.onReloadRejected(snapshot) }
                .onFailure { failures += "${context.javaClass.name}: ${it.message ?: it.javaClass.simpleName}" }
        }
        reloadSnapshot = null
        if (failures.isEmpty()) {
            quiesced = false
            acceptingEvents = true
        } else {
            // Keep the generation fail-closed when it could not be restored;
            // the service result will lead the user to an explicit scope restart.
            quiesced = true
            acceptingEvents = false
        }
        return failures
    }

}
