package dev.sonypods.data

import dev.sonypods.headphones.HeadphoneCommand
import dev.sonypods.protocol.ParsedTandemResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The capability exchange, run the way Sound Connect runs it.
 *
 * SC's initializer (`uv.d$c` V1 / `wv.e$e` V2) is a `Callable` on its own executor. Every step
 * goes through one primitive — `wv.e$e.P(msg, replyClass, predicate)` → `wv.e$c.f(...)`, which
 * `throws InterruptedException` — that sends one request and blocks on a `CountDownLatch` until
 * the reply matching `predicate` arrives. The whole sequence sits inside a single `try`:
 *
 * ```smali
 * :try_end_6b8
 *   .catch InterruptedException      → :catch_6fc   # "Initialization interrupted"
 *   .catch CancellationException     → :catch_6fc
 *   .catch IOException               → :catch_6fc
 *   .catchall                        → :catchall_1b
 * invoke-virtual {v0, v1, v2}, Lwv/a;->b(IZ)      # build the tableset
 * invoke-virtual {v1}, Lcapabilitystore/d;->h()V  # saveIntoStorage
 * const-string v1, "Initialize Completed!"
 * ```
 *
 * So the row on disk is a whole exchange by construction: a step that never answers throws out
 * of the sequence and `saveIntoStorage` is simply never reached. That is the guarantee our
 * event-driven probe never had — it fired every domain query and let a UI idle timer decide the
 * exchange was "done", and because that timer's wait set is derived from the capability table
 * itself, a table missing a domain never waited for it and was persisted as if complete.
 *
 * The initializer owns a thread of its own, never a `com.android.bluetooth` one, so the
 * interrupt SC relies on for cancellation stays inside the module.
 */
class TandemCapabilityInitializer(
    private val send: (HeadphoneCommand) -> Unit,
    private val log: (String) -> Unit,
) {
    /** What the sequence is currently blocked on, or null between steps. */
    private val awaiting = AtomicReference<Awaiting?>(null)

    @Volatile
    private var worker: Thread? = null

    private class Awaiting(
        val label: String,
        val accepts: (ParsedTandemResponse) -> Boolean,
    ) {
        val latch = CountDownLatch(1)
        @Volatile var reply: ParsedTandemResponse? = null
    }

    /** Raised by [sendAndAwait] when a step goes unanswered; ends the sequence like SC's throw. */
    class ExchangeFailed(message: String) : Exception(message)

    /**
     * Offer an inbound frame to the step the sequence is blocked on.
     *
     * Returns true when it satisfied that step. The caller keeps handling the frame either way —
     * SC's dispatcher does the same, the predicate only releases the latch.
     */
    fun offer(parsed: ParsedTandemResponse): Boolean {
        val pending = awaiting.get() ?: return false
        if (!pending.accepts(parsed)) return false
        pending.reply = parsed
        pending.latch.countDown()
        return true
    }

    /**
     * SC's `P(msg, replyClass, predicate)`: send one request, block until the reply the predicate
     * accepts arrives, and hand it back. A step that does not answer within [timeoutMs] throws,
     * which is what keeps a partial exchange from ever reaching the save.
     */
    @Throws(ExchangeFailed::class, InterruptedException::class)
    fun sendAndAwait(
        command: HeadphoneCommand,
        timeoutMs: Long,
        accepts: (ParsedTandemResponse) -> Boolean,
    ): ParsedTandemResponse {
        val pending = Awaiting(command.label, accepts)
        awaiting.set(pending)
        try {
            send(command)
            if (!pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw ExchangeFailed("${command.label} went unanswered after ${timeoutMs}ms")
            }
            return pending.reply ?: throw ExchangeFailed("${command.label} produced no reply")
        } finally {
            awaiting.compareAndSet(pending, null)
        }
    }

    /**
     * Run [sequence] on the initializer thread, then [onComplete] — and only then. Any throw
     * (a step that went unanswered, an interrupt from [cancel], anything else) skips it, exactly
     * as SC's catch skips `saveIntoStorage`.
     */
    fun start(name: String, sequence: () -> Unit, onComplete: () -> Unit) {
        cancel()
        val thread = Thread({
            try {
                sequence()
                onComplete()
                log("Initialize Completed!")
            } catch (interrupted: InterruptedException) {
                log("Initialization interrupted")
            } catch (failed: ExchangeFailed) {
                log("Initialization incomplete: ${failed.message}; nothing persisted")
            } catch (t: Throwable) {
                // A module thread must never let a Throwable escape into the Bluetooth process.
                log("Initialization failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                awaiting.set(null)
            }
        }, name)
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    /** Cancel a running sequence. Interrupts only the initializer's own thread. */
    fun cancel() {
        awaiting.getAndSet(null)?.latch?.countDown()
        worker?.let { if (it.isAlive) runCatching { it.interrupt() } }
        worker = null
    }

    val running: Boolean
        get() = worker?.isAlive == true
}
