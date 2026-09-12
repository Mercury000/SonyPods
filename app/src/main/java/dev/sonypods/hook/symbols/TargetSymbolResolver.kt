package dev.sonypods.hook.symbols

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/** One independently versioned, all-or-nothing group of target symbols. */
interface SymbolBundleDefinition {
    val id: String
    val schemaVersion: Int
    val requiredSymbols: Set<String>

    /** Required multi-value symbol families; at least one key with each prefix must resolve. */
    val requiredPrefixes: Set<String> get() = emptySet()

    /** Cross-symbol semantic checks which descriptors alone cannot express. */
    fun validate(symbols: Map<String, SymbolReference>) = Unit
}

/** A bundle whose descriptors are discovered from DEX and may be cached. */
interface DexKitSymbolBundleDefinition : SymbolBundleDefinition {
    /** Called only after a cache miss or failed cache validation. */
    fun resolve(query: SymbolQuery): Map<String, SymbolReference>
}

/** A bundle backed by a stable ABI whose exact descriptors are fixed in code. */
interface FixedSymbolBundleDefinition : SymbolBundleDefinition {
    val symbols: Map<String, SymbolReference>
}

class ResolvedSymbolBundle internal constructor(
    val id: String,
    val fromCache: Boolean,
    private val symbols: Map<String, SymbolReference>,
    private val classLoader: ClassLoader,
) {
    val keys: Set<String> get() = symbols.keys
    operator fun get(name: String): SymbolReference =
        symbols[name] ?: throw NoSuchElementException("$id has no symbol '$name'")

    fun clazz(name: String): Class<*> = get(name).resolveClass(classLoader)
    fun method(name: String): Method = get(name).resolveMethod(classLoader)
    fun constructor(name: String): Constructor<*> = get(name).resolveConstructor(classLoader)
    fun field(name: String): Field = get(name).resolveField(classLoader)
    fun descriptors(): Map<String, String> = symbols.mapValues { it.value.descriptor }
    fun descriptorsWithPrefix(prefix: String): List<String> =
        symbols.filterKeys { it.startsWith(prefix) }.values.sortedBy { it.descriptor }.map { it.descriptor }
    fun fieldsWithPrefix(prefix: String): List<Field> =
        symbols.filterKeys { it.startsWith(prefix) }.values.sortedBy { it.descriptor }.map { it.resolveField(classLoader) }
}

/**
 * Central cache/scan/validation boundary. It never falls back to another locator: an ambiguous,
 * incomplete, or unloadable bundle fails as a unit before any consumer installs hooks.
 */
