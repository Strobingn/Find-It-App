package com.example.ai

import android.content.Context
import android.util.Base64
import com.example.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
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

internal data class GeminiImageInput(
    val bytes: ByteArray,
    val mimeType: String,
    val description: String,
)

/** Lightweight Gemini REST client with optional inline terrain-image analysis. */
internal class GeminiApiClient(
    context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val appContext = context.applicationContext

    suspend fun generate(
        conversation: List<GeminiConversationTurn>,
        systemContext: String,
        image: GeminiImageInput? = null,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = configuredApiKey(appContext)
        require(apiKey.isNotBlank()) {
            "Gemini is not configured. Add a Gemini API key in the app or configure GEMINI_API_KEY for the build."
        }

        val model = configuredModel()
        val recentTurns = conversation.takeLast(MAX_HISTORY_TURNS)
        val lastUserIndex = recentTurns.indexOfLast { it.role != "model" }
        val contents = JSONArray()
        recentTurns.forEachIndexed { index, turn ->
            val parts = JSONArray()
            if (image != null && index == lastUserIndex) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", image.mimeType)
                            .put("data", Base64.encodeToString(image.bytes, Base64.NO_WRAP)),
                    ),
                )
            }
            parts.put(JSONObject().put("text", turn.text))
            contents.put(
                JSONObject()
                    .put("role", if (turn.role == "model") "model" else "user")
                    .put("parts", parts),
            )
        }

        val systemText = buildString {
            append(systemContext)
            append("\n\nUse advanced reasoning, rank uncertainty, and give field-verifiable observations.")
            if (image != null) {
                append("\n\nAttached image: ")
                append(image.description)
                append(". Treat it as a rendered visualization of measured terrain, not proof of buried objects.")
            }
        }
        val requestJson = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemText)),
                ),
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 1.0)
                    .put("topP", 0.95)
                    .put("maxOutputTokens", 8_192),
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
                ?: throw IOException(
                    root.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf(String::isNotBlank)
                        ?.let { "Gemini blocked this request: $it" }
                        ?: "Gemini returned no response candidates",
                )
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
        private const val DEFAULT_MODEL = "gemini-3.1-pro-preview"
        private const val MAX_HISTORY_TURNS = 16
        private const val PREFS_NAME = "gemini_credentials"
        private const val PREF_API_KEY = "api_key"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun isConfigured(context: Context): Boolean = configuredApiKey(context).isNotBlank()

        fun hasDeviceApiKey(context: Context): Boolean =
            sanitizeApiKey(
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_API_KEY, null),
            ).isNotBlank()

        fun saveDeviceApiKey(context: Context, value: String): Boolean {
            val cleaned = sanitizeApiKey(value)
            if (cleaned.isBlank()) return false
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_API_KEY, cleaned)
                .apply()
            return true
        }

        fun clearDeviceApiKey(context: Context) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_API_KEY)
                .apply()
        }

        fun configuredModel(): String = BuildConfig.GEMINI_MODEL.trim().ifBlank { DEFAULT_MODEL }

        private fun configuredApiKey(context: Context): String {
            val deviceKey = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_API_KEY, null)
            return sanitizeApiKey(deviceKey).ifBlank {
                sanitizeApiKey(BuildConfig.GEMINI_API_KEY)
            }
        }

        private fun sanitizeApiKey(value: String?): String {
            val cleaned = value?.trim().orEmpty()
            val upper = cleaned.uppercase()
            return cleaned.takeUnless {
                it.length < 20 ||
                    upper.startsWith("YOUR_") ||
                    upper.startsWith("MY_") ||
                    upper.contains("PLACEHOLDER")
            }.orEmpty()
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(210, TimeUnit.SECONDS)
            .build()
    }
}
