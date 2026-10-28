package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A(b) commit 1: the extracted OpenAI adapter must be behavior-IDENTICAL to
 * the pre-adapter engine — payload bytes, endpoint, headers, delta decode,
 * sentinel. This suite locks the format so later provider work cannot drift it.
 */
class OpenAIAdapterTest {

    private val a = OpenAIAdapter()

    private fun cfg(vararg pairs: Pair<String, Any?>): EngineConfig {
        val c = EngineConfig("m1")
        return c.copy(baseUrl = pairs.firstOrNull { it.first == "baseUrl" }?.second as? String ?: c.baseUrl)
    }

    @Test
    fun endpointAndHeadersComeFromConfig() {
        val c = EngineConfig("m1").copy(baseUrl = "https://api.example/v1/chat/completions", apiKey = "k-1")
        assertEquals("https://api.example/v1/chat/completions", a.endpoint(c))
        assertEquals(mapOf("Authorization" to "Bearer k-1"), a.headers(c))
    }

    @Test
    fun blankKeySendsNoAuthorizationHeader() {
        val a = OpenAIAdapter()
        val noKey = a.headers(cfg("apiKey" to ""))
        org.junit.Assert.assertTrue(noKey.isEmpty())
        val withKey = a.headers(cfg("apiKey" to "sk-1"))
        org.junit.Assert.assertEquals(mapOf("Authorization" to "Bearer sk-1"), withKey)
    }

    @Test
    fun payloadMatchesTheOriginalWireFormatExactly() {
        val window = listOf(
            ChatMessage("1", ChatRole.USER, "hi there"),
            ChatMessage("2", ChatRole.ASSISTANT, "hello"),
            ChatMessage("3", ChatRole.USER, "again"),
        )
        val expected = "{\"model\":\"m1\",\"stream\":true,\"max_tokens\":4096," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"hi there\"}," +
            "{\"role\":\"assistant\",\"content\":\"hello\"}," +
            "{\"role\":\"user\",\"content\":\"again\"}]}"
        assertEquals(expected, a.payload(window, "again", EngineConfig("m1")))
    }

    @Test
    fun payloadEscapesQuotesAndNewlines() {
        val window = listOf(ChatMessage("1", ChatRole.USER, "line1\nline2 \"quoted\""))
        val p = a.payload(window, "x", EngineConfig("m1"))
        assertTrue(p.contains("\\\"quoted\\\""))
        assertTrue(p.contains("line1\\nline2"))
        assertFalse(p.contains("line1\nline2"))
    }

    @Test
    fun extractDeltaDecodesEscapes() {
        assertEquals("he\nllo", a.extractDelta("""{"choices":[{"delta":{"content":"he\nllo"}}]}"""))
        assertEquals("q\"b\\", a.extractDelta("""{"choices":[{"delta":{"content":"q\"b\\"}}]}"""))
        assertEquals("A", a.extractDelta("""{"x":{"content":"\u0041"}}"""))
    }

    @Test
    fun extractDeltaRejectsNonJsonAndNonContent() {
        assertNull(a.extractDelta("data: nope"))
        assertNull(a.extractDelta("""{"role":"assistant"}"""))
        assertNull(a.extractDelta(""))
    }

    @Test
    fun doneSentinelIsBracketDoneOnly() {
        assertTrue(a.isDoneSentinel("[DONE]"))
        assertFalse(a.isDoneSentinel("{\"done\":true}"))
    }
}
