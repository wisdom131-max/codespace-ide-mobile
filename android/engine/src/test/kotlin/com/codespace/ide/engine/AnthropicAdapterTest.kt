package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A(b) WP3: Anthropic native adapter contract — headers (x-api-key +
 * anthropic-version), required max_tokens, bounded thinking (floor 1024,
 * 0 = off), text_delta extraction, thinking_delta filtering, no sentinel.
 * Every expectation verified against docs.anthropic.com/en/api/messages-streaming
 * and the official SDK sources on 2026-10-28 (fixture strings are the exact
 * wire shapes from those docs).
 */
class AnthropicAdapterTest {

    private val a = AnthropicAdapter()

    @Test
    fun endpointEchoesTheMessagesUrl() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            a.endpoint(EngineConfig("claude-opus-5-5", baseUrl = "https://api.anthropic.com/v1/messages"))
        )
    }

    @Test
    fun apiKeyInHeaderWithVersionNeverInUrl() {
        val c = EngineConfig("m", apiKey = "sk-ant-1")
        assertEquals("sk-ant-1", a.headers(c)["x-api-key"])
        assertEquals("2026-10-21", a.headers(c)["anthropic-version"])
        assertFalse(a.endpoint(c).contains("sk-ant-1"))
    }

    @Test
    fun payloadHasRequiredMaxTokensAndUserAssistantRoles() {
        val window = listOf(
            ChatMessage("1", ChatRole.USER, "hi"),
            ChatMessage("2", ChatRole.ASSISTANT, "hello"),
            ChatMessage("3", ChatRole.USER, "again"),
        )
        val expected = "{\"model\":\"claude-opus-5-5\",\"max_tokens\":4096," +
            "\"stream\":true,\"messages\":[" +
            "{\"role\":\"user\",\"content\":\"hi\"}," +
            "{\"role\":\"assistant\",\"content\":\"hello\"}," +
            "{\"role\":\"user\",\"content\":\"again\"}]}"
        assertEquals(expected, a.payload(window, "again", EngineConfig("claude-opus-5-5")))
    }

    @Test
    fun thinkingIsBoundedWithFloorAndOffAtZero() {
        assertNull(a.thinkingJson(EngineConfig("m", reasoningBudget = 0)))
        assertEquals(
            "{\"type\":\"enabled\",\"budget_tokens\":1024}",
            a.thinkingJson(EngineConfig("m", reasoningBudget = 1024))
        )
        assertEquals(
            "{\"type\":\"enabled\",\"budget_tokens\":1024}",
            a.thinkingJson(EngineConfig("m", reasoningBudget = 512))
        )
        assertEquals(
            "{\"type\":\"enabled\",\"budget_tokens\":2048}",
            a.thinkingJson(EngineConfig("m", reasoningBudget = 2048))
        )
        assertTrue(
            a.payload(listOf(ChatMessage("1", ChatRole.USER, "hi")), "hi",
                EngineConfig("m")).contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":1024}")
        )
        assertFalse(
            a.payload(listOf(ChatMessage("1", ChatRole.USER, "hi")), "hi",
                EngineConfig("m", reasoningBudget = 0)).contains("thinking")
        )
    }

    @Test
    fun extractsTextDeltaFromContentBlockDelta() {
        assertEquals(
            "ello frien",
            a.extractDelta(
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ello frien\"}}"
            )
        )
    }

    @Test
    fun thinkingDeltaAndNonDeltaEventsAreNotContent() {
        assertNull(a.extractDelta("{\"type\":\"thinking_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}"))
        assertNull(a.extractDelta("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[]}}"))
        assertNull(a.extractDelta("{\"type\":\"ping\"}"))
        assertNull(a.extractDelta("{\"type\":\"message_stop\"}"))
    }

    @Test
    fun escapedTextIsDecoded() {
        assertEquals(
            "line1\nline2 \"q\" \\d",
            a.extractDelta(
                "{\"delta\":{\"type\":\"text_delta\",\"text\":\"line1\\nline2 \\\"q\\\" \\\\d\"}}"
            )
        )
    }

    @Test
    fun anthropicHasNoDoneSentinel() {
        assertFalse(a.isDoneSentinel("[DONE]"))
        assertFalse(a.isDoneSentinel("{\"type\":\"message_stop\"}"))
    }
}
