package dev.sonypods.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LEA_RET_CAPABILITY` (0x41) for the LE/Classic device kinds — the reply that names the headset's
 * own identities. Layout mirrored from Sound Connect `kf0.C21806t` / `C21804r` / `C21807u`: 17-byte
 * ASCII address strings, the first being the classic BD address.
 */
class SonyTandemLeaIdentityProtocolTest {

    private fun addressBytes(text: String): ByteArray {
        require(text.length == 17) { "a BD address string is 17 characters" }
        return text.toByteArray(Charsets.US_ASCII)
    }

    private fun frame(type: Byte, vararg addresses: String): ByteArray =
        byteArrayOf(0x0E, 0x41, type) + addresses.fold(ByteArray(0)) { acc, a -> acc + addressBytes(a) }

    @Test
    fun twsReplyCarriesTheClassicAddressAndBothLeIdentities() {
        val response = SonyTandemV2Table1Protocol.parse(
            frame(0x00, "AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03")
        ) as ParsedTandemResponse.LeaIdentity

        assertEquals(LeaInquiredType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD, response.type)
        assertEquals("AA:BB:CC:DD:EE:01", response.uniqueId)
        assertEquals(listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"), response.leAddresses)
        assertEquals(SonyTable.NO_1, response.table)
    }

    @Test
    fun hbsReplyCarriesOneLeIdentity() {
        val response = SonyTandemV2Table1Protocol.parse(
            frame(0x01, "AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02")
        ) as ParsedTandemResponse.LeaIdentity

        assertEquals(LeaInquiredType.HBS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD, response.type)
        assertEquals("AA:BB:CC:DD:EE:01", response.uniqueId)
        assertEquals(listOf("AA:BB:CC:DD:EE:02"), response.leAddresses)
    }

    @Test
    fun aLowercaseReplyIsAcceptedAndNormalized() {
        // The headset's own validator (SC `C15210a`) accepts either case, and SC then compares with
        // a raw String.equals — so the case has to be settled here rather than at every comparison.
        val response = SonyTandemV2Table1Protocol.parse(
            frame(0x00, "aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02", "aa:bb:cc:dd:ee:03")
        ) as ParsedTandemResponse.LeaIdentity

        assertEquals("AA:BB:CC:DD:EE:01", response.uniqueId)
        assertEquals(listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"), response.leAddresses)
    }

    @Test
    fun aTruncatedReplyIsNotReadAsAHeadsetWithoutLeIdentities() {
        val response = SonyTandemV2Table1Protocol.parse(frame(0x00, "AA:BB:CC:DD:EE:01"))

        assertTrue(response is ParsedTandemResponse.Unknown)
    }

    @Test
    fun aMalformedAddressRejectsTheWholeReply() {
        val response = SonyTandemV2Table1Protocol.parse(
            frame(0x00, "AA:BB:CC:DD:EE:01", "not:a:bd:addres:x", "AA:BB:CC:DD:EE:03")
        )

        assertTrue(response is ParsedTandemResponse.Unknown)
    }

    @Test
    fun anUnrelatedInquiredTypeIsNotAnIdentityReply() {
        // 0x04 is CLASSIC-only quick-access exclusion, not a device kind.
        val response = SonyTandemV2Table1Protocol.parse(
            frame(0x04, "AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02")
        )

        assertTrue(response is ParsedTandemResponse.Unknown)
    }

    @Test
    fun theCapabilityRequestNamesTheInquiredType() {
        val bytes = SonyTandemV2Table1Protocol.buildGetLeaCapability(
            LeaInquiredType.TWS_SUPPORTS_A2DP_LEA_UNI_LEA_BROAD_WITH_CTKD
        )

        // [dataType][LEA_GET_CAPABILITY][type]
        assertEquals(0x40.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
    }

    @Test
    fun theTable2ParamReplyCarriesTheIdentityResolvingKey() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val response = SonyTandemV2Table2Protocol.parse(
            byteArrayOf(0x0F, 0x67, 0x03) + key
        ) as ParsedTandemResponse.LeaIdentityResolvingKey

        assertEquals(SonyTable.NO_2, response.table)
        assertEquals(key.toList(), response.key.toList())
    }
}
