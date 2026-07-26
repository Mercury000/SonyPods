package dev.sonypods.ble

import dev.sonypods.headphones.TandemChannel
import dev.sonypods.protocol.SonyGatt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 1: Tests for GATT service → channel mapping.
 *
 * Verifies that SonyGatt UUIDs match the documented well-known values and that
 * the TandemChannel routing helpers distinguish V2 HPC, V2 MC, V1 MC, and SPP
 * channels.
 *
 * Note: SonyBleClient itself is Android-dependent (BluetoothGatt, Context) and
 * cannot be unit-tested directly. These tests validate the UUID constants and
 * the channel routing logic used by SonyBleClient.
 */
class SonyBleClientChannelTest {

    // ── SonyGatt service UUID correctness ────────────────────────────────────

    @Test
    fun tandemV2HpcService_uuidMatchesDocumentedValue() {
        // service(0x20) → 5b833e20-6bc7-4802-8e9a-723ceca4bd8f
        val uuid = SonyGatt.TANDEM_V2_HPC_SERVICE
        assertEquals("5b833e20-6bc7-4802-8e9a-723ceca4bd8f", uuid.toString())
    }

    @Test
    fun tandemV2McService_uuidMatchesDocumentedValue() {
        // service(0x21) → 5b833e21-6bc7-4802-8e9a-723ceca4bd8f
        val uuid = SonyGatt.TANDEM_V2_MC_SERVICE
        assertEquals("5b833e21-6bc7-4802-8e9a-723ceca4bd8f", uuid.toString())
    }

    @Test
    fun tandemV1McService_uuidMatchesDocumentedValue() {
        // service(0x23) → 5b833e23-6bc7-4802-8e9a-723ceca4bd8f
        val uuid = SonyGatt.TANDEM_V1_MC_SERVICE
        assertEquals("5b833e23-6bc7-4802-8e9a-723ceca4bd8f", uuid.toString())
    }

    @Test
    fun hpcToAccCharacteristic_uuidMatchesDocumentedValue() {
        // characteristic(0x60) → 5b833c60-6bc7-4802-8e9a-723ceca4bd8f
        val uuid = SonyGatt.TANDEM_HPC_TO_ACC
        assertEquals("5b833c60-6bc7-4802-8e9a-723ceca4bd8f", uuid.toString())
    }

    @Test
    fun hpcFromAccCharacteristic_uuidMatchesDocumentedValue() {
        // characteristic(0x61) → 5b833c61-6bc7-4802-8e9a-723ceca4bd8f
        val uuid = SonyGatt.TANDEM_HPC_FROM_ACC
        assertEquals("5b833c61-6bc7-4802-8e9a-723ceca4bd8f", uuid.toString())
    }

    // ── MC characteristic UUIDs (added in Phase 5) ───────────────────────────

    @Test
    fun mcToAccCharacteristic_matchesExpectedUuid() {
        assertEquals("5b833c62-6bc7-4802-8e9a-723ceca4bd8f", SonyGatt.TANDEM_MC_TO_ACC.toString())
    }

    @Test
    fun mcFromAccCharacteristic_matchesExpectedUuid() {
        assertEquals("5b833c63-6bc7-4802-8e9a-723ceca4bd8f", SonyGatt.TANDEM_MC_FROM_ACC.toString())
    }

    // ── Channel mapping from service UUID ────────────────────────────────────

    @Test
    fun channelFromService_v2Hpc() {
        assertEquals(
            TandemChannel.GATT_V2_HPC,
            TandemChannel.fromServiceUuid(SonyGatt.TANDEM_V2_HPC_SERVICE),
        )
    }

    @Test
    fun channelFromService_v2Mc() {
        assertEquals(
            TandemChannel.GATT_V2_MC,
            TandemChannel.fromServiceUuid(SonyGatt.TANDEM_V2_MC_SERVICE),
        )
    }

    @Test
    fun channelFromService_v1Mc() {
        assertEquals(
            TandemChannel.GATT_V1_MC,
            TandemChannel.fromServiceUuid(SonyGatt.TANDEM_V1_MC_SERVICE),
        )
    }

    @Test
    fun channelFromService_unknownServiceReturnsNull() {
        val unknownUuid = UUID.fromString("00000000-0000-0000-0000-000000000000")
        assertEquals(null, TandemChannel.fromServiceUuid(unknownUuid))
    }

    @Test
    fun channelFromService_v2HpcIsNotSpp() {
        val v2HpcChannel = TandemChannel.fromServiceUuid(SonyGatt.TANDEM_V2_HPC_SERVICE)
        assertFalse(v2HpcChannel == TandemChannel.SPP_MDR)
    }

    @Test
    fun tandemEndpointSupportState_v2HpcIsSupported() {
        assertNull(tandemEndpointSupportState(listOf(SonyGatt.TANDEM_V2_HPC_SERVICE)))
    }

    @Test
    fun tandemEndpointSupportState_v1McOnlyIsSupported() {
        assertNull(tandemEndpointSupportState(listOf(SonyGatt.TANDEM_V1_MC_SERVICE)))
    }

    // ── Channel characteristic resolution ────────────────────────────────────

    @Test
    fun v2HpcChannel_characteristics() {
        // V2 HPC uses HPC TO_ACC / FROM_ACC characteristics
        assertEquals(SonyGatt.TANDEM_HPC_TO_ACC, GattEndpoint.forChannel(TandemChannel.GATT_V2_HPC).toAccUuid)
        assertEquals(SonyGatt.TANDEM_HPC_FROM_ACC, GattEndpoint.forChannel(TandemChannel.GATT_V2_HPC).fromAccUuid)
    }