class TargetSymbolResolver(
    private val target: TargetArtifact,
    private val classLoader: ClassLoader,
    private val cache: SymbolCache,
    private val queryFactory: SymbolQueryFactory,
    private val diagnostics: SymbolDiagnosticSink = SymbolDiagnosticSink {},
) {
    private val resolved = linkedMapOf<String, ResolvedSymbolBundle>()

    @Synchronized
    fun resolve(definition: SymbolBundleDefinition): ResolvedSymbolBundle {
        resolved[definition.id]?.let { return it }
        validateDefinition(definition)

        val bundle = when (definition) {
            is FixedSymbolBundleDefinition -> resolveFixed(definition)
            is DexKitSymbolBundleDefinition -> resolveWithDexKit(definition)
            else -> throw SymbolResolutionException(
                definition.id,
                "unsupported symbol bundle type: ${definition.javaClass.name}",
            )
        }
        resolved[definition.id] = bundle
        return bundle
    }

    private fun resolveFixed(definition: FixedSymbolBundleDefinition): ResolvedSymbolBundle {
        diagnostics.emit(SymbolDiagnostic(definition.id, "fixed", "resolved=${definition.symbols.size}"))
        return materialize(definition, definition.symbols, fromCache = false)
    }

    private fun resolveWithDexKit(definition: DexKitSymbolBundleDefinition): ResolvedSymbolBundle {
        loadCache(definition)?.let { references ->
            return materialize(definition, references, fromCache = true)
        }

        diagnostics.emit(SymbolDiagnostic(definition.id, "scan", "cache miss; opening DexKit"))
        val references = try {
            queryFactory.open(definition.id).use { query -> definition.resolve(query) }
        } catch (error: SymbolResolutionException) {
            throw error
        } catch (error: Throwable) {
            throw SymbolResolutionException(definition.id, "DEX query failed: ${error.message}", error)
        }
        val bundle = materialize(definition, references, fromCache = false)
        cache.write(
            CachedSymbolBundle(
                bundleId = definition.id,
                schemaVersion = definition.schemaVersion,
                targetFingerprint = target.fingerprint,
                symbols = references.mapValues { CachedSymbolReference.from(it.value) },
            ),
        )
        diagnostics.emit(
            SymbolDiagnostic(definition.id, "scan", "resolved=${references.size} cache=written"),
        )
        return bundle
    }

    @Synchronized
    fun clearMemory() = resolved.clear()

    private fun loadCache(definition: DexKitSymbolBundleDefinition): Map<String, SymbolReference>? {
        val cached = cache.read(definition.id) ?: return null
        if (cached.formatVersion != CachedSymbolBundle.CACHE_FORMAT_VERSION ||
            cached.schemaVersion != definition.schemaVersion ||
            cached.targetFingerprint != target.fingerprint
        ) {
            diagnostics.emit(SymbolDiagnostic(definition.id, "cache", "stale cache rejected"))
            cache.remove(definition.id)
            return null
        }
        val references = runCatching { cached.symbols.mapValues { it.value.toReference() } }
            .getOrElse {
                diagnostics.emit(SymbolDiagnostic(definition.id, "cache", "corrupt cache rejected: ${it.message}"))
                cache.remove(definition.id)
                return null
            }
        return runCatching {
            validateReferences(definition, references)
            validateLoadable(references)
            definition.validate(references)
            diagnostics.emit(SymbolDiagnostic(definition.id, "cache", "hit symbols=${references.size}"))
            references
        }.getOrElse {
            diagnostics.emit(SymbolDiagnostic(definition.id, "cache", "cache validation failed: ${it.message}"))
            cache.remove(definition.id)
            null
        }
    }

    private fun materialize(
        definition: SymbolBundleDefinition,
        references: Map<String, SymbolReference>,
        fromCache: Boolean,
    ): ResolvedSymbolBundle {
        try {
            validateReferences(definition, references)
            validateLoadable(references)
            definition.validate(references)
        } catch (error: Throwable) {
            throw SymbolResolutionException(definition.id, "validation failed: ${error.message}", error)
        }
        return ResolvedSymbolBundle(definition.id, fromCache, references.toMap(), classLoader)
    }

    private fun validateDefinition(definition: SymbolBundleDefinition) {
        require(definition.id.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe symbol bundle id" }
        require(definition.schemaVersion > 0) { "symbol schema version must be positive" }
        require(definition.requiredSymbols.isNotEmpty()) { "symbol bundle must require at least one symbol" }
        require(definition.requiredSymbols.none(String::isBlank)) { "blank required symbol key" }
        require(definition.requiredPrefixes.all { it.isNotBlank() && it.endsWith(".") }) {
            "required symbol prefixes must be non-blank and end with '.'"
        }
    }

    private fun validateReferences(
        definition: SymbolBundleDefinition,
        references: Map<String, SymbolReference>,
    ) {
        val missing = definition.requiredSymbols - references.keys
        val missingGroups = definition.requiredPrefixes.filter { prefix ->
            references.keys.none { it.startsWith(prefix) }
        }
        val unexpected = references.keys.filterNot { key ->
            key in definition.requiredSymbols || definition.requiredPrefixes.any { key.startsWith(it) }
        }
        require(missing.isEmpty()) { "missing required symbols: ${missing.sorted().joinToString()}" }
        require(missingGroups.isEmpty()) { "missing required symbol groups: ${missingGroups.sorted().joinToString()}" }
        require(unexpected.isEmpty()) { "unexpected symbols: ${unexpected.sorted().joinToString()}" }
    }

    private fun validateLoadable(references: Map<String, SymbolReference>) {
        references.forEach { (name, reference) ->
            runCatching {
                when (reference.kind) {
                    SymbolKind.CLASS -> reference.resolveClass(classLoader)
                    SymbolKind.METHOD -> {
                        val method = org.luckypray.dexkit.wrap.DexMethod(reference.descriptor)
                        if (method.isConstructor) reference.resolveConstructor(classLoader)
                        else reference.resolveMethod(classLoader)
                    }
                    SymbolKind.FIELD -> reference.resolveField(classLoader)
                }
            }.getOrElse { throw IllegalStateException("$name is not loadable (${reference.descriptor})", it) }
        }
    }
}