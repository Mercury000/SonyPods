package dev.sonypods.device

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpaResolverTest {

    private val irk = ByteArray(16) { (it + 1).toByte() }

    /**
     * Builds a resolvable private address for [irk] independently of the resolver: prand with the
     * `01` address-type bits, then `ah()` computed here rather than reused from production code, so
     * a wrong offset or byte order in the resolver shows up as a failure.
     */
    private fun rpaFor(key: ByteArray, prand: ByteArray): String {
        val block = ByteArray(16)
        prand.copyInto(block, destinationOffset = 13)
        val hash = Cipher.getInstance("AES/ECB/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            .doFinal(block)
            .copyOfRange(13, 16)
        return (prand + hash).joinToString(":") { "%02X".format(it) }
    }

    private val prand = byteArrayOf(0x40, 0x11, 0x22)

    @Test
    fun `an exactly matching bond is preferred and needs no key`() {
        val resolved = RpaResolver.resolveAll(
            reported = listOf("AA:BB:CC:DD:EE:02"),
            bonded = setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
        )

        assertEquals(listOf("AA:BB:CC:DD:EE:02"), resolved)
    }

    @Test
    fun `a bond stored under a private address resolves through the IRK`() {
        val rpa = rpaFor(irk, prand)

        val resolved = RpaResolver.resolveAll(
            reported = listOf("AA:BB:CC:DD:EE:02"),
            bonded = setOf(rpa),
            identityResolvingKeys = listOf(irk),
        )

        assertEquals(listOf(rpa), resolved)
    }

    @Test
    fun `without a key the private-address rung is skipped`() {
        val rpa = rpaFor(irk, prand)

        val resolved = RpaResolver.resolveAll(
            reported = listOf("AA:BB:CC:DD:EE:02"),
            bonded = setOf(rpa),
        )

        assertEquals(emptyList<String>(), resolved)
        assertFalse(RpaResolver.isResolvable(rpa, emptyList()))
    }

    @Test
    fun `a static address is never treated as resolvable`() {
        // Top two bits 11 is a static random address, not an RPA.
        assertFalse(RpaResolver.isResolvable("C0:11:22:33:44:55", listOf(irk)))
        // Top two bits 00 is a non-resolvable private address.
        assertFalse(RpaResolver.isResolvable("00:11:22:33:44:55", listOf(irk)))
        assertTrue(RpaResolver.isResolvable(rpaFor(irk, prand), listOf(irk)))
    }

    @Test
    fun `a wrong key does not resolve`() {
        val other = ByteArray(16) { 0x7F }

        assertFalse(RpaResolver.isResolvable(rpaFor(irk, prand), listOf(other)))
    }

    @Test
    fun `two reported identities never claim the same bond`() {
        val first = rpaFor(irk, byteArrayOf(0x40, 0x11, 0x22))
        val second = rpaFor(irk, byteArrayOf(0x41, 0x33, 0x44))

        val resolved = RpaResolver.resolveAll(
            reported = listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"),
            bonded = setOf(first, second),
            identityResolvingKeys = listOf(irk),
        )

        assertEquals(2, resolved.size)
        assertEquals(setOf(first, second), resolved.toSet())
    }

    @Test
    fun `a malformed address is rejected rather than throwing`() {
        assertFalse(RpaResolver.isResolvable("not-an-address", listOf(irk)))
        assertFalse(RpaResolver.isResolvable("AA:BB:CC:DD:EE", listOf(irk)))
        assertEquals(
            emptyList<String>(),
            RpaResolver.resolveAll(listOf("nonsense"), setOf("AA:BB:CC:DD:EE:01")),
        )
    }
}
