package dev.sonypods.device

import dev.sonypods.protocol.SonyGatt
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyDeviceServiceTest {
    @Test
    fun sonyNameVariantsAreRecognized() {
        assertTrue(SonyDeviceService.isSonyName("Sony WF-1000XM5"))
        assertTrue(SonyDeviceService.isSonyName("LE_WF-1000XM5"))
        assertTrue(SonyDeviceService.isSonyName("LinkBuds S"))
        assertFalse(SonyDeviceService.isSonyName("WHATEVER"))
    }

    @Test
    fun sonyGattSignalIsRecognizedWithoutName() {
        assertTrue(
            SonyDeviceService.isSony(
                address = "02:00:00:00:00:11",
                name = "耳机",
                serviceUuids = listOf(SonyGatt.TANDEM_V2_HPC_SERVICE),
            ),
        )
    }

    @Test
    fun confirmedAddressSurvivesRenamedDevice() {
        val address = "02:00:00:00:00:12"
        SonyDeviceService.rememberAddress(address)
        assertTrue(SonyDeviceService.isSony(address = address, name = "我的耳机"))
    }

    @Test
    fun arbitraryAddressAndNameAreNotAccepted() {
        assertFalse(SonyDeviceService.isSony(address = "02:00:00:00:00:13", name = "我的耳机"))
        assertFalse(
            SonyDeviceService.isSony(
                address = "02:00:00:00:00:14",
                name = null,
                serviceUuids = listOf(UUID.randomUUID()),
            ),
        )
    }

    /**
     * Both service sets were read off a LinkBuds S bonded twice with LE Audio enabled: the
     * LE identity carries the LC3 audio, the other one carries Tandem.
     */
    @Test
    fun theLeAudioIdentityIsTheOneWithoutASonyService() {
        val leAudioIdentity = listOf(
            uuid16(0x180F), // Battery
            uuid16(0x1844), // Volume Control
            uuid16(0x1846), // Coordinated Set Identification
            uuid16(0x184E), // Audio Stream Control
            uuid16(0x184F), // Broadcast Audio Scan
        )
        assertTrue(SonyDeviceService.isLeAudioIdentity(leAudioIdentity))

        val controlIdentity = listOf(
            UUID.fromString("00001108-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("443cce33-e85d-4b85-8d53-6e319ede53ae"),
            UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2"),
        )
        assertFalse(SonyDeviceService.isLeAudioIdentity(controlIdentity))
    }

    @Test
    fun anAscsAdvertiserThatAlsoExposesTandemIsNotTreatedAsTheLeIdentity() {
        // A headset that puts LE Audio and Tandem behind one address needs no aliasing:
        // retargeting it would move the session off the very device that can serve it.
        assertFalse(
            SonyDeviceService.isLeAudioIdentity(
                listOf(uuid16(0x184E), SonyGatt.TANDEM_V2_HPC_SERVICE),
            ),
        )
    }

    @Test
    fun anEmptyServiceListIsNotClassifiedEitherWay() {
        // Services are unknown until discovery completes; guessing would hide a real device.
        assertFalse(SonyDeviceService.isLeAudioIdentity(emptyList()))
    }

    @Test
    fun resolvingAnUnlinkedAddressReturnsItUnchanged() {
        assertEquals("02:00:00:00:00:20", SonyDeviceService.resolveControlAddress("02:00:00:00:00:20"))
    }

    @Test
    fun aLinkedLeIdentityResolvesToItsControlAddress() {
        SonyDeviceService.linkLeAudioIdentity("02:00:00:00:00:21", "02:00:00:00:00:22")

        assertEquals("02:00:00:00:00:22", SonyDeviceService.resolveControlAddress("02:00:00:00:00:21"))
        // Lower case in, canonical out: callers compare these against system-supplied values.
        assertEquals("02:00:00:00:00:22", SonyDeviceService.resolveControlAddress("02:00:00:00:00:21".lowercase()))
        // The control address itself must not be rewritten.
        assertEquals("02:00:00:00:00:22", SonyDeviceService.resolveControlAddress("02:00:00:00:00:22"))
    }

    @Test
    fun anIdentityIsNeverAliasedToItself() {
        SonyDeviceService.linkLeAudioIdentity("02:00:00:00:00:23", "02:00:00:00:00:23")

        assertEquals("02:00:00:00:00:23", SonyDeviceService.resolveControlAddress("02:00:00:00:00:23"))
    }

    @Test
    fun theControlAddressResolvesBackToItsLeIdentity() {
        SonyDeviceService.linkLeAudioIdentity("02:00:00:00:00:24", "02:00:00:00:00:25")

        assertEquals("02:00:00:00:00:24", SonyDeviceService.leAudioIdentityFor("02:00:00:00:00:25"))
        // An address with no LE twin resolves to nothing rather than to itself.
        assertEquals(null, SonyDeviceService.leAudioIdentityFor("02:00:00:00:00:26"))
    }

    @Test
    fun identityAliasesCoverBothDirectionsOfAPairing() {
        SonyDeviceService.linkLeAudioIdentity("02:00:00:00:00:27", "02:00:00:00:00:28")

        // Asked from the LE side: the classic identity is the one that can act.
        assertEquals(listOf("02:00:00:00:00:28"), SonyDeviceService.identityAliasesOf("02:00:00:00:00:27"))
        // Asked from the control side: the LE twin is what a source may have published.
        assertEquals(listOf("02:00:00:00:00:27"), SonyDeviceService.identityAliasesOf("02:00:00:00:00:28"))
        assertTrue(SonyDeviceService.identityAliasesOf("02:00:00:00:00:29").isEmpty())
    }

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", short))
}
