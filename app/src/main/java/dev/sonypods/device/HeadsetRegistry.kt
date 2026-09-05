package dev.sonypods.device

import android.content.Context
import android.util.Log
import dev.sonypods.device.HeadsetRecord.Companion.normalizeAddress

/**
 * The headsets we have had a Tandem session with, and what each one said about itself.
 *
 * This replaces classifying bonds. Sound Connect never classifies one — it keeps a registry of
 * devices it has connected to (`C13896f`, persisted as JSON per record) and takes every identity
 * fact from the headset's own Tandem replies:
 *
 * 1. `CONNECT_RET_CAPABILITY_INFO` (frame three of every session) carries the Tandem identifier.
 *    That is the record's [HeadsetRecord.key] and the only thing that says "same headset".
 * 2. `LEA_RET_CAPABILITY` lists the addresses serving the headset, with no reliable direction.
 *    They are unioned into that record's address set.
 * 3. Nothing else may invent an address set or a direction. A writer that does not know which
 *    address is the classic one does not write one — [HeadsetRecord.controlAddress] stays null and
 *    every consumer falls back to the address it already had.
 *
 * Consequences worth keeping in mind:
 * - **Nothing here answers before the first session.** A bonded-but-never-connected headset is
 *   unknown, by construction, so callers need a safe default rather than treating "unknown" as an
 *   answer.
 * - **No permission is needed.** No `bt_config.conf`, no reflection into `AdapterService`, no CSIS
 *   group read. The identity is the headset's testimony.
 *
 * All state is behind [lock]: writes arrive on Tandem frame-dispatch threads, on the LE Audio
 * pairing flow's handler and on the publish coroutine, and reads arrive from every hooked process's
 * callbacks.
 */
object HeadsetRegistry {
    private const val TAG = "SonyPods-Engine"
    private const val STORE_FILE = "sonypods_headsets.txt"

    private val lock = Any()

    /** Records by [HeadsetRecord.key], in first-seen order. */
    private val records = LinkedHashMap<String, HeadsetRecord>()

    /**
     * Addresses a run of the LE Audio pairing flow is holding.
     *
     * That flow removes the classic bond before it creates the LE one and records the pair in
     * between, so for a moment the record has no bonded address at all. [prune] must not read that
     * as "the user unpaired this headset".
     */
    private val pinned = mutableSetOf<String>()

    /**
     * Where the engine persists. Only the Bluetooth process passes a context; every other process
     * lives off [ingest] and never writes.
     */
    private var store: java.io.File? = null

    fun initializeForEngine(engineContext: Context? = null) {
        synchronized(lock) {
            store = engineContext?.let { java.io.File(it.filesDir, STORE_FILE) }
            load()
            log("initialized with ${records.size} headset record(s)")
        }
    }

    // ---- Lookup ----

    /** The headset [address] belongs to, or null when no session has ever named it. */
    fun recordFor(address: String?): HeadsetRecord? {
        val normalized = normalizeAddress(address) ?: return null
        return synchronized(lock) {
            records[normalized] ?: records.values.firstOrNull { normalized in it.addresses }
        }
    }

    /** Every known headset, most recently seen first. */
    fun all(): List<HeadsetRecord> = synchronized(lock) { records.values.reversed() }

    /**
     * The address to use for control, given any identity of the headset.
     *
     * Returns [address] unchanged unless a control identity has actually been proved. Answering a
     * *different* address on a guess is what inverts every consumer at once, so the unknown case
     * deliberately looks like "this address is already the one to use".
     */
    fun controlAddressFor(address: String?): String? {
        val normalized = normalizeAddress(address) ?: return null
        return recordFor(normalized)?.controlAddress ?: normalized
    }

    /** The addresses of this headset other than [address]. No direction implied. */
    fun siblingAddressesOf(address: String?): List<String> {
        val normalized = normalizeAddress(address) ?: return emptyList()
        return recordFor(normalized)?.addresses?.filterNot { it == normalized }.orEmpty()
    }

    /**
     * An identity of this headset that is known not to be its classic one.
     *
     * Null while the direction is unproved: a caller asking this wants somewhere to write an LE Audio
     * policy or retry a transfer, and guessing sends it at the classic bond.
     */
    fun leAddressFor(address: String?): String? {
        val record = recordFor(address) ?: return null
        val control = record.controlAddress ?: return null
        return record.addresses.firstOrNull { it != control }
    }

    /** Whether [address] is an identity of the headset other than its proved classic one. */
    fun isLeIdentity(address: String?): Boolean = recordFor(address)?.isLeIdentity(address) == true

