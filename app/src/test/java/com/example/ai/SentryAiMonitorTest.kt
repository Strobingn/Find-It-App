package com.example.ai

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryAiMonitorTest {

    @Test
    fun beginConversation_rotatesId() {
        val first = SentryAiMonitor.beginConversation()
        val second = SentryAiMonitor.beginConversation()
        assertTrue(first.startsWith("conv_"))
        assertTrue(second.startsWith("conv_"))
        assertNotEquals(first, second)
        assertTrue(SentryAiMonitor.currentConversationId() == second)
    }

    @Test
    fun callMeta_defaultsAreSafeForMetadataOnly() {
        val meta = SentryAiMonitor.CallMeta(
            model = "gpt-test",
            provider = "openai",
            hasImage = true,
            featureName = "DIG_BRIEF",
            messageCount = 3,
        )
        assertTrue(meta.operation == "chat")
        assertTrue(meta.hasImage)
        // No prompt fields exist on CallMeta — compile-time guarantee of metadata-only design.
    }
}
