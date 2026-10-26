package com.example.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GrokApiClientTest {

    private fun turn(role: String, text: String) = GeminiConversationTurn(role = role, text = text)

    @Test
    fun defaultsAreGrok47AtMediumEffort() {
        assertEquals("grok-4.7", GrokApiClient.DEFAULT_MODEL)
        assertEquals("medium", GrokApiClient.DEFAULT_REASONING_EFFORT)
        assertEquals(200_000, GrokApiClient.CONTEXT_LIMIT_TOKENS)
        assertTrue(GrokApiClient.INPUT_BUDGET_TOKENS < GrokApiClient.CONTEXT_LIMIT_TOKENS)
        assertEquals("https://api.x.ai/v1/responses", GrokApiClient.ENDPOINT)
    }

    @Test
    fun requestBodyCarriesModelEffortAndConversation() {
        val body = GrokApiClient.buildRequestBody(
            model = "grok-4.7",
            reasoningEffort = "medium",
            instructions = "You are a field assistant.",
            conversation = listOf(turn("user", "hi"), turn("model", "hello"), turn("user", "where do I sit?")),
        )
        assertEquals("grok-4.7", body.getString("model"))
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.getBoolean("store"))
        val input = body.getJSONArray("input")
        assertEquals(4, input.length())
        assertEquals("system", input.getJSONObject(0).getString("role"))
        assertEquals("assistant", input.getJSONObject(2).getString("role"))
        assertEquals("where do I sit?", input.getJSONObject(3).getString("content"))
    }

    @Test
    fun imageAttachesToTheLastUserTurnOnly() {
        val body = GrokApiClient.buildRequestBody(
            model = "grok-4.7",
            reasoningEffort = "low",
            instructions = "sys",
            conversation = listOf(turn("user", "first"), turn("model", "ok"), turn("user", "look at this")),
            imageDataUrl = "data:image/jpeg;base64,AAAA",
        )
        val input = body.getJSONArray("input")
        assertEquals("first", input.getJSONObject(1).getString("content"))
        val parts = input.getJSONObject(3).getJSONArray("content")
        assertEquals("input_text", parts.getJSONObject(0).getString("type"))
        assertEquals("input_image", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,AAAA", parts.getJSONObject(1).getString("image_url"))
    }

    @Test
    fun longChatsAreTrimmedUnderThe200kBudgetKeepingTheNewestTurns() {
        // ~400 turns × 2,000 chars ≈ 230K tokens by the conservative estimate.
        val history = (0 until 400).map { i -> turn(if (i % 2 == 0) "user" else "model", "turn $i " + "x".repeat(2_000)) } +
            turn("user", "latest question")
        val kept = GrokApiClient.trimToBudget("instructions", history, hasImage = false)
        val estimate = GrokApiClient.estimateTokens("instructions") + kept.sumOf { GrokApiClient.estimateTokens(it.text) }
        assertTrue("estimate $estimate should fit the input budget", estimate <= GrokApiClient.INPUT_BUDGET_TOKENS)
        assertTrue(kept.size < history.size)
        assertEquals("latest question", kept.last().text)
        assertEquals("user", kept.first().role)
    }

    @Test
    fun shortChatsAreSentWhole() {
        val history = listOf(turn("user", "a"), turn("model", "b"), turn("user", "c"))
        assertEquals(history, GrokApiClient.trimToBudget("sys", history, hasImage = true))
    }

    @Test
    fun oversizedSingleMessageKeepsItsEnd() {
        val huge = "start " + "y".repeat(1_000_000) + " the actual question?"
        val kept = GrokApiClient.trimToBudget("sys", listOf(turn("user", huge)), hasImage = false)
        assertEquals(1, kept.size)
        assertTrue(kept[0].text.endsWith("the actual question?"))
        assertTrue(GrokApiClient.estimateTokens(kept[0].text) <= GrokApiClient.INPUT_BUDGET_TOKENS)
    }

    @Test
    fun parsesMessageTextAndSkipsReasoning() {
        val response = JSONObject()
            .put(
                "output",
                JSONArray()
                    .put(JSONObject().put("type", "reasoning").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "hidden"))))
                    .put(
                        JSONObject().put("type", "message").put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "output_text").put("text", "Sit on the east side.")),
                        ),
                    ),
            )
        assertEquals("Sit on the east side.", GrokApiClient.parseOutputText(response.toString()))
    }

    @Test
    fun streamCollectorDeliversDeltasAndFinalText() {
        val seen = StringBuilder()
        val collector = StreamCollector { seen.append(it) }
        val lines = listOf(
            "event: response.created",
            """data: {"type":"response.created"}""",
            "",
            """data: {"type":"response.output_text.delta","delta":"Sit "}""",
            """data: {"type":"response.output_text.delta","delta":"low."}""",
            """data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"Sit low."}]}]}}""",
        )
        var done = false
        for (line in lines) {
            if (collector.accept(line)) {
                done = true
                break
            }
        }
        assertTrue(done)
        assertEquals("Sit low.", seen.toString())
        assertEquals("Sit low.", collector.result())
    }

    @Test
    fun errorMessageIsReadFromXaiErrorBodies() {
        assertEquals("Incorrect API key", GrokApiClient.errorMessage("""{"error":{"message":"Incorrect API key"}}"""))
        assertEquals("rate limited", GrokApiClient.errorMessage("""{"error":"rate limited"}"""))
    }

    @Test
    fun providerOrderIsGrokThenOpenAiThenGemini() {
        val all = TerrainAiProvider.entries.toSet()
        assertEquals(listOf(TerrainAiProvider.GROK, TerrainAiProvider.OPENAI, TerrainAiProvider.GEMINI), TerrainAiGateway.fallbackOrder(all))
        assertEquals(listOf(TerrainAiProvider.OPENAI, TerrainAiProvider.GEMINI), TerrainAiGateway.fallbackOrder(all - TerrainAiProvider.GROK))
        assertEquals(emptyList<TerrainAiProvider>(), TerrainAiGateway.fallbackOrder(emptySet()))
    }

    @Test
    fun placeholderKeysAreRejected() {
        assertEquals("", GrokApiClient.sanitizeSecret("YOUR_XAI_API_KEY"))
        assertEquals("", GrokApiClient.sanitizeSecret("short"))
        assertEquals("xai-abcdefghijklmnopqrstuvwxyz", GrokApiClient.sanitizeSecret("  xai-abcdefghijklmnopqrstuvwxyz "))
    }
}
