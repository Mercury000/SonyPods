package dev.sonypods.hook.symbols

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

enum class SymbolKind { CLASS, METHOD, FIELD }

/** A cache-safe DEX descriptor. Runtime reflection objects never cross generations or processes. */
data class SymbolReference(val kind: SymbolKind, val descriptor: String) {
    init {
        require(SymbolDescriptor.isValid(kind, descriptor)) {
            "invalid ${kind.name.lowercase()} descriptor: $descriptor"
        }
    }

    fun resolveClass(classLoader: ClassLoader): Class<*> {
        require(kind == SymbolKind.CLASS) { "symbol is not a class: $descriptor" }
        return DexClass(descriptor).getInstance(classLoader)
    }

    fun resolveMethod(classLoader: ClassLoader): Method {
        require(kind == SymbolKind.METHOD) { "symbol is not a method: $descriptor" }
        return DexMethod(descriptor).getMethodInstance(classLoader).apply { isAccessible = true }
    }

    fun resolveConstructor(classLoader: ClassLoader): Constructor<*> {
        require(kind == SymbolKind.METHOD) { "symbol is not a constructor: $descriptor" }
        return DexMethod(descriptor).getConstructorInstance(classLoader).apply { isAccessible = true }
    }

    fun resolveField(classLoader: ClassLoader): Field {
        require(kind == SymbolKind.FIELD) { "symbol is not a field: $descriptor" }
        return DexField(descriptor).getFieldInstance(classLoader).apply { isAccessible = true }
    }
}

/** Strict enough to reject corrupt cache entries without loading any target class. */
object SymbolDescriptor {
    private val classDescriptor = Regex("^\\[*L[^;]+;$")
    private val primitiveOrClass = Regex("^(?:\\[*[ZBCSIFJDV]|\\[*L[^;]+;)$")
    private val methodDescriptor = Regex("^(L[^;]+;)->[^()]+\\((.*)\\)(.+)$")
    private val fieldDescriptor = Regex("^(L[^;]+;)->[^:]+:(.+)$")

    fun isValid(kind: SymbolKind, descriptor: String): Boolean = when (kind) {
        SymbolKind.CLASS -> classDescriptor.matches(descriptor)
        SymbolKind.METHOD -> methodDescriptor.matchEntire(descriptor)?.let { match ->
            validParameterList(match.groupValues[2]) &&
                primitiveOrClass.matches(match.groupValues[3])
        } == true
        SymbolKind.FIELD -> fieldDescriptor.matchEntire(descriptor)?.let {
            primitiveOrClass.matches(it.groupValues[2]) && it.groupValues[2] != "V"
        } == true
    }

    private fun validParameterList(parameters: String): Boolean {
        var index = 0
        while (index < parameters.length) {
            while (index < parameters.length && parameters[index] == '[') index++
            if (index >= parameters.length) return false
            when (parameters[index]) {
                'Z', 'B', 'C', 'S', 'I', 'F', 'J', 'D' -> index++
                'L' -> {
                    val end = parameters.indexOf(';', index)
                    if (end < 0 || end == index + 1) return false
                    index = end + 1
                }
                else -> return false
            }
        }
        return true
    }
}