package dev.sonypods.hook

import dev.sonypods.hook.Log

/**
 * The single source of truth for whether a headset is absent, coming back, in
 * session, or in transport recovery. Every previous marker
 * (`physicalDisconnectAddress`, `transportRecoveryAddress`, the
 * `lastConnectAnimationKey == null` window probe) encoded a fragment of this
 * machine as an independently-timed flag; each one drifted out of sync with
 * reality whenever its setter or clearer raced a different event source.
 * This class replaces all of them with one phase per headset, driven only by
 * reliable inputs:
 *
 *  - `onLinkConnected` / `onLinkDisconnected` — A2DP / LE Audio profile state
 *    machine transitions (a link-level CONNECTED only happens on a real
 *    physical connection; a Tandem transport blip never moves them);
 *  - `onTransportDown` — a repository snapshot reporting no live transport,
 *    disambiguated by a live ACL probe into recovery vs departure;
 *  - `onSurfaceRendered` — the first usable (battery-bearing) render of a
 *    connection completed.
 *
 * Transitions:
 * ```
 * DISCONNECTED --linkConnected--> CONNECTING --surfaceRendered--> ACTIVE
 * ACTIVE --transportDown(link alive)--> RECOVERING --surfaceRendered--> ACTIVE
 * ACTIVE/RECOVERING --transportDown(link dead)--> DISCONNECTED
 * CONNECTING --transportDown--> CONNECTING   (blip inside a pending
 *                                            reconnect changes nothing)
 * anything --linkDisconnected--> DISCONNECTED
 * anything --linkConnected(other device)--> CONNECTING
 * ```
 */
internal class HeadsetLinkTracker {

    enum class Phase { DISCONNECTED, CONNECTING, ACTIVE, RECOVERING }

    /** What the renderer should do with a disconnected snapshot. */
    enum class DownOutcome {
        /** Headset left (link probe negative or a profile disconnect): clear surfaces. */
        TERMINAL,
        /** Session lost but the headset is still on air: keep surfaces, no reconnect animation. */
        PRESERVE,
        /** A blip inside a pending physical reconnect: leave everything untouched. */
        IGNORE,
    }

    private var address: String? = null
    private var phase: Phase = Phase.DISCONNECTED

    val currentAddress: String? get() = synchronized(this) { address }
    val currentPhase: Phase get() = synchronized(this) { phase }

    /**
     * Whether [candidate] is the headset this episode is about.
     *
     * Folded through [dev.sonypods.device.HeadsetRegistry] rather than compared raw: one headset
     * answers on two addresses, and the session legitimately moves between them — LE Audio teardown
     * hands control back to the classic identity for a moment, and a Tandem target change moves it
     * on purpose. Compared raw, that move reads as *a different headset took over*, which resets the
     * episode to CONNECTING and makes [isNewPhysicalConnection] true — so the island floats and the
     * connect popup fires in the middle of a disconnect. Observed 2026-09-05: session on
     * `80:99:E7:D8:60:09`, teardown opens one on `C5:93:15:6B:E6:34`, island pops.
     */
    private fun matches(candidate: String?): Boolean {
        val current = address ?: return false
        if (candidate == null) return false
        if (current.equals(candidate, ignoreCase = true)) return true
        val currentControl = runCatching {
            dev.sonypods.device.HeadsetRegistry.controlAddressFor(current)
        }.getOrNull() ?: return false
        val candidateControl = runCatching {
            dev.sonypods.device.HeadsetRegistry.controlAddressFor(candidate)
        }.getOrNull() ?: return false
        return currentControl.equals(candidateControl, ignoreCase = true)
    }

    /** A2DP / LE Audio state machine reports CONNECTED for this headset. */
    fun onLinkConnected(candidate: String) {
        synchronized(this) {
            if (matches(candidate)) {
                // Follow the identity the link is actually on: the episode is the same headset, but
                // the address the stack reports moves between its two identities, and the live one is
                // what onTransportDown has to probe for liveness.
                address = candidate
                if (phase == Phase.DISCONNECTED) {
                    // Same headset physically back: a new connection episode.
                    phase = Phase.CONNECTING
                    log("link connected → CONNECTING")
                }
                // ACTIVE / RECOVERING / CONNECTING: a second profile joining
                // (both CSIP members reach CONNECTED) or a repeat transition —
                // nothing to change.
                return
            }
            // A different headset takes over: full reset into a new episode.
            address = candidate
            phase = Phase.CONNECTING
            log("device changed → CONNECTING address=$candidate")
        }
    }

