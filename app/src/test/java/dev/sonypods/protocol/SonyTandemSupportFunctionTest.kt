package dev.sonypods.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CONNECT_GET/RET_SUPPORT_FUNCTION (0x06/0x07) probing, byte
 * layouts reverse-engineered from Sound Connect 13.2.1:
 * - V2 `ff0.C16478l` / `ze0.C32196c`: pairs (FunctionType.code, order), sorted by order.
 * - V1 `qe0.C26610s2` / `qe0.C26538e0`: flat single-byte list, no order.
 */
class SonyTandemSupportFunctionTest {

    @Test
    fun v2GetSupportFunction_payloadIsFixedValue() {
        val bytes = SonyTandemV2Table1Protocol.buildGetSupportFunction()
        assertEquals(byteArrayOf(0x0E, 0x06, 0x00).toList(), bytes.toList())
    }

    @Test
    fun v1GetSupportFunction_payloadIsFixedValue() {
        val bytes = SonyTandemV1Table1Protocol.buildGetSupportFunction()
        assertEquals(byteArrayOf(0x0E, 0x06, 0x00).toList(), bytes.toList())
    }

    @Test
    fun v2RetSupportFunction_parsesOrderedPairs() {
        // message: 0x0E 0x07 [0x00 fixed] [0x03 count]
        //          0x61 NOISE_CANCELLING_ONOFF order=0x02
        //          0x51 EBB order=0x00
        //          0x50 PRESET_EQ order=0x01
        val raw = byteArrayOf(0x0E, 0x07, 0x00, 0x03, 0x61, 0x02, 0x51, 0x00, 0x50, 0x01)
        val response = SonyTandemV2Table1Protocol.parse(raw) as ParsedTandemResponse.SupportFunction

        assertEquals(3, response.functions.size)
        // sorted by order field: EBB(order 0), PRESET_EQ(1), NC_ONOFF(2)
        assertEquals(
            listOf(
                SonySupportedFunction(0x51, 0),
                SonySupportedFunction(0x50, 1),
                SonySupportedFunction(0x61, 2),
            ),
            response.functions,
        )
    }

    @Test
    fun v2RetSupportFunction_skipsUnknownTypesWithoutFailing() {
        // 0xFE (ASSIGNABLE_SETTING_WITH_LIMITATION) + an unknown 0x7F byte
        val raw = byteArrayOf(0x0E, 0x07, 0x00, 0x02, 0xFE.toByte(), 0x00, 0x7F, 0x01)
        val response = SonyTandemV2Table1Protocol.parse(raw) as ParsedTandemResponse.SupportFunction

        assertEquals(1, response.functions.size)
        assertEquals(0xFE.toByte(), response.functions[0].code)
    }

    @Test
    fun v1RetSupportFunction_parsesFlatList() {
        // message: 0x0E 0x07 [0x00 fixed] [0x03 count]
        //          0x62 NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE
        //          0x51 PRESET_EQ
        //          0xA1 PLAYBACK_CONTROLLER
        val raw = byteArrayOf(0x0E, 0x07, 0x00, 0x03, 0x62, 0x51, 0xA1.toByte())
        val response = SonyTandemV1Table1Protocol.parse(raw) as ParsedTandemResponse.SupportFunction

        assertEquals(3, response.functions.size)
        assertEquals(0x62.toByte(), response.functions[0].code)
        assertEquals(0x51.toByte(), response.functions[1].code)
        assertEquals(0xA1.toByte(), response.functions[2].code)
        // V1 order = list position
        assertEquals(listOf(0, 1, 2), response.functions.map { it.order })
    }

    @Test
    fun v2Table2RetSupportFunction_parsesNo2Functions() {
        // table2 CONNECT_RET_SUPPORT_FUNCTION (dataType 0x0F) with NO_2 types
        // 0x30 PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT, 0x50 SAFE_LISTENING_HBS_1
        val raw = byteArrayOf(0x0F, 0x07, 0x00, 0x02, 0x30, 0x00, 0x50, 0x01)
        val response = SonyTandemV2Table2Protocol.parse(raw) as ParsedTandemResponse.SupportFunction

        assertEquals(2, response.functions.size)
        assertEquals(0x30.toByte(), response.functions[0].code)
        assertEquals(0x50.toByte(), response.functions[1].code)
    }

    @Test
    fun v2Table2GetSupportFunction_payloadIsFixedValue() {
        val bytes = SonyTandemV2Table2Protocol.buildGetSupportFunction()
        assertEquals(byteArrayOf(0x0F, 0x06, 0x00).toList(), bytes.toList())
    }

    @Test
    fun malformedPayload_returnsEmptyList() {
        assertTrue(SonyTandemV2Table1Protocol.parseSupportFunction(byteArrayOf(0x00)).isEmpty())
        assertTrue(SonyTandemV1Table1Protocol.parseSupportFunction(byteArrayOf()).isEmpty())
    }

    @Test
    fun functionTypeEnum_roundTripsKnownCodes() {
        assertEquals(SonyV2FunctionType.NOISE_CANCELLING_ONOFF, SonyV2FunctionType.fromByteCode(SonyTable.NO_1, 0x61))
        assertEquals(SonyV2FunctionType.PRESET_EQ, SonyV2FunctionType.fromByteCode(SonyTable.NO_1, 0x50))
        assertEquals(SonyV2FunctionType.EBB, SonyV2FunctionType.fromByteCode(SonyTable.NO_1, 0x51))
        assertEquals(SonyV2FunctionType.OUT_OF_RANGE, SonyV2FunctionType.fromByteCode(SonyTable.NO_1, 0x7F))
        assertEquals(SonyV2FunctionType.OUT_OF_RANGE, SonyV2FunctionType.fromByteCode(SonyTable.NO_2, 0x7F))
        assertEquals(SonyV1FunctionType.PRESET_EQ, SonyV1FunctionType.fromByteCode(0x51))
        assertEquals(SonyV1FunctionType.OUT_OF_RANGE, SonyV1FunctionType.fromByteCode(0x7F))
    }
}
