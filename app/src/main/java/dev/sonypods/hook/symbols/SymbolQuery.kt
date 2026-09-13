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
    fun requireUnique(symbol: String, descriptors: Collection<String>): String
    override fun close() = Unit
}

internal class DexKitSymbolQuery(
    private val bundleId: String,
    override val bridge: DexKitBridge,
    private val diagnostics: SymbolDiagnosticSink,
    private val onClose: () -> Unit,
) : SymbolQuery {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun requireUnique(symbol: String, descriptors: Collection<String>): String {
        val normalized = descriptors.distinct().sorted()
        diagnostics.emit(SymbolDiagnostic(bundleId, "query", "$symbol candidates=${normalized.size}"))
        if (normalized.size != 1) {
            throw SymbolResolutionException(
                bundleId,
                "$symbol expected exactly one candidate, found ${normalized.size}: ${normalized.joinToString()}",
            )
        }
        return normalized.single()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

internal object DexKitNative {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary("dexkit")
        } catch (error: Throwable) {
            throw IllegalStateException("DexKit native library failed to load", error)
        }
        loaded = true
    }
}

fun interface SymbolQueryFactory {
    fun open(bundleId: String): SymbolQuery

    companion object {
        fun dexKit(classLoader: ClassLoader, diagnostics: SymbolDiagnosticSink): SymbolQueryFactory =
            DexKitQueryProvider(classLoader, diagnostics)
    }
}

/**
 * Process-scoped DexKit provider.
 *
 * The bridge parses the original APK dex elements (cookie-free path mode) once and serves
 * every symbol bundle from that single parse. Its lifetime is event-driven: the resolver
 * releases it when a scan batch completes, after a lone scan outside any batch, or on an
 * explicit dispose — no timers, no background scheduler.
 */
internal class DexKitQueryProvider(
    private val classLoader: ClassLoader,
    private val diagnostics: SymbolDiagnosticSink,
) : SymbolQueryFactory, AutoCloseable {
    private val lock = Any()
    private var bridge: DexKitBridge? = null
    private var inFlight = 0
    private var closePending = false

    override fun open(bundleId: String): SymbolQuery {
        DexKitNative.ensureLoaded()
        val active = synchronized(lock) {
            val current = bridge ?: DexKitBridge.create(classLoader, false).also {
                bridge = it
                diagnostics.emit(SymbolDiagnostic(bundleId, "dexkit", "bridge opened (apk path mode)"))
            }
            inFlight++
            closePending = false
            current
        }
        return DexKitSymbolQuery(bundleId, active, diagnostics) { releaseQuery() }
    }

    private fun releaseQuery() {
        val released = synchronized(lock) {
            inFlight--
            if (closePending && inFlight == 0) {
                closePending = false
                bridge.also { bridge = null }
            } else {
                null
            }
        }
        released?.let(::releaseBridge)
    }

    private fun releaseBridge(released: DexKitBridge) {
        val outcome = runCatching { released.close() }
        outcome.onFailure {
            diagnostics.emit(SymbolDiagnostic("dexkit", "bridge", "release failed: ${it.message}"))
        }
        if (outcome.isSuccess) {
            diagnostics.emit(SymbolDiagnostic("dexkit", "bridge", "released"))
        }
    }

    override fun close() {
        val released = synchronized(lock) {
            if (inFlight > 0) {
                closePending = true
                null
            } else {
                bridge.also { bridge = null }
            }
        }
        released?.let(::releaseBridge)
    }
}

class SymbolResolutionException(
    val bundleId: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException("symbol bundle '$bundleId': $message", cause)
