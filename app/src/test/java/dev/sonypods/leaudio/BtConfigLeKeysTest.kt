package dev.sonypods.leaudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The excerpt mirrors the real `/data/misc/bluedroid/bt_config.conf` layout on the test phone:
 * the LE Audio identity had LE keys while the earbud reusing the classic address had none, and
 * that difference is what decides whether a destructive re-pair is needed.
 */
class BtConfigLeKeysTest {
    private val config = """
        [Adapter]
        Address = aa:bb:cc:dd:ee:ff

        [c5:93:15:6b:e6:34]
        Name = LinkBuds S
        DevType = 3
        LE_KEY_PENC = 199dd738d831a3f6f461ad7a5751a0b6000000000000000000000110
        LE_KEY_PID = bd4a0f12e3cdb305842b5ad9d9996f3701c593156be634

        [80:99:e7:d8:60:09]
        Name = LinkBuds S
        DevType = 3
        LinkKey = c4b63bb46b7a60dc06849796ffe4867d
        PeerSupportedCodecs = SBC,AAC,LDAC
    """.trimIndent()

    private fun hasKeys(address: String) =
        btConfigSectionHasLeKeys(config.lineSequence(), address)

    @Test
    fun theLeIdentitySectionReportsItsKeys() {
        assertTrue(hasKeys("c5:93:15:6b:e6:34"))
    }

    @Test
    fun addressCaseDoesNotMatter() {
        // Callers hand over uppercase addresses from BluetoothDevice; the file stores lowercase.
        assertTrue(hasKeys("C5:93:15:6B:E6:34"))
    }

    @Test
    fun aClassicOnlyBondReportsNoLeKeys() {
        // The whole point: this section has a LinkKey but no LE key, so the native LE Audio
        // client rejects it as "not bonded".
        assertFalse(hasKeys("80:99:e7:d8:60:09"))
    }

    @Test
    fun keysAreNotBorrowedFromAnotherSection() {
        // A file-wide search for LE_KEY_ would find the other device's keys and skip the
        // re-pair, leaving the ear silent with no indication why.
        assertFalse(hasKeys("11:22:33:44:55:66"))
    }

    @Test
    fun anAdapterSectionWithoutKeysIsNotConfusedForADevice() {
        assertFalse(hasKeys("Adapter"))
    }

    @Test
    fun aSectionWhoseKeysComeAfterOtherEntriesIsStillDetected() {
        val reordered = """
            [80:99:e7:d8:60:09]
            Name = LinkBuds S
            LinkKey = c4b63bb46b7a60dc06849796ffe4867d
            LE_KEY_PENC = deadbeef
        """.trimIndent()

        assertTrue(btConfigSectionHasLeKeys(reordered.lineSequence(), "80:99:e7:d8:60:09"))
    }
}
