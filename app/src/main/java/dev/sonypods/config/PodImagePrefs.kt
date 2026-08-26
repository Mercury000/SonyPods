package dev.sonypods.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
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
    /** Monotonic UI cache key; increments whenever automatic image bytes are replaced. */
    val imageRevision: Long = 0L,
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
 * notification/island/settings surfaces, and the module app reads and writes the same store
 * via its XposedService handle. No local SharedPreferences copy is kept anywhere; before the
 * LSPosed service binds, saves are buffered in memory by [pendingJson] and flushed by
 * [attachStore].
 *
 * The image BYTES themselves live in libxposed Remote Files (see [remoteImageFileName] /
 * [writeBytesToRemote]); the paths stored here point at the app's cache copy used for
 * Compose rendering.
 */
object PodImagePrefs {
    private const val TAG = "SonyPods-PodImage"
    const val AUTHORITY = "com.mercury.sonypods.podimages"
    const val PREF_KEY_EARPHONES = "earphone_prefs_json"
    private const val IMAGE_DIR = "pod_images"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var store: SharedPreferences? = null

    @Volatile
    private var pendingJson: String? = null

    /** Bind the app-side handle on the shared store and flush anything buffered pre-bind. */
    fun attachStore(prefs: SharedPreferences?) {
        if (prefs == null) return
        store = prefs
        pendingJson?.let { pending ->
            pendingJson = null
            writeToStore(prefs, pending)
            Log.d(TAG, "flushed buffered earphone metadata (${pending.length} bytes)")
        }
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

    fun loadCurrent(): List<EarphonePref> = load(store)

    fun findCurrent(address: String): EarphonePref? = find(store, address)

    fun imageDir(context: Context): File = File(context.filesDir, IMAGE_DIR).apply { mkdirs() }

    fun upsertConnected(
        address: String,
        name: String,
    ): List<EarphonePref> {
        if (address.isBlank()) return loadCurrent()
        val current = loadCurrent()
        val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
        // Drop records created by the removed custom-image feature. Catalog images keep
        // their URL marker and are refreshed by ModelImageSync when needed.
        val base = existing?.takeIf { it.autoImageUrl != null }
            ?: EarphonePref(address = address, name = name)
        val updated = base.copy(
            name = name.ifBlank { existing?.name.orEmpty() },
            lastConnectedAt = System.currentTimeMillis(),
        )
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        return persist(normalized)
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
        val current = loadCurrent()
        val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
        // Never carry image paths from the removed custom-image records into the
        // automatic catalog cache.
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
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        return persist(normalized)
    }

    private fun decode(raw: String): List<EarphonePref> = runCatching {
        json.decodeFromString(ListSerializer(EarphonePref.serializer()), raw)
    }.getOrDefault(emptyList())

    private fun persist(earphones: List<EarphonePref>): List<EarphonePref> {
        val normalized = earphones.distinctBy { it.address.uppercase() }
        val encoded = json.encodeToString(ListSerializer(EarphonePref.serializer()), normalized)
        val target = store
        if (target != null) {
            writeToStore(target, encoded)
        } else {
            pendingJson = encoded
            Log.w(TAG, "earphone metadata save before store bind; buffering until LSPosed service connects")
        }
        return normalized
    }

    private fun writeToStore(target: SharedPreferences, encoded: String) {
        runCatching {
            target.edit().putString(PREF_KEY_EARPHONES, encoded).apply()
        }.onFailure { Log.w(TAG, "earphone metadata write failed", it) }
    }

    /** Stable Remote File name shared by the module writer and Hook-side readers. */
    fun remoteImageFileName(address: String, resource: PodImageResource): String =
        "${address.safeFileName()}_${resource.fileSuffix}.img"

    /**
     * Write image bytes to the libxposed Remote Files store (the module's shared data dir)
     * so the hook process can read them via [io.github.libxposed.api.XposedInterface.openRemoteFile]
     * without depending on the PodImageProvider ContentProvider (which is unreachable from
     * com.android.bluetooth / com.xiaomi.bluetooth before user unlock, and fragile cross-process).
     * Returns true on success. Truncates to the exact byte count so a smaller replacement image
     * cannot leave a stale tail from a previous larger one with the same filename.
     */
    private fun writeBytesToRemote(s: XposedService, name: String, bytes: ByteArray): Boolean {
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
