package dev.sonypods.ble

import dev.sonypods.protocol.SonyTandemConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SonySppPayloadMapperTest {
    @Test
    fun outbound_table1DataMdr_usesDataMdrFrameAndDropsAppDataType() {
        val mapped = SonySppPayloadMapper.outboundFromTandemBytes(byteArrayOf(0x0E, 0x22, 0x01))

        assertEquals(SonySppFrameType.DATA_MDR, mapped.frameType)
        assertArrayEquals(byteArrayOf(0x22, 0x01), mapped.payload)
    }

    @Test
    fun outbound_table2DataMdrNo2_usesNo2FrameAndDropsAppDataType() {
        val mapped = SonySppPayloadMapper.outboundFromTandemBytes(byteArrayOf(0x0F, 0x32, 0x01))

        assertEquals(SonySppFrameType.DATA_MDR_NO2, mapped.frameType)
        assertArrayEquals(byteArrayOf(0x32, 0x01), mapped.payload)
    }

    @Test
    fun inbound_dataMdrNo2_restoresAppDataMdrNo2() {
        val raw = SonySppPayloadMapper.inboundToTandemBytes(SonySppFrameType.DATA_MDR_NO2, byteArrayOf(0x33, 0x01))

        assertArrayEquals(byteArrayOf(SonyTandemConstants.DATA_MDR_NO2, 0x33, 0x01), raw)
    }

    @Test
    fun inbound_shotMdrNo2_restoresAppDataMdrNo2() {
        val raw = SonySppPayloadMapper.inboundToTandemBytes(SonySppFrameType.SHOT_MDR_NO2, byteArrayOf(0x33, 0x01))

        assertArrayEquals(byteArrayOf(SonyTandemConstants.DATA_MDR_NO2, 0x33, 0x01), raw)
    }

    @Test
    fun inbound_dataMdr_restoresAppDataMdr() {
        val raw = SonySppPayloadMapper.inboundToTandemBytes(SonySppFrameType.DATA_MDR, byteArrayOf(0x23, 0x01))

        assertArrayEquals(byteArrayOf(SonyTandemConstants.DATA_MDR, 0x23, 0x01), raw)
    }

    @Test
    fun inbound_ackHasNoTandemPayload() {
        assertNull(SonySppPayloadMapper.inboundToTandemBytes(SonySppFrameType.ACK, byteArrayOf()))
    }
}
