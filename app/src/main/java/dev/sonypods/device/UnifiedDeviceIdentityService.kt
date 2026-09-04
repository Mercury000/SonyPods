package dev.sonypods.device

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified device identity service for LE/Classic judgment.
 *
 * This service provides a single source of truth for determining whether a Bluetooth address
 * represents an LE Audio identity, a Classic identity, or both — and, crucially, which two
 * addresses are the same headset.
 *
 * ## Priority
 * 1. Module-managed pairing ([recordIdentityPair]) — states the direction outright
 * 2. bt_config.conf ([scanBtConfig]) — for headsets that were paired without the module
 *
 * The second source is read as one whole-file pass, not per address. Classification of a single
 * section cannot produce a pair, and a pair is what every consumer actually needs: the device list
 * folds the LE identity into its control counterpart, Tandem targets the control address, and the
 * LE Audio policy is written on the LE address found from the control one. Reading one section at a
 * time is why a headset paired outside the module appeared twice and could not be controlled.
 *
 * ## Architecture
 * - Only the Bluetooth process can classify (bt_config key material is readable nowhere else), so
 *   only it passes a context to [initializeForEngine] and only it persists.
 * - Every other process is handed the result through
 *   [dev.sonypods.bridge.SonyStateSnapshot.identityPairs] and adopts it with [ingestPairs].
 */
@SuppressLint("MissingPermission")
object UnifiedDeviceIdentityService {
    private const val TAG = "SonyPods-Engine"

    /** Remote Preferences key for device identities JSON. */
    private const val STORE_FILE = "sonypods_device_identities.txt"

    private const val BT_CONFIG_PATH = "/data/misc/bluedroid/bt_config.conf"

    /** Local cache for engine-side storage (bluetooth process). */
    private val localCache = ConcurrentHashMap<String, DeviceIdentity>()

    /** In-memory cache loaded from Remote Preferences. */
    @Volatile
    private var remoteIdentities: Map<String, DeviceIdentity> = emptyMap()

    /**
     * `lastModified` of the bt_config.conf that produced the current cache.
     *
     * A lookup that misses has to be able to trigger a rescan — a headset can be bonded after
     * startup — but rescanning per miss would line-scan the file on every hook callback. The stamp
     * makes a rescan happen exactly when the stack has rewritten its bond store.
     */
    @Volatile
    private var scannedStamp = 0L

    /** What bt_config's key material says per address, unfiltered by source precedence. */
    @Volatile
    private var btConfigTypes: Map<String, IdentityType> = emptyMap()

    /**
     * Where the classifier's own process persists what it resolved.
     *
     * Only the Bluetooth process can classify — pairing states the direction, and bt_config key
     * material is readable nowhere else — so it is also the only process that has anything worth
     * storing. It writes into its own data directory; every other process is handed the result
     * through [dev.sonypods.bridge.SonyStateSnapshot.identityPairs] and never persists.
     */
    @Volatile
    private var store: java.io.File? = null

    // ---- Initialization ----

    /**
     * Bind the classifier's persistent store. Only the process that classifies passes a
     * context; consumers call this with null and live off the snapshot.
     */
    fun initializeForEngine(engineContext: Context? = null) {
        store = engineContext?.let { java.io.File(it.filesDir, STORE_FILE) }
        load()
        scanBtConfig()
        log("initialized with ${remoteIdentities.size} identities")
    }

    // ---- Identity Query API ----

    /**
     * Get the identity type for a given address.
     * Returns [IdentityType.UNKNOWN] if no information is available.
     */
    fun getIdentityType(address: String): IdentityType =
        getIdentity(address)?.type ?: IdentityType.UNKNOWN

    /**
     * Get the full identity for a given address.
     *
     * A miss triggers a bt_config rescan, which is a no-op unless the stack has rewritten its bond
     * store since the last one — a headset bonded after startup is picked up that way.
     */
    fun getIdentity(address: String): DeviceIdentity? {
        val normalized = normalizeAddress(address) ?: return null
        cached(normalized)?.let { return it }
        scanBtConfig()
        return cached(normalized)
    }

