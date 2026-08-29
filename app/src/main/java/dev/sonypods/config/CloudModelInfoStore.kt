package dev.sonypods.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Storage contract for the cloud model catalog.
 *
 * The app process owns the local preference cache. Hooked processes must never try to
 * read that preference because it belongs to a different application process. The
 * published copy is instead written to the libxposed Remote Files store and consumed
 * through XposedInterface.openRemoteFile().
 */
object CloudModelInfoStore {
    private const val TAG = "SonyPods-Cloud"

    const val PREFERENCES_NAME = "cloud_model_info_preference"
    const val PREF_KEY_MODEL_INFO_LIST_JSON = "MODEL_INFO_LIST_JSON"
    const val PREF_KEY_MODEL_INFO_LIST_JSON_SAVE_MILLIS = "MODEL_INFO_LIST_JSON_SAVE_MILLIS"
    const val REMOTE_FILE_NAME = "cloud_model_info.json"

    private const val MAX_JSON_BYTES = 2 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readCachedJson(prefs: SharedPreferences): String? =
        prefs.getString(PREF_KEY_MODEL_INFO_LIST_JSON, null)
            ?.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES }

    fun savedAtMillis(prefs: SharedPreferences): Long =
        prefs.getLong(PREF_KEY_MODEL_INFO_LIST_JSON_SAVE_MILLIS, 0L)

    fun encodeRecords(records: List<CloudModelInfo>): String {
        val raw = json.encodeToString(ListSerializer(CloudModelInfo.serializer()), records)
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "cloud model catalog exceeds $MAX_JSON_BYTES bytes"
        }
        return raw
    }

    fun parseRecords(raw: String?): List<CloudModelInfo> = runCatching {
        if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            return@runCatching emptyList()
        }
        json.decodeFromString(ListSerializer(CloudModelInfo.serializer()), raw)
    }.getOrDefault(emptyList())

    fun saveCachedJson(prefs: SharedPreferences, records: List<CloudModelInfo>): String {
        val raw = encodeRecords(records)
        prefs.edit()
            .putString(PREF_KEY_MODEL_INFO_LIST_JSON, raw)
            .putLong(PREF_KEY_MODEL_INFO_LIST_JSON_SAVE_MILLIS, System.currentTimeMillis())
            .apply()
        return raw
    }

    /** Publish the app-owned cache into the shared libxposed Remote Files namespace. */
    fun publishCached(prefs: SharedPreferences, service: XposedService?): Boolean {
        val raw = readCachedJson(prefs) ?: return false
        return publishJson(raw, service)
    }

    /** Read and validate the catalog already published in Remote Files. */
    fun readRemoteJson(service: XposedService?): String? {
        val xposedService = service ?: return null
        return runCatching {
            xposedService.openRemoteFile(REMOTE_FILE_NAME).use { pfd ->
                val bytes = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > MAX_JSON_BYTES) return@runCatching null
                bytes.toString(Charsets.UTF_8).takeIf { parseRecords(it).isNotEmpty() }
            }
        }.onFailure { Log.w(TAG, "reading remote model catalog failed", it) }
            .getOrNull()
    }

    /** Publish an already validated catalog JSON string into Remote Files. */
    fun publishJson(raw: String, service: XposedService?): Boolean {
        val xposedService = service ?: return false
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_JSON_BYTES) return false
        val existing = readRemoteJson(xposedService)?.toByteArray(Charsets.UTF_8)
        if (existing?.contentEquals(bytes) == true) return false
        return runCatching {
            xposedService.openRemoteFile(REMOTE_FILE_NAME).use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { output ->
                    output.write(bytes)
                    output.flush()
                }
                // Remote files are persistent shared files and opening one does not
                // guarantee truncation. Remove any tail from the previous catalog.
                runCatching { android.system.Os.ftruncate(pfd.fileDescriptor, bytes.size.toLong()) }
            }
            true
        }.onFailure { Log.w(TAG, "publishing remote model catalog failed", it) }
            .getOrDefault(false)
    }
}
