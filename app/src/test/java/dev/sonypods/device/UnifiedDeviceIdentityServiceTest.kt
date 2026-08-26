package dev.sonypods.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnifiedDeviceIdentityServiceTest {

    @Before
    fun setUp() {
        // Reset the service state before each test
        UnifiedDeviceIdentityService.resetForTesting()
        UnifiedDeviceIdentityService.initialize(null)
    }

    @Test
    fun `identity type unknown for unknown address`() {
        assertEquals(IdentityType.UNKNOWN, UnifiedDeviceIdentityService.getIdentityType("11:22:33:44:55:66"))
    }

    @Test
    fun `record LE identity from bt_config with LE keys only`() {
        UnifiedDeviceIdentityService.recordFromBtConfig(
            address = "AA:BB:CC:DD:EE:01",
            hasLeKeys = true,
            hasClassicKey = false,
            name = "LinkBuds S",
        )

        assertEquals(IdentityType.LE, UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:01"))
        assertTrue(UnifiedDeviceIdentityService.isLeAudioIdentity("AA:BB:CC:DD:EE:01"))
        assertFalse(UnifiedDeviceIdentityService.isClassicIdentity("AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun `record Classic identity from bt_config with LinkKey only`() {
        UnifiedDeviceIdentityService.recordFromBtConfig(
            address = "AA:BB:CC:DD:EE:02",
            hasLeKeys = false,
            hasClassicKey = true,
            name = "LinkBuds S",
        )

        assertEquals(IdentityType.CLASSIC, UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:02"))
        assertFalse(UnifiedDeviceIdentityService.isLeAudioIdentity("AA:BB:CC:DD:EE:02"))
        assertTrue(UnifiedDeviceIdentityService.isClassicIdentity("AA:BB:CC:DD:EE:02"))
    }

    @Test
    fun `record Dual identity from bt_config with both keys`() {
        UnifiedDeviceIdentityService.recordFromBtConfig(
            address = "AA:BB:CC:DD:EE:03",
            hasLeKeys = true,
            hasClassicKey = true,
            name = "LinkBuds S",
        )

        // Both keys present but no paired address = unknown type (bt_config alone can't
        // determine LE vs Classic identity direction without a paired address)
        assertEquals(IdentityType.DUAL, UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:03"))
    }

    @Test
    fun `record identity pair`() {
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:10", "AA:BB:CC:DD:EE:11")

        // LE address should be LE type
        assertEquals(IdentityType.LE, UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:10"))
        // Control address should be CLASSIC type
        assertEquals(IdentityType.CLASSIC, UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:11"))

        // LE address resolves control address
        assertEquals("AA:BB:CC:DD:EE:11", UnifiedDeviceIdentityService.resolveControlAddress("AA:BB:CC:DD:EE:10"))
        // Control address resolves to itself
        assertEquals("AA:BB:CC:DD:EE:11", UnifiedDeviceIdentityService.resolveControlAddress("AA:BB:CC:DD:EE:11"))

        // LE address for LE identity
        assertEquals("AA:BB:CC:DD:EE:10", UnifiedDeviceIdentityService.leAudioAddressFor("AA:BB:CC:DD:EE:11"))
    }

    @Test
    fun `identity pair returns both addresses for dual mode device`() {
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:20", "AA:BB:CC:DD:EE:21")

        val pair = UnifiedDeviceIdentityService.identityPairFor("AA:BB:CC:DD:EE:20")
        assertNotNull(pair)
        assertEquals("AA:BB:CC:DD:EE:20", pair!!.first)
        assertEquals("AA:BB:CC:DD:EE:21", pair.second)

        // Query from control side should also work
        val pairFromControl = UnifiedDeviceIdentityService.identityPairFor("AA:BB:CC:DD:EE:21")
        assertNotNull(pairFromControl)
        assertEquals("AA:BB:CC:DD:EE:20", pairFromControl!!.first)
        assertEquals("AA:BB:CC:DD:EE:21", pairFromControl.second)
    }

    @Test
    fun `identity pair returns null for single identity device`() {
        UnifiedDeviceIdentityService.recordFromBtConfig(
            address = "AA:BB:CC:DD:EE:30",
            hasLeKeys = true,
            hasClassicKey = false,
        )

        assertNull(UnifiedDeviceIdentityService.identityPairFor("AA:BB:CC:DD:EE:30"))
    }

    @Test
    fun `resolve control address returns unchanged for unknown address`() {
        assertEquals("AA:BB:CC:DD:EE:40", UnifiedDeviceIdentityService.resolveControlAddress("AA:BB:CC:DD:EE:40"))
    }

    @Test
    fun `address pairs returns all dual mode pairs`() {
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:50", "AA:BB:CC:DD:EE:51")
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:52", "AA:BB:CC:DD:EE:53")

        val pairs = UnifiedDeviceIdentityService.addressPairs()
        assertEquals(2, pairs.size)
    }

    @Test
    fun `address pairs deduplicates`() {
        // Record the same pair twice
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:60", "AA:BB:CC:DD:EE:61")
        UnifiedDeviceIdentityService.recordIdentityPair("AA:BB:CC:DD:EE:61", "AA:BB:CC:DD:EE:60")

        val pairs = UnifiedDeviceIdentityService.addressPairs()
        assertEquals(1, pairs.size)
    }

    @Test
    fun `identity snapshot includes all recorded identities`() {
        UnifiedDeviceIdentityService.recordFromBtConfig("AA:BB:CC:DD:EE:70", true, false)
        UnifiedDeviceIdentityService.recordFromBtConfig("AA:BB:CC:DD:EE:71", false, true)

        val snapshot = UnifiedDeviceIdentityService.identitySnapshot()
        assertEquals(2, snapshot.size)
        assertTrue(snapshot.containsKey("AA:BB:CC:DD:EE:70"))
        assertTrue(snapshot.containsKey("AA:BB:CC:DD:EE:71"))
    }

    @Test
    fun `identity serialization and deserialization`() {
        val identity = DeviceIdentity(
            address = "AA:BB:CC:DD:EE:80",
            type = IdentityType.DUAL,
            pairedAddress = "AA:BB:CC:DD:EE:81",
            name = "Test Device",
            source = IdentitySource.BT_CONFIG,
        )

        val serialized = identity.serialize()
        val deserialized = DeviceIdentity.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(identity.address, deserialized!!.address)
        assertEquals(identity.type, deserialized.type)
        assertEquals(identity.pairedAddress, deserialized.pairedAddress)
        assertEquals(identity.name, deserialized.name)
        assertEquals(identity.source, deserialized.source)
    }

    @Test
    fun `address case normalization works`() {
        UnifiedDeviceIdentityService.recordFromBtConfig("aa:bb:cc:dd:ee:90", true, false)

        // Query with different case should return the same result
        assertEquals(IdentityType.LE, UnifiedDeviceIdentityService.getIdentityType("AA:BB:CC:DD:EE:90"))
        assertEquals(IdentityType.LE, UnifiedDeviceIdentityService.getIdentityType("aa:bb:cc:dd:ee:90"))
    }
}
