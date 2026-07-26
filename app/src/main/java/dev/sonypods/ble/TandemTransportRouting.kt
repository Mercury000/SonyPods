package dev.sonypods.ble

import dev.sonypods.headphones.TandemChannel
import dev.sonypods.protocol.SonyGatt
import dev.sonypods.protocol.SonyTandemConstants
import java.util.UUID

data class PendingTandemWrite(
    val channel: TandemChannel,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingTandemWrite) return false
        return channel == other.channel && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * channel.hashCode() + bytes.contentHashCode()
}

data class TandemGattEndpointSpec(
    val channel: TandemChannel,
    val serviceUuid: UUID,
    val toAccUuid: UUID,
    val fromAccUuid: UUID,
)

object TandemGattRouting {
    private val gattNotificationOrder = mapOf(
        TandemChannel.GATT_V2_HPC to 0,
        TandemChannel.GATT_V2_MC to 1,
        TandemChannel.GATT_V1_MC to 2,
    )

    fun endpointSpecFor(channel: TandemChannel): TandemGattEndpointSpec = when (channel) {
        TandemChannel.GATT_V2_HPC -> TandemGattEndpointSpec(
            channel = channel,
            serviceUuid = SonyGatt.TANDEM_V2_HPC_SERVICE,
            toAccUuid = SonyGatt.TANDEM_HPC_TO_ACC,
            fromAccUuid = SonyGatt.TANDEM_HPC_FROM_ACC,
        )
        TandemChannel.GATT_V2_MC -> TandemGattEndpointSpec(
            channel = channel,
            serviceUuid = SonyGatt.TANDEM_V2_MC_SERVICE,
            toAccUuid = SonyGatt.TANDEM_MC_TO_ACC,
            fromAccUuid = SonyGatt.TANDEM_MC_FROM_ACC,
        )
        TandemChannel.GATT_V1_MC -> TandemGattEndpointSpec(
            channel = channel,
            serviceUuid = SonyGatt.TANDEM_V1_MC_SERVICE,
            toAccUuid = SonyGatt.TANDEM_MC_TO_ACC,
            fromAccUuid = SonyGatt.TANDEM_MC_FROM_ACC,
        )
        TandemChannel.SPP_MDR -> error("SPP has no GATT endpoint")
    }

    fun notificationOrder(channels: Iterable<TandemChannel>): List<TandemChannel> =
        channels
            .filter { it in gattNotificationOrder }
            .sortedBy { gattNotificationOrder.getValue(it) }

    fun fromAccChannelFor(serviceUuid: UUID?, characteristicUuid: UUID?): TandemChannel? {
        if (serviceUuid == null || characteristicUuid == null) return null
        return TandemChannel.entries
            .filter { it != TandemChannel.SPP_MDR }
            .firstOrNull { channel ->
                val spec = endpointSpecFor(channel)
                spec.serviceUuid == serviceUuid && spec.fromAccUuid == characteristicUuid
            }
    }

    fun fromAccChannel(
        endpoints: Map<TandemChannel, GattTandemEndpoint>,
        serviceUuid: UUID?,
        characteristicUuid: UUID?,
    ): TandemChannel? =
        fromAccChannelFor(serviceUuid, characteristicUuid)?.takeIf { it in endpoints }
            ?: endpoints.values
                .filter { it.fromAcc.uuid == characteristicUuid }
                .singleOrNull()
                ?.channel
}

data class SppPayloadMapping(
    val frameType: SonySppFrameType,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SppPayloadMapping) return false
        return frameType == other.frameType && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * frameType.hashCode() + payload.contentHashCode()
}

enum class SonySppFrameType(val code: Byte, val ackRequired: Boolean) {
    DATA_MDR(0x0C, true),
    DATA_MDR_NO2(0x0E, true),
    ACK(0x01, false),
    SHOT_MDR(0x1C, false),
    SHOT_MDR_NO2(0x1E, false),
    LARGE_DATA_MDR(0x2C, true),
    UNKNOWN(0xFF.toByte(), true);

    companion object {
        fun fromByte(code: Byte): SonySppFrameType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

object SonySppPayloadMapper {
    fun outboundFromTandemBytes(bytes: ByteArray): SppPayloadMapping {
        if (bytes.isEmpty()) return SppPayloadMapping(SonySppFrameType.DATA_MDR, bytes)
        return when (bytes[0]) {
            SonyTandemConstants.DATA_MDR ->
                SppPayloadMapping(SonySppFrameType.DATA_MDR, bytes.drop(1).toByteArray())
            SonyTandemConstants.DATA_MDR_NO2 ->
                SppPayloadMapping(SonySppFrameType.DATA_MDR_NO2, bytes.drop(1).toByteArray())
            else -> SppPayloadMapping(SonySppFrameType.DATA_MDR, bytes)
        }
    }

    fun inboundToTandemBytes(type: SonySppFrameType, payload: ByteArray): ByteArray? =
        when (type) {
            SonySppFrameType.DATA_MDR,
            SonySppFrameType.SHOT_MDR,
            SonySppFrameType.LARGE_DATA_MDR ->
                byteArrayOf(SonyTandemConstants.DATA_MDR) + payload
            SonySppFrameType.DATA_MDR_NO2,
            SonySppFrameType.SHOT_MDR_NO2 ->
                byteArrayOf(SonyTandemConstants.DATA_MDR_NO2) + payload
            SonySppFrameType.ACK,
            SonySppFrameType.UNKNOWN -> null
        }
}
