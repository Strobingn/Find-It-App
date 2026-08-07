package com.example.ai

import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import io.sentry.TransactionOptions
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Manual Sentry AI / gen_ai instrumentation for Find It's custom OpenAI + Gemini clients.
 *
 * **Metadata only** (product choice): model, provider, latency, image flag, conversation id,
 * agent name, feature label, success/error — **never** full prompt/response text (field PII).
 *
 * Conventions: https://getsentry.github.io/sentry-conventions/attributes/gen_ai/
 */
object SentryAiMonitor {

    private const val AGENT_NAME = "find-it-terrain"
    private val conversationId = AtomicReference(newConversationId())

    fun newConversationId(): String = "conv_${UUID.randomUUID()}"

    /** Call when the user clears AI chat so Conversations can split sessions. */
    fun beginConversation(id: String = newConversationId()): String {
        conversationId.set(id)
        return id
    }

    fun currentConversationId(): String = conversationId.get()

    data class CallMeta(
        val model: String,
        val provider: String,
        val operation: String = "chat",
        val hasImage: Boolean = false,
        val featureName: String? = null,
        val messageCount: Int = 0,
        val conversationId: String? = null,
    )

    /**
     * Wraps one cloud LLM request as `gen_ai.chat`. Starts a bound agent transaction when none
     * is active so gen_ai spans are not dropped.
     */
    suspend fun <T> traceLlmCall(
        meta: CallMeta,
        block: suspend () -> T,
    ): T {
        val convId = meta.conversationId ?: currentConversationId()
        val op = "gen_ai.${meta.operation}"
        val name = "${meta.operation} ${meta.model}"

        val existing = Sentry.getSpan()
        var ownedTx: ITransaction? = null
        val parent: ISpan = if (existing != null) {
            existing
        } else {
            val opts = TransactionOptions().apply { isBindToScope = true }
            Sentry.startTransaction(
                "invoke_agent $AGENT_NAME",
                "gen_ai.invoke_agent",
                opts,
            ).also { tx ->
                ownedTx = tx
                setCommonAttrs(tx, meta, convId, isAgentRoot = true)
            }
        }

        val span = parent.startChild(op, name)
        setCommonAttrs(span, meta, convId, isAgentRoot = false)

        return try {
            val result = block()
            span.status = SpanStatus.OK
            span.setData("gen_ai.response.success", true)
            result
        } catch (error: Throwable) {
            span.throwable = error
            span.status = SpanStatus.INTERNAL_ERROR
            span.setData("gen_ai.response.success", false)
            span.setData("gen_ai.response.error_type", error.javaClass.simpleName)
            ownedTx?.status = SpanStatus.INTERNAL_ERROR
            throw error
        } finally {
            span.finish()
            ownedTx?.let { tx ->
                if (tx.status == null) tx.status = SpanStatus.OK
                tx.finish()
            }
        }
    }

    private fun setCommonAttrs(
        span: ISpan,
        meta: CallMeta,
        convId: String,
        isAgentRoot: Boolean,
    ) {
        span.setData("gen_ai.request.model", meta.model)
        span.setData("gen_ai.operation.name", if (isAgentRoot) "invoke_agent" else meta.operation)
        span.setData("gen_ai.system", meta.provider.lowercase())
        span.setData("gen_ai.agent.name", AGENT_NAME)
        span.setData("gen_ai.conversation.id", convId)
        span.setData("gen_ai.request.has_image", meta.hasImage)
        span.setData("gen_ai.request.message_count", meta.messageCount)
        // Explicit: content not recorded (metadata-only policy).
        span.setData("gen_ai.prompt_capture", "disabled")
        meta.featureName?.takeIf { it.isNotBlank() }?.let {
            span.setData("gen_ai.request.feature", it.take(80))
        }
        if (isAgentRoot) {
            span.setData("gen_ai.pipeline.name", "terrain-ai-gateway")
        }
    }
}
