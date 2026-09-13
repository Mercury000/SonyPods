package dev.sonypods.ble

import dev.sonypods.protocol.LeaConnectionType

/**
 * One point-in-time view of the system LE Audio profile.
 *
 * [ready] is false while the profile proxy is still binding or when the vendor profile cannot
 * answer. Unknown never selects GATT; the legacy path may deliberately fall back to SPP after its
 * bounded proxy wait. It must never be mixed with a later, separate connected-device read to make
 * one half of the route see a different topology from the other.
 */
internal data class LeAudioTopology(
    val ready: Boolean,
    val connectedAddresses: Set<String>,
) {
    companion object {
        val Unknown = LeAudioTopology(ready = false, connectedAddresses = emptySet())
    }
}

/**
 * The route decision is intentionally a state, not a nullable route plus a second veto.
 *
 * [Ready] contains the exact topology that proved the target. [Wait] means the headset is
 * transitioning and the caller must re-evaluate when the LE Audio topology changes. Only [Reject]
 * is terminal.
 */
internal sealed interface TandemRouteDecision {
    val reason: String

    data class Ready(
        val targetAddress: String,
        val mode: TandemConnectionMode,
        val leAudioConnectedAddresses: Set<String>,
        override val reason: String,
    ) : TandemRouteDecision

    data class Wait(
        val targetAddress: String?,
        override val reason: String,
    ) : TandemRouteDecision

    data class Reject(
        override val reason: String,
    ) : TandemRouteDecision
}

/**
 * Resolves Tandem's transport and exact target from one LE Audio topology snapshot.
 *
 * The route rule and Sony's GATT execution veto answer different questions:
 * - whether this headset has LE Audio active (identity-folded), and
 * - which exact address is currently safe to dial over GATT.
 *
 * Keeping both answers in one function prevents the stale-snapshot race where the first query
 * sees a connected sibling, but the second query rejects the LE identity before it reaches
 * CONNECTED.
 */
internal object TandemRouteResolver {
    fun resolve(
        requestedAddress: String,
        knownAddresses: Collection<String>,
        controlAddress: String?,
        leAudioTopology: LeAudioTopology,
        tandemMigration: LeaConnectionType? = null,
    ): TandemRouteDecision {
        val requested = normalize(requestedAddress)
            ?: return TandemRouteDecision.Reject("Tandem route has an invalid requested address")
        val known = linkedSetOf(requested).apply {
            knownAddresses.mapNotNullTo(this, ::normalize)
        }
        val control = normalize(controlAddress)
        val connected = leAudioTopology.connectedAddresses
            .mapNotNullTo(linkedSetOf(), ::normalize)

        if (tandemMigration != null) {
            val mode = when (tandemMigration) {
                LeaConnectionType.SPP -> TandemConnectionMode.SPP
                LeaConnectionType.BLE_GATT -> TandemConnectionMode.GATT
                LeaConnectionType.OUT_OF_RANGE ->
                    return TandemRouteDecision.Reject(
                        "Tandem migration named an out-of-range ConnectionType; ignoring the frame",
                    )
            }
            if (mode == TandemConnectionMode.SPP) {
                return TandemRouteDecision.Ready(
                    targetAddress = requested,
                    mode = mode,
                    leAudioConnectedAddresses = connected,
                    reason = "headset-directed migration requested SPP",
                )
            }
            if (!leAudioTopology.ready) {
                return TandemRouteDecision.Wait(
                    targetAddress = requested,
                    reason = "headset requested GATT, but LE Audio topology is not available yet",
                )
            }
            if (requested !in connected) {
                return TandemRouteDecision.Wait(
                    targetAddress = requested,
                    reason = "headset requested GATT for $requested before that identity " +
                        "reached LE Audio CONNECTED",
                )
            }
            return TandemRouteDecision.Ready(
                targetAddress = requested,
                mode = mode,
                leAudioConnectedAddresses = connected,
                reason = "headset-directed migration requested GATT",
            )
        }

        val connectedKnown = known.filterTo(linkedSetOf()) { it in connected }
        if (leAudioTopology.ready && connectedKnown.isNotEmpty()) {
            val leCandidates = if (control == null) {
                emptyList()
            } else {
                known.filterNot { it == control }
            }
            val connectedLe = leCandidates.filterTo(linkedSetOf()) { it in connected }
            val target = when {
                connectedLe.isNotEmpty() -> connectedLe.first()
                // An identity with no proved classic/LE direction is dialable as-is. This preserves
                // the existing unknown-device behaviour without inventing a sibling direction.
                control == null && requested in connected -> requested
                control == null -> connectedKnown.firstOrNull()
                // No proved LE sibling is known yet. Preserve the single-identity fallback rather
                // than waiting forever for a sibling the headset may never report.
                leCandidates.isEmpty() && requested in connected -> requested
                // A proved control identity plus a known LE sibling is not a valid GATT target until
                // that exact sibling reaches CONNECTED. Wait instead of routing and vetoing.
                else -> null
            }
            if (target != null) {
                return TandemRouteDecision.Ready(
                    targetAddress = target,
                    mode = TandemConnectionMode.GATT,
                    leAudioConnectedAddresses = connected,
                    reason = "connected LE Audio identity selected GATT",
                )
            }
            return TandemRouteDecision.Wait(
                targetAddress = leCandidates.firstOrNull() ?: requested,
                reason = "LE Audio is active for the headset, but its exact GATT identity " +
                    "is not CONNECTED yet",
            )
        }

        val target = control ?: requested
        return TandemRouteDecision.Ready(
            targetAddress = target,
            mode = TandemConnectionMode.SPP,
            leAudioConnectedAddresses = connected,
            reason = "LE Audio is down; selected SPP on the control identity",
        )
    }

    private fun normalize(address: String?): String? =
        address?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
}
