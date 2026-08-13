package dev.sonypods.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** App-local cache for the Sound Connect model artwork catalog. */
object CloudModelInfoStore {
    private const val PREFERENCES_NAME = "cloud_model_info"
    private const val PREF_KEY_CATALOG = "catalog_json"
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readCachedJson(prefs: SharedPreferences): String? =
        prefs.getString(PREF_KEY_CATALOG, null)?.takeIf {
            it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES
        }

    fun encodeRecords(records: List<CloudModelInfo>): String =
        json.encodeToString(ListSerializer(CloudModelInfo.serializer()), records).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
                "cloud model catalog exceeds $MAX_JSON_BYTES bytes"
            }
        }

    fun parseRecords(raw: String?): List<CloudModelInfo> = runCatching {
        if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            return@runCatching emptyList()
        }
        json.decodeFromString(ListSerializer(CloudModelInfo.serializer()), raw)
    }.getOrDefault(emptyList())

    fun saveCached(prefs: SharedPreferences, records: List<CloudModelInfo>): String {
        val raw = encodeRecords(records)
        prefs.edit()
            .putString(PREF_KEY_CATALOG, raw)
            .putLong("catalog_saved_at", System.currentTimeMillis())
            .apply()
        return raw
    }
}