    /**
     * The address a session should target, given whatever address an event carried.
     *
     * SC `C14289i.m61741x` folds a raw event address onto the registered one. Here it only folds when
     * a control identity is proved; otherwise the event's own address is the best answer available.
     */
    fun sessionTargetFor(rawAddress: String): String =
        recordFor(rawAddress)?.controlAddress ?: rawAddress

    // ---- Recording ----

    /**
     * Register the headset a session is talking to, from `CONNECT_RET_CAPABILITY_INFO`.
     *
     * [key] is the Tandem identifier verbatim — not validated as an address, because it is not one by
     * contract. [sessionAddress] is added to the address set, since a session is proof that address
     * serves this headset. Nothing about direction is inferred.
     */
    fun rememberSession(
        key: String?,
        sessionAddress: String?,
        name: String? = null,
        service: PairingService? = null,
    ): HeadsetRecord? {
        val id = key?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return synchronized(lock) {
            val existing = records[id]
            val merged = (existing ?: HeadsetRecord(key = id)).let { base ->
                base.copy(
                    addresses = base.addresses.plusAddress(sessionAddress),
                    name = name?.takeIf { it.isNotBlank() } ?: base.name,
                    service = service ?: base.service,
                )
            }
            put(merged, existing)
        }
    }

    /**
     * Union the addresses the headset named into the record for [key].
     *
     * No-op without a key: an address list with nothing to attach it to cannot be stored without
     * inventing a key, and every key this could invent is one of the addresses whose role is exactly
     * what is unknown.
     *
     * [reported] is filtered to addresses the OS is bonded to — SC `C12272j.m52763b`, exact match
     * first then [RpaResolver] for a resolvable private address. Filtering is a union, never a
     * replacement: one reply naming one address must not erase a record that holds two.
     */
    fun rememberReportedAddresses(
        key: String?,
        reported: List<String>,
        bondedAddresses: Collection<String> = emptyList(),
        identityResolvingKeys: List<ByteArray> = emptyList(),
        name: String? = null,
        service: PairingService? = null,
        supportsLeClassic: Boolean? = null,
        bothPairedHistory: Boolean? = null,
    ): HeadsetRecord? {
        val id = key?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bonded = bondedAddresses.mapNotNull(::normalizeAddress).toSet()
        val resolved = RpaResolver.resolveAll(reported, bonded, identityResolvingKeys, ::log)
        return synchronized(lock) {
            val existing = records[id] ?: return@synchronized null
            val merged = existing.copy(
                addresses = resolved.fold(existing.addresses) { acc, a -> acc.plusAddress(a) },
                name = name?.takeIf { it.isNotBlank() } ?: existing.name,
                service = service ?: existing.service,
                supportsLeClassic = supportsLeClassic ?: existing.supportsLeClassic,
                bothPairedHistory = bothPairedHistory ?: existing.bothPairedHistory,
            )
            put(merged, existing)
        }
    }

    /**
     * Record that [controlAddress] is the headset's classic identity.
     *
     * Only two things may call this, and both have proof rather than an inference: a session that ran
     * over SPP (a transport only the classic identity can carry), and the module's own pairing flow,
     * which created both bonds and therefore knows which one it kept.
     */
    fun proveControlAddress(address: String?, controlAddress: String?): HeadsetRecord? {
        val control = normalizeAddress(controlAddress) ?: return null
        return synchronized(lock) {
            val existing = recordForLocked(address) ?: recordForLocked(control) ?: return@synchronized null
            if (existing.controlAddress == control) return@synchronized existing
            put(
                existing.copy(
                    addresses = existing.addresses.plusAddress(control),
                    controlAddress = control,
                ),
                existing,
            )
        }
    }

    /**
     * Record a pair the LE Audio pairing flow created: [leAddress] is an LE identity of the headset
     * whose classic identity is [controlAddress].
     *
     * This is the one writer that legitimately knows the direction without a Tandem reply, because it
     * removed one bond and created the other. It also keys on [controlAddress] when no record exists —
     * the flow only runs from a bonded headset's own page, so that address is a real identifier.
     */
    fun rememberPair(leAddress: String?, controlAddress: String?): HeadsetRecord? {
        val le = normalizeAddress(leAddress) ?: return null
        val control = normalizeAddress(controlAddress) ?: return null
        if (le == control) return null
        return synchronized(lock) {
            val existing = recordForLocked(control) ?: recordForLocked(le)
            val base = existing ?: HeadsetRecord(key = control)
            put(
                base.copy(
                    addresses = base.addresses.plusAddress(control).plusAddress(le),
                    controlAddress = control,
                ),
                existing,
            )
        }
    }

    /** Update which transport is live, leaving every other field alone. */
    fun updateService(address: String?, service: PairingService): HeadsetRecord? =
        synchronized(lock) {
            val existing = recordForLocked(address) ?: return@synchronized null
            if (existing.service == service) return@synchronized existing
            put(existing.copy(service = service), existing)
        }

