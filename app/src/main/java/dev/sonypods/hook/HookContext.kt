package dev.sonypods.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import dev.sonypods.bridge.SonyStateSnapshot
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import dev.sonypods.config.ConfigManager
import dev.sonypods.hook.reload.GenerationRuntime
import dev.sonypods.hook.reload.ResourceRegistry

abstract class HookContext {
    lateinit var module: XposedModule
    lateinit var appClassLoader: ClassLoader
    lateinit var prefs: SharedPreferences
    /**
     * Re-fetchable source of the framework-backed remote-preference store.
     * `SharedPreferences` objects handed out by `XposedModule.getRemotePreferences(...)`
     * are snapshots taken at call time; re-invoking the provider always returns a store
     * reflecting the latest data the LSPosed framework has on disk. The engine uses this
     * (instead of the single [prefs] instance captured at package-load) so a cold read that
     * raced the framework bridge is corrected by a later re-read.
     */
    lateinit var prefsProvider: () -> SharedPreferences
    /** Read-only access to libxposed Remote Files, installed by [HookEntry]. */
    lateinit var remoteFileReader: (String) -> ByteArray?
    lateinit var packageName: String
    lateinit var runtime: GenerationRuntime
        private set
    lateinit var hookRegistry: dev.sonypods.hook.reload.HookRegistry
        private set
    lateinit var resources: ResourceRegistry
        private set

    abstract fun onHook()

    /** Called in the old generation before libxposed captures old hook handles. */
    internal open fun onBeforeReload() = Unit

    /**
     * Re-activates the old generation when onBeforeReload rejected a reload.
     * API 102 cancellation must leave the old generation usable; this callback
     * restores resources that were intentionally quiesced during preparation.
     */
    internal open fun onReloadRejected(snapshot: SonyStateSnapshot) = Unit

    /**
     * Save small classloader-neutral state before API 102 replaces this generation.
     * Process-local objects must not be put in the Bundle; hooks may use this only
     * for stable values such as a Bluetooth address or a deduplication key.
     */
    internal open fun saveReloadState(state: Bundle) = Unit

    /** Restore the values written by [saveReloadState] in the replacement generation. */
    internal open fun restoreReloadState(state: Bundle) = Unit

    /** Unregisters a receiver while treating an already-unregistered receiver as idempotent. */
    internal fun unregisterReceiverForReload(context: Context?, receiver: BroadcastReceiver?) {
        if (context == null || receiver == null) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // The receiver was already unregistered; teardown remains idempotent.
        }
    }

    internal fun attachRuntime(runtime: GenerationRuntime) {
        this.runtime = runtime
        hookRegistry = runtime.hooks
        resources = runtime.resources
    }

    internal fun registerResource(key: String, disposer: () -> Unit) {
        resources.register("${javaClass.name}:$key", disposer)
    }

    internal fun registerDynamicTarget(className: String) {
        hookRegistry.registerDynamicTarget(className)
    }

    internal fun dynamicTargetClasses(): List<String> = hookRegistry.dynamicTargets()

    open fun fakeDeviceId(): String = ConfigManager.fakeDeviceId()

    fun fakeSupport(): String = ConfigManager.fakeSupport()

    private var remoteConfigListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var remoteConfigListenerSource: SharedPreferences? = null

    /**
     * Native config-change path (canonical libxposed pattern, see libxposed/example
     * ModuleMainKt): the framework notifies this hooked process whenever the module app
     * writes the shared remote-preference store. This replaces the former custom
     * ACTION_CONFIG_CHANGED broadcast — the store itself is the propagation channel.
     *
     * The shared [ConfigManager] cache is refreshed from a freshly fetched store snapshot,
     * then the concrete hook can react via [onRemoteConfigChanged]. Registration is
     * idempotent; pair with [unregisterRemoteConfigChangeListener] in onBeforeReload.
     */
    protected fun registerRemoteConfigChangeListener() {
        if (remoteConfigListener != null) return
        val source = runCatching { prefsProvider() }.getOrElse { prefs }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // Re-fetch: getRemotePreferences returns a snapshot at call time, and a fresh
            // fetch reflects the latest data the framework holds.
            val fresh = runCatching { prefsProvider() }.getOrElse { source }
            runCatching { ConfigManager.refreshFromPrefs(fresh) }
                .onFailure { android.util.Log.w("SonyPods-Hook", "remote config refresh failed", it) }
            onRemoteConfigChanged()
        }
        runCatching { source.registerOnSharedPreferenceChangeListener(listener) }
            .onSuccess {
                remoteConfigListener = listener
                remoteConfigListenerSource = source
            }
            .onFailure {
                android.util.Log.w("SonyPods-Hook", "remote config listener registration failed", it)
            }
    }

    /** Called after the shared config cache was refreshed from the remote-pref store. */
    protected open fun onRemoteConfigChanged() {}

    protected fun unregisterRemoteConfigChangeListener() {
        val listener = remoteConfigListener ?: return
        remoteConfigListenerSource?.let { source ->
            runCatching { source.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        remoteConfigListener = null
        remoteConfigListenerSource = null
    }

    internal fun readRemoteFileText(name: String): String? =
        runCatching { remoteFileReader(name)?.toString(Charsets.UTF_8) }
            .getOrNull()

    fun findClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)

    fun findMethod(className: String, methodName: String, vararg parameterTypes: Class<*>): Method =
        findClass(className).getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }

    fun findConstructor(className: String, vararg parameterTypes: Class<*>): Constructor<*> =
        findClass(className).getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }

    fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method =
        findClass(className).declaredMethods
            .filter { it.name == methodName && it.parameterTypes.size == paramCount }
            .sortedBy { it.parameterTypes.joinToString(",") { type -> type.name } + ":" + it.returnType.name }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("$className.$methodName/$paramCount")

    /**
     * Every constructor taking [paramCount] parameters, in a deterministic order.
     *
     * The number of overloads is not stable across ROM releases: Bluetooth Extension
     * 17 added a second two-argument `MiuiBluetoothNotification` constructor, and
     * which of the two actually runs depends on a device setting. A caller that needs
     * its callback to fire either way has to hook all of them.
     */
    fun findConstructorsByParamCount(className: String, paramCount: Int): List<Constructor<*>> {
        val matches = findClass(className).declaredConstructors
            .filter { it.parameterTypes.size == paramCount }
            .sortedBy { it.parameterTypes.joinToString(",") { type -> type.name } }
            .onEach { it.isAccessible = true }
        if (matches.isEmpty()) throw NoSuchMethodException("$className constructor/$paramCount")
        return matches
    }

    fun findConstructorByParamCount(className: String, paramCount: Int): Constructor<*> {
        val matches = findConstructorsByParamCount(className, paramCount)
        if (matches.size > 1) {
            Log.w(
                "SonyPods-Hook",
                "$className declares ${matches.size} constructors taking $paramCount arguments; " +
                    "using ${describeParams(matches.first())} and ignoring the rest",
            )
        }
        return matches.first()
    }

    private fun describeParams(executable: java.lang.reflect.Executable): String =
        executable.parameterTypes.joinToString(",") { it.name }

    fun hookAfter(method: Method, logicalRole: String? = null, required: Boolean = false, block: HookParam.() -> Unit) {
        hookRegistry.install(method, "after", logicalRole, required) { chain ->
            if (!runtime.acceptingEvents) return@install chain.proceed()
            val result = chain.proceed()
            HookParam(chain, result).apply(block).result
        }
    }

    fun hookBefore(method: Method, logicalRole: String? = null, required: Boolean = false, block: HookParam.() -> Unit) {
        hookRegistry.install(method, "before", logicalRole, required) { chain ->
            if (!runtime.acceptingEvents) return@install chain.proceed()
            val param = HookParam(chain, null).apply(block)
            if (param.hasResult) param.result else chain.proceed()
        }
    }

    fun hookConstructorAfter(constructor: Constructor<*>, logicalRole: String? = null, required: Boolean = false, block: HookParam.() -> Unit) {
        hookRegistry.install(constructor, "constructor-after", logicalRole, required) { chain ->
            if (!runtime.acceptingEvents) return@install chain.proceed()
            chain.proceed().also { HookParam(chain, it).apply(block) }
        }
    }

    /**
     * Installs [block] on every constructor in [constructors].
     *
     * Each one gets its own stable ID derived from its parameter types; [logicalRole]
     * on its own would collide in `HookRegistry` the moment a build declares more
     * than one overload.
     */
    fun hookConstructorAfterAll(
        constructors: List<Constructor<*>>,
        logicalRole: String,
        required: Boolean = false,
        block: HookParam.() -> Unit,
    ) {
        constructors.forEach { constructor ->
            hookConstructorAfter(constructor, "$logicalRole:${describeParams(constructor)}", required, block)
        }
    }
}

