package dev.sonypods.leaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both payloads were captured from a Sony LinkBuds S with LE Audio enabled. They are the two
 * coordinated-set members the headset advertises at the same time — one per earbud, not a
 * right and a wrong identity. The discoverable, Swift-Pair-advertising one reuses the
 * headset's classic BR/EDR address; a scan on the device confirmed it announcing ASCS from
 * 80:99:E7:D8:60:09 while the other earbud announced from C5:93:15:6B:E6:34, and the stack
 * reported that same classic address as the second member of the group.
 */
class LeAudioAnnouncementParserTest {
    private val classicAddressMember = hex(
        "02010209164E1800FF0F430000020A04041653180003194109051655182A0003164F18" +
            "0B094C696E6B427564732053072EE8FB2156304414FF06000302800444244C696E6B42756473205300"
    )

    private val leOnlyMember = hex(
        "02010009164E1800FF0F430000020A04041653180003194109051655182A0003164F18" +
            "0B094C696E6B427564732053072E040EDAC10D4E"
    )

    @Test
    fun bothIdentitiesAdvertiseTheSameLeAudioServiceSet() {
        for (raw in listOf(classicAddressMember, leOnlyMember)) {
            val announcement = LeAudioAnnouncementParser.parse(raw)!!

            assertTrue(announcement.isUnicastAnnouncement)
            assertTrue(announcement.isGeneralAnnouncement)
            assertEquals(0x0FFF, announcement.sinkContexts)
            assertEquals(0x0043, announcement.sourceContexts)
            assertTrue(announcement.hasCas)
            assertTrue(announcement.hasBass)
            assertTrue(announcement.hasTmas)
            assertEquals(0x002A, announcement.tmapRoles)
            assertEquals(0x0941, announcement.appearance)
            assertEquals("LinkBuds S", announcement.name)
        }
    }

    @Test
    fun onlyTheClassicFacingIdentityIsDiscoverableAndCarriesSwiftPair() {
        val classic = LeAudioAnnouncementParser.parse(classicAddressMember)!!
        assertEquals(0x02, classic.flags)
        assertTrue(classic.isDiscoverable)
        assertTrue(classic.hasSwiftPair)
        assertEquals(setOf(LeAudioAnnouncementParser.COMPANY_MICROSOFT), classic.manufacturerIds)
        assertEquals("E8FB21563044", classic.rsi)

        val leOnly = LeAudioAnnouncementParser.parse(leOnlyMember)!!
        assertEquals(0x00, leOnly.flags)
        assertFalse(leOnly.isDiscoverable)
        assertFalse(leOnly.hasSwiftPair)
        assertTrue(leOnly.manufacturerIds.isEmpty())
        assertEquals("040EDAC10D4E", leOnly.rsi)
    }

    @Test
    fun theLeOnlyMemberIsBondedFirst() {
        // Ordering, not selection: it is the member that needs a bond created, while the
        // classic-address member is already bonded over BR/EDR.
        val picked = LeAudioAnnouncementParser.pickPairingCandidate(
            candidates = candidates(),
            targetName = "LinkBuds S",
        )

        assertEquals("D0:11:11:11:D7:94", picked?.address)
    }

    @Test
    fun aReportedLeEndpointAddressOutranksTheHeuristics() {
        // The headset reports its own LE endpoints over Tandem; trust that over the
        // advertising flags even when the flags point at the other member.
        val picked = LeAudioAnnouncementParser.pickPairingCandidate(
            candidates = candidates(),
            targetName = "LinkBuds S",
            reportedLeAddresses = listOf("c0:22:22:22:56:d4"),
        )

        assertEquals("C0:22:22:22:56:D4", picked?.address)
    }

    @Test
    fun theClassicAddressIsStillAMemberWhenItAnnouncesAscs() {
        // One earbud reuses the headset's classic address for its LE identity. Excluding it —
        // which the module used to do, since it is also the bonded classic address — dropped
        // half the coordinated set and left that ear silent.
        val members = LeAudioAnnouncementParser.pairingCandidates(
            candidates = listOf(
                LeAudioAnnouncementParser.Candidate(
                    "80:99:E7:D8:60:09",
                    LeAudioAnnouncementParser.parse(classicAddressMember)!!,
                ),
            ),
            targetName = "LinkBuds S",
            excludeAddresses = listOf("80:99:e7:d8:60:09"),
        )

        assertEquals(listOf("80:99:E7:D8:60:09"), members.map { it.address })
    }

