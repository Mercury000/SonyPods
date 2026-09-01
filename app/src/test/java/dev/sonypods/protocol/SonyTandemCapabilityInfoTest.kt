package dev.sonypods.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the CONNECT_GET/RET_CAPABILITY_INFO (0x02/0x03) exchange, the
 * connection-time capability-counter gate that decides whether the per-domain
 * capability probe may be omitted on reconnection.
 *
 * Byte layouts reverse-engineered from Sound Connect 13.2.1:
 * - GET request `[cmd 0x02][FIXED_VALUE 0x00]`: V2 `ff0.C16467a` gate
 *   (`bArr.length==2 && bArr[0]==0x02 && bArr[1]==FIXED_VALUE`), V1 `qe0.C26552h`.
 * - RET response body `[cmd 0x03][type 0x00][counter][len][identifier...]`:
 *   V2 `ff0.C16471e` (`m69415c`=bArr[2], `m69416e`=bArr[3], id from bArr[4],
 *   gate `bArr.length - 4 == bArr[3]`), V1 `qe0.C26624v1.mo94092c`
 *   (type=bArr[1], counter=bArr[2], len=bArr[3], id=bArr[4..]).
 * Both cap the identifier at 128 bytes; the engine payload (dataType+command
 * stripped) is `[type][counter][len][id...]`.
 */
class SonyTandemCapabilityInfoTest {

    // ── GET request wire ──

    @Test
    fun v2GetCapabilityInfo_isFixedValueZero() {
        val bytes = SonyTandemV2Table1Protocol.buildGetCapabilityInfo()
        assertEquals(byteArrayOf(0x0E, 0x02, 0x00).toList(), bytes.toList())
    }

    @Test
    fun v1GetCapabilityInfo_isFixedValueZero() {
        val bytes = SonyTandemV1Table1Protocol.buildGetCapabilityInfo()
        assertEquals(byteArrayOf(0x0E, 0x02, 0x00).toList(), bytes.toList())
    }

    // ── RET response wire ──

    @Test
    fun v2RetCapabilityInfo_parsesCounterAndIdentifier() {
        // [0x0E][0x03][type 0x00][counter 0x05][len 0x04] 'XYZ1'
        val raw = byteArrayOf(0x0E, 0x03, 0x00, 0x05, 0x04, 'X'.code.toByte(), 'Y'.code.toByte(), 'Z'.code.toByte(), '1'.code.toByte())
        val response = SonyTandemV2Table1Protocol.parse(raw) as ParsedTandemResponse.ConnectCapabilityInfo
        assertEquals(5, response.capabilityCounter)
        assertEquals("XYZ1", response.identifier)
    }

    @Test
    fun v1RetCapabilityInfo_parsesCounterAndIdentifier() {
        val raw = byteArrayOf(0x0E, 0x03, 0x00, 0xFE.toByte(), 0x02, 'A'.code.toByte(), 'B'.code.toByte())
        val response = SonyTandemV1Table1Protocol.parse(raw) as ParsedTandemResponse.ConnectCapabilityInfo
        assertEquals(254, response.capabilityCounter)
        assertEquals("AB", response.identifier)
    }

    @Test
    fun retCapabilityInfo_counterIsUnsigned() {
        val raw = byteArrayOf(0x0E, 0x03, 0x00, 0xFF.toByte(), 0x01, 'A'.code.toByte())
        val response = SonyTandemV2Table1Protocol.parse(raw) as ParsedTandemResponse.ConnectCapabilityInfo
        assertEquals(255, response.capabilityCounter)
    }

    @Test
    fun retCapabilityInfo_rejectsNonFixedType() {
        val raw = byteArrayOf(0x0E, 0x03, 0x01, 0x00, 0x00)
        assertTrue(SonyTandemV2Table1Protocol.parse(raw) is ParsedTandemResponse.Unknown)
    }

    @Test
    fun retCapabilityInfo_rejectsShortPayload() {
        // only 3 payload bytes: no room for the fixed 4-byte head
        val raw = byteArrayOf(0x0E, 0x03, 0x00, 0x05)
        assertTrue(SonyTandemV1Table1Protocol.parse(raw) is ParsedTandemResponse.Unknown)
    }

    @Test
    fun retCapabilityInfo_rejectsTruncatedIdentifier() {
        // len=4 but only 2 identifier bytes follow
        val raw = byteArrayOf(0x0E, 0x03, 0x00, 0x05, 0x04, 'A'.code.toByte(), 'B'.code.toByte())
        assertTrue(SonyTandemV2Table1Protocol.parse(raw) is ParsedTandemResponse.Unknown)
    }

    @Test
    fun retCapabilityInfo_capsOversizedIdentifierAt128() {
        // len=200 > 128: SC caps display at 128 bytes, never errors
        val id = "A".repeat(200).toByteArray()
        val raw = byteArrayOf(0x0E, 0x03, 0x00, 0x05, 0xC8.toByte()) + id
        val response = SonyTandemV2Table1Protocol.parse(raw) as ParsedTandemResponse.ConnectCapabilityInfo
        assertEquals(5, response.capabilityCounter)
        assertEquals("A".repeat(128), response.identifier)
    }
}
