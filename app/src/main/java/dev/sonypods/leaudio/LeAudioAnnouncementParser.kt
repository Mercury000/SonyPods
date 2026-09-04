package dev.sonypods.leaudio

/**
 * Parses raw BLE advertising data into the LE Audio unicast-announcement fields, and picks the
 * headset's LE-only identity out of the several same-named advertisers a Sony headset exposes.
 *
 * Two identities, byte-identical apart from two fields. Captured from a LinkBuds S and covered by
 * the unit tests: both publish the same ASCS General Announcement, the same audio contexts, CAS,
 * TMAS, BASS, appearance and name; only the Flags octet and a Microsoft Swift Pair payload differ.
 * So the advertisement can say *which of a headset's two identities* an address is, but never
 * *which headset* — see [pickPairingCandidate] for how that is handled.
 *
 * Kept free of Android types on purpose: covered by JVM unit tests.
 */
object LeAudioAnnouncementParser {
    private const val AD_FLAGS = 0x01
    private const val AD_SHORT_LOCAL_NAME = 0x08
    private const val AD_COMPLETE_LOCAL_NAME = 0x09
    private const val AD_SERVICE_DATA_16 = 0x16
    private const val AD_APPEARANCE = 0x19
    private const val AD_RESOLVABLE_SET_IDENTIFIER = 0x2E
    private const val AD_MANUFACTURER_SPECIFIC = 0xFF

    /** Audio Stream Control Service. Its service data is the unicast announcement. */
    const val UUID_ASCS = 0x184E

    /** Common Audio Service. */
    const val UUID_CAS = 0x1853

    /** Broadcast Audio Scan Service. */
    const val UUID_BASS = 0x184F

    /** Telephony and Media Audio Service. */
    const val UUID_TMAS = 0x1855

    /** Microsoft, i.e. a Swift Pair payload. Only the classic-facing identity carries it. */
    const val COMPANY_MICROSOFT = 0x0006

    const val ANNOUNCEMENT_GENERAL = 0x00
    const val ANNOUNCEMENT_TARGETED = 0x01

    internal const val FLAG_LIMITED_DISCOVERABLE = 0x01
    internal const val FLAG_GENERAL_DISCOVERABLE = 0x02

