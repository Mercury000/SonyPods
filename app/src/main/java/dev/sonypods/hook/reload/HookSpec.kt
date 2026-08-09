package dev.sonypods.hook.reload

import java.lang.reflect.Executable

/** Classloader-neutral description of one installed executable hook. */
data class HookSpec(
    val id: String,
    val scopePackage: String,
    val hookGroup: String,
    val logicalRole: String,
    val executableSignature: String,
    val required: Boolean = false,
)

internal fun executableSignature(executable: Executable): String {
    val owner = executable.declaringClass.name
    val name = executable.name
    val params = executable.parameterTypes.joinToString(",") { it.name }
    val returnType = (executable as? java.lang.reflect.Method)?.returnType?.name ?: "void"
    val kind = if (executable is java.lang.reflect.Constructor<*>) "ctor" else "method"
    return "$kind:$owner#$name($params):$returnType"
}
