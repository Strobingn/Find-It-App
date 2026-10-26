package com.example.ai

import android.content.Context
import android.util.Base64
import android.util.Log
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

/**
 * xAI Grok client (Responses API, `POST https://api.x.ai/v1/responses`).
 *
 * Defaults: model `grok-4.7` with reasoning effort `medium`. Every request is trimmed to stay
 * under a 200K-token context budget — xAI bills requests above 200K tokens at a higher
 * long-context rate, so older chat turns are dropped first instead of crossing that line.
 */
internal class GrokApiClient(
    context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val appContext = context.applicationContext

    /**
     * @param reasoningEffort overrides the configured effort for this call (e.g. "low" for quick plans).
     * @param onDelta when set, the answer is streamed and each new chunk of text is delivered as it arrives.
     */
    suspend fun generate(
        conversation: List<GeminiConversationTurn>,
        systemContext: String,
        image: GeminiImageInput? = null,
        reasoningEffort: String? = null,
        onDelta: ((String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = configuredApiKey(appContext)
        require(apiKey.isNotBlank()) { "Grok is not configured. Add an xAI API key in AI settings or XAI_API_KEY for the build." }

        val model = configuredModel()
        val effort = reasoningEffort?.lowercase()?.takeIf { it in VALID_EFFORTS } ?: configuredReasoningEffort()
        val body = buildRequestBody(
            model = model,
            reasoningEffort = effort,
            instructions = instructionsFor(systemContext, image),
            conversation = conversation,
            imageDataUrl = image?.let { "data:${it.mimeType};base64,${Base64.encodeToString(it.bytes, Base64.NO_WRAP)}" },
        )
        if (onDelta != null) body.put("stream", true)
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val startedAt = System.nanoTime()
        Log.i(LOG_TAG, "Starting xAI request model=$model effort=$effort image=${image != null}")
        httpClient.newCall(request).executeCancellable { response ->
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            Log.i(LOG_TAG, "xAI request finished model=$model http=${response.code} elapsedMs=$elapsedMs")
            if (!response.isSuccessful) {
                val errorText = response.body?.string().orEmpty()
                throw IOException(errorMessage(errorText).ifBlank { "Grok request failed with HTTP ${response.code}" })
            }
            val source = response.body?.source() ?: throw IOException("Grok returned an empty response")
            val isEventStream = response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)
            if (onDelta != null && isEventStream) {
                val collector = StreamCollector(onDelta)
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (collector.accept(line)) break
                }
                collector.result()
            } else {
                parseOutputText(source.readUtf8())
            }
        }
    }

    companion object {
        const val ENDPOINT = "https://api.x.ai/v1/responses"
        const val DEFAULT_MODEL = "grok-4.7"
        const val DEFAULT_REASONING_EFFORT = "medium"

        /** xAI's standard-price ceiling; requests above it are billed at the long-context rate. */
        const val CONTEXT_LIMIT_TOKENS = 200_000

        /** Input we aim for, leaving headroom under [CONTEXT_LIMIT_TOKENS] for estimate error and the reply. */
        const val INPUT_BUDGET_TOKENS = 180_000

        /** Rough cost of one attached high-detail image. */
        const val IMAGE_TOKEN_ESTIMATE = 2_500

        private val VALID_EFFORTS = setOf("low", "medium", "high", "xhigh")
        private const val LOG_TAG = "FindItGrok"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val vault = ApiKeyVault(keyAlias = "findit_grok_api_key", prefsName = "grok_credentials")

        fun isConfigured(context: Context): Boolean = configuredApiKey(context).isNotBlank()

        fun hasDeviceApiKey(context: Context): Boolean = vault.read(context, ::sanitizeSecret).isNotBlank()

        fun saveDeviceApiKey(context: Context, value: String): Boolean {
            val cleaned = sanitizeSecret(value)
            if (cleaned.isBlank()) return false
            return vault.write(context, cleaned)
        }

        fun clearDeviceApiKey(context: Context) = vault.clear(context)

        fun configuredModel(): String = BuildConfig.GROK_MODEL.trim().ifBlank { DEFAULT_MODEL }

        fun configuredReasoningEffort(): String =
            BuildConfig.GROK_REASONING_EFFORT.trim().lowercase().takeIf { it in VALID_EFFORTS } ?: DEFAULT_REASONING_EFFORT

        private fun configuredApiKey(context: Context): String =
            vault.read(context, ::sanitizeSecret).ifBlank { sanitizeSecret(BuildConfig.XAI_API_KEY) }

        internal fun sanitizeSecret(value: String?): String {
            val cleaned = value?.trim().orEmpty()
            val upper = cleaned.uppercase()
            return cleaned.takeUnless {
                it.length < 20 || upper.startsWith("YOUR_") || upper.startsWith("MY_") || upper.contains("PLACEHOLDER")
            }.orEmpty()
        }

        internal fun instructionsFor(systemContext: String, image: GeminiImageInput?): String = buildString {
            append(systemContext)
            append("\n\nGive field-verifiable findings, rank uncertainty, and never claim that a rendered anomaly proves a buried object exists.")
            if (image != null) {
                append("\nAttached image: ").append(image.description)
                append(". Analyze only visible rendered patterns and the supplied measurements.")
            }
        }

        /** Conservative token estimate (~3.5 characters per token, plus per-message overhead). */
        internal fun estimateTokens(text: String): Int = (text.length / 3.5).toInt() + 8

        /**
         * Keeps the newest turns that fit the budget together with the instructions. The latest
         * user turn is always kept (its text is cut down if it alone would blow the budget).
         */
        internal fun trimToBudget(
            instructions: String,
            conversation: List<GeminiConversationTurn>,
            hasImage: Boolean,
            budgetTokens: Int = INPUT_BUDGET_TOKENS,
        ): List<GeminiConversationTurn> {
            if (conversation.isEmpty()) return conversation
            var remaining = budgetTokens - estimateTokens(instructions) - (if (hasImage) IMAGE_TOKEN_ESTIMATE else 0)
            val kept = ArrayDeque<GeminiConversationTurn>()
            val last = conversation.last()
            val lastCost = estimateTokens(last.text)
            if (lastCost > remaining) {
                val maxChars = ((remaining.coerceAtLeast(1_000) - 8) * 3.5).toInt()
                // Keep the end of an oversized message: the actual question is usually last.
                kept.addFirst(last.copy(text = last.text.takeLast(maxChars)))
                return kept.toList()
            }
            kept.addFirst(last)
            remaining -= lastCost
            for (i in conversation.size - 2 downTo 0) {
                val cost = estimateTokens(conversation[i].text)
                if (cost > remaining) break
                kept.addFirst(conversation[i])
                remaining -= cost
            }
            // A history must not start with an assistant turn.
            while (kept.size > 1 && kept.first().role == "model") kept.removeFirst()
            return kept.toList()
        }

        internal fun buildRequestBody(
            model: String,
            reasoningEffort: String,
            instructions: String,
            conversation: List<GeminiConversationTurn>,
            imageDataUrl: String? = null,
        ): JSONObject {
            val turns = trimToBudget(instructions, conversation, hasImage = imageDataUrl != null)
            val lastUserIndex = turns.indexOfLast { it.role != "model" }
            val input = JSONArray()
            input.put(JSONObject().put("role", "system").put("content", instructions))
            turns.forEachIndexed { index, turn ->
                val isAssistant = turn.role == "model"
                if (!isAssistant && imageDataUrl != null && index == lastUserIndex) {
                    input.put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "input_text").put("text", turn.text))
                                    .put(JSONObject().put("type", "input_image").put("image_url", imageDataUrl).put("detail", "high")),
                            ),
                    )
                } else {
                    input.put(JSONObject().put("role", if (isAssistant) "assistant" else "user").put("content", turn.text))
                }
            }
            return JSONObject()
                .put("model", model)
                .put("input", input)
                .put("reasoning", JSONObject().put("effort", reasoningEffort))
                .put("store", false)
        }

        internal fun parseOutputText(responseText: String): String {
            val root = JSONObject(responseText)
            root.optString("output_text").takeIf(String::isNotBlank)?.let { return it.trim() }
            val output = root.optJSONArray("output") ?: throw IOException("Grok returned no output")
            return buildString {
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    if (item.optString("type") == "reasoning") continue
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text" || part.optString("type") == "text") {
                            val text = part.optString("text")
                            if (text.isNotBlank()) {
                                if (isNotEmpty()) append('\n')
                                append(text.trim())
                            }
                        }
                    }
                }
            }.ifBlank { throw IOException("Grok returned an empty answer") }
        }

        internal fun errorMessage(responseText: String): String = runCatching {
            val root = JSONObject(responseText)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("error").takeIf { it.isNotBlank() }
                ?: root.optString("message")
        }.getOrNull().orEmpty()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Medium reasoning on a long chat can take a while before the first byte.
            .readTimeout(150, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Parses xAI/OpenAI-style Responses server-sent events. Text arrives as
 * `response.output_text.delta` events; `response.completed` carries the full response.
 */
internal class StreamCollector(private val onDelta: (String) -> Unit) {
    private val text = StringBuilder()
    private var completedText: String? = null
    private var error: String? = null

    /** Feeds one SSE line; returns true when the stream is finished. */
    fun accept(line: String): Boolean {
        if (!line.startsWith("data:")) return false
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return false
        if (data == "[DONE]") return true
        val event = runCatching { JSONObject(data) }.getOrNull() ?: return false
        when (event.optString("type")) {
            "response.output_text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    text.append(delta)
                    onDelta(delta)
                }
            }
            "response.completed" -> {
                completedText = event.optJSONObject("response")?.let { response ->
                    runCatching { GrokApiClient.parseOutputText(response.toString()) }.getOrNull()
                }
                return true
            }
            "response.failed", "error", "response.error" -> {
                error = event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                    ?: event.optJSONObject("error")?.optString("message")
                    ?: event.optString("message").ifBlank { "Grok stream failed" }
                return true
            }
        }
        return false
    }

    fun result(): String {
        error?.let { if (text.isEmpty()) throw IOException(it) }
        val streamed = text.toString().trim()
        return completedText?.takeIf { it.isNotBlank() } ?: streamed.ifBlank { throw IOException("Grok returned an empty answer") }
    }
}
