package dev.sonypods.data

import dev.sonypods.ble.DiscoveredSonyDevice
import dev.sonypods.headphones.HeadphoneAdapterRegistry
import dev.sonypods.headphones.HeadphoneFeature
import dev.sonypods.headphones.HeadphoneTransport
import dev.sonypods.headphones.SonyCapabilityProbe
import dev.sonypods.headphones.SonyTandemHeadphoneAdapter
import dev.sonypods.headphones.TandemChannel
import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.SonyTandemConstants
import dev.sonypods.protocol.SonyTandemV1Table1Protocol
import dev.sonypods.protocol.SonyTandemV2Table1Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolInfoStateTest {
    private fun initialState(): SonyHeadphoneUiState = SonyHeadphoneUiState(
        connectedProfile = SonyTandemHeadphoneAdapter.withEndpointChannels(
            HeadphoneAdapterRegistry.resolve(
                DiscoveredSonyDevice(
                    name = "WF-1000XM6",
                    address = "00:11:22:33:44:55",
                    rssi = 0,
                    source = "test",
                    isLikelyControlEndpoint = true,
                ),
            ),
            setOf(TandemChannel.GATT_V2_HPC),
        ),
    )

    @Test
    fun xm6Firmware205VersionIsRecordedWithoutOpeningReadinessGates() {
        val response = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x01, 0x00, 0x03, 0x00, 0x30, 0x33, 0x00, 0x00),
        ) as ParsedTandemResponse.ProtocolInfo
        val state = initialState().withProtocolInfo(response.protocolVersion)

        assertTrue(SonyTandemConstants.PROTOCOL_VERSIONS_V2.contains(0x03003033))
        assertEquals(0x03003033, state.deviceInfo.protocolVersion)
        assertEquals(0x03003033, state.connectedProfile?.protocolVersion)
        assertEquals(true, state.deviceInfo.protocolVersionAccepted)
        assertFalse(state.capabilitiesKnown)
        assertFalse(state.initialValuesReady)
        assertFalse(state.essentialValuesReady)
    }

    @Test
    fun unknownV2VersionRetainsKnownCapabilitiesAndSkipsUnknownFunctionCodes() {
        val response = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x01, 0x00, 0x03, 0x00, 0x30, 0x34, 0x00, 0x00),
        ) as ParsedTandemResponse.ProtocolInfo
        assertFalse(SonyTandemConstants.PROTOCOL_VERSIONS_V2.contains(response.protocolVersion))
        val state = initialState().withProtocolInfo(response.protocolVersion)
        assertEquals(true, state.deviceInfo.protocolVersionAccepted)
        assertEquals(0x03003034, state.connectedProfile?.protocolVersion)

        // Synthetic V2 support list: L/R battery, cradle, NC/ASM level, EQ,
        // DSEE, and an unknown code between the known entries.
        val functions = (SonyTandemV2Table1Protocol.parse(
            byteArrayOf(
                0x0E, 0x07, 0x00, 0x06,
                0x21, 0x00, 0x22, 0x01, 0x7F, 0x02,
                0x64, 0x03, 0x50, 0x04, 0xE2.toByte(), 0x05,
            ),
        ) as ParsedTandemResponse.SupportFunction).functions
        assertEquals(listOf(0x21, 0x22, 0x64, 0x50, 0xE2), functions.map { it.code.toInt() and 0xFF })
        val profile = requireNotNull(state.connectedProfile)
        val probes = SonyCapabilityProbe.buildCapabilityProbeCommands(profile, functions)
        assertTrue(probes.isNotEmpty())
        val probed = SonyCapabilityProbe.applyToProfile(profile, functions, HeadphoneTransport.GATT_HPC)

        assertEquals(0x03003034, probed.protocolVersion)
        assertTrue(probed.capabilitiesKnown)
        assertTrue(probed.capabilities.features.containsAll(
            setOf(HeadphoneFeature.BATTERY, HeadphoneFeature.NOISE_CONTROL,
                HeadphoneFeature.AMBIENT_LEVEL, HeadphoneFeature.EQ, HeadphoneFeature.UPSCALING),
        ))
        assertTrue(probed.capabilities.writableNoiseControlTypes.isNotEmpty())
        assertTrue(HeadphoneAdapterRegistry.buildRefreshCommands(probed).isNotEmpty())
    }

    @Test
    fun unknownV1VersionIsAcceptedAndRetained() {
        val response = SonyTandemV1Table1Protocol.parse(
            byteArrayOf(0x0E, 0x01, 0x00, 0x70, 0x20),
        ) as ParsedTandemResponse.ProtocolInfo
        assertFalse(SonyTandemConstants.PROTOCOL_VERSIONS.contains(response.protocolVersion))
        val state = SonyHeadphoneUiState().withProtocolInfo(response.protocolVersion)

        assertEquals(0x7020, state.deviceInfo.protocolVersion)
        assertEquals(true, state.deviceInfo.protocolVersionAccepted)
        assertEquals(null, state.connectedProfile)
        assertFalse(state.capabilitiesKnown)
        assertFalse(state.initialValuesReady)
    }
}
