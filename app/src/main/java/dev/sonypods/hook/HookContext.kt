package dev.sonypods.hook

import android.content.Intent
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import dev.sonypods.config.ConfigManager

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
    lateinit var packageName: String

    abstract fun onHook()

    fun fakeDeviceId(): String = ConfigManager.fakeDeviceId()

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

    fun findClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)

    fun findMethod(className: String, methodName: String, vararg parameterTypes: Class<*>): Method =
        findClass(className).getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }

    fun findConstructor(className: String, vararg parameterTypes: Class<*>): Constructor<*> =
        findClass(className).getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }

    fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method =
        findClass(className).declaredMethods.first { it.name == methodName && it.parameterTypes.size == paramCount }
            .apply { isAccessible = true }

    fun findConstructorByParamCount(className: String, paramCount: Int): Constructor<*> =
        findClass(className).declaredConstructors.first { it.parameterTypes.size == paramCount }
            .apply { isAccessible = true }

    fun hookAfter(method: Method, block: HookParam.() -> Unit) {
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            HookParam(chain, result).apply(block).result
        }
    }

    fun hookBefore(method: Method, block: HookParam.() -> Unit) {
        module.hook(method).intercept { chain ->
            val param = HookParam(chain, null).apply(block)
            if (param.hasResult) param.result else chain.proceed()
        }
    }

    fun hookConstructorAfter(constructor: Constructor<*>, block: HookParam.() -> Unit) {
        module.hook(constructor).intercept { chain ->
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
