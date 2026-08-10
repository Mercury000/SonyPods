package dev.sonypods.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One record returned by Sound Connect's HPC cloud model-info query. */
@Serializable
data class CloudModelInfo(
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("model_number") val modelNumber: String? = null,
    @SerialName("is_official_model_name") val isOfficialModelName: String? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("model_color_id") val modelColorId: String? = null,
    @SerialName("sca_image_image_url") val imageUrl: String? = null,
    @SerialName("sca_anime_image_url") val animeImageUrl: String? = null,
    @SerialName("sca_source_color") val sourceColor: String? = null,
    @SerialName("model_category") val modelCategory: String? = null,
    @SerialName("big_header_bg_gradient_color_1") val headerGradientColor1: String? = null,
    @SerialName("big_header_bg_gradient_color_2") val headerGradientColor2: String? = null,
    @SerialName("big_header_theme_label") val headerThemeLabel: String? = null,
    @SerialName("wp_left_image_url") val wallpaperLeftUrl: String? = null,
    @SerialName("wp_right_image_url") val wallpaperRightUrl: String? = null,
    @SerialName("wp_check_left_image_url") val wallpaperCheckLeftUrl: String? = null,
    @SerialName("wp_check_right_image_url") val wallpaperCheckRightUrl: String? = null,
)
