package dev.sonypods.data

import org.json.JSONArray

data class SonyModelImageMatch(
    val modelName: String,
    val modelColor: String,
    val imageUrl: String,
    val sourceColor: String?,
)

/**
 * Resolves model artwork from the catalog published by the app.
 */
class SonyModelImageCatalog(
    private var jsonReader: (() -> String?)? = null,
) {
    @Volatile
    private var entries: List<Entry> = emptyList()

    /** Reload the catalog. A transient read failure leaves the last valid catalog in place. */
    @Synchronized
    fun refresh(): Boolean {
        val raw = runCatching { jsonReader?.invoke() }
            .onFailure { /* Keep the last valid catalog on a transient read error. */ }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val parsed = runCatching { parse(raw) }.getOrNull() ?: return false
        if (parsed.isEmpty()) return false
        entries = parsed
        return true
    }

    /**
     * Resolve the cloud image for a device.
     *
     * Matching priority:
     *  1. exact numeric colour code against the catalog `model_color_id`;
     *  2. low-nibble fallback for 0x1x X-I variant codes;
     *  3. normalised colour-label equality for callers that have no numeric code.
     *
     * When the protocol has not reported a colour yet, do not guess. The
     * detail page keeps the device's existing cached artwork until the real
     * colour arrives; guessing here would temporarily replace a correct cache
     * with another colour and then replace it again a moment later.
     */
    fun resolve(modelName: String?, modelColor: String?, colorCode: Int? = null): SonyModelImageMatch? {
        val normalizedModel = normalizeModelName(modelName) ?: return null
        val currentEntries = entries
        val modelEntries = currentEntries.filter { normalizeModelName(it.modelName) == normalizedModel }
        if (modelEntries.isEmpty()) return null
        val normalizedColor = normalizeColor(modelColor)
        if (colorCode == null && normalizedColor == normalizeColor(DEFAULT_COLOR)) {
            return null
        }
        val entry = modelEntries.firstOrNull { it.modelColorId != null && it.modelColorId == colorCode }
            ?: colorCode?.let { code ->
                modelEntries.firstOrNull { it.modelColorId != null && it.modelColorId == (code and 0x0F) }
            }
            ?: modelEntries.firstOrNull { normalizeColor(it.modelColor) == normalizedColor }
        return entry?.let {
            SonyModelImageMatch(
                modelName = it.modelName,
                modelColor = it.modelColor,
                imageUrl = it.imageUrl,
                sourceColor = it.sourceColor,
            )
        }
    }

    private fun parse(raw: String): List<Entry> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val modelName = item.optString("model_name").takeIf { it.isNotBlank() } ?: continue
                val imageUrl = item.optString("sca_image_image_url").takeIf { it.isNotBlank() } ?: continue
                val colorId = item.optString("model_color_id")
                    .takeIf { it.isNotBlank() }
                    ?.let(::parseColorId)
                add(
                    Entry(
                        modelName = modelName,
                        modelColor = colorName(colorId),
                        modelColorId = colorId,
                        imageUrl = imageUrl,
                        sourceColor = item.optString("sca_source_color").takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    }

    private data class Entry(
        val modelName: String,
        val modelColor: String,
        val modelColorId: Int?,
        val imageUrl: String,
        val sourceColor: String?,
    )

    private companion object {
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

        fun parseColorId(raw: String): Int? = runCatching {
            val value = raw.trim()
            val normalized = when {
                value.startsWith("0x", ignoreCase = true) -> value.substring(2)
                value.matches(Regex("[0-9A-Fa-f]{2}")) -> value
                else -> return@runCatching value.toInt()
            }
            normalized.toInt(16)
        }.getOrNull()

        /** The cloud API exposes the numeric colour id, while the UI expects a label. */
        fun colorName(colorId: Int?): String = when (colorId) {
            0x00 -> "Default"
            0x01 -> "Black"
            0x02 -> "White"
            0x03 -> "Silver"
            0x04 -> "Red"
            0x05 -> "Blue"
            0x06 -> "Pink"
            0x07 -> "Yellow"
            0x08 -> "Green"
            0x09 -> "Gray"
            0x0A -> "Gold"
            0x0B -> "Cream"
            0x0C -> "Orange"
            0x0D -> "Brown"
            0x0E -> "Violet"
            else -> DEFAULT_COLOR
        }
    }
}
