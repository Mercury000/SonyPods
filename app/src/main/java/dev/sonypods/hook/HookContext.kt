package dev.sonypods.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

    fun refreshConfig() {
        // Intentionally a no-op: re-reading the hooked-side remote prefs is unreliable
        // (it does not receive the app's writes) and would clobber the live config held
        // in cachedConfig. The authoritative config is read from the module's own
        // SharedPreferences at engine start, and kept live via applyConfigJson.
    }

    /**
     * Apply config pushed from the app by value. The app attaches the full serialized
     * [dev.sonypods.config.AppConfig] to [SonyPodsAction.ACTION_CONFIG_CHANGED]; when
     * present we apply it directly (no dependency on remote-preferences propagation).
     * When no JSON payload is attached (older app build) we keep the current cached
     * config rather than re-reading the hooked-side remote prefs, which are not a
     * reliable cross-process channel here.
     */
    fun applyPushedConfig(intent: Intent?) {
        val json = intent?.getStringExtra(ConfigManager.PREF_KEY_CONFIG_JSON)
        if (json != null) ConfigManager.applyConfigJson(json)
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

    fun findConstructorByParamCount(className: String, paramCount: Int): Constructor<*> =
        findClass(className).declaredConstructors
            .filter { it.parameterTypes.size == paramCount }
            .sortedBy { it.parameterTypes.joinToString(",") { type -> type.name } }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("$className constructor/$paramCount")

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
}

/**
 * Hook-side logging. Writes to both the LSPosed module log and logcat: the LSPosed
 * channel is not visible to `adb logcat`, which makes hook problems in system
 * processes impossible to diagnose from a host machine.
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
        emit(android.util.Log.INFO, tag, message)
    }

    fun i(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.INFO, tag, message)
    }

    fun d(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        emit(android.util.Log.INFO, tag, message)
    }

    fun d(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        emit(android.util.Log.ERROR, tag, message, throwable)
    }

    fun w(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.WARN, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.ERROR, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
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
