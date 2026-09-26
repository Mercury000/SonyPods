package dev.sonypods.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.sonypods.device.SonyDeviceService
import io.github.libxposed.service.XposedService
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class PodImageResource(val fileSuffix: String) {
    BOX("box"),
    LEFT("left"),
    RIGHT("right"),
}
@Serializable
data class EarphonePref(
    val address: String,
    val name: String,
    val boxImagePath: String? = null,
    val leftImagePath: String? = null,
    val rightImagePath: String? = null,
    val lastConnectedAt: Long = System.currentTimeMillis(),
    /** Cloud catalog URL the box image was downloaded from; null = no catalog image cached. */
    val autoImageUrl: String? = null,
    /** Monotonic UI cache key; increments whenever image bytes are replaced (auto or manual). */
    val imageRevision: Long = 0L,
    /** User took over the BOX image manually; the automatic catalog download must not replace it. */
    val boxManual: Boolean = false,
) {
    fun imagePath(resource: PodImageResource): String? = when (resource) {
        PodImageResource.BOX -> boxImagePath
        PodImageResource.LEFT -> leftImagePath
        PodImageResource.RIGHT -> rightImagePath
    }
}

/**
 * Per-device earphone metadata (which model image belongs to which Bluetooth address).
 *
 * Persisted ONLY in the framework-backed remote-preference store ([ConfigManager.PREFS_NAME]
 * group): the hooked processes read `earphone_prefs_json` from it to resolve images for the
 * notification/island/settings surfaces, and the module app reads and writes the store via its
 * XposedService handle.
 *
 * Writes follow the same rule as [ConfigManager]: a mutation is always applied to the list read
 * back from the store at bind time, so nothing is written before the store was read and the
 * process default (an empty list) can never overwrite a real list.
 *
 * The image BYTES themselves live in libxposed Remote Files (see [remoteImageFileName] /
 * [writeBytesToRemote]); the paths stored here point at the app's cache copy used for
 * Compose rendering.
 */
object PodImagePrefs {
    private const val TAG = "SonyPods-Cloud"
    const val PREF_KEY_EARPHONES = "earphone_prefs_json"
    private const val IMAGE_DIR = "pod_images"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The adopted metadata list plus the store it came from. */
    private class Adopted(val store: SharedPreferences, val earphones: List<EarphonePref>)

    @Volatile
    private var adopted: Adopted? = null

    /**
     * App process only: bind the writable store, mirroring [ConfigManager.attachWritableStore].
     * The store is read once at bind; that read is the truth. An empty read adopts an empty
     * list in memory without writing, so a store that actually holds records is left intact and
     * the process default never overwrites it.
     */
    @Synchronized
    fun attachWritableStore(service: XposedService, legacySeed: () -> String?) {
        val prefs = runCatching { service.getRemotePreferences(ConfigManager.PREFS_NAME) }.getOrNull()
        if (prefs == null) {
            Log.w(TAG, "attachWritableStore skipped: remote-pref store unavailable")
            return
        }
        val raw = runCatching { prefs.getString(PREF_KEY_EARPHONES, null) }.getOrNull()
        val decoded = raw?.let(::decodeOrNull)
        when {
            decoded != null -> adopt(prefs, decoded)
            raw != null -> {
                Log.e(TAG, "earphone metadata present but undecodable; adopting empty without writing")
                adopt(prefs, emptyList())
            }
            else -> {
                val legacy = legacySeed()?.let(::decodeOrNull)
                if (legacy != null) {
                    Log.d(TAG, "seeding earphone metadata from legacy local preferences")
                    adopt(prefs, legacy)
                    writeToStore(prefs, encode(legacy))
                } else {
                    adopt(prefs, emptyList())
                }
            }
        }
    }

    @Synchronized
    private fun adopt(store: SharedPreferences, earphones: List<EarphonePref>) {
        adopted = Adopted(store, earphones)
    }