    @Test
    fun advertisementsWithoutAnAscsAnnouncementAreIgnored() {
        // Flags + complete local name only: a plain BLE advertiser, no LE Audio.
        val raw = hex("0201060B094C696E6B427564732053")
        val announcement = LeAudioAnnouncementParser.parse(raw)!!

        assertFalse(announcement.isUnicastAnnouncement)
        assertNull(
            LeAudioAnnouncementParser.pickPairingCandidate(
                candidates = listOf(LeAudioAnnouncementParser.Candidate("AA:BB:CC:DD:EE:FF", announcement)),
                targetName = "LinkBuds S",
            )
        )
    }

    @Test
    fun trailingZeroPaddingDoesNotBreakParsing() {
        val padded = leOnlyMember + ByteArray(20)
        assertEquals(
            LeAudioAnnouncementParser.parse(leOnlyMember),
            LeAudioAnnouncementParser.parse(padded),
        )
    }

    @Test
    fun truncatedStructureStopsAtTheLastCompleteField() {
        // Declares an 11-byte name structure but only 4 bytes follow.
        val truncated = hex("0201000B094C696E6B")
        val announcement = LeAudioAnnouncementParser.parse(truncated)!!

        assertEquals(0x00, announcement.flags)
        assertNull(announcement.name)
    }

    @Test
    fun bothCoordinatedSetMembersAreReturnedForBonding() {
        // Both earbuds must be bonded: each exposes a single-channel sink ASE and declares one
        // audio location, so the stack builds one CIS per member and a missing member means a
        // silent ear. The classic-address member sorts last because it needs no new bond.
        val members = LeAudioAnnouncementParser.pairingCandidates(
            candidates = candidates(),
            targetName = "LinkBuds S",
        )

        assertEquals(
            listOf("D0:11:11:11:D7:94", "C0:22:22:22:56:D4"),
            members.map { it.address },
        )
    }

    @Test
    fun resolvablePrivateAddressesAreNotBonded() {
        // 7B:D8:4A:BF:47:E8 came out of a real scan: high bits 01, so an RPA the stack could
        // not resolve. Bonding it failed on keys instead of adding the missing set member.
        assertFalse(LeAudioAnnouncementParser.isBondableAddress("7B:D8:4A:BF:47:E8"))
        assertFalse(LeAudioAnnouncementParser.isBondableAddress("43:47:C0:8E:FD:CC"))

        // C5:… is a static random address and 80:99:… is public; both are stable identities.
        assertTrue(LeAudioAnnouncementParser.isBondableAddress("C5:93:15:6B:E6:34"))
        assertTrue(LeAudioAnnouncementParser.isBondableAddress("80:99:E7:D8:60:09"))
    }

    @Test
    fun anUnparsableAddressIsNotRejected() {
        // Never drop a candidate because its address could not be classified.
        assertTrue(LeAudioAnnouncementParser.isBondableAddress("not-an-address"))
    }

    @Test
    fun anRpaIsNotOfferedAsASetMember() {
        val members = LeAudioAnnouncementParser.pairingCandidates(
            candidates = listOf(
                LeAudioAnnouncementParser.Candidate(
                    "7B:D8:4A:BF:47:E8",
                    LeAudioAnnouncementParser.parse(leOnlyMember)!!,
                ),
            ),
            targetName = "LinkBuds S",
        )

        assertEquals(emptyList<String>(), members.map { it.address })
    }

    private fun candidates() = listOf(
        LeAudioAnnouncementParser.Candidate(
            "C0:22:22:22:56:D4",
            LeAudioAnnouncementParser.parse(classicAddressMember)!!,
        ),
        LeAudioAnnouncementParser.Candidate(
            "D0:11:11:11:D7:94",
            LeAudioAnnouncementParser.parse(leOnlyMember)!!,
        ),
    )

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
