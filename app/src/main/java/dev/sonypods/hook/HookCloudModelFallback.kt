package dev.sonypods.hook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.CloudModelInfoNetwork
import dev.sonypods.config.CloudModelInfoStore
import dev.sonypods.config.PodImagePrefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Temporary per-hook-host fallback for the cloud catalog and model image.
 *
 * Remote Files remain authoritative. A hooked app can read them but cannot write them,
 * so this cache is deliberately local to the host process. When the module process later
 * publishes a valid Remote File, [catalogReader] switches to it and the corresponding
 * temporary files are removed after the remote image has also been verified.
 */
class HookCloudModelFallback(
    context: Context,
    private val remoteFileReader: (String) -> ByteArray?,
    private val remotePrefsProvider: () -> SharedPreferences?,
    private val onCatalogReady: () -> Unit,
    private val onImageReady: (String) -> Unit,
) {
    private val appContext = context.applicationContext ?: context
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val cacheDir = File(appContext.filesDir, CACHE_DIR).apply { mkdirs() }
    private val imageDir = File(cacheDir, IMAGE_DIR).apply { mkdirs() }
    private val catalogFile = File(cacheDir, CATALOG_FILE)
    private val connectionLock = Any()
    private var connectionActive = false
    private var activeAddress: String? = null
    private val addressUrls = ConcurrentHashMap<String, String>()
    private val imageInFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedImageKeys = ConcurrentHashMap.newKeySet<String>()
    private val catalogInFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedCatalogKeys = ConcurrentHashMap.newKeySet<String>()

    /** Read valid Remote File first, then this host's temporary catalog. */
    fun catalogReader(): String? {
        val remote = validCatalog(
            remoteFileReader(CloudModelInfoStore.REMOTE_FILE_NAME)?.toString(Charsets.UTF_8)
        )
        if (remote != null) {
            // Remote File is authoritative. Do not retain a stale local catalog once
            // the framework copy is readable.
            runCatching { catalogFile.delete() }
            return remote
        }
        return validCatalog(runCatching { catalogFile.takeIf(File::isFile)?.readText() }.getOrNull())
    }

    /** Fetch a temporary catalog only when the current device is not resolvable. */
    fun ensureCatalogFor(modelName: String?, imageUrl: String? = null) {
        if (!hasActiveConnection()) return
        val normalized = normalizeModelName(modelName) ?: return
        val current = CloudModelInfoStore.parseRecords(catalogReader())
        val alreadyPresent = current.any { record ->
            normalizeModelName(record.modelName) == normalized &&
                (imageUrl.isNullOrBlank() || record.imageUrl == imageUrl)
        }
        if (alreadyPresent) return

        val key = "$normalized|${imageUrl.orEmpty()}"
        if (failedCatalogKeys.contains(key)) return
        if (!catalogInFlight.add(key)) return
        scope.launch {
            try {
                val records = runCatching { CloudModelInfoNetwork.fetchCatalog() }
                    .onFailure { Log.w(TAG, "hook fallback catalog fetch failed key=$key", it) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                if (records == null) {
                    failedCatalogKeys.add(key)
                    return@launch
                }
                val raw = runCatching { CloudModelInfoStore.encodeRecords(records) }
                    .onFailure { Log.w(TAG, "hook fallback catalog encode failed", it) }
                    .getOrNull()
                if (raw == null) {
                    failedCatalogKeys.add(key)
                    return@launch
                }
                val matchesCurrentDevice = records.any { record ->
                    normalizeModelName(record.modelName) == normalized &&
                        (imageUrl.isNullOrBlank() || record.imageUrl == imageUrl)
                }
                val stored = runCatching {
                    writeAtomically(catalogFile, raw.toByteArray(Charsets.UTF_8))
                }.onFailure { Log.w(TAG, "hook fallback catalog store failed", it) }
                    .isSuccess
                if (!stored) {
                    failedCatalogKeys.add(key)
                    return@launch
                }
                if (matchesCurrentDevice) failedCatalogKeys.remove(key) else failedCatalogKeys.add(key)
                Log.i(TAG, "hook fallback catalog ready host=${appContext.packageName} records=${records.size}")
                onCatalogReady()
            } finally {
                catalogInFlight.remove(key)
            }
        }
    }

    /** Track the URL announced by the engine and download the host-local image if needed. */
    fun onState(snapshot: SonyStateSnapshot) {
        updateConnection(snapshot)
        if (!snapshot.connected) return
        val address = snapshot.deviceAddress ?: return
        val url = snapshot.modelImageUrl ?: return
        addressUrls[address.uppercase()] = url
        val key = "${address.uppercase()}|$url"
        if (remoteImageAvailable(address, url)) {
            failedImageKeys.remove(key)
            deleteTemporaryImage(url)
            return
        }
        val file = imageFile(url)
        if (file.isFile && file.length() > 0L) return
        if (failedImageKeys.contains(key)) return
        if (!imageInFlight.add(url)) return
        scope.launch {
            try {
                val bytes = runCatching { downloadImage(url) }
                    .onFailure { Log.w(TAG, "hook fallback image fetch failed url=$url", it) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                if (bytes == null) {
                    failedImageKeys.add(key)
                    return@launch
                }
                val stored = runCatching { writeAtomically(file, bytes) }
                    .onFailure { Log.w(TAG, "hook fallback image store failed url=$url", it) }
                    .isSuccess
                if (!stored) {
                    failedImageKeys.add(key)
                    return@launch
                }
                failedImageKeys.remove(key)
                Log.i(TAG, "hook fallback image ready host=${appContext.packageName} address=$address bytes=${bytes.size}")
                onImageReady(address)
            } finally {
                imageInFlight.remove(url)
            }
        }
    }

    /** Used by Hook-side renderers before the module process has published an image. */
    fun temporaryBitmap(address: String): Bitmap? {
        val url = addressUrls[address.uppercase()] ?: return null
        if (remoteImageAvailable(address, url)) {
            deleteTemporaryImage(url)
            return null
        }
        val file = imageFile(url)
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun close() {
        job.cancel()
    }

    private fun updateConnection(snapshot: SonyStateSnapshot) {
        val address = snapshot.deviceAddress?.uppercase()
        synchronized(connectionLock) {
            if (!snapshot.connected) {
                connectionActive = false
                activeAddress = null
                failedImageKeys.clear()
                failedCatalogKeys.clear()
                return
            }
            if (!connectionActive || activeAddress != address) {
                failedImageKeys.clear()
                failedCatalogKeys.clear()
                connectionActive = true
                activeAddress = address
            }
        }
    }

    private fun hasActiveConnection(): Boolean = synchronized(connectionLock) { connectionActive }

    private fun remoteImageAvailable(address: String, url: String): Boolean {
        val prefs = runCatching { remotePrefsProvider() }.getOrNull()
        val preferredName = PodImagePrefs.find(prefs ?: return false, address)
            ?.takeIf { it.autoImageUrl == url }
            ?.boxImagePath
            ?.let(::File)
            ?.name
        val remoteName = preferredName ?: imageFileName(address)
        return runCatching { remoteFileReader(remoteName)?.isNotEmpty() == true }.getOrDefault(false)
    }

    private fun deleteTemporaryImage(url: String) {
        runCatching { imageFile(url).delete() }
    }

    private fun imageFile(url: String): File = File(imageDir, "${sha256(url)}.img")

    private fun downloadImage(url: String): ByteArray {
        check(CloudModelInfoNetwork.isImageUrlAllowed(url)) { "image URL is not allowlisted" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = IMAGE_CONNECT_TIMEOUT_MS
            readTimeout = IMAGE_READ_TIMEOUT_MS
        }
        return try {
            check(connection.responseCode in 200..299) { "image HTTP ${connection.responseCode}" }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.outputStream().use { it.write(bytes) }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun validCatalog(raw: String?): String? =
        raw?.takeIf { CloudModelInfoStore.parseRecords(it).isNotEmpty() }

    private fun imageFileName(address: String): String =
        "${address.replace(Regex("[^A-Za-z0-9._-]"), "_")}_box.img"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun normalizeModelName(value: String?): String? =
        value
            ?.removePrefix("LE_")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()

    private companion object {
        const val TAG = "SonyPods-HookFallback"
        const val CACHE_DIR = "sonypods_hook_cloud"
        const val IMAGE_DIR = "images"
        const val CATALOG_FILE = "cloud_model_info.json"
        const val IMAGE_CONNECT_TIMEOUT_MS = 15_000
        const val IMAGE_READ_TIMEOUT_MS = 30_000
    }
}
