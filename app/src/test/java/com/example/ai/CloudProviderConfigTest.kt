package com.example.ai

import com.example.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloudProviderConfigTest {
    @Test
    fun openAiConfiguredModelIsNotSilentlyRewritten() {
        assertEquals(BuildConfig.OPENAI_MODEL.trim(), OpenAiApiClient.configuredModel())
        assertFalse(OpenAiApiClient.configuredModel().equals("gpt-5.1", ignoreCase = true))
    }

    @Test
    fun geminiDefaultUsesStableMultimodalModel() {
        assertEquals("gemini-3.5-flash", GeminiApiClient.configuredModel())
    }

    @Test
    fun geminiApiKeyBuildConfigFieldExists() {
        // Presence of the field is the contract: empty is fine in CI without secrets.
        assertEquals(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_API_KEY)
    }
}