/**
 * Hook-side logging. Writes to both the LSPosed module log and logcat: the LSPosed
 * channel is not visible to `adb logcat`, which makes hook problems in system
 * processes impossible to diagnose from a host machine.
 *
 * Levels: v/d require LOG_LEVEL_DEBUG, i/w require LOG_LEVEL_BASIC, e is always
 * emitted. In processes where the module is not loaded (the app itself) `module`
 * is null and this degrades to plain logcat, still level-gated.
 *
 * Tag taxonomy — every file logs under exactly one of these five, so one filter
 * shows a whole subsystem:
 *  - SonyPods-Engine — the bluetooth-process engine: Tandem transport, repository,
 *    connection state machine, LE audio policy
 *  - SonyPods-Hook — Xposed hooks and the surfaces they render (MIUI settings,
 *    notifications, island, toasts)
 *  - SonyPods-MiLink — the milink headset-circulate subsystem
 *  - SonyPods-Cloud — cloud model catalog and earphone image download/store
 *  - SonyPods-App — the module app process (UI, config, migration)
 */
object Log {
    @Volatile
    var module: XposedModule? = null

    private fun emit(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            module?.log(priority, tag, message)
            android.util.Log.println(priority, tag, message)
        } else {
            module?.log(priority, tag, message, throwable)
            android.util.Log.println(priority, tag, "$message\n${android.util.Log.getStackTraceString(throwable)}")
        }
    }

    fun v(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        emit(android.util.Log.VERBOSE, tag, message)
    }

    fun i(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.INFO, tag, message)
    }

    fun d(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        emit(android.util.Log.DEBUG, tag, message)
    }

    fun d(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        emit(android.util.Log.DEBUG, tag, message, throwable)
    }

    fun w(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.WARN, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.WARN, tag, message, throwable)
    }

    // Errors are emitted regardless of the configured level: a broken install must
    // not be able to silence its own diagnostics.
    fun e(tag: String, message: String) {
        emit(android.util.Log.ERROR, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        emit(android.util.Log.ERROR, tag, message, throwable)
    }
}

class HookParam(private val chain: XposedInterface.Chain, initialResult: Any?) {
    val args: List<Any?> = chain.args
    val instance: Any? = chain.thisObject
    var hasResult = false
        private set
    var result: Any? = initialResult
        set(value) {
            hasResult = true
            field = value
        }
}

fun getObjectField(instance: Any?, fieldName: String): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
    if (instance == null) return
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            cls.getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)
            return
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
            it.isAccessible = true
            return it.invoke(instance, *args)
        }
        cls = cls.superclass
    }
    throw NoSuchMethodException(methodName)
}
