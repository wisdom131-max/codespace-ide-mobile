package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Anthropic version header is REQUIRED and is the one place a wrong date silently breaks
 * every request. Source: https://docs.anthropic.com/en/api/versioning, read 2026-11-09, whose own
 * example is "anthropic-version: 2026-10-21" and whose history lists 2026-10-21 as current.
 */
class AnthropicVersionTest {

    private fun cfg(override: String? = null) = EngineConfig(
        modelId = "claude-test",
        providerId = "anthropic",
        baseUrl = "https://api.anthropic.com/v1/messages",
        apiKey = "fake-key-for-tests-only",
        apiVersionOverride = override,
    )

    @Test
    fun theDefaultVersionIsTheDocumentedOne() {
        val h = AnthropicAdapter().headers(cfg())
        assertEquals("2026-10-21", h["anthropic-version"])
        assertEquals("the key travels in x-api-key, never in Authorization",
            "fake-key-for-tests-only", h["x-api-key"])
        assertTrue("no Authorization header for Anthropic", h["Authorization"] == null)
    }

    @Test
    fun anEndpointMayPinAnOlderVersion() {
        val h = AnthropicAdapter().headers(cfg("2023-06-01"))
        assertEquals("2023-06-01", h["anthropic-version"])
    }

    @Test
    fun aBlankOverrideFallsBackToTheDocumentedDefault() {
        val h = AnthropicAdapter().headers(cfg("   "))
        assertEquals("2026-10-21", h["anthropic-version"])
    }

    @Test
    fun theAnswerCeilingAlwaysExceedsTheThinkingBudget() {
        // The bug this pins: budget == max_tokens is a hard 400 from Anthropic, and a reasoning
        // provider is exactly where a small answer ceiling is tempting.
        val tight = EngineConfig(
            modelId = "claude-test",
            providerId = "anthropic",
            baseUrl = "https://api.anthropic.com/v1/messages",
            apiKey = "fake-key-for-tests-only",
            maxResponseTokens = 1024,
            reasoningBudget = 1024,
        )
        val adapter = AnthropicAdapter()
        val body = adapter.payload(emptyList(), "hi", tight)
        assertTrue("the ceiling must clear the budget: " + body, body.contains("\"max_tokens\":2048"))
        assertTrue(body.contains("\"budget_tokens\":1024"))
        assertTrue("no temperature under API 2026-10-21", !body.contains("temperature"))
    }

    @Test
    fun aRoomyCeilingIsLeftAlone() {
        val roomy = EngineConfig(
            modelId = "claude-test",
            providerId = "anthropic",
            baseUrl = "https://api.anthropic.com/v1/messages",
            apiKey = "fake-key-for-tests-only",
            maxResponseTokens = 8192,
            reasoningBudget = 1024,
        )
        val body = AnthropicAdapter().payload(emptyList(), "hi", roomy)
        assertTrue(body.contains("\"max_tokens\":8192"))
    }

    @Test
    fun thinkingOffKeepsThePlainCeiling() {
        val off = EngineConfig(
            modelId = "claude-test",
            providerId = "anthropic",
            baseUrl = "https://api.anthropic.com/v1/messages",
            apiKey = "fake-key-for-tests-only",
            maxResponseTokens = 512,
            reasoningBudget = 0,
        )
        val body = AnthropicAdapter().payload(emptyList(), "hi", off)
        assertTrue(body.contains("\"max_tokens\":512"))
        assertTrue("no thinking block when off", !body.contains("thinking"))
    }
}
