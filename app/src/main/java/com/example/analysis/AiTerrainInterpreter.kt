package com.example.analysis

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Calls the owner's backend proxy. The OpenAI API key is deliberately never stored in the APK.
 */
class AiTerrainInterpreter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun interpret(
        layer: TerrainAnalysisLayer,
        terrainSummary: String,
        userQuestion: String = "Explain the strongest terrain patterns and the best places to inspect in the field.",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val proxyUrl = BuildConfig.OPENAI_PROXY_URL.trim()
            require(proxyUrl.startsWith("https://")) {
                "Set OPENAI_PROXY_URL to an HTTPS backend endpoint before using AI interpretation."
            }
            val requestJson = JSONObject()
                .put("terrainSummary", terrainSummary.take(4_000))
                .put("analysisType", layer.type.title)
                .put("analysisSummary", layer.aiSummary())
                .put("question", userQuestion.take(1_000))

            val request = Request.Builder()
                .url(proxyUrl)
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(body).optString("error") }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: "AI proxy request failed with HTTP ${response.code}."
                    error(message)
                }
                val text = JSONObject(body).optString("text").trim()
                require(text.isNotBlank()) { "AI proxy returned an empty interpretation." }
                text
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
