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

    private val key = "LinkBuds-S-identifier"
    private val classic = "C5:93:15:6B:E6:34"
    private val leLeft = "80:99:E7:D8:60:09"
    private val leRight = "AA:BB:CC:DD:EE:03"
    private val bonded = listOf(classic, leLeft, leRight)

    @Test
    fun `an unknown address has no record and resolves to itself`() {
        assertNull(HeadsetRegistry.recordFor(classic))
        // Never a different address on a guess: callers treat the answer as dialable.
        assertEquals(classic, HeadsetRegistry.controlAddressFor(classic))
        assertEquals(classic, HeadsetRegistry.sessionTargetFor(classic))
        assertNull(HeadsetRegistry.leAddressFor(classic))
        assertFalse(HeadsetRegistry.isLeIdentity(classic))
    }

    @Test
    fun `a session registers under the tandem identifier, not an address`() {
        HeadsetRegistry.rememberSession(key = key, sessionAddress = leLeft)

        val record = HeadsetRegistry.recordFor(leLeft)
        assertEquals(key, record?.key)
        assertEquals(listOf(leLeft), record?.addresses)
        // The identifier is not address-shaped, so it is a key and nothing else.
        assertNull(record?.controlAddress)
    }

    @Test
    fun `reported addresses need a key and are unioned, never substituted`() {
        assertNull(
            HeadsetRegistry.rememberReportedAddresses(
                key = null,
                reported = listOf(leLeft, classic),
                bondedAddresses = bonded,
            )
        )
        assertNull(HeadsetRegistry.recordFor(leLeft))

        HeadsetRegistry.rememberSession(key = key, sessionAddress = leLeft)
        HeadsetRegistry.rememberReportedAddresses(
            key = key,
            reported = listOf(leLeft, classic, leRight),
            bondedAddresses = bonded,
        )
        assertEquals(listOf(leLeft, classic, leRight), HeadsetRegistry.recordFor(classic)?.addresses)

        // A later reply naming one address must not shrink the set.
        HeadsetRegistry.rememberReportedAddresses(
            key = key,
            reported = listOf(leLeft),
            bondedAddresses = bonded,
        )
        assertEquals(listOf(leLeft, classic, leRight), HeadsetRegistry.recordFor(leRight)?.addresses)
    }

    @Test
    fun `an address the phone is not bonded to is not stored`() {
        HeadsetRegistry.rememberSession(key = key, sessionAddress = leLeft)
        HeadsetRegistry.rememberReportedAddresses(
            key = key,
            reported = listOf(classic),
            bondedAddresses = listOf(leLeft),
        )

        assertEquals(listOf(leLeft), HeadsetRegistry.recordFor(leLeft)?.addresses)
    }

    @Test
    fun `direction is only ever recorded by a prover`() {
        HeadsetRegistry.rememberSession(key = key, sessionAddress = leLeft)
        HeadsetRegistry.rememberReportedAddresses(
            key = key,
            reported = listOf(leLeft, classic),
            bondedAddresses = bonded,
        )
        // Both addresses known, direction unproved: every consumer still sees itself.
        assertEquals(leLeft, HeadsetRegistry.controlAddressFor(leLeft))
        assertEquals(classic, HeadsetRegistry.controlAddressFor(classic))
        assertNull(HeadsetRegistry.leAddressFor(leLeft))
        assertFalse(HeadsetRegistry.isLeIdentity(leLeft))

        HeadsetRegistry.proveControlAddress(leLeft, classic)

        assertEquals(classic, HeadsetRegistry.controlAddressFor(leLeft))
        assertEquals(classic, HeadsetRegistry.sessionTargetFor(leLeft))
        assertEquals(leLeft, HeadsetRegistry.leAddressFor(classic))
        assertTrue(HeadsetRegistry.isLeIdentity(leLeft))
        assertFalse(HeadsetRegistry.isLeIdentity(classic))
    }

    @Test
    fun `the pairing flow may state the pair before any session exists`() {
        HeadsetRegistry.rememberPair(leAddress = leLeft, controlAddress = classic)

        assertEquals(classic, HeadsetRegistry.controlAddressFor(leLeft))
        assertEquals(leLeft, HeadsetRegistry.leAddressFor(classic))
        assertEquals(1, HeadsetRegistry.all().size)
        assertEquals(HeadsetRegistry.recordFor(classic), HeadsetRegistry.recordFor(leLeft))
    }

    @Test
    fun `a pair naming an address of a known headset does not fork the record`() {
        HeadsetRegistry.rememberSession(key = key, sessionAddress = leLeft)
        HeadsetRegistry.rememberReportedAddresses(
            key = key,
            reported = listOf(leLeft, classic),
            bondedAddresses = bonded,
        )

        HeadsetRegistry.rememberPair(leAddress = leLeft, controlAddress = classic)

        assertEquals(1, HeadsetRegistry.all().size)
        assertEquals(key, HeadsetRegistry.recordFor(leLeft)?.key)
        assertEquals(classic, HeadsetRegistry.controlAddressFor(leLeft))
    }

    @Test
    fun `one bonded identity keeps a record, none drops it, and nothing is stripped`() {
        HeadsetRegistry.rememberSession(key = key, sessionAddress = leLeft)
        HeadsetRegistry.rememberReportedAddresses(
            key = key,
            reported = listOf(leLeft, classic),
            bondedAddresses = bonded,
        )

        HeadsetRegistry.prune(listOf(classic))
        // SC leaves a stale group member until the next capability reply rewrites it, and stripping
        // is what erased the pair the pairing flow records before its own bond exists.
        assertEquals(listOf(leLeft, classic), HeadsetRegistry.recordFor(classic)?.addresses)

        HeadsetRegistry.prune(listOf("11:22:33:44:55:66"))
        assertNull(HeadsetRegistry.recordFor(classic))
    }

    @Test
    fun `a pinned headset survives having no bonded identity at all`() {
        HeadsetRegistry.rememberPair(leAddress = leLeft, controlAddress = classic)
        // What the pairing flow looks like between removeBond and createBond.
        HeadsetRegistry.pin(listOf(classic, leLeft))

        HeadsetRegistry.prune(listOf("11:22:33:44:55:66"))
        assertEquals(classic, HeadsetRegistry.controlAddressFor(leLeft))

        HeadsetRegistry.unpin()
        HeadsetRegistry.prune(listOf("11:22:33:44:55:66"))
        assertNull(HeadsetRegistry.recordFor(leLeft))
    }

    @Test
    fun `an empty bonded set prunes nothing`() {
        HeadsetRegistry.rememberPair(leAddress = leLeft, controlAddress = classic)

        HeadsetRegistry.prune(emptyList())

        assertEquals(classic, HeadsetRegistry.controlAddressFor(leLeft))
    }

    @Test
    fun `the snapshot feed replaces, so a pruned record does not linger`() {
        HeadsetRegistry.rememberPair(leAddress = leLeft, controlAddress = classic)
        HeadsetRegistry.rememberSession(key = "second", sessionAddress = leRight)
        val lines = HeadsetRegistry.snapshotLines()
        assertEquals(2, lines.size)

        HeadsetRegistry.resetForTesting()
        HeadsetRegistry.ingest(lines)
        assertEquals(2, HeadsetRegistry.all().size)
        assertEquals(classic, HeadsetRegistry.controlAddressFor(leLeft))

        // The engine forgot one; a consumer must forget it too.
        HeadsetRegistry.ingest(lines.take(1))
        assertEquals(1, HeadsetRegistry.all().size)
        assertNull(HeadsetRegistry.recordFor(leRight))
    }

    @Test
    fun `a key or name carrying a separator survives serialization`() {
        val nasty = "we|ird\nname"
        HeadsetRegistry.rememberSession(key = nasty, sessionAddress = leLeft, name = nasty)
        HeadsetRegistry.proveControlAddress(leLeft, leLeft)

        val round = HeadsetRecord.deserialize(HeadsetRegistry.snapshotLines().single())
        assertEquals(nasty, round?.key)
        assertEquals(nasty, round?.name)
        assertEquals(listOf(leLeft), round?.addresses)
        assertEquals(leLeft, round?.controlAddress)
    }

    @Test
    fun `address case is normalized on the way in and out`() {
        HeadsetRegistry.rememberSession(key = key, sessionAddress = leLeft.lowercase())
        HeadsetRegistry.rememberReportedAddresses(
            key = key,
            reported = listOf(classic.lowercase()),
            bondedAddresses = listOf(classic.lowercase()),
        )
        HeadsetRegistry.proveControlAddress(leLeft.lowercase(), classic.lowercase())

        assertEquals(classic, HeadsetRegistry.controlAddressFor(leLeft.lowercase()))
        assertEquals(listOf(leLeft, classic), HeadsetRegistry.recordFor(classic)?.addresses)
    }
}
