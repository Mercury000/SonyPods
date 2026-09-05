package dev.sonypods.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HeadsetRegistryTest {

    @Before
    fun setUp() {
        HeadsetRegistry.resetForTesting()
    }

    private val control = "AA:BB:CC:DD:EE:01"
    private val leLeft = "AA:BB:CC:DD:EE:02"
    private val leRight = "AA:BB:CC:DD:EE:03"
    private val bonded = listOf(control, leLeft, leRight)

    @Test
    fun `an unknown address has no record`() {
        assertNull(HeadsetRegistry.recordFor(control))
        assertNull(HeadsetRegistry.controlAddressFor(control))
        assertEquals(emptyList<String>(), HeadsetRegistry.leAddressesFor(control))
        // sessionTargetFor passes through so callers never need a null check.
        assertEquals(control, HeadsetRegistry.sessionTargetFor(control))
    }

    @Test
    fun `a reported identity is found from any of its addresses`() {
        HeadsetRegistry.remember(
            uniqueId = control,
            reportedLeAddresses = listOf(leLeft, leRight),
            bondedAddresses = bonded,
        )

        listOf(control, leLeft, leRight).forEach { address ->
            assertEquals(control, HeadsetRegistry.controlAddressFor(address))
            assertEquals(control, HeadsetRegistry.sessionTargetFor(address))
        }
        assertFalse(HeadsetRegistry.isLeIdentity(control))
        assertTrue(HeadsetRegistry.isLeIdentity(leLeft))
        assertTrue(HeadsetRegistry.isLeIdentity(leRight))
    }

    @Test
    fun `an LE address the phone is not bonded to is not stored`() {
        HeadsetRegistry.remember(
            uniqueId = control,
            reportedLeAddresses = listOf(leLeft, leRight),
            bondedAddresses = listOf(control, leLeft),
        )

        assertEquals(listOf(leLeft), HeadsetRegistry.leAddressesFor(control))
    }

    @Test
    fun `a later reply that resolves nothing does not erase the known LE addresses`() {
        HeadsetRegistry.remember(
            uniqueId = control,
            reportedLeAddresses = listOf(leLeft),
            bondedAddresses = bonded,
        )
        // Same headset, reported while the LE bond is momentarily absent from the bonded set.
        HeadsetRegistry.remember(
            uniqueId = control,
            reportedLeAddresses = listOf(leLeft),
            bondedAddresses = listOf(control),
        )

        assertEquals(listOf(leLeft), HeadsetRegistry.leAddressesFor(control))
    }

    @Test
    fun `the pairing flow can state the pair before any session exists`() {
        HeadsetRegistry.rememberLeAddress(leLeft, control)

        assertEquals(control, HeadsetRegistry.controlAddressFor(leLeft))
        assertEquals(listOf(leLeft), HeadsetRegistry.leAddressesFor(control))
        // An address is never its own LE identity.
        assertNull(HeadsetRegistry.rememberLeAddress(control, control))
    }

    @Test
    fun `the live transport is recorded from any identity of the headset`() {
        HeadsetRegistry.remember(
            uniqueId = control,
            reportedLeAddresses = listOf(leLeft),
            bondedAddresses = bonded,
        )

        HeadsetRegistry.updateService(leLeft, PairingService.LEA)

        assertEquals(PairingService.LEA, HeadsetRegistry.serviceFor(control))
    }

    @Test
    fun `unpairing any identity keeps the record while another is still bonded`() {
        HeadsetRegistry.remember(
            uniqueId = control,
            reportedLeAddresses = listOf(leLeft),
            bondedAddresses = bonded,
        )

        HeadsetRegistry.prune(listOf(leLeft))

        assertEquals(control, HeadsetRegistry.controlAddressFor(leLeft))
    }

    @Test
    fun `an LE identity that was unpaired stops being listed on its record`() {
        HeadsetRegistry.remember(
            uniqueId = control,
            reportedLeAddresses = listOf(leLeft, leRight),
            bondedAddresses = bonded,
        )

        HeadsetRegistry.prune(listOf(control, leRight))

        assertEquals(listOf(leRight), HeadsetRegistry.leAddressesFor(control))
        assertNull(HeadsetRegistry.recordFor(leLeft))
    }

    @Test
    fun `unpairing every identity drops the record`() {
        HeadsetRegistry.remember(
            uniqueId = control,
            reportedLeAddresses = listOf(leLeft),
            bondedAddresses = bonded,
        )

        HeadsetRegistry.prune(listOf("11:22:33:44:55:66"))

        assertNull(HeadsetRegistry.recordFor(control))
    }

    @Test
    fun `an empty bonded set prunes nothing`() {
        HeadsetRegistry.remember(uniqueId = control, bondedAddresses = bonded)

        // The adapter being off, or the permission missing, is not "everything was unpaired".
        HeadsetRegistry.prune(emptyList())

        assertEquals(control, HeadsetRegistry.controlAddressFor(control))
    }

    @Test
    fun `records round trip through the snapshot feed and normalize case`() {
        HeadsetRegistry.remember(
            uniqueId = control.lowercase(),
            name = "WF-1000XM6",
            reportedLeAddresses = listOf(leLeft.lowercase()),
            bondedAddresses = bonded.map { it.lowercase() },
            service = PairingService.LEA,
            supportsLeClassic = true,
            bothPairedHistory = true,
        )
        val lines = HeadsetRegistry.snapshotLines()

        HeadsetRegistry.resetForTesting()
        HeadsetRegistry.ingest(lines)

        val record = HeadsetRegistry.recordFor(leLeft.lowercase())!!
        assertEquals(control, record.uniqueId)
        assertEquals(listOf(leLeft), record.leAddresses)
        assertEquals("WF-1000XM6", record.name)
        assertEquals(PairingService.LEA, record.service)
        assertTrue(record.supportsLeClassic)
        assertTrue(record.bothPairedHistory)
    }

    @Test
    fun `a name containing the field separator survives serialization`() {
        HeadsetRegistry.remember(uniqueId = control, name = "WH|1000", bondedAddresses = bonded)

        val record = HeadsetRecord.deserialize(HeadsetRegistry.snapshotLines().single())!!

        assertEquals("WH|1000", record.name)
    }
}
