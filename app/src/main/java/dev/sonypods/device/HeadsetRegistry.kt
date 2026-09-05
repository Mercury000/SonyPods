package dev.sonypods.device

import android.content.Context
import android.util.Log
import dev.sonypods.device.HeadsetRecord.Companion.normalizeAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * The headsets we have had a Tandem session with, and what each one said about itself.
 *
 * This replaces classifying bonds. Sound Connect never classifies one — it keeps a registry of
 * devices it has connected to (`C13896f`, persisted as JSON per record) and takes every identity
 * fact from the headset's own Tandem replies. The chain, end to end:
 *
 * 1. Something connects — A2DP or LE Audio. SC's `C14326e.m61860c` (*autoPickup*) asks the stack
 *    which is live, LE Audio winning over A2DP (`C14332g.m61875b`), and looks the raw address up in
 *    the registry. A hit connects the stored record; a miss registers a shallow one first.
 * 2. The Tandem session comes up and the capability table completes. `C14322c1.m61848o`
 *    (*syncDeviceState*) then builds the full record — `zb0.C32170h.m115675v` — whose identity
 *    fields come from `LEA_RET_CAPABILITY` (unique id + LE addresses) and the LEA status'
 *    `StreamingStatus` (which transport is live).
 * 3. That record is saved, deduped against the existing ones by unique id.
 * 4. `C14322c1.m61845g` (*connectHoldingDeviceIfNeed*) then dials the sibling identity over GATT.
 *
 * Consequences worth keeping in mind:
 * - **Nothing here answers before the first session.** A bonded-but-never-connected headset is
 *   unknown, by construction. That is why the device list is this registry rather than the OS
 *   bonded set.
 * - **No permission is needed.** No `bt_config.conf`, no reflection into `AdapterService`, no CSIS
 *   group read. The identity is the headset's testimony.
 *
 * One deliberate deviation from SC: it keys its repository by **address**, so a headset connected
 * once over classic and once over LE Audio ends up as two records cross-linked by `tandem_uniqueId`
 * (`C13896f.m60185h` matches them, `C21510k.m84700o` propagates flags across them, `m84703t` deletes
 * them together, and `UpdateDeviceItemListTask` merely syncs their selected state so both rows
 * highlight at once). Here one record is one headset: the unique id is the key and the LE addresses
 * are a field on it. Every consumer wants a single canonical control address, and one row per
 * headset is what the picker should show.
 */
object HeadsetRegistry {
    private const val TAG = "SonyPods-Engine"
    private const val STORE_FILE = "sonypods_headsets.txt"

    /** Records keyed by [HeadsetRecord.uniqueId]. */
    private val records = ConcurrentHashMap<String, HeadsetRecord>()

    /** Insertion order of [records], oldest first. */
    private val order = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * Where the engine persists. Only the Bluetooth process passes a context; every other process
     * lives off [ingest] and never writes, the same split the old identity service used.
     */
    @Volatile
    private var store: java.io.File? = null

    fun initializeForEngine(engineContext: Context? = null) {
        store = engineContext?.let { java.io.File(it.filesDir, STORE_FILE) }
        load()
        log("initialized with ${records.size} headset record(s)")
    }

    // ---- Lookup ----

    /**
     * The headset [address] belongs to, or null when no session has ever identified it.
     *
     * Set membership over the record's own address plus its reported LE addresses — SC's
     * `SettingsTopPresenter.m59962m`, and the same predicate its A2DP auto-connect path
     * (`C14289i.m61741x`) uses to turn a raw event address into a registered one.
     */
    fun recordFor(address: String?): HeadsetRecord? {
        val normalized = normalizeAddress(address) ?: return null
        records[normalized]?.let { return it }
        return records.values.firstOrNull { normalized in it.leAddresses }
    }

    /** Every known headset, in the order they were first identified. */
    fun all(): List<HeadsetRecord> = order.mapNotNull { records[it] }

    /**
     * The control (classic) address for [address], or null when the headset is unknown.
     *
     * This is the whole of "normalization" in SC's model: one record, one canonical address, and
     * every identity of the headset resolves to it.
     */
    fun controlAddressFor(address: String?): String? = recordFor(address)?.uniqueId

    /** The LE addresses of the headset [address] belongs to, in the order it reported them. */
    fun leAddressesFor(address: String?): List<String> = recordFor(address)?.leAddresses.orEmpty()

    /** Which transport the headset [address] belongs to last reported as carrying audio. */
    fun serviceFor(address: String?): PairingService? = recordFor(address)?.service

    /** Whether [address] is an LE identity rather than the control one. */
    fun isLeIdentity(address: String?): Boolean = recordFor(address)?.isLeIdentity(address) == true

    // ---- Recording ----

