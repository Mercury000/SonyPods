package dev.sonypods.device

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Matches the LE addresses a headset reports against the addresses the phone is bonded to.
 *
 * Port of Sound Connect's `C12272j.m52763b` plus `C12258c`. Two rungs:
 *
 * 1. **Exact match.** The reported address is itself in the bonded set — SC logs *"Match with OS
 *    Setting history."* This is the common case and the only one that works without an IRK.
 * 2. **IRK resolution.** The bond is stored under a resolvable private address while the headset
 *    reported its identity address (or the reverse). Standard BLE `ah()`: for a bonded address whose
 *    top two bits are `01`, `AES-128-ECB(IRK, 13 zero bytes ‖ prand)`'s last three bytes must equal
 *    the address' low three bytes. SC logs *"Identified by IRK resolve."*
 *
 * The IRK itself is a Tandem fact: Table2 `LEA_GET_PARAM(0x66)` / `LEA_RET_PARAM(0x67)` inquired
 * type `0x03`, sixteen bytes at payload offset 2 (SC
 * `V2GetIdentityResolvingKeyRepository` → `qb0.C26493v`). With no IRK the second rung is simply
 * skipped, which is also what SC does when its KMP repository is absent (`m52768g` answers an empty
 * array).
 */
object RpaResolver {

    /**
     * The bonded addresses corresponding to [reported], in reported order, dropping any that cannot
     * be accounted for.
     *
     * Unlike SC's per-address helper this claims each bonded address at most once. SC's version
     * returns the first IRK-resolvable bonded address for every reported address it is asked about,
     * so a TWS reporting a left and a right identity can be handed the same bond twice.
     */
    fun resolveAll(
        reported: List<String>,
        bonded: Set<String>,
        identityResolvingKeys: List<ByteArray> = emptyList(),
        log: (String) -> Unit = {},
    ): List<String> {
        if (bonded.isEmpty()) return emptyList()
        val claimed = mutableSetOf<String>()
        val out = mutableListOf<String>()
        // Exact matches first, across the whole list: they are certain, and letting an IRK match on
        // an earlier entry claim a bond that a later entry matches exactly would be a downgrade.
        val normalized = reported.mapNotNull(HeadsetRecord.Companion::normalizeAddress)
        normalized.forEach { address ->
            if (address in bonded && claimed.add(address)) log("identity $address matches a bond")
        }
        normalized.forEach { address ->
            if (address in bonded) {
                out += address
                return@forEach
            }
            val resolved = bonded.firstOrNull { candidate ->
                candidate !in claimed && isResolvable(candidate, identityResolvingKeys)
            }
            if (resolved == null) {
                log("identity $address has no bond and no IRK resolves one")
                return@forEach
            }
            claimed += resolved
            out += resolved
            log("identity $address resolved to bonded $resolved by IRK")
        }
        return out.distinct()
    }

    /** Whether [address] is a resolvable private address matching one of [identityResolvingKeys]. */
    fun isResolvable(address: String, identityResolvingKeys: List<ByteArray>): Boolean {
        if (identityResolvingKeys.isEmpty()) return false
        val bytes = parse(address) ?: return false
        if (!isResolvablePrivate(bytes)) return false
        val hash = bytes.copyOfRange(3, 6)
        val prand = bytes.copyOfRange(0, 3)
        return identityResolvingKeys.any { irk -> ah(irk, prand)?.contentEquals(hash) == true }
    }

    /** `ah(k, r)` from the Core spec: low 3 bytes of `AES-128-ECB(k, 0…0 ‖ r)`. */
    private fun ah(irk: ByteArray, prand: ByteArray): ByteArray? {
        if (irk.size != 16 || prand.size != 3) return null
        val block = ByteArray(16)
        prand.copyInto(block, destinationOffset = 13)
        return runCatching {
            Cipher.getInstance("AES/ECB/NoPadding")
                .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(irk, "AES")) }
                .doFinal(block)
                .takeIf { it.size == 16 }
                ?.copyOfRange(13, 16)
        }.getOrNull()
    }

    /** Address type bits `01` in the top two bits of the most significant byte. */
    private fun isResolvablePrivate(bytes: ByteArray): Boolean =
        bytes.size == 6 &&
            (bytes[0].toInt() and 0x80) == 0 &&
            (bytes[0].toInt() and 0x40) != 0

    private fun parse(address: String): ByteArray? {
        val parts = address.split(":")
        if (parts.size != 6) return null
        return runCatching {
            ByteArray(6) { index -> parts[index].toInt(16).toByte() }
        }.getOrNull()
    }
}