    // ── Hook side (read-only store passed explicitly) and generic readers ──

    fun load(prefs: SharedPreferences?): List<EarphonePref> {
        val raw = prefs?.getString(PREF_KEY_EARPHONES, null) ?: return emptyList()
        return decode(raw)
    }

    fun find(prefs: SharedPreferences?, address: String): EarphonePref? {
        if (address.isBlank()) return null
        return load(prefs).firstOrNull { it.address.equals(address, ignoreCase = true) }
    }

    fun findOrLatest(prefs: SharedPreferences?, address: String): EarphonePref? {
        return find(prefs, address) ?: load(prefs).maxByOrNull { it.lastConnectedAt }
    }

    // ── App process (uses the bound store handle) ──

    fun loadCurrent(): List<EarphonePref> = adopted?.earphones ?: emptyList()

    /** True once a confirmed metadata list was adopted; until then nothing may be written. */
    fun isMetadataReady(): Boolean = adopted != null

    fun findCurrent(address: String): EarphonePref? {
        if (address.isBlank()) return null
        return loadCurrent().firstOrNull { it.address.equals(address, ignoreCase = true) }
    }

    fun imageDir(context: Context): File = File(context.filesDir, IMAGE_DIR).apply { mkdirs() }

    fun upsertConnected(
        address: String,
        name: String,
    ): List<EarphonePref> {
        if (address.isBlank()) return loadCurrent()
        return persist { current ->
            val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
            // Keep catalog-owned records and manual BOX overrides; only stale records with
            // neither (leftovers of the pre-catalog custom-image era) are dropped on connect.
            val base = existing?.takeIf { it.autoImageUrl != null || it.boxManual }
                ?: EarphonePref(address = address, name = name)
            val updated = base.copy(
                name = name.ifBlank { existing?.name.orEmpty() },
                lastConnectedAt = System.currentTimeMillis(),
            )
            listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        }
    }

    fun saveImageBytes(
        context: Context,
        service: XposedService?,
        address: String,
        name: String,
        images: Map<PodImageResource, ByteArray>,
        autoImageUrl: String? = null,
    ): List<EarphonePref> {
        if (address.isBlank()) return loadCurrent()
        return persist { current ->
            val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
            // A manual BOX override is user-owned: automatic catalog bytes must not clobber it.
            if (existing?.boxManual == true) {
                current
            } else {
                // Do not carry image paths from stale pre-catalog records into the automatic
                // catalog cache.
                var updated = existing?.takeIf { it.autoImageUrl != null }
                    ?: EarphonePref(address = address, name = name)
                var imageUpdated = false
                images.forEach { (resource, bytes) ->
                    if (bytes.isNotEmpty()) {
                        imageUpdated = true
                        updated = updated.withImagePath(resource, copyImage(context, service, address, resource, bytes))
                    }
                }
                updated = updated.copy(
                    name = name.ifBlank { updated.name },
                    lastConnectedAt = System.currentTimeMillis(),
                    autoImageUrl = autoImageUrl ?: updated.autoImageUrl,
                    imageRevision = if (imageUpdated) updated.imageRevision + 1L else updated.imageRevision,
                )
                listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
            }
        }
    }

