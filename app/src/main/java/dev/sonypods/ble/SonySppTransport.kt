package dev.sonypods.ble

import android.bluetooth.BluetoothSocket
import dev.sonypods.protocol.hexString
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
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
    private var nextTxSequence: Byte = 0
    private var awaitingAck: Byte? = null
    private var awaitingFrame: ByteArray? = null
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

    fun close() {
        if (!closed.getAndSet(true)) {
            pendingWrites.clear()
            awaitingAck = null
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { socket.close() }
        }
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
                        awaitingRetries = 0
                    } else {
                        log("SPP RX ACK unexpected seq=${sequence.u} awaiting=${awaitingAck?.u}")
                    }
                }
                drainWrites()
            }
            SonySppFrameType.DATA_MDR,
            SonySppFrameType.DATA_MDR_NO2,
            SonySppFrameType.LARGE_DATA_MDR -> {
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
            val expectedAck = if (outbound.frameType.ackRequired) inverseSequence(sequence) else null
            awaitingAck = expectedAck
            awaitingFrame = if (expectedAck != null) encoded else null
            awaitingRetries = 0
            if (!outbound.frameType.ackRequired) {
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
                if (expectedAck != null) {
                    scheduleAckTimeout(expectedAck, generation)
                }
            } catch (e: IOException) {
                awaitingAck = null
                notifyClosed("SPP write failed: ${e.message}")
            }
        }
    }

    private fun scheduleAckTimeout(expectedAck: Byte, generation: Int) {
        Thread({
            try {
                Thread.sleep(ACK_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            var retryScheduled = false
            synchronized(lock) {
                if (closed.get() || ackGeneration != generation || awaitingAck != expectedAck) return@synchronized
                val frame = awaitingFrame
                if (frame != null && awaitingRetries < MAX_ACK_RETRIES) {
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
                    scheduleAckTimeout(expectedAck, retryGeneration)
                    retryScheduled = true
                    return@synchronized
                }
                log("SPP ACK timeout expected=${expectedAck.u}; closing transport")
                awaitingAck = null
                awaitingFrame = null
                notifyClosed("SPP remote endpoint did not ACK seq=${inverseSequence(expectedAck).u}")
            }
            if (retryScheduled) return@Thread
        }, "OpenBuds-SppAckTimeout").start()
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
            runCatching { socket.close() }
            onClosed(reason)
        }
    }

    private companion object {
        const val WRITABLE_VALUE_LENGTH = 1024
        private const val ACK_TIMEOUT_MS = 1_200L
        private const val MAX_ACK_RETRIES = 1
        private const val HEADER_SIZE = 6
        private const val CHECKSUM_SIZE = 1
        private const val FRAME_START: Byte = 0x3E
        private const val FRAME_END: Byte = 0x3C
        private const val ESCAPE: Byte = 0x3D

        fun encodeFrame(type: SonySppFrameType, sequence: Byte, payload: ByteArray): ByteArray {
            val body = ByteArray(HEADER_SIZE + payload.size + CHECKSUM_SIZE)
            body[0] = type.code
            body[1] = sequence
            body[2] = ((payload.size ushr 24) and 0xFF).toByte()
            body[3] = ((payload.size ushr 16) and 0xFF).toByte()
            body[4] = ((payload.size ushr 8) and 0xFF).toByte()
            body[5] = (payload.size and 0xFF).toByte()
            payload.copyInto(body, HEADER_SIZE)
            body[body.lastIndex] = checksum(body, body.size - CHECKSUM_SIZE).toByte()
            return byteArrayOf(FRAME_START) + escape(body) + byteArrayOf(FRAME_END)
        }

        fun escape(bytes: ByteArray): ByteArray {
            val escaped = ArrayList<Byte>(bytes.size)
            bytes.forEach { byte ->
                when (byte) {
                    FRAME_END -> {
                        escaped += ESCAPE
                        escaped += 0x2C.toByte()
                    }
                    ESCAPE -> {
                        escaped += ESCAPE
                        escaped += 0x2D.toByte()
                    }
                    FRAME_START -> {
                        escaped += ESCAPE
                        escaped += 0x2E.toByte()
                    }
                    else -> escaped += byte
                }
            }
            return escaped.toByteArray()
        }

        fun unescape(bytes: ByteArray): ByteArray {
            val unescaped = ArrayList<Byte>(bytes.size)
            var index = 0
            while (index < bytes.size) {
                val byte = bytes[index]
                if (byte == ESCAPE && index + 1 < bytes.size) {
                    index++
                    unescaped += (bytes[index].toInt() or 0x10).toByte()
                } else {
                    unescaped += byte
                }
                index++
            }
            return unescaped.toByteArray()
        }

        fun checksum(bytes: ByteArray, length: Int): Int =
            (0 until length).fold(0) { acc, index -> (acc + bytes[index].u) and 0xFF }

        fun inverseSequence(sequence: Byte): Byte = (1 - sequence).toByte()

        fun ByteArray.int32be(offset: Int): Int =
            ((this[offset].u) shl 24) or
                ((this[offset + 1].u) shl 16) or
                ((this[offset + 2].u) shl 8) or
                this[offset + 3].u

        val Byte.u: Int
            get() = toInt() and 0xFF
    }
}