    /**
     * Record what a Tandem session learned about the headset it is talking to.
     *
     * [reportedLeAddresses] are the raw strings the headset sent; they are kept only when the OS is
     * actually bonded to them, which is SC's `C12272j.m52763b` — exact match against the bonded set
     * first, then [RpaResolver] for a resolvable private address. An LE address the phone has no
     * bond for is not a dialable identity, so storing it would only produce connect attempts that
     * cannot land.
     *
     * Returns the stored record, or null when [uniqueId] is not a usable address.
     */
    fun remember(
        uniqueId: String?,
        name: String? = null,
        reportedLeAddresses: List<String> = emptyList(),
        bondedAddresses: Collection<String> = emptyList(),
        identityResolvingKeys: List<ByteArray> = emptyList(),
        service: PairingService? = null,
        supportsLeClassic: Boolean? = null,
        bothPairedHistory: Boolean? = null,
    ): HeadsetRecord? {
        val key = normalizeAddress(uniqueId) ?: return null
        val bonded = bondedAddresses.mapNotNull(::normalizeAddress).toSet()
        val resolved = RpaResolver
            .resolveAll(reportedLeAddresses, bonded, identityResolvingKeys, ::log)
            .filterNot { it == key }
        val existing = records[key]
        val merged = HeadsetRecord(
            uniqueId = key,
            name = name ?: existing?.name,
            // A reply that resolved nothing must not erase what an earlier one did: the LE bond can
            // be absent at the moment a classic-only session reports, and SC keeps the stored group
            // in exactly that case (`C13896f.m60188o` saves the incoming record but its uniqueId
            // branch leaves the previous fields alone).
            leAddresses = resolved.ifEmpty { existing?.leAddresses.orEmpty() },
            service = service ?: existing?.service ?: PairingService.CLASSIC,
            supportsLeClassic = supportsLeClassic ?: existing?.supportsLeClassic ?: false,
            bothPairedHistory = bothPairedHistory ?: existing?.bothPairedHistory ?: false,
        )
        return put(merged, existing)
    }

    /**
     * Merge a set of addresses the headset named as its own into whichever record already owns one
     * of them, without deciding which of them is the control identity.
     *
     * The `LEA_RET_CAPABILITY` reply's first field is documented as a *Device Unique Id*, and it is
     * tempting to read that as the classic BD address. On a LinkBuds S it is not: measured
     * 2026-09-05, the headset answers `unique=80:99:E7:D8:60:09 le=[C5:93:15:6B:E6:34,
     * 80:99:E7:D8:60:09]` while `80:99:E7:D8:60:09` is the address the LE Audio profile is connected
     * on and `C5:93:15:6B:E6:34` is the classic one the capability store is keyed by. Taking the
     * first field as the key therefore inverts the pair and every consumer of
     * [controlAddressFor] with it.
     *
     * So direction is not taken from this reply at all. The control identity is whatever
     * `CONNECT_RET_CAPABILITY_INFO` named — it arrives first, on the link that carries control — and
     * this call only widens that record's address set. SC's own "same headset" test is set
     * membership with no direction (`SettingsTopPresenter.m59962m`), which is exactly what this
     * preserves.
     *
     * Falls back to keying on the first bonded address when no record exists yet, so a session that
     * somehow never saw the capability info still records something usable.
     */
    fun rememberReportedAddresses(
        addresses: List<String>,
        name: String? = null,
        bondedAddresses: Collection<String> = emptyList(),
        identityResolvingKeys: List<ByteArray> = emptyList(),
        service: PairingService? = null,
        supportsLeClassic: Boolean? = null,
        bothPairedHistory: Boolean? = null,
    ): HeadsetRecord? {
        val bonded = bondedAddresses.mapNotNull(::normalizeAddress).toSet()
        val resolved = RpaResolver.resolveAll(addresses, bonded, identityResolvingKeys, ::log)
        if (resolved.isEmpty()) return null
        val existing = resolved.firstNotNullOfOrNull { recordFor(it) }
        val key = existing?.uniqueId ?: resolved.first()
        return remember(
            uniqueId = key,
            name = name,
            reportedLeAddresses = resolved,
            bondedAddresses = bondedAddresses,
            identityResolvingKeys = identityResolvingKeys,
            service = service,
            supportsLeClassic = supportsLeClassic,
            bothPairedHistory = bothPairedHistory,
        )
    }

    /**
     * Record that [leAddress] serves the headset reachable at [controlAddress].
     *
     * The module's own LE Audio pairing flow is the one place that knows the pair before any Tandem
     * session exists — it created both bonds. SC has no equivalent because it never pairs.
     */
    fun rememberLeAddress(leAddress: String?, controlAddress: String?): HeadsetRecord? {
        val le = normalizeAddress(leAddress) ?: return null
        val key = normalizeAddress(controlAddress) ?: return null
        if (le == key) return null
        val existing = records[key]
        val merged = (existing ?: HeadsetRecord(uniqueId = key)).copy(
            leAddresses = (existing?.leAddresses.orEmpty() + le).distinct(),
        )
        return put(merged, existing)
    }