    /**
     * Replace the BOX image with a user-picked picture. Writes into the same
     * `<address>_box.img` slot the catalog uses, so every surface (detail page,
     * notification, island, card) sees it once the Remote File is published.
     *
     * A headset holds two Bluetooth identities (CLASSIC control + LE Audio) that the module
     * stores under separate addresses, so the picture is written for every identity of the
     * same headset — whichever identity a surface renders from, it sees the same override.
     * Marks each record [EarphonePref.boxManual] so the automatic catalog download leaves it
     * alone; the retained [EarphonePref.autoImageUrl] stays as the target for a later
     * "restore from cloud". Bumps [EarphonePref.imageRevision] so the detail page repaints
     * even though the file path is unchanged.
     */
    fun saveBoxOverride(
        context: Context,
        service: XposedService?,
        address: String,
        name: String,
        bytes: ByteArray,
    ): EarphonePref? {
        if (address.isBlank() || bytes.isEmpty()) return null
        val primary = address.trim().uppercase()
        var primarySaved: EarphonePref? = null
        boxIdentityAddresses(primary).forEach { target ->
            val saved = persist { current ->
                val existing = current.firstOrNull { it.address.equals(target, ignoreCase = true) }
                val base = existing ?: EarphonePref(address = target, name = name)
                val path = copyImage(context, service, target, PodImageResource.BOX, bytes)
                val updated = base.copy(
                    boxImagePath = path,
                    boxManual = true,
                    name = name.ifBlank { base.name },
                    lastConnectedAt = System.currentTimeMillis(),
                    imageRevision = base.imageRevision + 1L,
                )
                listOf(updated) + current.filterNot { it.address.equals(target, ignoreCase = true) }
            }.firstOrNull { it.address.equals(target, ignoreCase = true) }
            if (target == primary) primarySaved = saved
        }
        return primarySaved
    }

    /**
     * Restore the BOX image from the cloud catalog: clears the manual override and
     * stores freshly downloaded [bytes] for [url] into the shared slot, for every
     * identity of the headset. Bumps [EarphonePref.imageRevision] so surfaces repaint.
     */
    fun applyCloudBox(
        context: Context,
        service: XposedService?,
        address: String,
        name: String,
        url: String,
        bytes: ByteArray,
    ): EarphonePref? {
        if (address.isBlank() || bytes.isEmpty() || url.isBlank()) return null
        val primary = address.trim().uppercase()
        var primaryStored: EarphonePref? = null
        boxIdentityAddresses(primary).forEach { target ->
            val stored = persist { current ->
                val existing = current.firstOrNull { it.address.equals(target, ignoreCase = true) }
                val base = existing ?: EarphonePref(address = target, name = name)
                val path = copyImage(context, service, target, PodImageResource.BOX, bytes)
                val updated = base.copy(
                    boxImagePath = path,
                    boxManual = false,
                    autoImageUrl = url,
                    name = name.ifBlank { base.name },
                    lastConnectedAt = System.currentTimeMillis(),
                    imageRevision = base.imageRevision + 1L,
                )
                listOf(updated) + current.filterNot { it.address.equals(target, ignoreCase = true) }
            }.firstOrNull { it.address.equals(target, ignoreCase = true) }
            if (target == primary) primaryStored = stored
        }
        return primaryStored
    }

    /**
     * Every identity address that must share the BOX picture: the given one plus the other
     * bonded identities (LE/CLASSIC) of the same headset, per [dev.sonypods.device.HeadsetRegistry].
     * The registry is process-local and populated by ingesting the engine snapshot, so when it
     * has not learned the headset yet this degrades to the single address given.
     */
    private fun boxIdentityAddresses(address: String): List<String> {
        val primary = address.trim().uppercase()
        val siblings = runCatching { SonyDeviceService.identityAliasesOf(address) }
            .getOrDefault(emptyList())
        return buildList {
            add(primary)
            siblings.forEach { sib ->
                val normalized = sib.trim().uppercase()
                if (normalized != primary && normalized !in this) add(normalized)
            }
        }
    }

    private fun decode(raw: String): List<EarphonePref> = decodeOrNull(raw) ?: emptyList()

    private fun decodeOrNull(raw: String): List<EarphonePref>? = runCatching {
        json.decodeFromString(ListSerializer(EarphonePref.serializer()), raw)
    }.getOrNull()

    private fun encode(earphones: List<EarphonePref>): String =
        json.encodeToString(ListSerializer(EarphonePref.serializer()), earphones)

