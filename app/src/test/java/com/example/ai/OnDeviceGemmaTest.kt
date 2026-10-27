package com.example.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceGemmaTest {

    @Test
    fun modelsPointAtTheHuggingFaceLiteRtFiles() {
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            OnDeviceModel.GEMMA4_E2B.downloadUrl,
        )
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            OnDeviceModel.GEMMA4_E4B.downloadUrl,
        )
        assertEquals("2.6 GB", OnDeviceModel.GEMMA4_E2B.sizeLabel)
        assertEquals("3.7 GB", OnDeviceModel.GEMMA4_E4B.sizeLabel)
    }

    @Test
    fun offlineIsAlwaysTheLastFallback() {
        val all = TerrainAiProvider.entries.toSet()
        assertEquals(TerrainAiProvider.ON_DEVICE, TerrainAiGateway.fallbackOrder(all).last())
        assertEquals(listOf(TerrainAiProvider.ON_DEVICE), TerrainAiGateway.fallbackOrder(setOf(TerrainAiProvider.ON_DEVICE)))
    }

    @Test
    fun promptKeepsTheNewestTurnsThatFitAndStartsWithTheUser() {
        val turns = buildList {
            repeat(40) { i ->
                add(GeminiConversationTurn("user", "question $i " + "x".repeat(400)))
                add(GeminiConversationTurn("model", "answer $i " + "y".repeat(400)))
            }
            add(GeminiConversationTurn("user", "latest question"))
        }
        val (history, last) = OnDeviceGemma.splitForPrompt(turns, systemChars = 2_000)
        assertEquals("latest question", last)
        assertTrue(history.isNotEmpty())
        assertEquals("user", history.first().role)
        assertTrue(history.last().text.startsWith("answer 39"))
        assertTrue(history.sumOf { it.text.length } <= 14_000)
        // A single short question has no history.
        val (none, only) = OnDeviceGemma.splitForPrompt(listOf(GeminiConversationTurn("user", "hi")), 100)
        assertTrue(none.isEmpty())
        assertEquals("hi", only)
    }
}
