package dev.sonypods.config

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Shared cloud transport used by the module process and Hook-host fallbacks. */
object CloudModelInfoNetwork {
    private const val TAG = "SonyPods-CloudModelInfo"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val IMAGE_HOST = "hpc-image.data-gateway.seeds.services"
    private const val GRAPHQL_URL = "https://v1.api.data-gateway.seeds.services/graphql"
    private const val API_KEY = "37ksfjEuDRYXYtgus4rEDw6QC2NqhTfr"

    private val json = Json { ignoreUnknownKeys = true }

    private val query = """
        query {
          HPC {
            getAllCloudModelInfos {
              model_id
              model_number
              is_official_model_name
              model_name
              model_color_id
              sca_image_image_url
              sca_anime_image_url
              sca_source_color
              model_category
              big_header_bg_gradient_color_1
              big_header_bg_gradient_color_2
              big_header_theme_label
              wp_left_image_url
              wp_right_image_url
              wp_check_left_image_url
              wp_check_right_image_url
            }
          }
        }
    """.trimIndent()

    fun fetchCatalog(): List<CloudModelInfo> {
        val requestBody = JSONObject().put("query", query).toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(GRAPHQL_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", API_KEY)
        }
        return try {
            connection.outputStream.use { it.write(requestBody) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                input.readBytes().also { bytes ->
                    require(bytes.size <= MAX_RESPONSE_BYTES) { "cloud response too large" }
                }.toString(Charsets.UTF_8)
            }.orEmpty()
            check(status in 200..299) {
                "cloud request returned HTTP $status: ${response.take(256)}"
            }
            parseResponse(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(raw: String): List<CloudModelInfo> {
        val response = json.decodeFromString<CloudModelInfoResponse>(raw)
        check(response.errors.isNullOrEmpty()) { "cloud response contains GraphQL errors" }
        return response.data?.hpc?.records.orEmpty().filter { record ->
            !record.modelName.isNullOrBlank() && isImageUrlAllowed(record.imageUrl)
        }
    }

    fun isImageUrlAllowed(value: String?): Boolean {
        val raw = value?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            val url = URL(raw)
            url.protocol == "https" && url.host == IMAGE_HOST && url.path.isNotBlank()
        }.getOrDefault(false)
    }

    @Serializable
    private data class CloudModelInfoResponse(
        val data: CloudModelInfoData? = null,
        val errors: List<CloudGraphQlError>? = null,
    )

    @Serializable
    private data class CloudModelInfoData(
        @SerialName("HPC") val hpc: CloudModelInfoContainer? = null,
    )

    @Serializable
    private data class CloudModelInfoContainer(
        @SerialName("getAllCloudModelInfos") val records: List<CloudModelInfo> = emptyList(),
    )

    @Serializable
    private data class CloudGraphQlError(
        val message: String? = null,
    )
}
