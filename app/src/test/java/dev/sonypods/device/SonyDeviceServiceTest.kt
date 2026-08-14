package dev.sonypods.device

import dev.sonypods.protocol.SonyGatt
import java.util.UUID
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
}