    /** Update which transport is live, leaving every other field alone. */
    fun updateService(address: String?, service: PairingService): HeadsetRecord? {
        val existing = recordFor(address) ?: return null
        if (existing.service == service) return existing
        return put(existing.copy(service = service), existing)
    }

    private fun put(record: HeadsetRecord, existing: HeadsetRecord?): HeadsetRecord {
        records[record.uniqueId] = record
        // Append-only: the list the picker renders should not reshuffle every time a poll reports the
        // same transport again.
        if (record.uniqueId !in order) order.add(record.uniqueId)
        if (record != existing) {
            log(
                "headset ${record.uniqueId} service=${record.service} " +
                    "le=${record.leAddresses} bothHistory=${record.bothPairedHistory}"
            )
            persist()
        }
        return record
    }

    // ---- Pruning ----

    /**
     * Reconciles the registry with what the OS is still bonded to.
     *
     * SC `C14356p0.m62048o1` (*maintainRegisteredDevicesWithOsBondedDevices*) with the survivor test
     * in `m61982d0`: a record survives if the bonded set contains its own address **or** any address
     * in its group. Unpairing in system settings is what makes a registry entry stale, and this is
     * the only thing that removes one — nothing prunes on a mere disconnect.
     *
     * An LE address of a surviving record is dropped the same way. SC leaves those in place until the
     * next capability reply rewrites the group, which leaves the module's own LE-only unpair flow
     * advertising a bond it just removed.
     *
     * A no-op when [bondedAddresses] is empty: an empty answer means the adapter is off or the
     * permission is missing, not that every headset was unpaired.
     */
    fun prune(bondedAddresses: Collection<String>) {
        val bonded = bondedAddresses.mapNotNull(::normalizeAddress).toSet()
        if (bonded.isEmpty()) return
        var changed = false
        records.values.toList().forEach { record ->
            if (record.addresses.none { it in bonded }) {
                records.remove(record.uniqueId)
                order.remove(record.uniqueId)
                log("forgetting ${record.uniqueId}: no identity of it is bonded any more")
                changed = true
                return@forEach
            }
            val live = record.leAddresses.filter { it in bonded }
            if (live.size != record.leAddresses.size) {
                records[record.uniqueId] = record.copy(leAddresses = live)
                log("dropping unbonded LE identities of ${record.uniqueId}: kept $live")
                changed = true
            }
        }
        if (changed) persist()
    }

    // ---- Session targeting ----

    /**
     * The address a session should target, given whatever address an event carried.
     *
     * SC `C14289i.m61741x`: the registry is searched for a record owning the event's address and
     * that record's own address is dialed instead. Unknown addresses pass through unchanged, so
     * callers can use this without a null check.
     */
    fun sessionTargetFor(rawAddress: String): String =
        recordFor(rawAddress)?.uniqueId ?: rawAddress

    // ---- Persistence (engine only) ----

    private fun load() {
        val file = store ?: return
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return
        records.clear()
        order.clear()
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            HeadsetRecord.deserialize(line)?.let {
                records[it.uniqueId] = it
                order.remove(it.uniqueId)
                order.add(it.uniqueId)
            }
        }
    }

    private fun persist() {
        val file = store ?: return
        val text = order.mapNotNull { records[it] }.joinToString("\n") { it.serialize() }
        runCatching { file.writeText(text) }
            .onFailure { log("persist failed: ${it.javaClass.simpleName}") }
    }

    // ---- Cross-process feed ----

    /**
     * Every record as one serialized line, for [dev.sonypods.bridge.SonyStateSnapshot].
     *
     * Only the process holding the Tandem session learns any of this, so this is how the answer
     * reaches the app and the MiLink hooks. No consumer derives it locally.
     */
    fun snapshotLines(): List<String> = order.mapNotNull { records[it]?.serialize() }

    /** Adopt the records a snapshot carried. Never persists — only the engine's store is real. */
    fun ingest(lines: List<String>) {
        var changed = false
        lines.forEach { line ->
            val record = HeadsetRecord.deserialize(line) ?: return@forEach
            if (records[record.uniqueId] == record) return@forEach
            records[record.uniqueId] = record
            order.remove(record.uniqueId)
            order.add(record.uniqueId)
            changed = true
        }
        if (changed) log("adopted ${lines.size} headset record(s) from snapshot")
    }

    private fun log(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    /** Reset all state. Only for unit tests. */
    internal fun resetForTesting() {
        records.clear()
        order.clear()
        store = null
    }
}
