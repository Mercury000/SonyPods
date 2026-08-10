package dev.sonypods.hook

import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import dev.sonypods.config.ConfigManager
import dev.sonypods.hook.milink.MiLinkServiceHook
import dev.sonypods.hook.reload.GenerationRuntime
import dev.sonypods.utils.PodImageLoader

/** The single libxposed 102 entry and lifecycle coordinator. */
class HookEntry : XposedModule() {
    private val tag = "SonyPods-HookEntry"
    private val supportedScopes = setOf(
        "com.android.bluetooth",
        "com.android.settings",
        "com.milink.service",
        "com.xiaomi.bluetooth",
        "com.sony.songpal.mdr",
    )
    private var processName: String = "unknown"
    private var runtime: GenerationRuntime? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        if (apiVersion < XposedInterface.API_102) {
            Log.e(tag, "libxposed API ${apiVersion} is below required 102; refusing to initialize")
            return
        }
        Log.i(tag, "module generation loaded process=$processName api=$apiVersion")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (apiVersion < XposedInterface.API_102 || param.packageName !in supportedScopes) return
        processName = runCatching { android.app.Application.getProcessName() }.getOrDefault(processName)
        // Class discovery and installation are intentionally deferred to
        // onPackageReady(), where the final application ClassLoader is available.
        Log.d(tag, "package loaded package=${param.packageName} first=${param.isFirstPackage} process=$processName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (apiVersion < XposedInterface.API_102 || param.packageName !in supportedScopes) return
        processName = runCatching { android.app.Application.getProcessName() }.getOrDefault(processName)
        val current = runtime
        if (current != null) {
            // A process may load multiple packages. Only the package which
            // created this generation is allowed to replace its ClassLoader.
            if (current.scopePackage == param.packageName) current.updateClassLoader(param.classLoader)
            Log.d(tag, "package ready package=${param.packageName} process=$processName activeScope=${current.scopePackage}")
            return
        }

        val scope = param.packageName
        val active = GenerationRuntime(this, processName, scope).also { runtime = it }
        try {
            loadScope(scope, param.classLoader, active)
        } catch (error: Throwable) {
            active.abortReplacement()
            if (runtime === active) runtime = null
            Log.e(tag, "initial hook load failed scope=$scope process=$processName", error)
            throw error
        }
        Log.d(tag, "package ready package=${param.packageName} process=$processName")
        Log.d(tag, "initial hooks loaded scope=$scope process=$processName")
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (apiVersion < XposedInterface.API_102) return false
        val oldRuntime = runtime ?: return false
        val saved = oldRuntime.prepareForReload() ?: return false
        val previousImageReader = PodImageLoader.remoteImageReader
        val previousModuleLogger = Log.module
        return runCatching {
            param.setSavedInstanceState(saved)
            PodImageLoader.remoteImageReader = null
            Log.module = null
            true
        }.getOrElse {
            oldRuntime.restoreAfterReloadRejection()
            PodImageLoader.remoteImageReader = previousImageReader
            Log.module = previousModuleLogger
            Log.e(tag, "failed to publish hot-reload state", it)
            false
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        if (apiVersion < XposedInterface.API_102) throw IllegalStateException("libxposed API 102 is required")
        val saved = param.savedInstanceState as? Bundle
            ?: throw IllegalStateException("missing classloader-neutral hot-reload state")
        if (saved.getInt(GenerationRuntime.KEY_SCHEMA, -1) != GenerationRuntime.STATE_SCHEMA) {
            throw IllegalStateException("unsupported hot-reload state schema")
        }
        val oldHandles = param.oldHookHandles
        val scope = saved.getString(GenerationRuntime.KEY_PRIMARY_PACKAGE)
            ?: inferScope(oldHandles)
            ?: throw IllegalStateException("cannot infer hot-reload scope")
        val anchorCandidates = oldHandles
            .mapNotNull { it.executable.declaringClass.classLoader }
            .groupingBy { it }
            .eachCount()
        val anchor = anchorCandidates.maxByOrNull { it.value }?.key
            ?: currentApplicationContext()?.classLoader
            ?: throw IllegalStateException("old hooks have no non-bootstrap classloader anchor and application is unavailable")
        Log.d(
            tag,
            "hot reload classloader anchor=${anchor.javaClass.name} " +
                "source=${if (anchorCandidates.isEmpty()) "application-fallback" else "old-hook-majority"}",
        )

        val next = GenerationRuntime(
            module = this,
            processName = param.processName,
            scopePackage = scope,
            oldHandles = oldHandles,
            reloadExtras = param.extras,
            savedState = saved,
        )
        runtime = next
        try {
            loadScope(scope, anchor, next)
            val dynamicClasses = saved.getStringArrayList(GenerationRuntime.KEY_DYNAMIC_BINDER_CLASSES).orEmpty()
            if (dynamicClasses.isNotEmpty()) restoreDynamicHooks(scope, dynamicClasses, next)
            next.hooks.removeObsoleteHooks()
            if (!next.finishReplacement()) throw IllegalStateException("not all required old hooks were restored")
            activateRuntimeAfterReload(scope, next, saved)
            Log.d(tag, "hot reload complete process=${param.processName} scope=$scope generation=${next.generationId}")
        } catch (error: Throwable) {
            next.abortReplacement()
            Log.e(tag, "hot reload failed process=${param.processName} scope=$scope", error)
            throw error
        }
    }

    private fun inferScope(handles: List<XposedInterface.HookHandle>): String? {
        val known = listOf(
            "com.android.bluetooth",
            "com.android.settings",
            "com.milink.service",
            "com.xiaomi.bluetooth",
            "com.sony.songpal.mdr",
        )
        return handles.asSequence()
            .mapNotNull { it.id }
            .firstNotNullOfOrNull { id -> known.firstOrNull { id.startsWith("sonypods.v1:$it:") } }
    }

    private fun loadScope(scope: String, classLoader: ClassLoader, active: GenerationRuntime) {
        when (scope) {
            "com.android.bluetooth" -> {
                loadHook(HeadsetStateDispatcher, classLoader, scope, active)
                loadHook(BluetoothUpstreamHeadsetHook(), classLoader, scope, active)
            }
            "com.android.settings" -> {
                loadHook(SettingsRenderHook(), classLoader, scope, active)
                loadHook(SettingsHeadsetHook, classLoader, scope, active)
            }
            "com.milink.service" -> loadHook(MiLinkServiceHook, classLoader, scope, active)
            "com.xiaomi.bluetooth" -> {
                loadHook(MiBluetoothToastHook, classLoader, scope, active)
                loadHook(BluetoothUpstreamHeadsetHook(), classLoader, scope, active)
            }
            "com.sony.songpal.mdr" -> loadHook(SoundConnectHandoverHook, classLoader, scope, active)
            else -> throw IllegalArgumentException("unsupported SonyPods scope: $scope")
        }
        active.commitHooks()
    }

    private fun restoreDynamicHooks(scope: String, classNames: List<String>, active: GenerationRuntime) {
        if (scope != "com.android.bluetooth" && scope != "com.xiaomi.bluetooth") return
        val upstream = activeContext(active, BluetoothUpstreamHeadsetHook::class.java)
            as? BluetoothUpstreamHeadsetHook ?: return
        upstream.restoreDynamicHookClasses(classNames)
    }

    private fun activateRuntimeAfterReload(scope: String, active: GenerationRuntime, saved: Bundle) {
        val context = currentApplicationContext()
            ?: throw IllegalStateException("target application context is unavailable after hot reload")
        when (scope) {
            "com.android.bluetooth" -> {
                val dispatcher = active.contexts().filterIsInstance<HeadsetStateDispatcher>().firstOrNull()
                    ?: throw IllegalStateException("bluetooth dispatcher was not rebuilt")
                dispatcher.startAfterReload(
                    context = context,
                    address = saved.getString(GenerationRuntime.KEY_DEVICE_ADDRESS),
                    name = saved.getString(GenerationRuntime.KEY_DEVICE_NAME),
                )
                active.contexts().filterIsInstance<BluetoothUpstreamHeadsetHook>().forEach { it.startAfterReload(context) }
            }
            "com.android.settings" ->
                active.contexts().filterIsInstance<SettingsHeadsetHook>().forEach { it.startAfterReload(context) }
            "com.milink.service" ->
                active.contexts().filterIsInstance<MiLinkServiceHook>().forEach { it.startAfterReload(context) }
            "com.xiaomi.bluetooth" -> {
                active.contexts().filterIsInstance<MiBluetoothToastHook>().forEach { it.startAfterReload(context) }
                // Same process also hosts the upstream headset hook, whose
                // ACTION_CONFIG_CHANGED receiver keeps the new generation's
                // ConfigManager live — without this, config changes made after a
                // hot reload (island mode/duration) never reach the island renderer.
                active.contexts().filterIsInstance<BluetoothUpstreamHeadsetHook>().forEach { it.startAfterReload(context) }
            }
            "com.sony.songpal.mdr" ->
                active.contexts().filterIsInstance<SoundConnectHandoverHook>().forEach {
                    val application = context.applicationContext as? android.app.Application
                        ?: throw IllegalStateException("Sound Connect application context is unavailable")
                    it.startAfterReload(application)
                }
        }
    }

    private fun currentApplicationContext(): android.content.Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? android.app.Application
    }.getOrNull() ?: runCatching {
        Class.forName("android.app.AppGlobals")
            .getDeclaredMethod("getInitialApplication")
            .apply { isAccessible = true }
            .invoke(null) as? android.app.Application
    }.getOrNull()

    private fun activeContext(runtime: GenerationRuntime, type: Class<*>): HookContext? =
        runtime.contexts().firstOrNull { type.isInstance(it) }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader, packageName: String, active: GenerationRuntime) {
        active.attach(hook)
        Log.module = this
        hook.module = this
        hook.appClassLoader = classLoader
        hook.packageName = packageName
        hook.prefs = getRemotePreferences("sonypods_settings")
        hook.prefsProvider = { getRemotePreferences("sonypods_settings") }
        val remoteReader: (String) -> ByteArray? = { name ->
            runCatching {
                openRemoteFile(name).use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                }
            }.getOrNull()
        }
        hook.remoteFileReader = remoteReader
        ConfigManager.init(hook.prefs)
        hook.onHook()
        PodImageLoader.remoteImageReader = { name ->
            runCatching {
                openRemoteFile(name).use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }
            }.getOrNull()
        }
    }
}
