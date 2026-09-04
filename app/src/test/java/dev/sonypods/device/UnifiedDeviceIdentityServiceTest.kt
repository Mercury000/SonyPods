package dev.sonypods.device

import dev.sonypods.device.UnifiedDeviceIdentityService.BtConfigSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnifiedDeviceIdentityServiceTest {

    @Before
    fun setUp() {
        UnifiedDeviceIdentityService.resetForTesting()
        UnifiedDeviceIdentityService.initializeForEngine(null)
    }

    @Test
    fun `identity type unknown for unknown address`() {
        assertEquals(
            IdentityType.UNKNOWN,
            UnifiedDeviceIdentityService.getIdentityType("11:22:33:44:55:66"),
        )
    }

    // ---- bt_config classification: one address at a time, never a pair ----

    @Test
    fun `a sony tws pair classifies but does not relate the two records`() {
        // The shape a LinkBuds S leaves behind: the classic record also holds LE keys, because CTKD
        // writes them there and the second earbud's LE identity is that same address. Nothing in a
        // bond record relates the two — that is [recordGroup]'s job.
        val identities = UnifiedDeviceIdentityService.classifySections(
            listOf(
                section("80:99:E7:D8:60:09", linkKey = true, leKeys = true, name = "LinkBuds S"),
                section("C5:93:15:6B:E6:34", linkKey = false, leKeys = true, name = "LinkBuds S"),
            )
        ).associateBy { it.address }

        assertEquals(IdentityType.DUAL, identities.getValue("80:99:E7:D8:60:09").type)
        assertEquals(IdentityType.LE, identities.getValue("C5:93:15:6B:E6:34").type)
        assertTrue(identities.values.all { it.pairedAddress == null })
    }

    @Test
    fun `a single address CTKD device is its own LE address`() {
        val identity = UnifiedDeviceIdentityService.classifySections(
            listOf(section("80:99:E7:D8:60:09", linkKey = true, leKeys = true, name = "WH-1000XM6"))
        ).single()

        assertEquals(IdentityType.DUAL, identity.type)
        assertNull(identity.pairedAddress)
        assertEquals("80:99:E7:D8:60:09", identity.leAddress)
        assertEquals("80:99:E7:D8:60:09", identity.controlAddress)
    }

    @Test
    fun `a classic only record stays classic with no LE address`() {
        val identity = UnifiedDeviceIdentityService.classifySections(
            listOf(section("80:99:E7:D8:60:09", linkKey = true, leKeys = false, name = "Speaker"))
        ).single()

        assertEquals(IdentityType.CLASSIC, identity.type)
        assertNull(identity.leAddress)
    }

    // ---- CSIS group: the authoritative pairing source ----

    @Test
    fun `a CSIS group relates the two identities, direction from the key material`() {
        UnifiedDeviceIdentityService.ingestSections(
            listOf(
                section("80:99:E7:D8:60:09", linkKey = true, leKeys = true, name = "LinkBuds S"),
                section("C5:93:15:6B:E6:34", linkKey = false, leKeys = true, name = "LinkBuds S"),
            )
        )
        UnifiedDeviceIdentityService.recordGroup(
            listOf("C5:93:15:6B:E6:34", "80:99:E7:D8:60:09")
        )

        assertEquals(
            "80:99:E7:D8:60:09",
            UnifiedDeviceIdentityService.resolveControlAddress("C5:93:15:6B:E6:34"),
        )
        assertEquals(
            "C5:93:15:6B:E6:34",
            UnifiedDeviceIdentityService.leAudioAddressFor("80:99:E7:D8:60:09"),
        )
        // What the device list folds on; nothing else merges the two rows.
        assertEquals(
            listOf("C5:93:15:6B:E6:34>80:99:E7:D8:60:09"),
            UnifiedDeviceIdentityService.leToControlPairs(),
        )
    }

    @Test
    fun `a group with two link key holders is not paired`() {
        // Two control-capable records in one group means the direction cannot be settled, and a
        // backwards pair aims Tandem writes at the wrong identity.
        UnifiedDeviceIdentityService.ingestSections(
            listOf(
                section("80:99:E7:D8:60:09", linkKey = true, leKeys = true, name = "A"),
                section("80:99:E7:D8:60:0A", linkKey = true, leKeys = true, name = "B"),
            )
        )
        UnifiedDeviceIdentityService.recordGroup(
            listOf("80:99:E7:D8:60:09", "80:99:E7:D8:60:0A")
        )

        assertTrue(UnifiedDeviceIdentityService.leToControlPairs().isEmpty())
    }

    @Test
    fun `a group of one is ignored`() {
        UnifiedDeviceIdentityService.recordGroup(listOf("80:99:E7:D8:60:09"))
        assertTrue(UnifiedDeviceIdentityService.leToControlPairs().isEmpty())
    }

    @Test
    fun `the group answer outranks a pairing record that disagrees`() {
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02")
        UnifiedDeviceIdentityService.ingestSections(
            listOf(
                section("AA:BB:CC:DD:EE:01", linkKey = true, leKeys = true, name = "X"),
                section("AA:BB:CC:DD:EE:03", linkKey = false, leKeys = true, name = "X"),
            )
        )
        UnifiedDeviceIdentityService.recordGroup(
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:03")
        )

        // 01 was recorded as the LE side by pairing; the group says it is the control side.
        assertEquals(
            "AA:BB:CC:DD:EE:01",
            UnifiedDeviceIdentityService.resolveControlAddress("AA:BB:CC:DD:EE:03"),
        )
        assertEquals(
            "AA:BB:CC:DD:EE:03",
            UnifiedDeviceIdentityService.leAudioAddressFor("AA:BB:CC:DD:EE:01"),
        )
    }

    // ---- module-managed pairing ----

    @Test
    fun `record identity pair`() {
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:10", "AA:BB:CC:DD:EE:11")

        assertEquals(
            IdentityType.LE,
            UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:10"),
        )
        assertEquals(
            IdentityType.CLASSIC,
            UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:11"),
        )
        assertEquals(
            "AA:BB:CC:DD:EE:11",
            UnifiedDeviceIdentityService.resolveControlAddress("AA:BB:CC:DD:EE:10"),
        )
        assertEquals(
            "AA:BB:CC:DD:EE:11",
            UnifiedDeviceIdentityService.resolveControlAddress("AA:BB:CC:DD:EE:11"),
        )
        assertEquals(
            "AA:BB:CC:DD:EE:10",
            UnifiedDeviceIdentityService.leAudioAddressFor("AA:BB:CC:DD:EE:11"),
        )
    }

    @Test
    fun `identity pair returns both addresses from either side`() {
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:20", "AA:BB:CC:DD:EE:21")

        val fromLe = UnifiedDeviceIdentityService.identityPairFor("AA:BB:CC:DD:EE:20")
        assertNotNull(fromLe)
        assertEquals("AA:BB:CC:DD:EE:20" to "AA:BB:CC:DD:EE:21", fromLe)

        val fromControl = UnifiedDeviceIdentityService.identityPairFor("AA:BB:CC:DD:EE:21")
        assertEquals("AA:BB:CC:DD:EE:20" to "AA:BB:CC:DD:EE:21", fromControl)
    }

    @Test
    fun `identity pair returns null for an unknown address`() {
        assertNull(UnifiedDeviceIdentityService.identityPairFor("AA:BB:CC:DD:EE:30"))
    }

    @Test
    fun `resolve control address returns unchanged for unknown address`() {
        assertEquals(
            "AA:BB:CC:DD:EE:40",
            UnifiedDeviceIdentityService.resolveControlAddress("AA:BB:CC:DD:EE:40"),
        )
    }

    @Test
    fun `pairing outranks a bt_config classification of the same address`() {
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:50", "AA:BB:CC:DD:EE:51")
        UnifiedDeviceIdentityService.ingestSections(
            listOf(section("AA:BB:CC:DD:EE:51", linkKey = true, leKeys = true, name = "X"))
        )

        assertEquals(
            IdentityType.CLASSIC,
            UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:51"),
        )
        assertEquals(
            "AA:BB:CC:DD:EE:50",
            UnifiedDeviceIdentityService.leAudioAddressFor("AA:BB:CC:DD:EE:51"),
        )
    }

    // ---- what the other processes are handed ----

    @Test
    fun `pairs are published once per headset, LE first`() {
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:60", "AA:BB:CC:DD:EE:61")
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:62", "AA:BB:CC:DD:EE:63")

        val pairs = UnifiedDeviceIdentityService.leToControlPairs()
        assertEquals(2, pairs.size)
        assertTrue("AA:BB:CC:DD:EE:60>AA:BB:CC:DD:EE:61" in pairs)
        assertTrue("AA:BB:CC:DD:EE:62>AA:BB:CC:DD:EE:63" in pairs)
    }

    @Test
    fun `ingested pairs round trip`() {
        UnifiedDeviceIdentityService.ingestPairs(listOf("AA:BB:CC:DD:EE:70>AA:BB:CC:DD:EE:71"))

        assertEquals(
            "AA:BB:CC:DD:EE:71",
            UnifiedDeviceIdentityService.resolveControlAddress("AA:BB:CC:DD:EE:70"),
        )
        assertEquals(
            listOf("AA:BB:CC:DD:EE:70>AA:BB:CC:DD:EE:71"),
            UnifiedDeviceIdentityService.leToControlPairs(),
        )
    }

    @Test
    fun `address case is normalized on the way in and out`() {
        UnifiedDeviceIdentityService.recordIdentityPair("aa:bb:cc:dd:ee:80", "aa:bb:cc:dd:ee:81")

        assertEquals(
            IdentityType.LE,
            UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:80"),
        )
        assertEquals(
            IdentityType.LE,
            UnifiedDeviceIdentityService.getIdentityType("aa:bb:cc:dd:ee:80"),
        )
    }

    @Test
    fun `identity serialization round trips`() {
        val identity = DeviceIdentity(
            address = "AA:BB:CC:DD:EE:90",
            type = IdentityType.DUAL,
            pairedAddress = "AA:BB:CC:DD:EE:91",
            name = "Test Device",
            source = IdentitySource.BT_CONFIG,
        )

        assertEquals(identity, DeviceIdentity.deserialize(identity.serialize()))
    }

    private fun section(
        address: String,
        linkKey: Boolean,
        leKeys: Boolean,
        name: String?,
    ) = BtConfigSection(address = address, hasLinkKey = linkKey, hasLeKeys = leKeys, name = name)
}