    private fun cached(normalized: String): DeviceIdentity? =
        remoteIdentities[normalized] ?: localCache[normalized]

    /**
     * Check if an address is the LE Audio identity.
     */
    fun isLeAudioIdentity(address: String): Boolean =
        getIdentityType(address) == IdentityType.LE

    /**
     * Check if an address is the Classic identity.
     *
     * False for [IdentityType.DUAL]: a control identity that also holds LE keys — every Sony TWS
     * control address does, because CTKD writes them there — is not "classic only".
     */
    fun isClassicIdentity(address: String): Boolean =
        getIdentityType(address) == IdentityType.CLASSIC

    /**
     * Resolve the control address for a given address.
     * Returns the address unchanged if no mapping exists.
     */
    fun resolveControlAddress(address: String): String {
        val normalized = normalizeAddress(address) ?: return address
        val identity = getIdentity(normalized) ?: return address
        return identity.controlAddress ?: address
    }

    /**
     * Resolve the LE address for a given address.
     * Returns the address unchanged if no mapping exists.
     */
    fun leAudioAddressFor(address: String): String? {
        val normalized = normalizeAddress(address) ?: return null
        val identity = getIdentity(normalized) ?: return null
        return identity.leAddress
    }

    /**
     * Get both identities (LE + Control) for a given address.
     */
    fun identityPairFor(address: String): Pair<String, String>? {
        val normalized = normalizeAddress(address) ?: return null
        val identity = getIdentity(normalized) ?: return null
        val paired = identity.pairedAddress ?: return null
        val le = identity.leAddress ?: return null
        val control = identity.controlAddress ?: return null
        return le to control
    }

    // ---- Identity Recording API ----

    /**
     * Record identity pairing (LE address ↔ Control address mapping).
     */
    fun recordIdentityPair(leAddress: String, controlAddress: String) {
        val le = normalizeAddress(leAddress) ?: return
        val control = normalizeAddress(controlAddress) ?: return
        if (le == control) return

        // Record LE identity (type=LE, pairedAddress points to control)
        val leIdentity = DeviceIdentity(
            address = le,
            type = IdentityType.LE,
            pairedAddress = control,
            source = IdentitySource.PAIRING,
        )
        recordIdentity(leIdentity)

        // Record Control identity (type=CLASSIC, pairedAddress points to LE)
        val controlIdentity = DeviceIdentity(
            address = control,
            type = IdentityType.CLASSIC,
            pairedAddress = le,
            source = IdentitySource.PAIRING,
        )
        recordIdentity(controlIdentity)
    }

    // ---- Internal Recording ----

    private fun recordIdentity(identity: DeviceIdentity) {
        if (mergeIdentity(identity)) persist()
    }

    private fun recordIdentities(identities: Collection<DeviceIdentity>) {
        var changed = false
        identities.forEach { if (mergeIdentity(it)) changed = true }
        if (changed) persist()
    }

    /** Merges one identity into both caches. Returns whether anything changed. */
    private fun mergeIdentity(identity: DeviceIdentity): Boolean {
        val existing = cached(identity.address)
        // A weaker source never overwrites a stronger one. CSIS group id is the stack's own answer
        // and outranks everything; module-managed pairing states the direction outright; bt_config
        // can only classify one address at a time and never relates two.
        if (existing != null && rank(existing.source) < rank(identity.source)) return false
        if (existing == identity) return false
        localCache[identity.address] = identity
        remoteIdentities = remoteIdentities + (identity.address to identity)
        log("recorded identity: ${identity.address} -> ${identity.type} " +
            "paired=${identity.pairedAddress.orEmpty()} (source=${identity.source})")
        return true
    }

    private fun rank(source: IdentitySource): Int = when (source) {
        IdentitySource.CSIS_GROUP -> 0
        IdentitySource.PAIRING -> 1
        else -> 2
    }

    // ---- CSIS group: the authoritative pairing source ----

