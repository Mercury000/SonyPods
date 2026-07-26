package dev.sonypods.data

import dev.sonypods.ble.DiscoveredSonyDevice
import dev.sonypods.headphones.EqWriteContext
import dev.sonypods.headphones.HeadphoneAdapterRegistry
import dev.sonypods.headphones.SonyTandemHeadphoneAdapter
import dev.sonypods.protocol.EqPresetId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EqStateTest {
    @Test
    fun clearBassSyncUpdatesCachedRawBandBeforeNextBandWrite() {
        val cachedRawSteps = listOf(0x13, 0x0A, 0x0A, 0x0A, 0x0B, 0x0C)
        val synced = EqState(
            preset = EqPresetId.USER_SETTING2,
            clearBass = 9,
            rawBandSteps = cachedRawSteps,
            bandSteps = displayEqBands(cachedRawSteps),
        ).withClearBassSynced(3)

        assertEquals(3, synced.clearBass)
        assertEquals(listOf(0x0D, 0x0A, 0x0A, 0x0A, 0x0B, 0x0C), synced.rawBandSteps)
        assertEquals(listOf(0, 0, 0, 1, 2), synced.bandSteps)

        val nextRawSteps = synced.rawBandSteps.toMutableList()
            .also { it[1] = displayEqStepToRaw(1) }
        val profile = HeadphoneAdapterRegistry.resolve(
            DiscoveredSonyDevice(
                name = "LinkBuds S",
                address = "00:11:22:33:44:55",
                rssi = 0,
                source = "test",
                isLikelyControlEndpoint = true,
            )
        )
        val context = EqWriteContext(rawBandSteps = nextRawSteps)

        val command = SonyTandemHeadphoneAdapter.buildSetEqBandCommands(
            profile = profile,
            rawSteps = nextRawSteps,
            preset = synced.preset,
            context = context,
        ).single()

        assertArrayEquals(
            byteArrayOf(0x0E, 0x58, 0x00, 0xA2.toByte(), 0x06, 0x0D, 0x0B, 0x0A, 0x0A, 0x0B, 0x0C),
            command.bytes,
        )
    }
}
