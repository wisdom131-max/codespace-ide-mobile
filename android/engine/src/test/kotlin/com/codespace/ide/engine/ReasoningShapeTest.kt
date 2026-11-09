package com.codespace.ide.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenRouter takes one consolidated `reasoning` object; OpenAI-compatible endpoints take the flat
 * `reasoning_effort`. Source: openrouter.ai/docs/use-cases/reasoning-tokens (read 2026-11-09).
 * A wrong shape here is a parameter error on an otherwise healthy request.
 */
class ReasoningShapeTest {

    private fun cfg(baseUrl: String, budget: Int) = EngineConfig(
        modelId = "m-1",
        providerId = "openai",
        baseUrl = baseUrl,
        apiKey = "fake-key-for-tests-only",
        reasoningBudget = budget,
    )

    @Test
    fun openRouterGetsTheConsolidatedReasoningObject() {
        val body = OpenAIAdapter().payload(
            emptyList(), "hi", cfg("https://openrouter.ai/api/v1", 8192),
        )
        assertTrue(body, body.contains("\"reasoning\":{\"effort\":\"medium\"}"))
        assertFalse("never the flat field too: $body", body.contains("reasoning_effort"))
    }

    @Test
    fun aCompatibleEndpointGetsTheFlatField() {
        val body = OpenAIAdapter().payload(
            emptyList(), "hi", cfg("https://api.groq.com/openai/v1", 8192),
        )
        assertTrue(body, body.contains("\"reasoning_effort\":\"medium\""))
        assertFalse(body, body.contains("\"reasoning\":{"))
    }

    @Test
    fun noBudgetSendsNoReasoningFieldAtAll() {
        val body = OpenAIAdapter().payload(
            emptyList(), "hi", cfg("https://openrouter.ai/api/v1", 0),
        )
        assertFalse(body, body.contains("reasoning"))
    }

    @Test
    fun theEffortLadderIsUnchanged() {
        val a = OpenAIAdapter()
        assertTrue(a.payload(emptyList(), "h", cfg("https://x/v1", 1024)).contains("\"low\""))
        assertTrue(a.payload(emptyList(), "h", cfg("https://x/v1", 8192)).contains("\"medium\""))
        assertTrue(a.payload(emptyList(), "h", cfg("https://x/v1", 32768)).contains("\"high\""))
    }
}
