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

object PodImagePrefs {
    private const val TAG = "SonyPods-PodImage"
    const val AUTHORITY = "com.mercury.sonypods.podimages"
    const val PREF_KEY_EARPHONES = "earphone_prefs_json"
    private const val IMAGE_DIR = "pod_images"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(prefs: SharedPreferences): List<EarphonePref> {
        val raw = prefs.getString(PREF_KEY_EARPHONES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(EarphonePref.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun find(prefs: SharedPreferences, address: String): EarphonePref? {
        if (address.isBlank()) return null
        return load(prefs).firstOrNull { it.address.equals(address, ignoreCase = true) }
    }

    fun findOrLatest(prefs: SharedPreferences, address: String): EarphonePref? {
        return find(prefs, address) ?: load(prefs).maxByOrNull { it.lastConnectedAt }
    }

    fun imageDir(context: Context): File = File(context.filesDir, IMAGE_DIR).apply { mkdirs() }

    fun upsertConnected(
        prefs: SharedPreferences,
        service: XposedService?,
        address: String,
        name: String,
    ): List<EarphonePref> {
        if (address.isBlank()) return load(prefs)
        val current = load(prefs)
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
        // Publish earphone metadata to BOTH local prefs and the framework-backed remote
        // prefs. The hook process (com.android.bluetooth / com.xiaomi.bluetooth) reads
        // earphone_prefs_json from remote prefs to resolve which image belongs to which
        // device address — without it, PodImagePrefs.find returns null and the notification
        // falls back to the stock image. This is safe now that config_json is written to
        // remote prefs separately (ConfigManager.writeRemoteConfig); readConfig never reads
        // earphone_prefs_json, so it cannot default the engine config.
        return saveBoth(prefs, service, normalized)
    }

    fun saveImageBytes(
        context: Context,
        prefs: SharedPreferences,
        service: XposedService?,
        address: String,
        name: String,
        images: Map<PodImageResource, ByteArray>,
        autoImageUrl: String? = null,
    ): List<EarphonePref> {
        if (address.isBlank()) return load(prefs)
        val current = load(prefs)
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
        return saveBoth(prefs, service, normalized)
    }

    private fun save(prefs: SharedPreferences, earphones: List<EarphonePref>): List<EarphonePref> {
        val normalized = earphones.distinctBy { it.address.uppercase() }
        prefs.edit()
            .putString(PREF_KEY_EARPHONES, json.encodeToString(ListSerializer(EarphonePref.serializer()), normalized))
            .apply()
        return normalized
    }

    /**
     * Persist earphone metadata to local prefs AND the framework-backed remote-prefs store.
     * The hook process reads earphone_prefs_json from remote prefs to resolve per-device
     * images; without the remote write it would only see stale/empty metadata and fall back
     * to the stock image in the notification/island. See [upsertConnected] for why this is
     * safe alongside config_json.
     */
    private fun saveBoth(
        prefs: SharedPreferences,
        service: XposedService?,
        earphones: List<EarphonePref>,
    ): List<EarphonePref> {
        val normalized = save(prefs, earphones)
        runCatching {
            service?.getRemotePreferences(ConfigManager.PREFS_NAME)
                ?.edit()
                ?.putString(PREF_KEY_EARPHONES, json.encodeToString(ListSerializer(EarphonePref.serializer()), normalized))
                ?.apply()
        }.onFailure { Log.w(TAG, "saveBoth: remote prefs write failed", it) }
        return normalized
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

    private fun writeImageToRemote(service: XposedService?, name: String, bytes: ByteArray) {
        val s = service ?: return
        writeBytesToRemote(s, name, bytes)
    }

    /**
     * Synchronize local pod images into the Remote Files store, so the hook process can
     * read images saved before or while the app's Xposed service was unavailable. This
     * is intentionally idempotent and runs on every service bind: a static state receiver
     * can download an image before the service callback arrives.
     */
    fun migrateImagesToRemote(context: Context, service: XposedService?) {
        val s = service ?: return
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        var migrated = 0
        load(prefs).forEach { earphone ->
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
        writeImageToRemote(service, file.name, bytes)
        return file.absolutePath
    }

    private fun EarphonePref.withImagePath(resource: PodImageResource, path: String?): EarphonePref = when (resource) {
        PodImageResource.BOX -> copy(boxImagePath = path)
        PodImageResource.LEFT -> copy(leftImagePath = path)
        PodImageResource.RIGHT -> copy(rightImagePath = path)
    }

    private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
}
