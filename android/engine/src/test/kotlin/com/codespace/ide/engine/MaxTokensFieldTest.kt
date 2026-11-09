package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ceiling field is a capability row. Wrong row = one 400, never a loop. */
class MaxTokensFieldTest {

    @Test
    fun openAiReasoningModelsTakeTheNewName() {
        listOf("o1", "o1-mini", "o3-mini", "o4-mini", "gpt-5", "gpt-5-codex").forEach { m ->
            assertEquals(
                m,
                MaxTokensField.MAX_COMPLETION_TOKENS,
                MaxTokensField.forModel("openai", "https://api.openai.com/v1", m),
            )
        }
    }

    @Test
    fun olderOpenAiModelsKeepTheOldName() {
        listOf("gpt-4o", "gpt-4.1", "gpt-4o-mini").forEach { m ->
            assertEquals(m, MaxTokensField.MAX_TOKENS,
                MaxTokensField.forModel("openai", "https://api.openai.com/v1", m))
        }
    }

    @Test
    fun compatibleEndpointsAndOpenRouterKeepMaxTokens() {
        assertEquals(MaxTokensField.MAX_TOKENS,
            MaxTokensField.forModel("openai", "https://api.groq.com/openai/v1", "o3-mini"))
        assertEquals(MaxTokensField.MAX_TOKENS,
            MaxTokensField.forModel("openai", "https://openrouter.ai/api/v1", "openai/o3-mini"))
    }

    @Test
    fun thePayloadUsesWhateverTheRowSays() {
        val cfg = EngineConfig(
            modelId = "gpt-5", providerId = "openai",
            baseUrl = "https://api.openai.com/v1", apiKey = "fake-key-for-tests-only",
            maxResponseTokens = 512,
            maxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
        )
        val body = OpenAIAdapter().payload(emptyList(), "hi", cfg)
        assertTrue(body, body.contains("\"max_completion_tokens\":512"))
        assertFalse("never both names: $body", body.contains("\"max_tokens\""))
    }

    @Test
    fun flipReturnsTheOtherName() {
        assertEquals(MaxTokensField.MAX_TOKENS,
            MaxTokensField.flip(MaxTokensField.MAX_COMPLETION_TOKENS))
        assertEquals(MaxTokensField.MAX_COMPLETION_TOKENS,
            MaxTokensField.flip(MaxTokensField.MAX_TOKENS))
    }
}