    /**
     * Records that [members] are one coordinated set, i.e. one headset.
     *
     * The group says *which addresses belong together*; it does not say which of them is the LE
     * Audio identity. That comes from the key material: only the control identity holds a `LinkKey`,
     * because only it is reachable over BR/EDR. A group whose direction the keys cannot settle is
     * left alone rather than recorded backwards.
     *
     * Direction is read from [btConfigTypes] rather than from the merged record on purpose. The
     * merged record is precedence-filtered, so a stale pairing entry could otherwise stop the group
     * — the authoritative source — from correcting it.
     */
    fun recordGroup(
        members: Collection<String>,
        source: IdentitySource = IdentitySource.CSIS_GROUP,
    ) {
        val addresses = members.mapNotNull { normalizeAddress(it) }.distinct()
        if (addresses.size < 2) return
        val control = addresses.filter { keyType(it) != null && keyType(it) != IdentityType.LE }
        val le = addresses.filter { keyType(it) == IdentityType.LE }
        if (control.size != 1 || le.isEmpty()) {
            log("group $addresses: key material does not settle the direction; not paired")
            return
        }
        val controlAddress = control.single()
        recordIdentities(
            le.map { leAddress ->
                DeviceIdentity(
                    address = leAddress,
                    type = IdentityType.LE,
                    pairedAddress = controlAddress,
                    name = cached(leAddress)?.name,
                    source = source,
                )
            } + DeviceIdentity(
                address = controlAddress,
                type = keyType(controlAddress) ?: IdentityType.CLASSIC,
                pairedAddress = le.first(),
                name = cached(controlAddress)?.name,
                source = source,
            )
        )
    }

    /** What the bond record's key material says this address is, independent of any pairing entry. */
    private fun keyType(address: String): IdentityType? = btConfigTypes[address]

    // ---- bt_config.conf analysis ----

    /**
     * One `[MAC]` section of bt_config.conf, reduced to what identity classification needs.
     */
    internal data class BtConfigSection(
        val address: String,
        val hasLinkKey: Boolean,
        val hasLeKeys: Boolean,
        val name: String?,
    )

    /**
     * Reads every bonded record and records what it says, including which two addresses are one
     * headset. A no-op outside the classifier process, and outside a bt_config that changed since
     * the last pass.
     */
    private fun scanBtConfig() {
        if (store == null) return
        val file = java.io.File(BT_CONFIG_PATH)
        val stamp = runCatching { if (file.isFile) file.lastModified() else 0L }.getOrDefault(0L)
        if (stamp == 0L || stamp == scannedStamp) return
        val sections = runCatching { parseBtConfig(file) }.getOrElse {
            log("bt_config read failed: ${it.javaClass.simpleName}")
            return
        }
        // Stamped even on an empty read: a file with no bonded record is a complete answer, and
        // re-reading it on every lookup miss would line-scan it from every hook callback.
        scannedStamp = stamp
        log("bt_config scan: ${sections.size} section(s)")
        ingestSections(sections)
    }

    /** Classifies [sections] and merges the result, subject to the source priority. */
    internal fun ingestSections(sections: List<BtConfigSection>) {
        val identities = classifySections(sections)
        // Kept separately from the merged cache: this is what the *keys* say, and [recordGroup]
        // needs that answer unfiltered by whatever a pairing entry claimed earlier.
        btConfigTypes = btConfigTypes + identities.associate { it.address to it.type }
        recordIdentities(identities)
    }

    private fun parseBtConfig(file: java.io.File): List<BtConfigSection> {
        val sections = mutableListOf<BtConfigSection>()
        var address: String? = null
        var hasLinkKey = false
        var hasLeKeys = false
        var name: String? = null

        fun flush() {
            val current = address ?: return
            if (hasLinkKey || hasLeKeys) {
                sections += BtConfigSection(current, hasLinkKey, hasLeKeys, name)
            }
        }

        file.useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    flush()
                    address = normalizeAddress(trimmed.removeSurrounding("[", "]"))
                    hasLinkKey = false
                    hasLeKeys = false
                    name = null
                    continue
                }
                if (address == null) continue
                when {
                    trimmed.startsWith("LE_KEY_") -> hasLeKeys = true
                    trimmed.startsWith("LinkKey") -> hasLinkKey = true
                    trimmed.startsWith("Name") && '=' in trimmed ->
                        name = trimmed.substringAfter('=').trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        flush()
        return sections
    }

