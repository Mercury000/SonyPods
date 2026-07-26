package dev.sonypods.data

import android.content.Context
import org.json.JSONArray

data class SonyModelImageMatch(
    val modelName: String,
    val modelColor: String,
    val imageUrl: String,
    val sourceColor: String?,
)

class SonyModelImageCatalog(context: Context) {
    private val entries: List<Entry> = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
            val array = JSONArray(reader.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val modelName = item.optString("modelName").takeIf { it.isNotBlank() } ?: continue
                    val imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() } ?: continue
                    add(
                        Entry(
                            modelName = modelName,
                            modelColor = item.optString("modelColor").takeIf { it.isNotBlank() } ?: DEFAULT_COLOR,
                            imageUrl = imageUrl,
                            sourceColor = item.optString("sourceColor").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    fun resolve(modelName: String?, modelColor: String?): SonyModelImageMatch? {
        val normalizedModel = normalizeModelName(modelName) ?: return null
        val modelEntries = entries.filter { normalizeModelName(it.modelName) == normalizedModel }
        if (modelEntries.isEmpty()) {
            return null
        }
        val normalizedColor = normalizeColor(modelColor)
        val entry = modelEntries.firstOrNull { normalizeColor(it.modelColor) == normalizedColor }
            ?: modelEntries.firstOrNull { normalizeColor(it.modelColor) == normalizeColor(DEFAULT_COLOR) }
            ?: modelEntries.first()
        return SonyModelImageMatch(
            modelName = entry.modelName,
            modelColor = entry.modelColor,
            imageUrl = entry.imageUrl,
            sourceColor = entry.sourceColor,
        )
    }

    private data class Entry(
        val modelName: String,
        val modelColor: String,
        val imageUrl: String,
        val sourceColor: String?,
    )

    private companion object {
        const val ASSET_NAME = "sony_model_images.json"
        const val DEFAULT_COLOR = "Default"

        fun normalizeModelName(value: String?): String? =
            value
                ?.removePrefix("LE_")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.lowercase()

        fun normalizeColor(value: String?): String =
            value
                ?.substringAfter("/", value)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.lowercase()
                ?: DEFAULT_COLOR.lowercase()
    }
}
