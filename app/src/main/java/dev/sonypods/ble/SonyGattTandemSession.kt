package dev.sonypods.ble

import dev.sonypods.headphones.TandemChannel
import dev.sonypods.protocol.hexString
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Tandem session over a GATT characteristic pair.
 *
 * Sound Connect drives GATT and RFCOMM through the same reliability layer: `ie0.C18010c` frames
 * every payload with `ne0.C24171b`, waits for an ACK when `DataType.ackRequired()`, resends on
 * timeout, and ACKs inbound data frames itself (`C24171b.m94089d`). Its transport abstraction
 * `InterfaceC18013f` is just a byte pipe — the GATT implementation `je0.C19229b` writes to a
 * characteristic and reports the negotiated MTU as its fragment size.
 *
 * This mirrors [SonySppTransport] for that reason: writing bare Tandem payloads to the
 * characteristic, which is what the module did before, is dropped by the headset. Notifications
 * are reassembled because one frame can span several of them, and fragments are emitted because
 * a frame can exceed the writable value length.
 *
 * Not thread-confined: [send] and [onNotification] arrive on the caller's threads, so all
 * sequence state is guarded by a single lock.
 */
internal class SonyGattTandemSession(
    private val channel: TandemChannel,
    /** Fragment size, the same value `je0.C19229b.mo850j0()` reports. Null means unknown, in
     * which case the frame is written whole and the stack decides. */
    private val writableValueLength: Int?,
    private val writeBytes: (ByteArray) -> Boolean,
    private val onPayload: (TandemChannel, ByteArray) -> Unit,
    private val onFailure: (String) -> Unit,
    private val log: (String) -> Unit,
    private val scheduleTimeout: (delayMs: Long, action: () -> Unit) -> Unit,
) {
    private val pendingWrites = ConcurrentLinkedQueue<Outbound>()
    private val rxSequenceTracker = SppRxSequenceTracker()
    private val lock = Any()
    private val inboundFrame = mutableListOf<Byte>()
    private var inFrame = false

    private var nextTxSequence: Byte = 0
    private var awaitingAck: Byte? = null
    private var awaitingFrame: ByteArray? = null
    private var awaitingPolicy: SppRetryPolicy? = null
    private var awaitingRetries = 0
    private var ackGeneration = 0
    private var writeInFlight = false
    /** Fragments of the current frame still waiting for their write callback. */
    private var fragmentsInFlight = 0
    private var closed = false

    private data class Outbound(val frameType: SonySppFrameType, val payload: ByteArray)

    fun send(tandemBytes: ByteArray) {
        val mapping = SonySppPayloadMapper.outboundFromTandemBytes(tandemBytes)
        pendingWrites.add(Outbound(mapping.frameType, mapping.payload))
        drainWrites()
    }

    /**
     * True while a command handed to [send] is still on its way out: queued, mid-write,
     * or written and waiting for the headset's ACK.
     *
     * Framed GATT serializes frames behind that ACK exactly as RFCOMM does, so a
     * connection-time burst takes seconds to transmit. A consumer waiting for the burst's
     * replies has to distinguish "the headset has answered everything" from "we have not
     * finished asking yet".
     */
    fun hasOutstandingWrites(): Boolean = synchronized(lock) {
        !closed && (pendingWrites.isNotEmpty() || writeInFlight || fragmentsInFlight > 0 || awaitingAck != null)
    }

    /** Called when a characteristic write completed, successfully or not. */
    fun onWriteComplete(success: Boolean) {
        synchronized(lock) {
            if (!success) {
                fragmentsInFlight = 0
                writeInFlight = false
                pendingWrites.clear()
                awaitingAck = null
                awaitingFrame = null
                awaitingPolicy = null
                return
            }
            // A frame may have been split, and each fragment gets its own completion; the slot
            // stays taken until the last one lands so a later frame cannot interleave with it.
            if (fragmentsInFlight > 0) fragmentsInFlight -= 1
            if (fragmentsInFlight > 0) return
            writeInFlight = false
        }
        drainWrites()
    }

    fun onNotification(value: ByteArray) {
        val completed = mutableListOf<ByteArray>()
        synchronized(lock) {
            if (closed) return
            // One Tandem frame can arrive across several notifications, and one notification can
            // carry more than one frame; boundaries are the escaped start/end markers.
            value.forEach { byte ->
                when (byte) {
                    SonyTandemFraming.FRAME_START -> {
                        inboundFrame.clear()
                        inFrame = true
                    }
                    SonyTandemFraming.FRAME_END -> {
                        if (inFrame) completed += inboundFrame.toByteArray()
                        inboundFrame.clear()
                        inFrame = false
                    }
                    else -> if (inFrame) inboundFrame += byte
                }
            }
        }
        completed.forEach(::handleFrame)
    }

    fun close() {
        synchronized(lock) {
            closed = true
            pendingWrites.clear()
            awaitingAck = null
            awaitingFrame = null
            awaitingPolicy = null
            ackGeneration += 1
            inboundFrame.clear()
            inFrame = false
            rxSequenceTracker.reset()
        }
    }

    private fun handleFrame(escapedBody: ByteArray) {
        val frame = SonyTandemFraming.decode(escapedBody) { reason ->
            log("GATT[$channel] RX $reason raw=${escapedBody.hexString()}")
        } ?: return
        val type = SonySppFrameType.fromByte(frame.type)
        log("GATT[$channel] RX type=${type.name} seq=${frame.sequence.u} payload=${frame.payload.hexString()}")

        when (type) {
            SonySppFrameType.ACK -> {
                synchronized(lock) {
                    if (awaitingAck == frame.sequence) {
                        nextTxSequence = frame.sequence
                        awaitingAck = null
                        awaitingFrame = null
                        awaitingPolicy = null
                        awaitingRetries = 0
                        ackGeneration += 1
                    } else {
                        log("GATT[$channel] RX ACK unexpected seq=${frame.sequence.u} awaiting=${awaitingAck?.u}")
                    }
                }
                drainWrites()
            }
            SonySppFrameType.DATA_MDR,
            SonySppFrameType.DATA_MDR_NO2,
            SonySppFrameType.LARGE_DATA_MDR -> {
                // A retransmission still has to be ACKed, but its payload is dispatched once.
                sendAck(frame.sequence)
                if (rxSequenceTracker.shouldDispatch(frame.sequence)) {
                    SonySppPayloadMapper.inboundToTandemBytes(type, frame.payload)?.let {
                        onPayload(channel, it)
                    }
                } else {
                    log("GATT[$channel] RX duplicate seq=${frame.sequence.u}; ACKed without redispatch")
                }
            }
            SonySppFrameType.SHOT_MDR,
            SonySppFrameType.SHOT_MDR_NO2 -> {
                SonySppPayloadMapper.inboundToTandemBytes(type, frame.payload)?.let {
                    onPayload(channel, it)
                }
            }
            SonySppFrameType.UNKNOWN ->
                log("GATT[$channel] RX unsupported data type=0x${frame.type.u.toString(16)}")
        }
    }

    private fun drainWrites() {
        val toWrite: ByteArray
        val policy: SppRetryPolicy?
        val expectedAck: Byte?
        val generation: Int
        synchronized(lock) {
            if (closed || writeInFlight || awaitingAck != null) return
            val outbound = pendingWrites.poll() ?: return
            val sequence = nextTxSequence
            toWrite = SonyTandemFraming.encode(outbound.frameType.code, sequence, outbound.payload)
            policy = outbound.frameType.retryPolicy()
            expectedAck = policy?.let { SonyTandemFraming.inverseSequence(sequence) }
            awaitingAck = expectedAck
            awaitingFrame = if (expectedAck != null) toWrite else null
            awaitingPolicy = policy
            awaitingRetries = 0
            if (policy == null) {
                nextTxSequence = SonyTandemFraming.inverseSequence(sequence)
            }
            generation = ++ackGeneration
            writeInFlight = true
            log(
                "GATT[$channel] TX type=${outbound.frameType.name} seq=${sequence.u} " +
                    "payload=${outbound.payload.hexString()} frame=${toWrite.hexString()}"
            )
        }
        val fragments = emit(toWrite)
        if (fragments == null) {
            synchronized(lock) {
                writeInFlight = false
                fragmentsInFlight = 0
                awaitingAck = null
                awaitingFrame = null
                awaitingPolicy = null
            }
            onFailure("GATT[$channel] write rejected")
            return
        }
        synchronized(lock) { fragmentsInFlight = fragments }
        if (policy != null && expectedAck != null) {
            scheduleAckTimeout(expectedAck, generation, policy)
        }
    }

    /**
     * Writes a frame, splitting it when it exceeds the writable value length.
     *
     * The framer does the same against `interfaceC18013f.mo850j0()`; the receiver reassembles on
     * the frame markers, so a split is transparent to it. Returns the number of writes issued, or
     * null when one was rejected.
     */
    private fun emit(frame: ByteArray): Int? {
        val limit = writableValueLength?.takeIf { it > 0 }
        if (limit == null || frame.size <= limit) {
            return if (writeBytes(frame)) 1 else null
        }
        var offset = 0
        var fragments = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!writeBytes(frame.copyOfRange(offset, end))) return null
            fragments += 1
            offset = end
        }
        return fragments
    }

    private fun sendAck(sequence: Byte) {
        val ackSequence = SonyTandemFraming.inverseSequence(sequence)
        val encoded = SonyTandemFraming.encode(SonySppFrameType.ACK.code, ackSequence, byteArrayOf())
        log("GATT[$channel] TX ACK seq=${ackSequence.u} frame=${encoded.hexString()}")
        if (emit(encoded) == null) onFailure("GATT[$channel] ACK write rejected")
    }

    private fun scheduleAckTimeout(expectedAck: Byte, generation: Int, policy: SppRetryPolicy) {
        scheduleTimeout(policy.timeoutMs) {
            var resend: ByteArray? = null
            var retryGeneration = 0
            var giveUp = false
            synchronized(lock) {
                if (closed || ackGeneration != generation || awaitingAck != expectedAck) {
                    return@scheduleTimeout
                }
                val frame = awaitingFrame
                if (frame != null && awaitingPolicy == policy && awaitingRetries < policy.maxRetries) {
                    awaitingRetries += 1
                    retryGeneration = ++ackGeneration
                    resend = frame
                    log("GATT[$channel] ACK timeout expected=${expectedAck.u}; resending retry=$awaitingRetries")
                } else {
                    awaitingAck = null
                    awaitingFrame = null
                    awaitingPolicy = null
                    giveUp = true
                }
            }
            resend?.let { frame ->
                val fragments = emit(frame)
                if (fragments != null) {
                    synchronized(lock) { fragmentsInFlight = fragments }
                    scheduleAckTimeout(expectedAck, retryGeneration, policy)
                } else {
                    onFailure("GATT[$channel] retry write rejected")
                }
                return@scheduleTimeout
            }
            if (giveUp) {
                onFailure(
                    "GATT[$channel] remote endpoint did not ACK " +
                        "seq=${SonyTandemFraming.inverseSequence(expectedAck).u}"
                )
            }
        }
    }

    private val Byte.u: Int
        get() = toInt() and 0xFF
}
