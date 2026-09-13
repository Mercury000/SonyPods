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
    private val onFirstResolve: (String, SymbolReference) -> Unit = { _, _ -> },
) {
    private val loadChecked = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun ensureLoadChecked(name: String, reference: SymbolReference) {
        if (loadChecked.putIfAbsent(name, java.lang.Boolean.TRUE) != null) return
        try {
            onFirstResolve(name, reference)
        } catch (error: Throwable) {
            loadChecked.remove(name)
            throw error
        }
    }

    val keys: Set<String> get() = symbols.keys
    internal fun references(): Map<String, SymbolReference> = symbols
    operator fun get(name: String): SymbolReference =
        (symbols[name] ?: throw NoSuchElementException("$id has no symbol '$name'"))
            .also { ensureLoadChecked(name, it) }

    fun clazz(name: String): Class<*> = get(name).resolveClass(classLoader)
    fun method(name: String): Method = get(name).resolveMethod(classLoader)
    fun constructor(name: String): Constructor<*> = get(name).resolveConstructor(classLoader)
    fun field(name: String): Field = get(name).resolveField(classLoader)
    fun descriptors(): Map<String, String> = symbols.mapValues { it.value.descriptor }
    fun descriptorsWithPrefix(prefix: String): List<String> =
        symbols.filterKeys { it.startsWith(prefix) }.values.sortedBy { it.descriptor }.map { it.descriptor }
    fun methodsWithPrefix(prefix: String): List<Method> =
        symbols.filterKeys { it.startsWith(prefix) }.entries.sortedBy { it.value.descriptor }
            .onEach { (name, reference) -> ensureLoadChecked(name, reference) }
            .map { it.value.resolveMethod(classLoader) }
    fun constructorsWithPrefix(prefix: String): List<Constructor<*>> =
        symbols.filterKeys { it.startsWith(prefix) }.entries.sortedBy { it.value.descriptor }
            .onEach { (name, reference) -> ensureLoadChecked(name, reference) }
            .map { it.value.resolveConstructor(classLoader) }
    fun fieldsWithPrefix(prefix: String): List<Field> =
        symbols.filterKeys { it.startsWith(prefix) }.entries.sortedBy { it.value.descriptor }
            .onEach { (name, reference) -> ensureLoadChecked(name, reference) }
            .map { it.value.resolveField(classLoader) }
}

/**
 * Central cache/scan/validation boundary. It never falls back to another locator: an ambiguous,
 * incomplete, or unloadable bundle fails as a unit before any consumer installs hooks.
 */
