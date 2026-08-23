package dev.sonypods.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two LE Audio verdicts the surfaces read. Both are pure derivations over the snapshot,
 * so they are checked here rather than through a Bundle round-trip (no Android runtime in
 * these unit tests).
 */
class SonyStateSnapshotLeAudioTest {
    private val base = SonyStateSnapshot(deviceAddress = "AA:BB:CC:DD:EE:FF")

    /** Before either witness speaks, we must not claim LC3 — that is the "waiting" UI state. */
    @Test
    fun noWitness_isNotUsingLeAudio() {
        assertFalse(base.usingLeAudio)
        assertFalse(base.connectedViaLeAudio)
    }

    /**
     * The stack routing this headset's LE Audio group is conclusive on its own: the headset's
     * streaming status is momentary and can be missed, which is what left the UI stuck on
     * "等待系统建立 LC3".
     */
    @Test
    fun systemActiveRoute_isUsingLeAudio() {
        val snapshot = base.copy(leAudioSystemActive = true)

        assertTrue(snapshot.usingLeAudio)
        assertTrue(snapshot.connectedViaLeAudio)
    }

    /** The headset's own report is equally conclusive, from either bud. */
    @Test
    fun budStreamingUnicast_isUsingLeAudio() {
        listOf(
            base.copy(leaStreamingStatusL = SonyStateSnapshot.LEA_STREAMING_UNICAST),
            base.copy(leaStreamingStatusR = SonyStateSnapshot.LEA_STREAMING_UNICAST),
        ).forEach { snapshot ->
            assertTrue(snapshot.usingLeAudio)
            assertTrue(snapshot.connectedViaLeAudio)
        }
    }

    /** A streaming status that is not unicast says nothing about LC3. */
    @Test
    fun otherStreamingStatus_isNotUsingLeAudio() {
        val snapshot = base.copy(
            leaStreamingStatusL = "VIA_CLASSIC_AUDIO",
            leaStreamingStatusR = "VIA_CLASSIC_AUDIO",
        )

        assertFalse(snapshot.usingLeAudio)
        assertFalse(snapshot.connectedViaLeAudio)
    }

    /**
     * A connected-but-not-active LE Audio link still means this phone holds the headset over
     * LE Audio, which is what gates the dual-device restriction — while media is not yet
     * carried as LC3, so the LE Audio card must keep waiting.
     */
    @Test
    fun systemConnectedWithoutActiveRoute_restrictsWithoutClaimingLc3() {
        val snapshot = base.copy(leAudioSystemConnected = true, leAudioSystemActive = false)

        assertFalse(snapshot.usingLeAudio)
        assertTrue(snapshot.connectedViaLeAudio)
    }

    /** Unknown system state (profile service not up) must not be read as a connection. */
    @Test
    fun unknownSystemState_doesNotRestrict() {
        val snapshot = base.copy(leAudioSystemConnected = null, leAudioSystemActive = null)

        assertFalse(snapshot.usingLeAudio)
        assertFalse(snapshot.connectedViaLeAudio)
    }

    /** Permission alone is not a connection: it only says the system *may* connect LE Audio. */
    @Test
    fun policyAllowedAlone_doesNotRestrict() {
        val snapshot = base.copy(leAudioPolicyAllowed = true)

        assertFalse(snapshot.usingLeAudio)
        assertFalse(snapshot.connectedViaLeAudio)
    }
}
