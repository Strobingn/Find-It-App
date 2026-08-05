package com.example.ai

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CancellationException

enum class TerrainAiProvider(val label: String) {
    OPENAI("OpenAI"),
    GEMINI("Gemini"),
}

data class TerrainAiAnswer(
    val text: String,
    val provider: TerrainAiProvider,
    val fallbackReason: String? = null,
)

/**
 * Provider order is intentional: OpenAI first, Gemini second.
 * Local terrain intelligence is separate and never depends on either cloud provider.
 */
internal class TerrainAiGateway(context: Context) {
    private val appContext = context.applicationContext
    private val openAi = OpenAiApiClient(appContext)
    private val gemini = GeminiApiClient(appContext)

    suspend fun generate(
        conversation: List<GeminiConversationTurn>,
        systemContext: String,
        image: GeminiImageInput? = null,
        requestedProvider: TerrainAiProvider? = null,
        onProviderStage: (String) -> Unit = {},
    ): TerrainAiAnswer {
        val openAiConfigured = OpenAiApiClient.isConfigured(appContext)
        val geminiConfigured = GeminiApiClient.isConfigured(appContext)

        if (requestedProvider == TerrainAiProvider.OPENAI) {
            check(openAiConfigured) { "OpenAI is not configured on this device." }
            onProviderStage("Asking OpenAI ${OpenAiApiClient.configuredModel()}…")
            return TerrainAiAnswer(
                text = openAi.generate(conversation, systemContext, image),
                provider = TerrainAiProvider.OPENAI,
            )
        }

        if (requestedProvider == TerrainAiProvider.GEMINI) {
            check(geminiConfigured) { "Gemini is not configured on this device." }
            onProviderStage("Asking Gemini ${GeminiApiClient.configuredModel()}…")
            return TerrainAiAnswer(
                text = gemini.generate(conversation, systemContext, image),
                provider = TerrainAiProvider.GEMINI,
            )
        }

        if (openAiConfigured) {
            onProviderStage("Asking OpenAI ${OpenAiApiClient.configuredModel()}…")
            try {
                return TerrainAiAnswer(
                    text = openAi.generate(conversation, systemContext, image),
                    provider = TerrainAiProvider.OPENAI,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (openAiError: Throwable) {
                val openAiReason = openAiError.localizedMessage ?: "OpenAI request failed"
                if (!geminiConfigured) {
                    throw IOException(
                        "OpenAI failed: $openAiReason. Gemini fallback is unavailable because no Gemini API key is configured in this APK or on this device.",
                        openAiError,
                    )
                }
                try {
                    onProviderStage("OpenAI failed · trying Gemini ${GeminiApiClient.configuredModel()}…")
                    return TerrainAiAnswer(
                        text = gemini.generate(conversation, systemContext, image),
                        provider = TerrainAiProvider.GEMINI,
                        fallbackReason = openAiReason,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (geminiError: Throwable) {
                    val geminiReason = geminiError.localizedMessage ?: "Gemini fallback failed"
                    throw IOException(
                        "Both cloud providers failed. OpenAI: $openAiReason. Gemini: $geminiReason",
                        geminiError,
                    )
                }
            }
        }

        if (geminiConfigured) {
            onProviderStage("Asking Gemini ${GeminiApiClient.configuredModel()}…")
            try {
                return TerrainAiAnswer(
                    text = gemini.generate(conversation, systemContext, image),
                    provider = TerrainAiProvider.GEMINI,
                    fallbackReason = "OpenAI was not configured",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (geminiError: Throwable) {
                throw IOException(
                    "OpenAI is not configured and Gemini failed: ${geminiError.localizedMessage ?: "unknown Gemini error"}",
                    geminiError,
                )
            }
        }

        error(
            "No cloud AI provider is configured. Add a provider key under AI settings. Offline terrain analysis still works without either key.",
        )
    }

    companion object {
        fun preferredProvider(context: Context): TerrainAiProvider? = when {
            OpenAiApiClient.isConfigured(context) -> TerrainAiProvider.OPENAI
            GeminiApiClient.isConfigured(context) -> TerrainAiProvider.GEMINI
            else -> null
        }

        fun providerStatus(context: Context): String = buildString {
            append("OpenAI ")
            append(OpenAiApiClient.configuredModel())
            append("=")
            append(if (OpenAiApiClient.isConfigured(context)) "configured" else "missing")
            append(" · Gemini ")
            append(GeminiApiClient.configuredModel())
            append("=")
            append(if (GeminiApiClient.isConfigured(context)) "configured" else "missing")
        }
    }
}