class TargetSymbolResolver(
    private var target: TargetArtifact,
    private val classLoader: ClassLoader,
    private var cache: SymbolCache,
    private val queryFactory: SymbolQueryFactory,
    private val diagnostics: SymbolDiagnosticSink = SymbolDiagnosticSink {},
    private val scanCoordinator: SymbolScanCoordinator = ProcessSymbolScanCoordinator,
) {
    private val resolved = linkedMapOf<String, ResolvedSymbolBundle>()
    private val schemas = HashMap<String, Int>()
    private val persisted = HashSet<String>()
    private var persistentCache = false
    private val batchLock = Any()
    private var scanBatchDepth = 0

    @Synchronized
    fun resolve(definition: SymbolBundleDefinition): ResolvedSymbolBundle {
        resolved[definition.id]?.let { bundle ->
            // Pre-warmed bundles may predate the persistent cache; write them on demand for
            // the rare upgrade ordering where attachPersistentCache ran after resolution.
            persistIfNeeded(definition, bundle)
            return bundle
        }
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
        if (definition is DexKitSymbolBundleDefinition) {
            schemas[definition.id] = definition.schemaVersion
        }
        return bundle
    }

    /**
     * Resolves a whole declared batch in one scanner session and releases the DexKit bridge
     * when the batch ends. Failures are diagnostics, never exceptions: a conditional bundle
     * missing from one target must not abort the remaining pre-warm.
     */
    @Synchronized
    fun preload(
        definitions: Collection<SymbolBundleDefinition>,
        cancelled: () -> Boolean = { false },
    ): Int {
        if (definitions.isEmpty()) return 0
        synchronized(batchLock) { scanBatchDepth++ }
        try {
            var count = 0
            for (definition in definitions) {
                if (cancelled()) break
                runCatching { resolve(definition) }
                    .onSuccess { count++ }
                    .onFailure {
                        diagnostics.emit(
                            SymbolDiagnostic(definition.id, "prewarm", "skipped: ${it.message}"),
                        )
                    }
            }
            return count
        } finally {
            val batchFinished = synchronized(batchLock) {
                scanBatchDepth--
                scanBatchDepth == 0
            }
            if (batchFinished) releaseScanner()
        }
    }

    /** Releases the DexKit scanner while keeping every resolved descriptor in memory. */
    @Synchronized
    fun releaseScanner() {
        val closable = queryFactory as? AutoCloseable ?: return
        runCatching { closable.close() }
            .onFailure { diagnostics.emit(SymbolDiagnostic("dexkit", "scanner", "release failed: ${it.message}")) }
    }

    /** Drops the scanner and every in-memory bundle; used when a generation is torn down. */
    @Synchronized
    fun dispose() {
        resolved.clear()
        schemas.clear()
        persisted.clear()
        releaseScanner()
    }

    /**
     * Swaps the memory cache for the real artifact store once the application Context exists.
     * The resolver instance survives, so hooks never need re-attaching; every bundle resolved
     * before the upgrade is written to the new store immediately.
     */
    @Synchronized
    fun attachPersistentCache(target: TargetArtifact, cache: SymbolCache) {
        this.target = target
        this.cache = cache
        persistentCache = true
        persisted.clear()
        resolved.forEach { (bundleId, bundle) ->
            val schema = schemas[bundleId] ?: return@forEach
            writeCache(bundleId, schema, bundle)
        }
        diagnostics.emit(SymbolDiagnostic("dexkit", "cache", "persistent symbol cache attached"))
    }

    private fun resolveFixed(definition: FixedSymbolBundleDefinition): ResolvedSymbolBundle {
        diagnostics.emit(SymbolDiagnostic(definition.id, "fixed", "resolved=${definition.symbols.size}"))
        return materialize(definition, definition.symbols, fromCache = false)
    }

    private fun resolveWithDexKit(definition: DexKitSymbolBundleDefinition): ResolvedSymbolBundle {
        loadCache(definition, removeInvalid = false)?.let { references ->
            return materialize(definition, references, fromCache = true)
        }

        return scanCoordinator.withScanLock(scanKey(definition)) {
            // Another process may have populated the descriptor cache while this process waited.
            loadCache(definition, removeInvalid = true)?.let { references ->
                return@withScanLock materialize(definition, references, fromCache = true)
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
            writeCache(definition.id, definition.schemaVersion, bundle)
            diagnostics.emit(
                SymbolDiagnostic(definition.id, "scan", "resolved=${references.size} cache=written"),
            )
            releaseScannerAfterSingleScan()
            bundle
        }
    }

    // Match the physical cache identity: fingerprints and schemas share the same bundle JSON file.
    private fun scanKey(definition: DexKitSymbolBundleDefinition): String =
        "${target.packageName}|${definition.id}"

    @Synchronized
    fun clearMemory() = resolved.clear()

    private fun releaseScannerAfterSingleScan() {
        val outsideBatch = synchronized(batchLock) { scanBatchDepth == 0 }
        if (outsideBatch) releaseScanner()
    }

    private fun persistIfNeeded(definition: SymbolBundleDefinition, bundle: ResolvedSymbolBundle) {
        if (definition !is DexKitSymbolBundleDefinition || !persistentCache) return
        writeCache(definition.id, definition.schemaVersion, bundle)
    }

    private fun writeCache(bundleId: String, schemaVersion: Int, bundle: ResolvedSymbolBundle) {
        if (!persisted.add(bundleId)) return
        cache.write(
            CachedSymbolBundle(
                bundleId = bundleId,
                schemaVersion = schemaVersion,
                targetFingerprint = target.fingerprint,
                symbols = bundle.references().mapValues { CachedSymbolReference.from(it.value) },
            ),
        )
    }

    private fun loadCache(
        definition: DexKitSymbolBundleDefinition,
        removeInvalid: Boolean = true,
    ): Map<String, SymbolReference>? {
        val cached = cache.read(definition.id) ?: return null
        if (cached.formatVersion != CachedSymbolBundle.CACHE_FORMAT_VERSION ||
            cached.schemaVersion != definition.schemaVersion ||
            cached.targetFingerprint != target.fingerprint
        ) {
            diagnostics.emit(SymbolDiagnostic(definition.id, "cache", "stale cache rejected"))
            if (removeInvalid) cache.remove(definition.id)
            return null
        }
        val references = runCatching { cached.symbols.mapValues { it.value.toReference() } }
            .getOrElse {
                diagnostics.emit(SymbolDiagnostic(definition.id, "cache", "corrupt cache rejected: ${it.message}"))
                if (removeInvalid) cache.remove(definition.id)
                return null
            }
        return runCatching {
            validateReferences(definition, references)
            validateLoadable(requiredReferences(definition, references))
            definition.validate(references)
            diagnostics.emit(SymbolDiagnostic(definition.id, "cache", "hit symbols=${references.size}"))
            references
        }.getOrElse {
            diagnostics.emit(SymbolDiagnostic(definition.id, "cache", "cache validation failed: ${it.message}"))
            if (removeInvalid) cache.remove(definition.id)
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
            validateLoadable(requiredReferences(definition, references))
            definition.validate(references)
        } catch (error: Throwable) {
            throw SymbolResolutionException(definition.id, "validation failed: ${error.message}", error)
        }
        return ResolvedSymbolBundle(
            definition.id,
            fromCache,
            references.toMap(),
            classLoader,
            ::validateLoadableReference,
        )
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

    private fun requiredReferences(
        definition: SymbolBundleDefinition,
        references: Map<String, SymbolReference>,
    ): Map<String, SymbolReference> = references.filterKeys { it in definition.requiredSymbols }

    private fun validateLoadable(references: Map<String, SymbolReference>) {
        references.forEach { (name, reference) -> validateLoadableReference(name, reference) }
    }

    private fun validateLoadableReference(name: String, reference: SymbolReference) {
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