    /** A2DP / LE Audio state machine reports DISCONNECTED, or a user-requested disconnect. */
    fun onLinkDisconnected(candidate: String) {
        synchronized(this) {
            if (!matches(candidate) && address != null) return
            address = candidate.takeIf { !it.isNullOrBlank() } ?: address
            if (phase != Phase.DISCONNECTED) {
                log("link disconnected → DISCONNECTED address=$candidate")
            }
            phase = Phase.DISCONNECTED
        }
    }

    /**
     * A repository snapshot reports no live transport. [linkAlive] is invoked
     * only when the answer changes the outcome; it must query the stack live
     * (ACL state of both identities), never a remembered marker.
     */
    fun onTransportDown(candidate: String?, linkAlive: () -> Boolean): DownOutcome {
        synchronized(this) {
            if (!matches(candidate)) return DownOutcome.IGNORE
            return when (phase) {
                Phase.DISCONNECTED -> DownOutcome.TERMINAL
                Phase.CONNECTING -> {
                    // The reconnect is literally in progress; its capability
                    // probe routinely delays the first render and a transport
                    // blip in that window must not latch recovery.
                    log("transport blip during pending reconnect; ignored")
                    DownOutcome.IGNORE
                }
                Phase.ACTIVE, Phase.RECOVERING -> {
                    if (linkAlive()) {
                        phase = Phase.RECOVERING
                        log("transport lost, link alive → RECOVERING")
                        DownOutcome.PRESERVE
                    } else {
                        phase = Phase.DISCONNECTED
                        log("transport lost, link gone → DISCONNECTED")
                        DownOutcome.TERMINAL
                    }
                }
            }
        }
    }

    /** The first usable render of a connection completed. */
    fun onSurfaceRendered(candidate: String) {
        synchronized(this) {
            if (!matches(candidate)) return
            if (phase == Phase.CONNECTING || phase == Phase.RECOVERING) {
                log("surface rendered → ACTIVE")
                phase = Phase.ACTIVE
            }
        }
    }

    /**
     * An external authority declares this address "not a new connection"
     * (Sound Connect lease handoff, hot reload self-recovery): the surfaces
     * must update without the connect animation.
     */
    fun forceRecovery(candidate: String) {
        synchronized(this) {
            if (!matches(candidate) && address != null) return
            address = candidate
            if (phase != Phase.ACTIVE) {
                log("forced recovery → RECOVERING address=$candidate")
                phase = Phase.RECOVERING
            }
        }
    }

    /** Hot reload restore: the reload's own reconnect is a recovery, never a new episode. */
    fun restore(candidate: String?) {
        synchronized(this) {
            address = candidate?.takeIf { it.isNotBlank() }
            phase = if (address == null) {
                Phase.DISCONNECTED
            } else {
                // The reload's own saved-address reconnect must not replay
                // connect UI. A later genuine link disconnect/reconnect cycle
                // still goes through the full state machine.
                Phase.RECOVERING
            }
            log("restored address=$address phase=$phase")
        }
    }

    /** Whether [candidate] is a fresh physical connection awaiting its first render. */
    fun isNewPhysicalConnection(candidate: String?): Boolean =
        synchronized(this) { matches(candidate) && phase == Phase.CONNECTING }

    /** Whether [candidate] is in transport recovery (surfaces exist, no connect animation). */
    fun isRecovery(candidate: String?): Boolean =
        synchronized(this) { matches(candidate) && phase == Phase.RECOVERING }

    /** Whether the tracked headset is known to be gone. */
    fun isDisconnected(): Boolean = synchronized(this) { phase == Phase.DISCONNECTED }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "SonyPods-Engine"
    }
}
