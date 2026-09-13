package dev.sonypods.ble

import dev.sonypods.protocol.LeaConnectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TandemRouteResolverTest {
    private val control = "80:99:E7:D8:60:09"
    private val le = "C5:93:15:6B:E6:34"

    @Test
    fun onlyControlIdentityConnected_waitsForExactLeIdentity() {
        val decision = resolve(connected = setOf(control))

        assertTrue(decision is TandemRouteDecision.Wait)
        decision as TandemRouteDecision.Wait
        assertEquals(le, decision.targetAddress)
    }

    @Test
    fun connectedLeLeadAlone_isTheOnlyGattTarget() {
        val decision = resolve(connected = setOf(le))

        assertTrue(decision is TandemRouteDecision.Ready)
        decision as TandemRouteDecision.Ready
        assertEquals(le, decision.targetAddress)
        assertEquals(TandemConnectionMode.GATT, decision.mode)
    }

    @Test
    fun exactLeIdentityAppearing_makesRouteReady() {
        val decision = resolve(connected = setOf(control, le))

        assertTrue(decision is TandemRouteDecision.Ready)
        decision as TandemRouteDecision.Ready
        assertEquals(le, decision.targetAddress)
        assertEquals(TandemConnectionMode.GATT, decision.mode)
    }

    @Test
    fun connectedLeIdentityWinsOverFirstStoredIdentity() {
        val otherLe = "D4:11:22:33:44:55"
        val decision = TandemRouteResolver.resolve(
            requestedAddress = control,
            knownAddresses = listOf(control, le, otherLe),
            controlAddress = control,
            leAudioTopology = LeAudioTopology(
                ready = true,
                connectedAddresses = setOf(control, otherLe),
            ),
        )

        assertTrue(decision is TandemRouteDecision.Ready)
        decision as TandemRouteDecision.Ready
        assertEquals(otherLe, decision.targetAddress)
        assertEquals(TandemConnectionMode.GATT, decision.mode)
    }

    @Test
    fun leAudioDown_routesSppToProvedControlIdentity() {
        val decision = resolve(connected = emptySet())

        assertTrue(decision is TandemRouteDecision.Ready)
        decision as TandemRouteDecision.Ready
        assertEquals(control, decision.targetAddress)
        assertEquals(TandemConnectionMode.SPP, decision.mode)
    }

    @Test
    fun gattMigration_waitsUntilExactNamedIdentityConnects() {
        val decision = resolve(
            connected = setOf(control),
            migration = LeaConnectionType.BLE_GATT,
            requested = le,
        )

        assertTrue(decision is TandemRouteDecision.Wait)
        decision as TandemRouteDecision.Wait
        assertEquals(le, decision.targetAddress)
    }

    @Test
    fun gattMigration_usesTheSameTopologyProof() {
        val decision = resolve(
            connected = setOf(le),
            migration = LeaConnectionType.BLE_GATT,
            requested = le,
        )

        assertTrue(decision is TandemRouteDecision.Ready)
        decision as TandemRouteDecision.Ready
        assertEquals(le, decision.targetAddress)
        assertEquals(TandemConnectionMode.GATT, decision.mode)
    }

    @Test
    fun outOfRangeMigrationIsRejected() {
        val decision = resolve(
            connected = setOf(control, le),
            migration = LeaConnectionType.OUT_OF_RANGE,
        )

        assertTrue(decision is TandemRouteDecision.Reject)
    }

    @Test
    fun noKnownLeSibling_keepsSingleIdentityGattFallback() {
        val decision = TandemRouteResolver.resolve(
            requestedAddress = control,
            knownAddresses = listOf(control),
            controlAddress = control,
            leAudioTopology = LeAudioTopology(ready = true, connectedAddresses = setOf(control)),
        )

        assertTrue(decision is TandemRouteDecision.Ready)
        decision as TandemRouteDecision.Ready
        assertEquals(control, decision.targetAddress)
        assertEquals(TandemConnectionMode.GATT, decision.mode)
    }

    private fun resolve(
        connected: Set<String>,
        migration: LeaConnectionType? = null,
        requested: String = control,
    ): TandemRouteDecision =
        TandemRouteResolver.resolve(
            requestedAddress = requested,
            knownAddresses = listOf(control, le),
            controlAddress = control,
            leAudioTopology = LeAudioTopology(ready = true, connectedAddresses = connected),
            tandemMigration = migration,
        )
}
