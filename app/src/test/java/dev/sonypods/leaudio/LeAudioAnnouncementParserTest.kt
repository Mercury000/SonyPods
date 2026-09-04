package dev.sonypods.leaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both payloads were captured from a Sony LinkBuds S with LE Audio enabled. They are the two
 * coordinated-set members the headset advertises at the same time — one per earbud. The
 * discoverable, Swift-Pair-advertising one reuses the headset's classic BR/EDR address; a scan on
 * the device confirmed it announcing ASCS from 80:99:E7:D8:60:09 while the other earbud announced
 * from C5:93:15:6B:E6:34, and the stack reported that same classic address as the second member.
 *
 * The point these fixtures make is negative: apart from the Flags octet and the Swift Pair payload
 * the two are byte-identical, so no advertisement field can say *which headset* an identity belongs
 * to. That is why [LeAudioBond] matches on the address the headset reports over Tandem instead, and
 * why nothing here selects a candidate.
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
    fun bothMembersPublishADifferentResolvableSetIdentifier() {
        // An RSI is recomputed from the SIRK and a fresh random per advertisement, so two members of
        // one set never publish the same value and the value is not comparable over time. Only its
        // presence means anything.
        val classic = LeAudioAnnouncementParser.parse(classicAddressMember)!!
        val leOnly = LeAudioAnnouncementParser.parse(leOnlyMember)!!

        assertTrue(classic.rsi != leOnly.rsi)
    }

    @Test
    fun anAdvertisementWithoutAnAscsAnnouncementIsNotUnicast() {
        // Flags + complete local name only: a plain BLE advertiser, no LE Audio.
        val announcement = LeAudioAnnouncementParser.parse(hex("0201060B094C696E6B427564732053"))!!

        assertFalse(announcement.isUnicastAnnouncement)
        assertNull(announcement.announcementType)
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
        val announcement = LeAudioAnnouncementParser.parse(hex("0201000B094C696E6B"))!!

        assertEquals(0x00, announcement.flags)
        assertNull(announcement.name)
    }

    @Test
    fun emptyInputIsNotAnAnnouncement() {
        assertNull(LeAudioAnnouncementParser.parse(null))
        assertNull(LeAudioAnnouncementParser.parse(ByteArray(0)))
        assertNull(LeAudioAnnouncementParser.parse(ByteArray(4)))
    }

    // ---- selection ----

    @Test
    fun `the LE only member is the one picked`() {
        // The shape of a real sweep: both members seen in one window, and the flow bonded C5:93:…
        // (flags=0, swiftPair=false) over 80:99:… (flags=2, swiftPair=true).
        val picked = LeAudioAnnouncementParser.pickPairingCandidate(
            candidates = loggedSweep(),
            targetName = "LinkBuds S",
        )

        assertEquals("C5:93:15:6B:E6:34", picked?.address)
    }

    @Test
    fun `a reported endpoint address outranks the advertisement`() {
        // A device declaring supported-function 0x64 names its own LE endpoint over Tandem. Trust
        // that over the advertising flags even when the flags point at the other member.
        val picked = LeAudioAnnouncementParser.pickPairingCandidate(
            candidates = loggedSweep(),
            targetName = "LinkBuds S",
            reportedLeAddresses = listOf("80:99:e7:d8:60:09"),
        )

        assertEquals("80:99:E7:D8:60:09", picked?.address)
    }

    @Test
    fun `the excluded control address is never the pick`() {
        val picked = LeAudioAnnouncementParser.pickPairingCandidate(
            candidates = listOf(candidate("80:99:E7:D8:60:09", classicAddressMember, rssi = -40)),
            targetName = "LinkBuds S",
            excludeAddresses = listOf("80:99:e7:d8:60:09"),
        )

        assertNull(picked)
    }

    @Test
    fun `a discoverable Swift Pair advertiser is never the pick`() {
        val picked = LeAudioAnnouncementParser.pickPairingCandidate(
            candidates = listOf(candidate("C0:11:11:11:11:11", classicAddressMember, rssi = -30)),
            targetName = "LinkBuds S",
        )

        assertNull(picked)
    }

    @Test
    fun `an advertiser of another model is never the pick`() {
        val picked = LeAudioAnnouncementParser.pickPairingCandidate(
            candidates = listOf(candidate("C5:93:15:6B:E6:34", leOnlyMember, rssi = -30)),
            targetName = "WF-1000XM5",
        )

        assertNull(picked)
    }

    @Test
    fun `two same model candidates need an RSSI lead before one is picked`() {
        val near = candidate("C5:93:15:6B:E6:34", leOnlyMember, rssi = -35)
        val far = candidate("D0:11:11:11:D7:94", leOnlyMember, rssi = -95)
        val tied = candidate("D0:11:11:11:D7:94", leOnlyMember, rssi = -38)

        assertEquals(
            "C5:93:15:6B:E6:34",
            LeAudioAnnouncementParser.pickPairingCandidate(listOf(near, far), "LinkBuds S")?.address,
        )
        assertNull(LeAudioAnnouncementParser.pickPairingCandidate(listOf(near, tied), "LinkBuds S"))
    }

    @Test
    fun `resolvable private addresses are not bondable`() {
        // 7B:D8:4A:BF:47:E8 came out of a real scan: high bits 01, so an RPA the stack could not
        // resolve. Bonding it fails on keys instead of adding the missing set member.
        assertFalse(LeAudioAnnouncementParser.isBondableAddress("7B:D8:4A:BF:47:E8"))
        assertFalse(LeAudioAnnouncementParser.isBondableAddress("43:47:C0:8E:FD:CC"))
        // C5:… is a static random address and 80:99:… is public; both are stable identities.
        assertTrue(LeAudioAnnouncementParser.isBondableAddress("C5:93:15:6B:E6:34"))
        assertTrue(LeAudioAnnouncementParser.isBondableAddress("80:99:E7:D8:60:09"))
        // Never drop a candidate because its address could not be classified.
        assertTrue(LeAudioAnnouncementParser.isBondableAddress("not-an-address"))
    }

    @Test
    fun `an advertisement without an ASCS announcement is never a candidate`() {
        assertNull(
            LeAudioAnnouncementParser.pickPairingCandidate(
                candidates = listOf(
                    candidate("C0:11:11:11:11:11", hex("0201060B094C696E6B427564732053"), rssi = -30)
                ),
                targetName = "LinkBuds S",
            )
        )
    }

    private fun loggedSweep() = listOf(
        candidate("C5:93:15:6B:E6:34", leOnlyMember, rssi = -52),
        candidate("80:99:E7:D8:60:09", classicAddressMember, rssi = -48),
    )

    private fun candidate(address: String, raw: ByteArray, rssi: Int) =
        LeAudioAnnouncementParser.Candidate(
            address = address,
            announcement = LeAudioAnnouncementParser.parse(raw)!!,
            rssi = rssi,
        )

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
