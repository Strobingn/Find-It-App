package com.example.ai

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CancellationException

enum class TerrainAiProvider(val label: String) {
    GROK("Grok"),
    OPENAI("OpenAI"),
    GEMINI("Gemini"),
}

data class TerrainAiAnswer(
    val text: String,
    val provider: TerrainAiProvider,
    val fallbackReason: String? = null,
)

/**
 * Provider order is intentional: Grok first, then OpenAI, then Gemini. Each configured provider
 * is tried in turn; a failure falls through to the next one with the reason attached.
 * Local terrain intelligence is separate and never depends on any cloud provider.
 */
internal class TerrainAiGateway(context: Context) {
    private val appContext = context.applicationContext
    private val grok = GrokApiClient(appContext)
    private val openAi = OpenAiApiClient(appContext)
    private val gemini = GeminiApiClient(appContext)

    /**
     * @param fast quick-answer mode for short feature replies: Grok uses low reasoning effort.
     *   Chat leaves this off so Grok runs at its configured effort (medium).
     * @param onPartialText receives the answer as it streams in (Grok); other providers deliver it at the end.
     */
    suspend fun generate(
        conversation: List<GeminiConversationTurn>,
        systemContext: String,
        image: GeminiImageInput? = null,
        requestedProvider: TerrainAiProvider? = null,
        onProviderStage: (String) -> Unit = {},
        /** Optional field-pack / feature label for Sentry gen_ai metadata (not prompt text). */
        featureName: String? = null,
        conversationId: String? = null,
        fast: Boolean = false,
        onPartialText: ((String) -> Unit)? = null,
    ): TerrainAiAnswer {
        val configured = configuredProviders(appContext)

        suspend fun run(provider: TerrainAiProvider, fallbackReason: String?): TerrainAiAnswer {
            val model = modelFor(provider)
            onProviderStage(
                if (fallbackReason == null) "Asking ${provider.label} $model…" else "Trying ${provider.label} $model…",
            )
            val text = SentryAiMonitor.traceLlmCall(
                SentryAiMonitor.CallMeta(
                    model = model,
                    provider = sentryName(provider),
                    hasImage = image != null,
                    featureName = featureName,
                    messageCount = conversation.size,
                    conversationId = conversationId,
                ),
            ) {
                when (provider) {
                    TerrainAiProvider.GROK -> {
                        val streamed = StringBuilder()
                        grok.generate(
                            conversation = conversation,
                            systemContext = systemContext,
                            image = image,
                            reasoningEffort = if (fast) "low" else null,
                            onDelta = onPartialText?.let { sink ->
                                { delta: String ->
                                    streamed.append(delta)
                                    sink(streamed.toString())
                                }
                            },
                        )
                    }
                    TerrainAiProvider.OPENAI -> openAi.generate(conversation, systemContext, image)
                    TerrainAiProvider.GEMINI -> gemini.generate(conversation, systemContext, image)
                }
            }
            return TerrainAiAnswer(text = text, provider = provider, fallbackReason = fallbackReason)
        }

        if (requestedProvider != null) {
            check(requestedProvider in configured) { "${requestedProvider.label} is not configured on this device." }
            return run(requestedProvider, null)
        }

        val order = fallbackOrder(configured)
        if (order.isEmpty()) {
            error(
                "No cloud AI provider is configured. Add a Grok, OpenAI or Gemini key under AI settings. Offline terrain analysis still works without any key.",
            )
        }
        val failures = mutableListOf<String>()
        for (provider in order) {
            try {
                return run(provider, failures.lastOrNull())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failures += "${provider.label}: ${error.localizedMessage ?: "request failed"}"
                onPartialText?.invoke("")
            }
        }
        throw IOException(
            if (failures.size == 1) {
                "${failures.first()}. No other AI provider is configured as a fallback."
            } else {
                "All cloud providers failed. ${failures.joinToString(". ")}"
            },
        )
    }

    companion object {
        /** Grok → OpenAI → Gemini, keeping only the configured ones. */
        fun fallbackOrder(configured: Set<TerrainAiProvider>): List<TerrainAiProvider> =
            listOf(TerrainAiProvider.GROK, TerrainAiProvider.OPENAI, TerrainAiProvider.GEMINI).filter { it in configured }

        fun configuredProviders(context: Context): Set<TerrainAiProvider> = buildSet {
            if (GrokApiClient.isConfigured(context)) add(TerrainAiProvider.GROK)
            if (OpenAiApiClient.isConfigured(context)) add(TerrainAiProvider.OPENAI)
            if (GeminiApiClient.isConfigured(context)) add(TerrainAiProvider.GEMINI)
        }

        fun isConfigured(context: Context, provider: TerrainAiProvider): Boolean = provider in configuredProviders(context)

        fun preferredProvider(context: Context): TerrainAiProvider? = fallbackOrder(configuredProviders(context)).firstOrNull()

        fun modelFor(provider: TerrainAiProvider): String = when (provider) {
            TerrainAiProvider.GROK -> GrokApiClient.configuredModel()
            TerrainAiProvider.OPENAI -> OpenAiApiClient.configuredModel()
            TerrainAiProvider.GEMINI -> GeminiApiClient.configuredModel()
        }

        /** "Grok grok-4.7 (medium) primary · OpenAI gpt-5.5 fallback" style status line. */
        fun activeSummary(context: Context): String {
            val order = fallbackOrder(configuredProviders(context))
            if (order.isEmpty()) return "Add a Grok, OpenAI or Gemini key"
            return order.mapIndexed { i, p ->
                val model = modelFor(p) + if (p == TerrainAiProvider.GROK) " (${GrokApiClient.configuredReasoningEffort()})" else ""
                "${p.label} $model ${if (i == 0) "primary" else "fallback"}"
            }.joinToString(" · ")
        }

        private fun sentryName(provider: TerrainAiProvider): String = when (provider) {
            TerrainAiProvider.GROK -> "xai"
            TerrainAiProvider.OPENAI -> "openai"
            TerrainAiProvider.GEMINI -> "gemini"
        }

        fun providerStatus(context: Context): String = TerrainAiProvider.entries.joinToString(" · ") { p ->
            "${p.label} ${modelFor(p)}=${if (isConfigured(context, p)) "configured" else "missing"}"
        }
    }
}