    @Test
    fun mcChannels_useMcCharacteristics() {
        // Both V2 MC and V1 MC use the same MC TO_ACC / FROM_ACC characteristics
        val mcToAcc = SonyGatt.characteristic(0x62)
        val mcFromAcc = SonyGatt.characteristic(0x63)

        listOf(TandemChannel.GATT_V2_MC, TandemChannel.GATT_V1_MC).forEach { channel ->
            val endpoint = GattEndpoint.forChannel(channel)
            assertEquals("MC TO_ACC mismatch for $channel", mcToAcc, endpoint.toAccUuid)
            assertEquals("MC FROM_ACC mismatch for $channel", mcFromAcc, endpoint.fromAccUuid)
        }
    }

    @Test
    fun tandemGattRouting_endpointSpecUsesMcWriteCharacteristicForMcChannels() {
        listOf(TandemChannel.GATT_V2_MC, TandemChannel.GATT_V1_MC).forEach { channel ->
            val spec = TandemGattRouting.endpointSpecFor(channel)
            assertEquals(channel, spec.channel)
            assertEquals(SonyGatt.TANDEM_MC_TO_ACC, spec.toAccUuid)
            assertEquals(SonyGatt.TANDEM_MC_FROM_ACC, spec.fromAccUuid)
        }
    }

    @Test
    fun tandemGattRouting_distinguishesV2AndV1McFromAccByServiceUuid() {
        assertEquals(
            TandemChannel.GATT_V2_MC,
            TandemGattRouting.fromAccChannelFor(
                serviceUuid = SonyGatt.TANDEM_V2_MC_SERVICE,
                characteristicUuid = SonyGatt.TANDEM_MC_FROM_ACC,
            ),
        )
        assertEquals(
            TandemChannel.GATT_V1_MC,
            TandemGattRouting.fromAccChannelFor(
                serviceUuid = SonyGatt.TANDEM_V1_MC_SERVICE,
                characteristicUuid = SonyGatt.TANDEM_MC_FROM_ACC,
            ),
        )
    }

    @Test
    fun tandemGattRouting_notificationOrderSubscribesHpcThenV2McThenV1Mc() {
        assertEquals(
            listOf(TandemChannel.GATT_V2_HPC, TandemChannel.GATT_V2_MC, TandemChannel.GATT_V1_MC),
            TandemGattRouting.notificationOrder(
                listOf(TandemChannel.GATT_V1_MC, TandemChannel.SPP_MDR, TandemChannel.GATT_V2_MC, TandemChannel.GATT_V2_HPC),
            ),
        )
    }

    // ── Channel uniqueness ───────────────────────────────────────────────────

    @Test
    fun allChannels_haveDistinctIdentities() {
        val channels = TandemChannel.entries.toSet()
        assertEquals(4, channels.size) // SPP_MDR, GATT_V2_HPC, GATT_V2_MC, GATT_V1_MC
    }

    @Test
    fun gattChannels_eachHaveUniqueServiceUuid() {
        val gattChannels = TandemChannel.entries.filter { it != TandemChannel.SPP_MDR }
        val serviceUuids = gattChannels.map { GattEndpoint.forChannel(it).serviceUuid }
        assertEquals(serviceUuids.size, serviceUuids.toSet().size)
    }

    @Test
    fun gattChannels_allHaveDefinedEndpoints() {
        TandemChannel.entries.filter { it != TandemChannel.SPP_MDR }.forEach { channel ->
            val endpoint = GattEndpoint.forChannel(channel)
            assertTrue("${endpoint.toAccUuid} should not be null", endpoint.toAccUuid.toString().isNotEmpty())
            assertTrue("${endpoint.fromAccUuid} should not be null", endpoint.fromAccUuid.toString().isNotEmpty())
        }
    }
}

// ── Test-only GATT endpoint helper ──────────────────────────────────────────

/**
 * Describes the GATT characteristic pair for a Tandem channel.
 * Phase 5: absorbed into SonyBleClient's endpoint management.
 */
data class GattEndpoint(
    val serviceUuid: UUID,
    val toAccUuid: UUID,
    val fromAccUuid: UUID,
) {
    companion object {
        fun forChannel(channel: TandemChannel): GattEndpoint = when (channel) {
            TandemChannel.GATT_V2_HPC -> GattEndpoint(
                serviceUuid = SonyGatt.TANDEM_V2_HPC_SERVICE,
                toAccUuid = SonyGatt.TANDEM_HPC_TO_ACC,
                fromAccUuid = SonyGatt.TANDEM_HPC_FROM_ACC,
            )
            TandemChannel.GATT_V2_MC -> GattEndpoint(
                serviceUuid = SonyGatt.TANDEM_V2_MC_SERVICE,
                toAccUuid = SonyGatt.TANDEM_MC_TO_ACC,
                fromAccUuid = SonyGatt.TANDEM_MC_FROM_ACC,
            )
            TandemChannel.GATT_V1_MC -> GattEndpoint(
                serviceUuid = SonyGatt.TANDEM_V1_MC_SERVICE,
                toAccUuid = SonyGatt.TANDEM_MC_TO_ACC,
                fromAccUuid = SonyGatt.TANDEM_MC_FROM_ACC,
            )
            TandemChannel.SPP_MDR -> throw IllegalArgumentException("SPP has no GATT endpoint")
        }
    }
}
