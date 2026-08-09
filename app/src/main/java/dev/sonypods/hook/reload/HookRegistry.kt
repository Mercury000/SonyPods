package dev.sonypods.hook.reload

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * The only module-side gateway to libxposed hook installation.
 *
 * In a new generation an existing stable ID is replaced through its old handle;
 * only genuinely new IDs call module.hook(). This is the API 102 replacement
 * contract and also gives us an auditable list of hooks that were not restored.
 */
class HookRegistry(
    private val module: XposedModule,
    private val scopePackage: String,
    oldHandles: List<XposedInterface.HookHandle> = emptyList(),
) {
    private val oldById = linkedMapOf<String, XposedInterface.HookHandle>()
    private val installed = linkedMapOf<String, XposedInterface.HookHandle>()
    private val specs = linkedMapOf<String, HookSpec>()
    private val dynamicTargets = linkedSetOf<String>()
    private val pending = mutableListOf<PendingHook>()
    private var committed = false

    init {
        oldHandles.forEach { handle ->
            val id = handle.id ?: error("old hook has no stable id: ${handle.executable}")
            check(id !in oldById) { "duplicate old hook id: $id" }
            oldById[id] = handle
        }
    }

    fun stableId(executable: Executable, kind: String, logicalRole: String? = null): String {
        val owner = executable.declaringClass.name
        val role = logicalRole?.takeIf { it.isNotBlank() } ?: executableSignature(executable)
        return "sonypods.v1:$scopePackage:${owner}:$kind:$role"
    }

    fun install(
        executable: Executable,
        kind: String,
        logicalRole: String? = null,
        required: Boolean = false,
        hooker: XposedInterface.Hooker,
    ) {
        val id = stableId(executable, kind, logicalRole)
        check(id !in specs) { "duplicate hook spec: $id" }
        specs[id] = HookSpec(
            id = id,
            scopePackage = scopePackage,
            hookGroup = executable.declaringClass.name,
            logicalRole = logicalRole ?: executableSignature(executable),
            executableSignature = executableSignature(executable),
            required = required,
        )
        if (!committed) {
            pending += PendingHook(id, executable, hooker)
            return
        }
        installNow(id, executable, hooker)
    }

    /** Commits all preloaded hooks in deterministic order after the complete plan is known. */
    fun commit() {
        if (committed) return
        committed = true
        val plan = pending.sortedBy { it.id }.toList()
        pending.clear()
        try {
            plan.forEach { installNow(it.id, it.executable, it.hooker) }
        } catch (error: Throwable) {
            abort()
            throw error
        }
    }

    private fun installNow(id: String, executable: Executable, hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
        // Keep the old handle indexed until replaceHook() has returned. If the
        // framework rejects the replacement, the old handle is still the only
        // valid handle and must remain observable for fail-closed diagnostics.
        val old = oldById[id]
        val handle = if (old != null) {
            old.replaceHook(hooker)
        } else {
            module.hook(executable)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker)
        }
        if (old != null) oldById.remove(id)
        installed[id] = handle
        return handle
    }

    /** Removes hooks that disappeared from the current generation. */
    fun removeObsoleteHooks() {
        val obsolete = oldById.filterKeys { it !in specs }
        obsolete.forEach { (id, handle) ->
            handle.unhook()
            oldById.remove(id)
        }
    }

    fun abort() {
        pending.clear()
        installed.values.forEach { runCatching { it.unhook() } }
        installed.clear()
    }

    /**
     * Returns old handles which were neither atomically replaced nor explicitly
     * removed. This check must happen after the obsolete set has been decided;
     * clearing the entire old-handle map before checking makes the check vacuous.
     */
    fun unresolvedOldHookIds(): List<String> = oldById.keys.toList()
    fun handles(): List<XposedInterface.HookHandle> = installed.values.toList()
    fun specs(): List<HookSpec> = specs.values.toList()
    fun registerDynamicTarget(className: String) {
        if (className.isNotBlank()) dynamicTargets += className
    }
    fun dynamicTargets(): List<String> = dynamicTargets.toList()

    private data class PendingHook(
        val id: String,
        val executable: Executable,
        val hooker: XposedInterface.Hooker,
    )

}
