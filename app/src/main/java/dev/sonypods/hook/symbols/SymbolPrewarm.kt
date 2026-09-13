package dev.sonypods.hook.symbols

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background pre-warm of one process's declared symbol bundles.
 *
 * Started at package load, where the exposed default ClassLoader is not yet guaranteed to be
 * the final application ClassLoader. The resolver is handed over to the generation only when
 * the final loader is the same instance; otherwise the batch is cancelled and the runtime
 * runs a fresh batch once the final loader is known.
 */
internal class SymbolPrewarm(
    val packageName: String,
    private val defaultClassLoader: ClassLoader,
    private val definitions: List<SymbolBundleDefinition>,
    private val log: (String, Throwable?) -> Unit,
    private val resolverFactory: (String, ClassLoader) -> TargetSymbolResolver,
) {
    private val lock = Any()
    private val cancelled = AtomicBoolean(false)
    private var resolver: TargetSymbolResolver? = null
    private var worker: Thread? = null
    private var handedOver = false
    private var discarded = false
    private var started = false

    fun start() {
        val target = synchronized(lock) {
            if (started || discarded) return
            started = true
            resolver ?: resolverFactory(packageName, defaultClassLoader).also { resolver = it }
        }
        val thread = Thread({ runBatch(target) }, "SonyPods-Prewarm").apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    private fun runBatch(target: TargetSymbolResolver) {
        try {
            val count = target.preload(definitions) { cancelled.get() }
            log("symbol prewarm finished package=$packageName resolved=$count/${definitions.size}", null)
        } catch (error: Throwable) {
            log("symbol prewarm failed package=$packageName", error)
        }
        // The resolver stays alive after the batch: it is either handed over at package ready
        // or disposed by close(). Disposing here would discard results whenever the batch wins
        // the race against onPackageReady().
    }

    /** Hands the pre-warmed resolver over when the final loader is the pre-warmed instance. */
    fun takeResolverIfLoader(loader: ClassLoader): TargetSymbolResolver? = synchronized(lock) {
        if (handedOver || discarded || loader !== defaultClassLoader) return null
        val current = resolver ?: return null
        handedOver = true
        current
    }

    /** Disposes a batch which was never handed over; an owned resolver is left untouched. */
    fun close() {
        cancelled.set(true)
        val current = synchronized(lock) {
            if (handedOver || discarded) null else { discarded = true; resolver }
        }
        current?.dispose()
    }

    /** Waits for the pre-warm batch to end. Used by tests and reload preparation. */
    internal fun awaitBatch(timeoutMillis: Long = 5_000L): Boolean {
        val thread = worker ?: return true
        thread.join(timeoutMillis)
        return !thread.isAlive
    }
}
