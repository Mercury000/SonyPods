package dev.sonypods.config

/**
 * In-memory accumulation of probe results before they are written to
 * [CapabilityStorage] — the same split Sound Connect has between `C15170d`
 * (working set) and `CapabilityStorageAndroid` (SQLite).
 *
 * Keyed by (identifier, store_group, command_table_number). A probe appends the
 * raw capability command payloads (command byte + payload, exactly SC's
 * `C15171e.encodedPayload`) it receives; [saveAll] writes every group back
 * in one pass (REPLACE per row), so a reconnection that hits the cache never
 * touches the table. Nothing is deleted here or in storage.
 */
class CapabilityCacheManager(
    private val storage: CapabilityStorage,
) {
    private data class Group(
        val counter: Int,
        val blobs: MutableList<ByteArray>,
    )

    private val groups = HashMap<Key, Group>()

    data class Key(
        val identifier: String,
        val storeGroup: Int,
        val tableNumber: Int,
    )

    /** Record one capability command byte array. SC appends every capability
     * response for a matching counter. A mismatching counter is ignored rather
     * than merged into (or replacing) the current working group. Identical
     * payloads are skipped: the recording window spans the whole session, so
     * periodic refresh bursts would otherwise grow the list without end. */
    fun put(identifier: String, storeGroup: Int, tableNumber: Int, counter: Int, bytes: ByteArray) {
        val key = Key(identifier, storeGroup, tableNumber)
        val existing = groups[key]
        when {
            existing == null -> groups[key] = Group(counter, mutableListOf(bytes.copyOf()))
            existing.counter != counter -> Unit
            existing.blobs.none { it.contentEquals(bytes) } -> existing.blobs.add(bytes.copyOf())
        }
    }

    /**
     * Write every working-set group to SQLite in one pass.
     *
     * Called once, when the exchange has completed — the same contract as Sound Connect's
     * `C15170d.m65487h` (`saveIntoStorage`), which the initializer invokes after it has
     * finished. Whatever reaches storage is therefore a whole exchange, which is what makes
     * the whole-row REPLACE in [CapabilityStorage.writeRow] safe: there is no partial state to
     * overwrite a complete one with. An exchange that never completes never writes, so the
     * previous row survives untouched.
     */
    fun saveAll() {
        groups.forEach { (key, group) ->
            storage.writeRow(key.identifier, key.storeGroup, key.tableNumber, group.counter, group.blobs)
        }
    }

    /** Drop the working set (process restart / disconnect teardown). */
    fun clear() {
        groups.clear()
    }
}
