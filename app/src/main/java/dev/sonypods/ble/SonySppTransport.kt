package dev.sonypods.ble

import android.bluetooth.BluetoothSocket
import dev.sonypods.protocol.hexString
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class SonySppTransport(
    private val socket: BluetoothSocket,
    private val onPayload: (ByteArray) -> Unit,
    private val onClosed: (String?) -> Unit,
    private val log: (String) -> Unit,
) {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val closed = AtomicBoolean(false)
    private val pendingWrites = ConcurrentLinkedQueue<SppPayloadMapping>()
    private val lock = Any()

    private var readerThread: Thread? = null
    private val ackThreads = ConcurrentHashMap.newKeySet<Thread>()
    private var nextTxSequence: Byte = 0
    private var awaitingAck: Byte? = null
    private var awaitingFrame: ByteArray? = null
    private var awaitingRetryPolicy: SppRetryPolicy? = null
    private var awaitingRetries: Int = 0
    private var ackGeneration: Int = 0

    fun start() {
        readerThread = Thread(::readLoop, "OpenBuds-SppTransport").also { it.start() }
    }

    fun send(tandemBytes: ByteArray) {
        val frame = SonySppPayloadMapper.outboundFromTandemBytes(tandemBytes)
        pendingWrites.add(frame)
        drainWrites()
    }

    /**
     * True while a command handed to [send] has not left the transport yet: still queued,
     * or written and waiting for the headset's ACK.
     *
     * Frames go out one at a time behind that ACK, so a connection-time burst of twenty
     * GETs is transmitted over several seconds. A consumer waiting for the burst's replies
     * must know the burst is still being *sent*, or it mistakes the quiet between two
     * commands for the end of the exchange.
     */
    fun hasOutstandingWrites(): Boolean {
        if (closed.get()) return false
        return pendingWrites.isNotEmpty() || synchronized(lock) { awaitingAck != null }
    }

    fun close() {
        if (!closed.getAndSet(true)) {
            pendingWrites.clear()
            awaitingAck = null
            awaitingFrame = null
            awaitingRetryPolicy = null
            ackGeneration += 1
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { socket.close() }
            val current = Thread.currentThread()
            ackThreads.toList().forEach { thread ->
                if (thread !== current) thread.interrupt()
            }
            readerThread?.let { thread ->
                if (thread !== current) {
                    // Closing input/socket above is what unblocks read(); interrupting instead
                    // risks firing inside a framework runBlocking and killing this process.
                    runCatching { thread.join(1_000L) }
                }
            }
        }
        readerThread = null
        ackThreads.clear()
    }

    private fun readLoop() {
        val frame = mutableListOf<Byte>()
        var inFrame = false
        val buffer = ByteArray(512)
        try {
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                for (index in 0 until read) {
                    when (val byte = buffer[index]) {
                        FRAME_START -> {
                            frame.clear()
                            inFrame = true
                        }
                        FRAME_END -> {
                            if (inFrame) {
                                handleFrame(frame.toByteArray())
                            }
                            frame.clear()
                            inFrame = false
                        }
                        else -> if (inFrame) frame += byte
                    }
                }
            }
            notifyClosed(null)
        } catch (e: IOException) {
            if (!closed.get()) {
                notifyClosed(e.message)
            }
        } catch (t: Throwable) {
            Thread.interrupted()
            runCatching {
                log("SPP reader aborted: ${t.javaClass.simpleName}: ${t.message}")
                notifyClosed(t.message)
            }
        }
    }

    private fun handleFrame(escapedBody: ByteArray) {
        val body = unescape(escapedBody)
        if (body.size < HEADER_SIZE + CHECKSUM_SIZE) {
            log("SPP RX short frame ${body.hexString()}")
            return
        }
        val expectedChecksum = checksum(body, body.size - CHECKSUM_SIZE)
        val actualChecksum = body.last().u
        if (expectedChecksum != actualChecksum) {
            log(
                "SPP RX checksum mismatch expected=${expectedChecksum.toString(16)} " +
                    "actual=${actualChecksum.toString(16)} raw=${body.hexString()}"
            )
            return
        }

        val type = SonySppFrameType.fromByte(body[0])
        val sequence = body[1]
        val length = body.int32be(2)
        if (length < 0 || body.size != HEADER_SIZE + length + CHECKSUM_SIZE) {
            log("SPP RX invalid length=$length raw=${body.hexString()}")
            return
        }
        val payload = body.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)
        log("SPP RX type=${type.name} seq=${sequence.u} payload=${payload.hexString()}")

        when (type) {
            SonySppFrameType.ACK -> {
                synchronized(lock) {
                    if (awaitingAck == sequence) {
                        nextTxSequence = sequence
                        awaitingAck = null
                        awaitingFrame = null
                        awaitingRetryPolicy = null
                        awaitingRetries = 0
                        ackGeneration += 1
                    } else {
                        log("SPP RX ACK unexpected seq=${sequence.u} awaiting=${awaitingAck?.u}")
                    }
                }
                drainWrites()
            }
            SonySppFrameType.DATA_MDR,
            SonySppFrameType.DATA_MDR_NO2,
            SonySppFrameType.LARGE_DATA_MDR -> {
                // ACK the frame so the headset stops retransmitting; the payload is
                // always dispatched — re-applying an identical status is idempotent.
                sendAck(sequence)
                SonySppPayloadMapper.inboundToTandemBytes(type, payload)?.let(onPayload)
            }
            SonySppFrameType.SHOT_MDR,
            SonySppFrameType.SHOT_MDR_NO2 -> {
                SonySppPayloadMapper.inboundToTandemBytes(type, payload)?.let(onPayload)
            }
            SonySppFrameType.UNKNOWN -> log("SPP RX unsupported data type=0x${body[0].u.toString(16)}")
        }
    }

    private fun drainWrites() {
        synchronized(lock) {
            if (closed.get() || awaitingAck != null) return
            val outbound = pendingWrites.poll() ?: return
            val sequence = nextTxSequence
            val encoded = encodeFrame(outbound.frameType, sequence, outbound.payload)
            val retryPolicy = outbound.frameType.retryPolicy()
            val expectedAck = retryPolicy?.let { inverseSequence(sequence) }
            awaitingAck = expectedAck
            awaitingFrame = if (expectedAck != null) encoded else null
            awaitingRetryPolicy = retryPolicy
            awaitingRetries = 0
            if (retryPolicy == null) {
                nextTxSequence = inverseSequence(sequence)
                awaitingFrame = null
            }
            val generation = ++ackGeneration
            log(
                "SPP TX type=${outbound.frameType.name} seq=${sequence.u} " +
                    "payload=${outbound.payload.hexString()} frame=${encoded.hexString()}"
            )
            try {
                output.write(encoded)
                output.flush()
                if (retryPolicy != null) {
                    scheduleAckTimeout(inverseSequence(sequence), generation, retryPolicy)
                }
            } catch (e: IOException) {
                awaitingAck = null
                notifyClosed("SPP write failed: ${e.message}")
            }
        }
    }

    private fun scheduleAckTimeout(
        expectedAck: Byte,
        generation: Int,
        policy: SppRetryPolicy,
    ) {
        val timeoutThread = Thread({
            try {
                try {
                    Thread.sleep(policy.timeoutMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                var retryScheduled = false
                synchronized(lock) {
                    if (closed.get() || ackGeneration != generation || awaitingAck != expectedAck) return@synchronized
                    val frame = awaitingFrame
                    if (frame != null && awaitingRetryPolicy == policy && awaitingRetries < policy.maxRetries) {
                        awaitingRetries += 1
                        val retryGeneration = ++ackGeneration
                        log(
                            "SPP ACK timeout expected=${expectedAck.u}; " +
                                "resending frame retry=$awaitingRetries"
                        )
                        try {
                            output.write(frame)
                            output.flush()
                        } catch (e: IOException) {
                            awaitingAck = null
                            awaitingFrame = null
                            notifyClosed("SPP retry failed: ${e.message}")
                            return@synchronized
                        }
                        scheduleAckTimeout(expectedAck, retryGeneration, policy)
                        retryScheduled = true
                        return@synchronized
                    }
                    log("SPP ACK timeout expected=${expectedAck.u}; closing transport")
                    awaitingAck = null
                    awaitingFrame = null
                    awaitingRetryPolicy = null
                    notifyClosed("SPP remote endpoint did not ACK seq=${inverseSequence(expectedAck).u}")
                }
                if (retryScheduled) return@Thread
            } catch (t: Throwable) {
                Thread.interrupted()
                runCatching { log("SPP ACK timer aborted: ${t.javaClass.simpleName}: ${t.message}") }
            } finally {
                ackThreads.remove(Thread.currentThread())
            }
        }, "OpenBuds-SppAckTimeout")
        ackThreads += timeoutThread
        timeoutThread.start()
    }

    private fun sendAck(sequence: Byte) {
        val ackSequence = inverseSequence(sequence)
        val encoded = encodeFrame(SonySppFrameType.ACK, ackSequence, byteArrayOf())
        log("SPP TX ACK seq=${ackSequence.u} frame=${encoded.hexString()}")
        try {
            output.write(encoded)
            output.flush()
        } catch (e: IOException) {
            notifyClosed("SPP ACK failed: ${e.message}")
        }
    }

    private fun notifyClosed(reason: String?) {
        if (!closed.getAndSet(true)) {
            pendingWrites.clear()
            awaitingAck = null
            awaitingFrame = null
            awaitingRetryPolicy = null
            ackGeneration += 1
            runCatching { socket.close() }
            onClosed(reason)
        }
    }

    private companion object {
        const val WRITABLE_VALUE_LENGTH = 1024
        private const val HEADER_SIZE = SonyTandemFraming.HEADER_SIZE
        private const val CHECKSUM_SIZE = SonyTandemFraming.CHECKSUM_SIZE
        private const val FRAME_START: Byte = SonyTandemFraming.FRAME_START
        private const val FRAME_END: Byte = SonyTandemFraming.FRAME_END

        fun encodeFrame(type: SonySppFrameType, sequence: Byte, payload: ByteArray): ByteArray =
            SonyTandemFraming.encode(type.code, sequence, payload)

        fun unescape(bytes: ByteArray): ByteArray = SonyTandemFraming.unescape(bytes)

        fun checksum(bytes: ByteArray, length: Int): Int =
            SonyTandemFraming.checksum(bytes, length)

        fun inverseSequence(sequence: Byte): Byte = SonyTandemFraming.inverseSequence(sequence)

        fun ByteArray.int32be(offset: Int): Int =
            ((this[offset].u) shl 24) or
                ((this[offset + 1].u) shl 16) or
                ((this[offset + 2].u) shl 8) or
                this[offset + 3].u

        val Byte.u: Int
            get() = toInt() and 0xFF
    }
}
