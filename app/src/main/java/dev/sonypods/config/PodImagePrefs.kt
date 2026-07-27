package dev.sonypods.config

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.github.libxposed.service.XposedService
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    /** Cloud catalog URL the box image was auto-downloaded from; null = user-provided image. */
    val autoImageUrl: String? = null,
) {
    fun imagePath(resource: PodImageResource): String? = when (resource) {
        PodImageResource.BOX -> boxImagePath
        PodImageResource.LEFT -> leftImagePath
        PodImageResource.RIGHT -> rightImagePath
    }
}

object PodImagePrefs {
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
        val updated = (existing ?: EarphonePref(address = address, name = name)).copy(
            name = name.ifBlank { existing?.name.orEmpty() },
            lastConnectedAt = System.currentTimeMillis(),
        )
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        service?.getRemotePreferences(ConfigManager.PREFS_NAME)?.let { save(it, normalized) }
        return save(prefs, normalized)
    }

    fun saveImages(
        context: Context,
        prefs: SharedPreferences,
        service: XposedService?,
        address: String,
        name: String,
        selectedImages: Map<PodImageResource, Uri?>,
        clearedImages: Set<PodImageResource> = emptySet(),
    ): List<EarphonePref> {
        if (address.isBlank()) return load(prefs)
        val current = load(prefs)
        val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
        var updated = existing ?: EarphonePref(address = address, name = name)
        clearedImages.forEach { resource ->
            updated = updated.withImagePath(resource, null)
        }
        selectedImages.forEach { (resource, uri) ->
            if (uri != null) {
                updated = updated.withImagePath(resource, copyImage(context, address, resource, uri))
            }
        }
        // The box image is now user-managed: stop treating it as auto-downloaded.
        if (PodImageResource.BOX in clearedImages || selectedImages[PodImageResource.BOX] != null) {
            updated = updated.copy(autoImageUrl = null)
        }
        updated = updated.copy(
            name = name.ifBlank { updated.name },
            lastConnectedAt = System.currentTimeMillis(),
        )
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        service?.getRemotePreferences(ConfigManager.PREFS_NAME)?.let { save(it, normalized) }
        return save(prefs, normalized)
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
        var updated = existing ?: EarphonePref(address = address, name = name)
        images.forEach { (resource, bytes) ->
            if (bytes.isNotEmpty()) {
                updated = updated.withImagePath(resource, copyImage(context, address, resource, bytes))
            }
        }
        updated = updated.copy(
            name = name.ifBlank { updated.name },
            lastConnectedAt = System.currentTimeMillis(),
            autoImageUrl = autoImageUrl ?: updated.autoImageUrl,
        )
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        service?.getRemotePreferences(ConfigManager.PREFS_NAME)?.let { save(it, normalized) }
        return save(prefs, normalized)
    }

    private fun save(prefs: SharedPreferences, earphones: List<EarphonePref>): List<EarphonePref> {
        val normalized = earphones.distinctBy { it.address.uppercase() }
        prefs.edit()
            .putString(PREF_KEY_EARPHONES, json.encodeToString(ListSerializer(EarphonePref.serializer()), normalized))
            .apply()
        return normalized
    }

    private fun copyImage(
        context: Context,
        address: String,
        resource: PodImageResource,
        uri: Uri,
    ): String {
        val dir = imageDir(context)
        val file = File(dir, "${address.safeFileName()}_${resource.fileSuffix}.img")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open image uri: $uri" }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.absolutePath
    }

    private fun copyImage(
        context: Context,
        address: String,
        resource: PodImageResource,
        bytes: ByteArray,
    ): String {
        val dir = imageDir(context)
        val file = File(dir, "${address.safeFileName()}_${resource.fileSuffix}.img")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun EarphonePref.withImagePath(resource: PodImageResource, path: String?): EarphonePref = when (resource) {
        PodImageResource.BOX -> copy(boxImagePath = path)
        PodImageResource.LEFT -> copy(leftImagePath = path)
        PodImageResource.RIGHT -> copy(rightImagePath = path)
    }

    private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
}

fun EarphonePref.imageUri(resource: PodImageResource): Uri? {
    val path = imagePath(resource) ?: return null
    val fileName = File(path).name.takeIf { it.isNotBlank() } ?: return null
    return Uri.Builder()
        .scheme("content")
        .authority(PodImagePrefs.AUTHORITY)
        .appendPath(fileName)
        .build()
}
