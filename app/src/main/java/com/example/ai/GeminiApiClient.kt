package com.example.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import java.io.IOException
import java.security.MessageDigest
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
            "Gemini is not configured. Add a valid Gemini API key under AI settings on this device."
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
                    .put("temperature", 0.4)
                    .put("topP", 0.9)
                    .put("maxOutputTokens", 2_048),
            )

        val requestBuilder = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Accept", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
        androidSigningCertificateSha1(appContext)?.let { certificateSha1 ->
            requestBuilder
                .header("X-Android-Package", appContext.packageName)
                .header("X-Android-Cert", certificateSha1)
        }
        val request = requestBuilder.build()

        val startedAt = System.nanoTime()
        Log.i(LOG_TAG, "Starting generateContent request model=$model image=${image != null}")
        httpClient.newCall(request).execute().use { response ->
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            Log.i(LOG_TAG, "generateContent finished model=$model http=${response.code} elapsedMs=$elapsedMs")
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                if (response.code == 403 && message.contains("blocked", ignoreCase = true)) {
                    throw IOException(
                        "Google blocked this Gemini API key. In Google AI Studio, create a new auth key " +
                            "or restrict the existing key to Gemini API only, then replace it under Keys.",
                    )
                }
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
        private const val DEFAULT_MODEL = "gemini-3.5-flash"
        private const val LOG_TAG = "FindItGemini"
        private const val MAX_HISTORY_TURNS = 16
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun isConfigured(context: Context): Boolean = configuredApiKey(context).isNotBlank()

        /** True when a key was saved on-device (excludes build-time [BuildConfig.GEMINI_API_KEY]). */
        fun hasDeviceApiKey(context: Context): Boolean =
            sanitizeApiKey(GeminiCredentialVault.read(context, ::sanitizeApiKey)).isNotBlank()

        fun saveDeviceApiKey(context: Context, value: String): Boolean {
            val cleaned = sanitizeApiKey(value)
            if (cleaned.isBlank()) return false
            return GeminiCredentialVault.write(context, cleaned)
        }

        fun clearDeviceApiKey(context: Context) {
            GeminiCredentialVault.clear(context)
        }

        fun configuredModel(): String = BuildConfig.GEMINI_MODEL.trim().ifBlank { DEFAULT_MODEL }

        /** Build-time key from local.properties / env (never logged). */
        fun hasBuildConfigApiKey(): Boolean = sanitizeApiKey(BuildConfig.GEMINI_API_KEY).isNotBlank()

        @Suppress("DEPRECATION")
        private fun androidSigningCertificateSha1(context: Context): String? {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                ).signingInfo
                if (info?.hasMultipleSigners() == true) {
                    info.apkContentsSigners
                } else {
                    info?.signingCertificateHistory
                }
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                ).signatures
            }
            val certificate = signatures?.firstOrNull()?.toByteArray() ?: return null
            return MessageDigest.getInstance("SHA-1")
                .digest(certificate)
                .joinToString(separator = "") { byte -> "%02X".format(byte) }
        }

        /**
         * Device vault first (user can rotate without rebuild), then [BuildConfig.GEMINI_API_KEY]
         * from local.properties / env `GEMINI_API_KEY`.
         */
        private fun configuredApiKey(context: Context): String {
            val deviceKey = sanitizeApiKey(GeminiCredentialVault.read(context, ::sanitizeApiKey))
            if (deviceKey.isNotBlank()) return deviceKey
            return sanitizeApiKey(BuildConfig.GEMINI_API_KEY)
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(65, TimeUnit.SECONDS)
            .build()
    }
}