    /**
     * Apply [mutate] to the adopted list and persist the outcome.
     *
     * Nothing is written — and [mutate] is not even run — before a source was adopted: the
     * list it would build on does not exist yet, and inventing an empty one would drop every
     * other headset's metadata.
     */
    @Synchronized
    private fun persist(mutate: (List<EarphonePref>) -> List<EarphonePref>): List<EarphonePref> {
        val base = adopted
        if (base == null) {
            Log.e(TAG, "refusing metadata write before a source was adopted; change dropped")
            return emptyList()
        }
        val next = mutate(base.earphones).distinctBy { it.address.uppercase() }
        adopted = Adopted(base.store, next)
        writeToStore(base.store, encode(next))
        return next
    }

    private fun writeToStore(store: SharedPreferences, encoded: String) {
        runCatching {
            store.edit().putString(PREF_KEY_EARPHONES, encoded).apply()
        }.onFailure { Log.w(TAG, "earphone metadata write failed", it) }
    }

    /** Stable Remote File name shared by the module writer and Hook-side readers. */
    fun remoteImageFileName(address: String, resource: PodImageResource): String =
        "${address.safeFileName()}_${resource.fileSuffix}.img"

    /**
     * Write image bytes to the libxposed Remote Files store (the module's shared data dir)
     * so the hook process can read them via [io.github.libxposed.api.XposedInterface.openRemoteFile].
     * Returns true on success. Truncates to the exact byte count so a smaller replacement image
     * cannot leave a stale tail from a previous larger one with the same filename.
     */
    private fun writeBytesToRemote(s: XposedService, name: String, bytes: ByteArray): Boolean {
        val unchanged = runCatching {
            s.openRemoteFile(name).use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { it.readBytes() }.contentEquals(bytes)
            }
        }.getOrDefault(false)
        if (unchanged) return false
        return runCatching {
            s.openRemoteFile(name).use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { it.write(bytes) }
                // openRemoteFile may not truncate on open; chop any leftover tail.
                runCatching { android.system.Os.ftruncate(pfd.fileDescriptor, bytes.size.toLong()) }
            }
            true
        }.onFailure { Log.w(TAG, "writeBytesToRemote failed for $name", it) }.getOrDefault(false)
    }

    /**
     * Synchronize cached pod images into the Remote Files store, so the hook process can
     * read images saved before or while the app's Xposed service was unavailable. This
     * is intentionally idempotent and runs on every service bind: a static state receiver
     * can download an image before the service callback arrives.
     */
    fun migrateImagesToRemote(service: XposedService?) {
        val s = service ?: return
        var migrated = 0
        loadCurrent().forEach { earphone ->
            PodImageResource.entries.forEach { res ->
                val path = earphone.imagePath(res) ?: return@forEach
                val file = File(path)
                if (!file.isFile) return@forEach
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                if (writeBytesToRemote(s, file.name, bytes)) migrated++
            }
        }
        Log.d(TAG, "migrateImagesToRemote: migrated $migrated file(s)")
    }

    private fun copyImage(
        context: Context,
        service: XposedService?,
        address: String,
        resource: PodImageResource,
        bytes: ByteArray,
    ): String {
        val dir = imageDir(context)
        val file = File(dir, remoteImageFileName(address, resource))
        // Do not expose a partially downloaded image to Compose or a hooked
        // system surface. The path is published only after the complete file
        // has been atomically moved into place.
        val temp = File(dir, "${file.name}.tmp")
        temp.outputStream().use { it.write(bytes) }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        service?.let { writeBytesToRemote(it, file.name, bytes) }
        return file.absolutePath
    }

    private fun EarphonePref.withImagePath(resource: PodImageResource, path: String?): EarphonePref = when (resource) {
        PodImageResource.BOX -> copy(boxImagePath = path)
        PodImageResource.LEFT -> copy(leftImagePath = path)
        PodImageResource.RIGHT -> copy(rightImagePath = path)
    }

    private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
}
