package com.example.ai

import com.example.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class GeminiConversationTurn(
    val role: String,
    val text: String,
)

/** Lightweight Gemini REST client. The API key is supplied from the local .env/build environment. */
internal class GeminiApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun generate(
        conversation: List<GeminiConversationTurn>,
        systemContext: String,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        require(apiKey.isNotBlank()) {
            "Gemini is not configured. Add GEMINI_API_KEY to the project .env file and rebuild the app."
        }

        val model = BuildConfig.GEMINI_MODEL.trim().ifBlank { DEFAULT_MODEL }
        val contents = JSONArray()
        conversation.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
            contents.put(
                JSONObject()
                    .put("role", if (turn.role == "model") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text))),
            )
        }

        val requestJson = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemContext)),
                ),
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.25)
                    .put("topP", 0.9)
                    .put("maxOutputTokens", 2_048),
            )

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Accept", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IOException(message.ifBlank { "Gemini request failed with HTTP ${response.code}" })
            }

            val root = JSONObject(responseText)
            val candidates = root.optJSONArray("candidates")
                ?: throw IOException("Gemini returned no response candidates")
            val first = candidates.optJSONObject(0)
                ?: throw IOException("Gemini returned an empty response")
            val parts = first.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("Gemini returned no text")

            buildString {
                for (index in 0 until parts.length()) {
                    val text = parts.optJSONObject(index)?.optString("text").orEmpty()
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(text.trim())
                    }
                }
            }.ifBlank { throw IOException("Gemini returned an empty answer") }
        }
    }

    companion object {
        private const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val MAX_HISTORY_TURNS = 16
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