    fun parse(raw: ByteArray?): LeAudioAnnouncement? {
        if (raw == null || raw.isEmpty()) return null

        var flags: Int? = null
        var name: String? = null
        var appearance: Int? = null
        var rsi: String? = null
        var ascs: ByteArray? = null
        var tmas: ByteArray? = null
        var hasCas = false
        var hasBass = false
        val companies = LinkedHashSet<Int>()
        var structures = 0

        var index = 0
        while (index < raw.size) {
            val length = raw[index].toInt() and 0xFF
            // A zero length terminates the payload; the remainder is zero padding.
            if (length == 0) break
            val typeIndex = index + 1
            if (typeIndex >= raw.size) break
            val dataStart = typeIndex + 1
            val dataEnd = typeIndex + length
            if (dataEnd > raw.size) break
            val type = raw[typeIndex].toInt() and 0xFF
            val data = raw.copyOfRange(dataStart, dataEnd)
            structures++

            when (type) {
                AD_FLAGS -> if (data.isNotEmpty()) flags = data[0].toInt() and 0xFF
                AD_COMPLETE_LOCAL_NAME -> name = String(data, Charsets.UTF_8)
                AD_SHORT_LOCAL_NAME -> if (name == null) name = String(data, Charsets.UTF_8)
                AD_APPEARANCE -> if (data.size >= 2) appearance = littleEndian16(data, 0)
                AD_RESOLVABLE_SET_IDENTIFIER -> if (data.isNotEmpty()) rsi = data.toHex()
                AD_MANUFACTURER_SPECIFIC ->
                    if (data.size >= 2) companies.add(littleEndian16(data, 0))
                AD_SERVICE_DATA_16 -> if (data.size >= 2) {
                    val payload = data.copyOfRange(2, data.size)
                    when (littleEndian16(data, 0)) {
                        UUID_ASCS -> ascs = payload
                        UUID_TMAS -> tmas = payload
                        UUID_CAS -> hasCas = true
                        UUID_BASS -> hasBass = true
                    }
                }
            }
            index = dataEnd
        }

        if (structures == 0) return null
        return LeAudioAnnouncement(
            flags = flags,
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            appearance = appearance,
            rsi = rsi,
            hasAscs = ascs != null,
            announcementType = ascs?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF },
            sinkContexts = ascs?.takeIf { it.size >= 3 }?.let { littleEndian16(it, 1) },
            sourceContexts = ascs?.takeIf { it.size >= 5 }?.let { littleEndian16(it, 3) },
            hasCas = hasCas,
            hasBass = hasBass,
            hasTmas = tmas != null,
            tmapRoles = tmas?.takeIf { it.size >= 2 }?.let { littleEndian16(it, 0) },
            manufacturerIds = companies,
        )
    }

    private fun littleEndian16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02X".format(it) }

    // ---- selection ----

    /**
     * Picks the identity to bond, by the strongest criterion the headset actually offers.
     *
     * **Rung 1 — the headset named it.** [reportedLeAddresses] is `LeaState.leAudioAddresses`, the
     * answer to Table2 `LEA_RET_CAPABILITY` inquired type 0x04. An exact address match is
     * definitive and needs nothing else. Only devices declaring supported-function 0x64 answer that
     * query at all; Table1 devices (0x40 / 0x41 / 0x43) never do, which is why there is a rung 2.
     *
     * **Rung 2 — the advertisement.** All three of these are required, not scored: the LE-only
     * identity is **not** discoverable, carries **no** Swift Pair payload, and publishes an RSI. The
     * classic-facing identity is discoverable and does carry Swift Pair, and the two are otherwise
     * byte-identical — same ASCS announcement, same contexts, same name. Verified on a LinkBuds S:
     * one sweep saw `C5:93:…(flags=0 swiftPair=false)` alongside `80:99:…(flags=2 swiftPair=true)`.
     *
     * Ties are broken by RSSI and only when one candidate leads by [RSSI_MARGIN_DB]. Two units of
     * the same model in pairing mode are indistinguishable by advertisement alone, and the cost of
     * guessing is bonding a stranger's earbud after this run has already dropped the classic bond —
     * so an ambiguous field yields null and the caller keeps looking.
     */
    fun pickPairingCandidate(
        candidates: List<Candidate>,
        targetName: String?,
        reportedLeAddresses: Collection<String> = emptyList(),
        excludeAddresses: Collection<String> = emptyList(),
    ): Candidate? {
        val reported = reportedLeAddresses.mapNotNull { it.normalizeAddress() }.toSet()
        val excluded = excludeAddresses.mapNotNull { it.normalizeAddress() }.toSet()
        val target = targetName?.trim()?.takeIf { it.isNotEmpty() }

        val viable = candidates
            .filter { it.announcement.isUnicastAnnouncement }
            .filter { isBondableAddress(it.address) }
            .filterNot { it.address.normalizeAddress() in excluded }

        viable.filter { it.address.normalizeAddress() in reported }
            .maxByOrNull { it.rssi }
            ?.let { return it }

        val ranked = viable
            .filter { nameMatches(it.announcement.name, target) }
            .filterNot { it.announcement.isDiscoverable }
            .filterNot { it.announcement.hasSwiftPair }
            .filter { it.announcement.rsi != null }
            .sortedByDescending { it.rssi }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1) ?: return best
        return best.takeIf { best.rssi - runnerUp.rssi >= RSSI_MARGIN_DB }
    }

    /**
     * Rejects resolvable private addresses.
     *
     * An RPA is a temporary address the earbud rotates for privacy. Once a bond exists its IRK
     * resolves it back to the identity address, so a scan that still reports the raw RPA means the
     * stack could not resolve it — and bonding it creates a second, competing pairing. The two high
     * bits of the first octet carry the address type: `01` is an RPA, `11` is a static random
     * address (what the LE-only identity uses), and anything else is public.
     */
    internal fun isBondableAddress(address: String): Boolean {
        val firstOctet = address.trim().take(2).toIntOrNull(16) ?: return true
        return (firstOctet and 0xC0) != 0x40
    }

    /**
     * A missing name is not a rejection: the name may live in a scan response the scanner has not
     * merged yet. Sony also prefixes the name of some LE identities with `LE_`.
     */
    private fun nameMatches(name: String?, target: String?): Boolean {
        if (target == null || name == null) return true
        return name.equals(target, ignoreCase = true) ||
            name.equals("LE_$target", ignoreCase = true) ||
            target.equals("LE_$name", ignoreCase = true)
    }

    private fun String.normalizeAddress(): String? =
        trim().uppercase().takeIf { it.isNotEmpty() }

    /** How far the strongest candidate must lead before RSSI is allowed to decide. */
    internal const val RSSI_MARGIN_DB = 15

    data class Candidate(
        val address: String,
        val announcement: LeAudioAnnouncement,
        /** Only used to break a tie between otherwise indistinguishable candidates. */
        val rssi: Int,
    )
}

data class LeAudioAnnouncement(
    val flags: Int?,
    val name: String?,
    val appearance: Int?,
    /** Resolvable Set Identifier, uppercase hex. Regenerated per advertisement from the SIRK, so
     * its presence is meaningful and its value is not comparable across identities or over time. */
    val rsi: String?,
    val hasAscs: Boolean,
    val announcementType: Int?,
    val sinkContexts: Int?,
    val sourceContexts: Int?,
    val hasCas: Boolean,
    val hasBass: Boolean,
    val hasTmas: Boolean,
    val tmapRoles: Int?,
    val manufacturerIds: Set<Int>,
) {
    val isUnicastAnnouncement: Boolean
        get() = hasAscs && announcementType != null

    val isGeneralAnnouncement: Boolean
        get() = announcementType == LeAudioAnnouncementParser.ANNOUNCEMENT_GENERAL

    val isTargetedAnnouncement: Boolean
        get() = announcementType == LeAudioAnnouncementParser.ANNOUNCEMENT_TARGETED

    /** Whether the advertiser asks to be listed by a discovery scan. The LE-only identity does
     * not, which is why it never shows up in the system pairing list. */
    val isDiscoverable: Boolean
        get() = flags != null &&
            (flags and (LeAudioAnnouncementParser.FLAG_LIMITED_DISCOVERABLE or
                LeAudioAnnouncementParser.FLAG_GENERAL_DISCOVERABLE)) != 0

    val hasSwiftPair: Boolean
        get() = LeAudioAnnouncementParser.COMPANY_MICROSOFT in manufacturerIds
}
