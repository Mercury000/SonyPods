package dev.sonypods.headphones

import dev.sonypods.ble.DiscoveredSonyDevice
import dev.sonypods.protocol.PlaybackControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ProtocolCompatibilityArchitectureTest {
    @Test
    fun profileBindingsBackProtocolForAndCarryChannels() {
        val profile = HeadphoneAdapterRegistry.resolve(
            DiscoveredSonyDevice(
                name = "LinkBuds S",
                address = "00:11:22:33:44:55",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            )
        )

        val binding = profile.bindingFor(HeadphoneFeature.NOISE_CONTROL)
        requireNotNull(binding)
        assertEquals(profile.protocolFor(HeadphoneFeature.NOISE_CONTROL), binding.variant)
        assertEquals(TandemChannel.GATT_V2_HPC, binding.channel)
    }

    @Test
    fun protocolDefaultChannelsMatchGattRouting() {
        assertEquals(TandemChannel.GATT_V1_MC, defaultChannelFor(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE1))
        assertEquals(TandemChannel.GATT_V2_MC, defaultChannelFor(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE2))
        assertEquals(TandemChannel.GATT_V1_MC, defaultChannelFor(HeadphoneProtocolVariant.SONY_TANDEM_V1_TABLE2))
        assertEquals(TandemChannel.GATT_V2_HPC, defaultChannelFor(HeadphoneProtocolVariant.SONY_TANDEM_V2_TABLE1))
        try {
            defaultChannelFor(HeadphoneProtocolVariant.UNKNOWN)
            fail("UNKNOWN protocol must not silently default to a concrete channel")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun codecDefaultChannelsMatchProtocolDefaults() {
        assertEquals(TandemChannel.GATT_V1_MC, SonyTandemV1Table1Codec.defaultChannel)
        assertEquals(TandemChannel.GATT_V1_MC, SonyTandemV1Table2Codec.defaultChannel)
        assertEquals(TandemChannel.GATT_V2_HPC, SonyTandemV2Table1Codec.defaultChannel)
        assertEquals(TandemChannel.GATT_V2_MC, SonyTandemV2Table2Codec.defaultChannel)
    }

    @Test
    fun playbackCommandsAreTandemFirstForStaticProfiles() {
        val profile = HeadphoneAdapterRegistry.resolve(
            DiscoveredSonyDevice(
                name = "LinkBuds S",
                address = "00:11:22:33:44:55",
                rssi = 0,
                source = "bonded",
                isLikelyControlEndpoint = true,
            )
        )

        assertEquals(PlaybackDispatchStrategy.TANDEM_FIRST, profile.playbackDispatchStrategy)
        val command = HeadphoneAdapterRegistry.buildPlaybackCommands(profile, PlaybackControl.PLAY).single()
        assertEquals(TandemChannel.GATT_V2_HPC, command.channel)
        assertEquals(0xA4, command.bytes[1].toInt() and 0xFF)
        assertEquals(0x01, command.bytes[2].toInt() and 0xFF)
    }

    @Test
    fun adapterDoesNotImportProtocolObjectsDirectly() {
        val source = mainSource("headphones/SonyTandemHeadphoneAdapter.kt")
        assertFalse(source.contains("import dev.sonypods.protocol.SonyTandemV1Table1Protocol"))
        assertFalse(source.contains("import dev.sonypods.protocol.SonyTandemV2Table1Protocol"))
        assertFalse(source.contains("SonyTandemV1Table1Protocol."))
        assertFalse(source.contains("SonyTandemV2Table1Protocol."))
    }

    @Test
    fun profileTemplateDoesNotInferProtocolFromModelName() {
        val source = mainSource("headphones/HeadphoneAdapter.kt")
        assertFalse(source.contains("when (modelName)"))
        assertFalse(source.contains("buildFeatureProtocolMap"))
    }

    @Test
    fun v1Table1ParserDoesNotDelegateToV2Parser() {
        val source = mainSource("protocol/SonyTandemV1Table1Protocol.kt")
        assertFalse(source.contains("SonyTandemV2Table1Protocol.parse"))
    }

    @Test
    fun repositoryDoesNotDropCommandChannel() {
        val source = mainSource("data/SonyHeadphoneRepository.kt")
        assertFalse(source.contains("client.send(bytes)"))
        assertTrue(source.contains("client.sendToChannel(command.channel, command.bytes)"))
    }

    private fun mainSource(path: String): String {
        val relativePath = "src/main/java/dev/sonypods/$path"
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val candidates = generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .flatMap { dir ->
                sequenceOf(
                    File(dir, relativePath),
                    File(dir, "app/$relativePath"),
                    File(dir, "App/app/$relativePath"),
                )
            }

        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $relativePath from $userDir")
    }
}