    private fun List<String>.plusAddress(address: String?): List<String> {
        val normalized = normalizeAddress(address) ?: return this
        return if (normalized in this) this else this + normalized
    }

    private fun recordForLocked(address: String?): HeadsetRecord? {
        val normalized = normalizeAddress(address) ?: return null
        return records[normalized] ?: records.values.firstOrNull { normalized in it.addresses }
    }

    private fun put(record: HeadsetRecord, existing: HeadsetRecord?): HeadsetRecord {
        records[record.key] = record
        if (record != existing) {
            log(
                "headset ${record.key} addresses=${record.addresses} " +
                    "control=${record.controlAddress ?: "unproved"} service=${record.service}"
            )
            persist()
        }
        return record
    }

    // ---- Pruning ----

    /**
     * Hold [addresses] against [prune] for the duration of a pairing run.
     *
     * The LE Audio pairing flow removes the classic bond, records the pair, and only then creates the
     * LE bond, so between those steps the record has no bonded address. Without this the very next
     * publish deletes it and the LE-only identity comes up unrecognised — which is what makes the
     * session build its capability table from the LEA-only support-function list.
     */
    fun pin(addresses: Collection<String>) {
        synchronized(lock) { pinned += addresses.mapNotNull(::normalizeAddress) }
    }

    fun unpin() {
        synchronized(lock) { pinned.clear() }
    }

    /**
     * Forget headsets the phone is no longer bonded to any identity of.
     *
     * SC `C14356p0.m62048o1` with the survivor test in `m61982d0`: a record survives while the bonded
     * set contains any address in its group. Unpairing in system settings is the only thing that makes
     * a record stale, and this is the only thing that removes one.
     *
     * Individual addresses are never stripped. SC leaves a stale group member in place until the next
     * capability reply rewrites the group, and stripping instead is what erased the pair the pairing
     * flow records before its bond exists.
     *
     * A no-op on an empty bonded set: that means the adapter is off or the permission is missing, not
     * that every headset was unpaired.
     */
    fun prune(bondedAddresses: Collection<String>) {
        val bonded = bondedAddresses.mapNotNull(::normalizeAddress).toSet()
        if (bonded.isEmpty()) return
        synchronized(lock) {
            val stale = records.values.filter { record ->
                record.addresses.none { it in bonded || it in pinned }
            }
            if (stale.isEmpty()) return
            stale.forEach { record ->
                records.remove(record.key)
                log("forgetting ${record.key}: no identity of it is bonded any more")
            }
            persist()
        }
    }

    /** Forget the headset [address] belongs to, for an explicit unpair from our own UI. */
    fun forget(address: String?) {
        synchronized(lock) {
            val record = recordForLocked(address) ?: return
            records.remove(record.key)
            log("forgetting ${record.key} on request")
            persist()
        }
    }

    // ---- Persistence and the cross-process feed ----

    /** Callers already hold [lock]. */
    private fun load() {
        val file = store ?: return
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return
        records.clear()
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            HeadsetRecord.deserialize(line)?.let { records[it.key] = it }
        }
    }

    /** Callers already hold [lock]. */
    private fun persist() {
        val file = store ?: return
        val text = records.values.joinToString("\n") { it.serialize() }
        runCatching { file.writeText(text) }
            .onFailure { log("persist failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Every record as one serialized line, for [dev.sonypods.bridge.SonyStateSnapshot].
     *
     * Only the process holding the Tandem session learns any of this, so this is how the answer
     * reaches the app and the MiLink hooks.
     */
    fun snapshotLines(): List<String> =
        synchronized(lock) { records.values.map { it.serialize() } }

    /**
     * Adopt the engine's records verbatim.
     *
     * A replacement, not a merge: the snapshot is the engine's complete list, so a record missing from
     * it is one the engine pruned. Merging left every consumer process holding an unpaired headset's
     * aliases until that process died. Never persists — only the engine's store is real.
     */
    fun ingest(lines: List<String>) {
        val incoming = LinkedHashMap<String, HeadsetRecord>()
        lines.forEach { line ->
            HeadsetRecord.deserialize(line)?.let { incoming[it.key] = it }
        }
        synchronized(lock) {
            if (store != null || records == incoming) return
            records.clear()
            records.putAll(incoming)
        }
        log("adopted ${incoming.size} headset record(s) from the engine")
    }

    private fun log(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    /** Reset all state. Only for unit tests. */
    internal fun resetForTesting() {
        synchronized(lock) {
            records.clear()
            pinned.clear()
            store = null
        }
    }
}
