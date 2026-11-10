package com.codespace.ide.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version 2026-10-21 removed temperature/top_p/top_k and 400s if any of them appears (owner-checked
 * at platform.claude.com/docs/en/api/versioning, 2026-11-10). The settings port that lands next adds
 * temperature for other providers, so this test exists to fail the moment one of the three leaks into
 * an Anthropic body.
 *
 * Same version: a document block inside an ASSISTANT message, and a text block with non-empty
 * citations in a USER message, also 400. Both checked here by shape, not by trusting the builder.
 */
class AnthropicSamplingGuardTest {

    private val adapter = AnthropicAdapter()

    private fun cfg() = EngineConfig(
        modelId = "claude-sonnet-test", providerId = "anthropic",
        baseUrl = "https://api.anthropic.com", apiKey = "fake-key-for-tests-only",
        maxResponseTokens = 1024, reasoningBudget = 0,
    )

    @Test
    fun noSamplingParameterEverAppears() {
        val plain = adapter.payload(emptyList(), "hi", cfg())
        val withThinking = adapter.payload(emptyList(), "hi", cfg().copy(reasoningBudget = 2048))
        listOf("temperature", "top_p", "top_k").forEach { key ->
            assertFalse("plain payload leaked $key: $plain", plain.contains("\"" + key + "\""))
            assertFalse("thinking payload leaked $key: $withThinking", withThinking.contains("\"" + key + "\""))
        }
    }

    @Test
    fun aDocumentBlockNeverLandsInAnAssistantMessage() {
        val attachment = ChatAttachment(
            id = "a1", name = "spec.pdf", mimeType = "application/pdf",
            base64Data = "JVBERi0xLjQK", textData = null,
        )
        val payload = adapter.payload(emptyList(), "summarise this", listOf(attachment), cfg())
        println("[anthropic] " + payload.take(240))
        val docAt = payload.indexOf("\"type\":\"document\"")
        assertTrue("the document block must be present", docAt > 0)
        // Everything before the document must be a role marker; the block must sit inside a user turn.
        val lastRole = payload.substring(0, docAt).lastIndexOf("\"role\"")
        val roleValue = payload.substring(lastRole, docAt)
        assertTrue("document block inside an assistant turn: $roleValue", roleValue.contains("user"))
    }

    @Test
    fun weNeverEmitACitationsField() {
        val payload = adapter.payload(emptyList(), "hi", cfg())
        assertFalse("citations in a user text block is a 400 at this version",
            payload.contains("\"citations\""))
    }
}
