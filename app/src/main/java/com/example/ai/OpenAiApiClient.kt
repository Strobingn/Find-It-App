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

/** OpenAI Responses API client for terrain text and viewport-image analysis. */
internal class OpenAiApiClient(
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
        val proxyToken = configuredProxyToken()
        val endpoint = configuredEndpoint()
        val directOpenAi = isDirectOpenAiEndpoint(endpoint)
        val authToken = if (directOpenAi) apiKey else proxyToken.ifBlank { apiKey }
        require(authToken.isNotBlank()) {
            if (directOpenAi) {
                "OpenAI is not configured. Add an OpenAI API key in the app or configure OPENAI_API_KEY for the build."
            } else {
                "OpenAI proxy is not configured. Add OPENAI_PROXY_TOKEN or OPENAI_API_KEY."
            }
        }

        val recentTurns = conversation.takeLast(MAX_HISTORY_TURNS)
        val lastUserIndex = recentTurns.indexOfLast { it.role != "model" }
        val input = JSONArray()
        recentTurns.forEachIndexed { index, turn ->
            val isAssistant = turn.role == "model"
            val content = JSONArray()
            content.put(
                JSONObject()
                    .put("type", if (isAssistant) "output_text" else "input_text")
                    .put("text", turn.text),
            )
            if (!isAssistant && image != null && index == lastUserIndex) {
                content.put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("detail", "high")
                        .put(
                            "image_url",
                            "data:${image.mimeType};base64,${Base64.encodeToString(image.bytes, Base64.NO_WRAP)}",
                        ),
                )
            }
            input.put(
                JSONObject()
                    .put("type", "message")
                    .put("role", if (isAssistant) "assistant" else "user")
                    .put("content", content),
            )
        }

        val instructions = buildString {
            append(systemContext)
            append("\n\nYou are the primary cloud terrain-analysis provider. Give field-verifiable findings, rank uncertainty, and never claim that a rendered anomaly proves a buried object exists.")
            if (image != null) {
                append("\nAttached image: ")
                append(image.description)
                append(". Analyze only visible rendered terrain patterns and the supplied measurements.")
            }
        }

        val body = JSONObject()
            .put("model", configuredModel())
            .put("instructions", instructions)
            .put("input", input)
            .put("reasoning", JSONObject().put("effort", "high"))
            .put("max_output_tokens", 4_096)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $authToken")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IOException(message.ifBlank { "OpenAI request failed with HTTP ${response.code}" })
            }
            parseOutputText(responseText)
        }
    }

    companion object {
        private const val DEFAULT_MODEL = "gpt-5.1"
        private const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/responses"
        private const val MAX_HISTORY_TURNS = 16
        private const val PREFS_NAME = "openai_credentials"
        private const val PREF_API_KEY = "api_key"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun isConfigured(context: Context): Boolean {
            val endpoint = configuredEndpoint()
            return if (isDirectOpenAiEndpoint(endpoint)) {
                configuredApiKey(context).isNotBlank()
            } else {
                configuredProxyToken().isNotBlank() || configuredApiKey(context).isNotBlank()
            }
        }

        fun hasDeviceApiKey(context: Context): Boolean =
            sanitizeSecret(
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_API_KEY, null),
            ).isNotBlank()

        fun saveDeviceApiKey(context: Context, value: String): Boolean {
            val cleaned = sanitizeSecret(value)
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

        fun configuredModel(): String = BuildConfig.OPENAI_MODEL.trim().ifBlank { DEFAULT_MODEL }

        fun configuredEndpoint(): String = BuildConfig.OPENAI_BASE_URL.trim().ifBlank { DEFAULT_ENDPOINT }

        private fun configuredApiKey(context: Context): String {
            val deviceKey = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_API_KEY, null)
            return sanitizeSecret(deviceKey).ifBlank { sanitizeSecret(BuildConfig.OPENAI_API_KEY) }
        }

        private fun configuredProxyToken(): String = sanitizeSecret(BuildConfig.OPENAI_PROXY_TOKEN)

        private fun isDirectOpenAiEndpoint(endpoint: String): Boolean =
            endpoint.startsWith("https://api.openai.com/", ignoreCase = true)

        private fun sanitizeSecret(value: String?): String {
            val cleaned = value?.trim().orEmpty()
            val upper = cleaned.uppercase()
            return cleaned.takeUnless {
                it.length < 20 ||
                    upper.startsWith("YOUR_") ||
                    upper.startsWith("MY_") ||
                    upper.contains("PLACEHOLDER")
            }.orEmpty()
        }

        private fun parseOutputText(responseText: String): String {
            val root = JSONObject(responseText)
            root.optString("output_text").takeIf(String::isNotBlank)?.let { return it.trim() }
            val output = root.optJSONArray("output") ?: throw IOException("OpenAI returned no output")
            return buildString {
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") {
                            val text = part.optString("text")
                            if (text.isNotBlank()) {
                                if (isNotEmpty()) append('\n')
                                append(text.trim())
                            }
                        }
                    }
                }
            }.ifBlank { throw IOException("OpenAI returned an empty answer") }
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(210, TimeUnit.SECONDS)
            .build()
    }
}
