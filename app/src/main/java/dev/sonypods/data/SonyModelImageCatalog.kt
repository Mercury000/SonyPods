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
                            modelColorId = item.optString("modelColorId").takeIf { it.isNotBlank() }?.let(::parseColorId),
                            imageUrl = imageUrl,
                            sourceColor = item.optString("sourceColor").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    /**
     * Resolve the cloud image for a device.
     *
     * Matching priority:
     *  1. exact numeric colour code ([colorCode]) against the catalog `modelColorId` —
     *     bypasses the per-protocol colour-label tables, which disagree between V1 and
     *     V2 for codes 0x06–0x0B and would otherwise pick the wrong image;
     *  2. low-nibble fallback (`colorCode and 0x0F`) so the 0x1x "X-I" variant codes a
     *     device may report map back to their base colour entry;
     *  3. normalised colour-label equality (legacy path, kept for devices that report
     *     a colour string but no code).
     *
     * No Default fallback: if nothing above matches, returns null so a previously cached
     * correct image for this MAC is kept rather than being overwritten with an unrelated
     * Default image (a device that genuinely reports 0x00/Default is matched by step 1).
     *
     * Returns null when the device's colour is still unknown this session (no code and
     * the label is absent or just the "Default" placeholder). On reconnect the colour
     * code has not arrived yet at that moment, and resolving to the Default image would
     * make [dev.sonypods.bridge.ModelImageSync] download a wrong-colour image and
     * overwrite the correctly cached one for this MAC. Returning null leaves the cached
     * image in place until the real colour is reported.
     */
    fun resolve(modelName: String?, modelColor: String?, colorCode: Int? = null): SonyModelImageMatch? {
        val normalizedModel = normalizeModelName(modelName) ?: return null
        val modelEntries = entries.filter { normalizeModelName(it.modelName) == normalizedModel }
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
            // No Default fallback: if the device's reported colour does not actually match
            // any catalog entry, return null so a previously cached correct image for this
            // MAC is kept rather than being overwritten with an unrelated Default image.
            // (A device that genuinely reports 0x00/Default is still matched by the
            // colorCode branch above.)
        return entry?.let {
            SonyModelImageMatch(
                modelName = it.modelName,
                modelColor = it.modelColor,
                imageUrl = it.imageUrl,
                sourceColor = it.sourceColor,
            )
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

        fun parseColorId(raw: String): Int? = runCatching {
            val v = raw.trim()
            val n = when {
                v.startsWith("0x", ignoreCase = true) || v.startsWith("0X") -> v.substring(2)
                else -> v
            }
            n.toInt(16)
        }.getOrNull()
    }
}
