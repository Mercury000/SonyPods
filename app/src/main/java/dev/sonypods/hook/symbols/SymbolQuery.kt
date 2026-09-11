package dev.sonypods.hook.symbols

import org.luckypray.dexkit.DexKitBridge

/** Diagnostic event emitted during cache validation and DEX resolution. */
data class SymbolDiagnostic(
    val bundleId: String,
    val phase: String,
    val message: String,
)

fun interface SymbolDiagnosticSink {
    fun emit(event: SymbolDiagnostic)
}

interface SymbolQuery : AutoCloseable {
    val bridge: DexKitBridge?
    fun candidates(symbol: String, descriptors: Collection<String>): List<String>
    fun requireUnique(symbol: String, descriptors: Collection<String>): String
}

internal class DexKitSymbolQuery(
    private val bundleId: String,
    override val bridge: DexKitBridge,
    private val diagnostics: SymbolDiagnosticSink,
) : SymbolQuery {
    override fun candidates(symbol: String, descriptors: Collection<String>): List<String> {
        val normalized = descriptors.distinct().sorted()
        diagnostics.emit(SymbolDiagnostic(bundleId, "query", "$symbol candidates=${normalized.size}"))
        return normalized
    }

    override fun requireUnique(symbol: String, descriptors: Collection<String>): String {
        val normalized = candidates(symbol, descriptors)
        if (normalized.size != 1) {
            throw SymbolResolutionException(
                bundleId,
                "$symbol expected exactly one candidate, found ${normalized.size}: ${normalized.joinToString()}",
            )
        }
        return normalized.single()
    }

    override fun close() = bridge.close()
}

internal object DexKitNative {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("dexkit")
        loaded = true
    }
}

fun interface SymbolQueryFactory {
    fun open(bundleId: String): SymbolQuery

    companion object {
        fun dexKit(classLoader: ClassLoader, diagnostics: SymbolDiagnosticSink): SymbolQueryFactory =
            SymbolQueryFactory { bundleId ->
                DexKitNative.ensureLoaded()
                DexKitSymbolQuery(
                    bundleId,
                    DexKitBridge.create(classLoader, true),
                    diagnostics,
                )
            }
    }
}

class SymbolResolutionException(
    val bundleId: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException("symbol bundle '$bundleId': $message", cause)