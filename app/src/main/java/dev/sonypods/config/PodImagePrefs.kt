package dev.sonypods.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

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
    const val PREF_KEY_EARPHONES = "earphone_prefs_json"
    private const val IMAGE_DIR = "pod_images"

    private val _cacheRevision = MutableStateFlow(0L)
    /** Changes after an image/device record has been published to preferences. */
    val cacheRevision: StateFlow<Long> = _cacheRevision.asStateFlow()

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

    /** True when this device already has usable local artwork. */
    fun hasCachedBoxImage(prefs: SharedPreferences, address: String): Boolean =
        find(prefs, address)?.boxImagePath?.let { path ->
            File(path).isFile && File(path).length() > 0L
        } == true

    fun findOrLatest(prefs: SharedPreferences, address: String): EarphonePref? {
        return find(prefs, address) ?: load(prefs).maxByOrNull { it.lastConnectedAt }
    }

    fun imageDir(context: Context): File = File(context.filesDir, IMAGE_DIR).apply { mkdirs() }

    fun upsertConnected(
        prefs: SharedPreferences,
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
        // Keep the device record local; the app-hosted engine and renderer share this cache.
        return save(prefs, normalized)
    }

    fun saveImageBytes(
        context: Context,
        prefs: SharedPreferences,
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
                updated = updated.withImagePath(resource, copyImage(context, address, resource, bytes))
            }
        }
        updated = updated.copy(
            name = name.ifBlank { updated.name },
            lastConnectedAt = System.currentTimeMillis(),
            autoImageUrl = autoImageUrl ?: updated.autoImageUrl,
            imageRevision = if (imageUpdated) updated.imageRevision + 1L else updated.imageRevision,
        )
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        return save(prefs, normalized)
    }

    private fun save(prefs: SharedPreferences, earphones: List<EarphonePref>): List<EarphonePref> {
        val normalized = earphones.distinctBy { it.address.uppercase() }
        check(prefs.edit()
            .putString(PREF_KEY_EARPHONES, json.encodeToString(ListSerializer(EarphonePref.serializer()), normalized))
            .commit()) { "Unable to persist earphone image cache" }
        _cacheRevision.update { it + 1L }
        return normalized
    }

    /** Stable file name for an image in the app-private cache. */
    fun imageFileName(address: String, resource: PodImageResource): String =
        "${address.safeFileName()}_${resource.fileSuffix}.img"

    private fun copyImage(
        context: Context,
        address: String,
        resource: PodImageResource,
        bytes: ByteArray,
    ): String {
        val dir = imageDir(context)
        val file = File(dir, imageFileName(address, resource))
        // Do not expose a partially downloaded image to Compose.
        // The path is published only after the complete file
        // has been atomically moved into place.
        val temp = File.createTempFile(file.name, ".tmp", dir)
        try {
            temp.outputStream().use { it.write(bytes) }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
            }
            check(file.isFile && file.length() == bytes.size.toLong()) {
                "Image cache replacement was incomplete: ${file.absolutePath}"
            }
        } finally {
            temp.delete()
        }
        return file.absolutePath
    }

    private fun EarphonePref.withImagePath(resource: PodImageResource, path: String?): EarphonePref = when (resource) {
        PodImageResource.BOX -> copy(boxImagePath = path)
        PodImageResource.LEFT -> copy(leftImagePath = path)
        PodImageResource.RIGHT -> copy(rightImagePath = path)
    }

    private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
}
