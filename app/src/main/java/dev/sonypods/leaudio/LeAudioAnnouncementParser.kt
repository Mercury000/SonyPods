package dev.sonypods.leaudio

/**
 * Parses raw BLE advertising data into the LE Audio unicast-announcement fields the
 * device-side pairing flow needs, and picks the headset's LE-only identity out of the
 * several same-named advertisers a Sony headset exposes once LE Audio is enabled.
 *
 * Kept free of Android types on purpose: the selection rules come from captured
 * advertisements and are covered by JVM unit tests.
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

    /**
     * Picks the identity to bond from everything a scan turned up.
     *
     * A Sony headset with LE Audio enabled advertises the same name from more than one
     * LE identity. They expose an identical LE Audio service set, so the only usable
     * discriminators are that the bondable LE-only identity is **not** discoverable and
     * carries **no** Swift Pair payload, while the classic-facing one is and does.
     *
     * [reportedLeAddresses] are the LE endpoint addresses the headset reports over Tandem
     * (`LeaState.leAudioAddresses`). A match there is definitive and outranks the heuristics.
     *
     * Candidates that satisfy neither test are rejected outright rather than ranked last,
     * so that a scan which only ever saw the classic-facing identity returns nothing
     * instead of offering it up as the best of a bad set.
     */
    fun pickPairingCandidate(
        candidates: List<Candidate>,
        targetName: String?,
        reportedLeAddresses: Collection<String> = emptyList(),
        excludeAddresses: Collection<String> = emptyList(),
    ): Candidate? = pairingCandidates(
        candidates = candidates,
        targetName = targetName,
        reportedLeAddresses = reportedLeAddresses,
        excludeAddresses = excludeAddresses,
    ).firstOrNull()

    /**
     * Every coordinated-set member worth bonding, best first.
     *
     * A TWS headset is two LE Audio devices in one set and the phone needs both: each earbud
     * exposes a single-channel sink ASE and declares one audio location, so the stack builds a
     * CIG with one CIS per member and a missing member means a silent ear.
     *
     * Both an undiscoverable identity and a discoverable, Swift-Pair-advertising one are real
     * members — captured advertisements that looked like "the right one and the wrong one"
     * turned out to be the left and right earbuds. The one earbud reuses the headset's classic
     * BR/EDR address for its LE identity, which is why [excludeAddresses] is not applied here:
     * excluding the classic address would drop half the set. Announcing ASCS at all is what
     * makes an advertiser a member.
     */
    fun pairingCandidates(
        candidates: List<Candidate>,
        targetName: String?,
        reportedLeAddresses: Collection<String> = emptyList(),
        excludeAddresses: Collection<String> = emptyList(),
    ): List<Candidate> {
        val reported = reportedLeAddresses.mapNotNull { it.normalizeAddress() }.toSet()
        val target = targetName?.trim()?.takeIf { it.isNotEmpty() }

        return candidates
            .filter { it.announcement.isUnicastAnnouncement }
            .filter { nameMatches(it.announcement.name, target) }
            .filter { isBondableAddress(it.address) }
            .sortedWith(
                compareByDescending<Candidate> { score(it, reported) }.thenBy { it.address }
            )
    }

    /**
     * Rejects resolvable private addresses.
     *
     * An RPA is a temporary address the earbud rotates for privacy. Once a bond exists its IRK
     * resolves it back to the identity address, so a scan that still reports the raw RPA means
     * the stack could not resolve it — and bonding it creates a second, competing pairing for
     * an earbud that is very likely already bonded, which fails on keys rather than adding the
     * missing set member. The two high bits of the first octet carry the address type: `01` is
     * an RPA, `11` is a static random address, and anything else is public — both of the latter
     * are stable enough to bond.
     */
    internal fun isBondableAddress(address: String): Boolean {
        val firstOctet = address.trim().take(2).toIntOrNull(16) ?: return true
        return (firstOctet and 0xC0) != 0x40
    }

    /**
     * Ordering only, no longer a rejection test: the undiscoverable identity is bonded first
     * because it is the one that needs a bond created, while the discoverable member is
     * usually already bonded over BR/EDR and only needs its LE side brought up.
     */
    private fun score(candidate: Candidate, reportedLeAddresses: Set<String>): Int {
        val announcement = candidate.announcement
        var score = 0
        if (candidate.address.normalizeAddress() in reportedLeAddresses) score += 100
        if (!announcement.isDiscoverable) score += 10
        if (!announcement.hasSwiftPair) score += 5
        if (announcement.rsi != null) score += 1
        return score
    }

    /**
     * A missing name is not a rejection: the name may live in a scan response the
     * scanner has not merged yet. Sony also prefixes the name of some LE identities
     * with `LE_`.
     */
    private fun nameMatches(name: String?, target: String?): Boolean {
        if (target == null || name == null) return true
        return name.equals(target, ignoreCase = true) ||
            name.equals("LE_$target", ignoreCase = true) ||
            target.equals("LE_$name", ignoreCase = true)
    }

    private fun littleEndian16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02X".format(it) }

    private fun String.normalizeAddress(): String? =
        trim().uppercase().takeIf { it.isNotEmpty() }

    data class Candidate(
        val address: String,
        val announcement: LeAudioAnnouncement,
    )
}

data class LeAudioAnnouncement(
    val flags: Int?,
    val name: String?,
    val appearance: Int?,
    /** Resolvable Set Identifier, uppercase hex. Present means the device is a CSIS set member. */
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

    /**
     * Whether the advertiser asks to be listed by a classic discovery scan. The bondable
     * LE-only identity does not, which is why it never shows up in the system pairing list.
     */
    val isDiscoverable: Boolean
        get() = flags != null &&
            (flags and (LeAudioAnnouncementParser.FLAG_LIMITED_DISCOVERABLE or
                LeAudioAnnouncementParser.FLAG_GENERAL_DISCOVERABLE)) != 0

    val hasSwiftPair: Boolean
        get() = LeAudioAnnouncementParser.COMPANY_MICROSOFT in manufacturerIds
}