    /**
     * Classifies one bond record at a time. It does **not** pair two addresses.
     *
     * A `LinkKey` is what makes a record reachable over BR/EDR, so a record holding one is the
     * control identity — the only one that can carry Tandem. A record with LE keys and no `LinkKey`
     * is an LE-only identity. On a Sony TWS headset the control record holds LE keys as well (CTKD
     * writes them there, and the second earbud's LE identity *is* the classic address), which is why
     * it classifies as [IdentityType.DUAL] rather than CLASSIC.
     *
     * Relating the two halves is [recordGroup]'s job. Matching them by name — which this used to do
     * — is not something Sony's own app ever does, and two bonded units of the same model make it
     * wrong in the one case where being wrong costs the user their pairing.
     */
    internal fun classifySections(sections: List<BtConfigSection>): List<DeviceIdentity> =
        sections.map { section ->
            DeviceIdentity(
                address = section.address,
                type = when {
                    section.hasLinkKey && section.hasLeKeys -> IdentityType.DUAL
                    section.hasLinkKey -> IdentityType.CLASSIC
                    else -> IdentityType.LE
                },
                name = section.name,
                source = IdentitySource.BT_CONFIG,
            )
        }

    // ---- Persistence (classifier process only) ----

    private fun load() {
        val file = store ?: return
        val serialized = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return
        val identities = mutableMapOf<String, DeviceIdentity>()
        serialized.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            DeviceIdentity.deserialize(line)?.let { identities[it.address] = it }
        }
        remoteIdentities = identities
    }

    private fun persist() {
        val file = store ?: return
        val serialized = buildString {
            remoteIdentities.values.forEach { identity -> appendLine(identity.serialize()) }
        }
        runCatching { file.writeText(serialized) }
            .onFailure { log("persist failed: ${it.javaClass.simpleName}") }
    }


    // ---- Snapshot API ----

    /**
     * Every known pair as `LE>control`, for [dev.sonypods.bridge.SonyStateSnapshot].
     *
     * Only the Bluetooth process can classify (pairing flow, or bt_config key material), so this is
     * how the answer reaches every other process — including the device list, which folds the LE
     * identity into its control counterpart on nothing else.
     */
    fun leToControlPairs(): List<String> =
        (remoteIdentities + localCache).values
            .mapNotNull { identity ->
                val paired = identity.pairedAddress ?: return@mapNotNull null
                when (identity.type) {
                    IdentityType.LE -> "${identity.address}>$paired"
                    IdentityType.CLASSIC, IdentityType.DUAL -> "$paired>${identity.address}"
                    else -> null
                }
            }
            .distinct()

    /** Adopt the pairs a snapshot carried. Recorded as PAIRING: the engine already classified. */
    fun ingestPairs(pairs: List<String>) {
        pairs.forEach { entry ->
            val le = normalizeAddress(entry.substringBefore('>')) ?: return@forEach
            val control = normalizeAddress(entry.substringAfter('>', "")) ?: return@forEach
            if (le == control) return@forEach
            if (getIdentity(le)?.pairedAddress == control) return@forEach
            recordIdentityPair(le, control)
        }
    }

    // ---- Utility ----

    private fun normalizeAddress(address: String?): String? {
        val normalized = address?.trim()?.uppercase(Locale.ROOT) ?: return null
        return normalized.takeIf { it.matches(BLUETOOTH_ADDRESS) }
    }

    private val BLUETOOTH_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    private fun log(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    /**
     * Reset all state. Only for unit tests.
     */
    internal fun resetForTesting() {
        localCache.clear()
        remoteIdentities = emptyMap()
        btConfigTypes = emptyMap()
        store = null
        scannedStamp = 0L
    }
}
