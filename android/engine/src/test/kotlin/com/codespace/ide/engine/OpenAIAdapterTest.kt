package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/**
 * A(b) commit 1: the extracted OpenAI adapter must be behavior-IDENTICAL to
 * the pre-adapter engine — payload bytes, endpoint, headers, delta decode,
 * sentinel. This suite locks the format so later provider work cannot drift it.
 */
class OpenAIAdapterTest {

    private val a = OpenAIAdapter()

    private fun cfg(vararg pairs: Pair<String, Any?>): EngineConfig {
        val c = EngineConfig("m1")
        return c.copy(
            baseUrl = pairs.firstOrNull { it.first == "baseUrl" }?.second as? String ?: c.baseUrl,
            apiKey = pairs.firstOrNull { it.first == "apiKey" }?.second as? String ?: c.apiKey,
            reasoningBudget = (pairs.firstOrNull { it.first == "reasoningBudget" }?.second as? Int) ?: c.reasoningBudget,
        )
    }

    @Test
    fun endpointAndHeadersComeFromConfig() {
        val c = EngineConfig("m1").copy(baseUrl = "https://api.example/v1/chat/completions", apiKey = "k-1")
        assertEquals("https://api.example/v1/chat/completions", a.endpoint(c))
        assertEquals(mapOf("Accept" to "application/json, text/event-stream", "Authorization" to "Bearer k-1"), a.headers(c))
    }

    @Test
    fun blankKeySendsNoAuthorizationHeader() {
        val a = OpenAIAdapter()
        val noKey = a.headers(cfg("apiKey" to ""))
        // A blank key still sends Accept and still sends NO Authorization: the header must be
        // absent, not empty, so a keyless endpoint (Ollama) is not asked to authenticate.
        org.junit.Assert.assertFalse(noKey.containsKey("Authorization"))
        org.junit.Assert.assertEquals(
            "application/json, text/event-stream", noKey["Accept"])
        val withKey = a.headers(cfg("apiKey" to "sk-1"))
        org.junit.Assert.assertEquals(mapOf("Accept" to "application/json, text/event-stream", "Authorization" to "Bearer sk-1"), withKey)
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
        // Effort pinned OFF: this test locks the PRE-reasoning wire format; the
        // default reasoningBudget 1024 would otherwise append reasoning_effort low.
        assertEquals(expected, a.payload(window, "again", EngineConfig("m1").copy(reasoningBudget = 0)))
    }

    @Test
    fun reasoningEffortMapsForEffortKindPresetsOnly() {
        val a = OpenAIAdapter()
        // Omitted: off, kind none, unknown provider.
        org.junit.Assert.assertNull(a.reasoningEffort(cfg("reasoningBudget" to 0)))
        org.junit.Assert.assertNull(a.reasoningEffort(EngineConfig("m1", providerId = "groq", reasoningBudget = 8000)))
        org.junit.Assert.assertNull(a.reasoningEffort(EngineConfig("m1", providerId = "custom_abc", reasoningBudget = 8000)))
        // Mapped for openai-kind presets.
        org.junit.Assert.assertEquals("low", a.reasoningEffort(cfg("reasoningBudget" to 1024)))
        org.junit.Assert.assertEquals("medium", a.reasoningEffort(cfg("reasoningBudget" to 4096)))
        org.junit.Assert.assertEquals("high", a.reasoningEffort(cfg("reasoningBudget" to 20000)))
        org.junit.Assert.assertEquals(
            "reasoning_effort", // present in payload when on
            if (a.payload(emptyList(), "hi", EngineConfig("m1", reasoningBudget = 2048)).contains("reasoning_effort")) "reasoning_effort" else "MISSING"
        )
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

/** WP8: image_url data-URL parts; inline PDF refused before the request. */
class OpenAIAttachmentPayloadTest {

    private fun cfg() = EngineConfig(modelId = "gpt-test", providerId = "openai",
        baseUrl = "http://127.0.0.1:1/chat/completions", apiKey = "k")

    @Test
    fun imageBecomesDataUrlPart() {
        val a = OpenAIAdapter()
        val payload = a.payload(emptyList(), "look",
            listOf(ChatAttachment("1", "x.png", "image/png", "aGVsbG8=")), cfg())
        assertTrue(payload.contains("\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,aGVsbG8=\"}"))
        assertTrue(payload.contains("\"type\":\"text\",\"text\":\"look\""))
    }

    @Test
    fun inlinePdfRefusedBeforeRequest() {
        val a = OpenAIAdapter()
        try {
            a.payload(emptyList(), "hi",
                listOf(ChatAttachment("1", "d.pdf", "application/pdf", "cGRm")), cfg())
            fail("inline PDF is not part of chat/completions")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not supported"))
        }
    }

    // PHASE 1 (2026-11-17): Cloudflare wraps a successful completion in {"result": {...}}; the
    // live 200 body produced no text before this. Real shape captured from api.cloudflare.com.
    @org.junit.Test
    fun `a wrapped result body still yields its text`() {
        val body = "{\"result\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}}"
        val out = ProviderStreamNormaliser(StreamProvider.OPENAI).feed(body)
        val text = out.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
        org.junit.Assert.assertEquals("OK", text)
    }

    // P0.1 (2026-11-19): Cloudflare /ai/v1 validates messages[].content as a STRING. Live
    // diagnostic showed 'array' not in 'string' for every message when a text attachment rode
    // the final turn. These three tests pin the fix.
    @org.junit.Test
    fun `cloudflare text attachment is serialised as a string not a content array`() {
        val cfg = EngineConfig(modelId = "m", baseUrl = "https://api.cloudflare.com/x/ai/v1", supportsContentParts = false)
        val body = OpenAIAdapter().payload(
            listOf(ChatMessage("1", ChatRole.USER, "hello")), "question",
            listOf(ChatAttachment("a1", "notes.txt", "text/plain", "", textData = "attached text")), cfg)
        org.junit.Assert.assertTrue(body.contains("\"content\":\"question"))
        org.junit.Assert.assertTrue(body.contains("attached text"))
        org.junit.Assert.assertFalse(body.contains("\"content\":["))
    }

    @org.junit.Test
    fun `cloudflare request with several text blocks stays one string`() {
        val cfg = EngineConfig(modelId = "m", baseUrl = "https://api.cloudflare.com/x/ai/v1", supportsContentParts = false)
        val body = OpenAIAdapter().payload(
            listOf(ChatMessage("1", ChatRole.USER, "a")), "q",
            listOf(ChatAttachment("a2", "a.txt", "text/plain", "", textData = "AAA"),
                 ChatAttachment("a3", "b.md", "text/markdown", "", textData = "BBB")), cfg)
        org.junit.Assert.assertFalse(body.contains("\"type\":\"text\""))
        org.junit.Assert.assertTrue(body.contains("AAA"))
        org.junit.Assert.assertTrue(body.contains("BBB"))
    }

    @org.junit.Test
    fun `cloudflare image is named honestly instead of silently dropped`() {
        val cfg = EngineConfig(modelId = "m", baseUrl = "https://api.cloudflare.com/x/ai/v1", supportsContentParts = false)
        val body = OpenAIAdapter().payload(
            listOf(ChatMessage("1", ChatRole.USER, "a")), "q",
            listOf(ChatAttachment("a4", "shot.png", "image/png", "AAAA")), cfg)
        org.junit.Assert.assertTrue(body.contains("Attachment not sent"))
        org.junit.Assert.assertTrue(body.contains("shot.png"))
        org.junit.Assert.assertFalse(body.contains("image_url"))
    }

    @org.junit.Test
    fun `a multimodal provider still gets the content array`() {
        val cfg = EngineConfig(modelId = "m", baseUrl = "https://api.openai.com/v1", supportsContentParts = true)
        val body = OpenAIAdapter().payload(
            listOf(ChatMessage("1", ChatRole.USER, "a")), "q",
            listOf(ChatAttachment("a4", "shot.png", "image/png", "AAAA")), cfg)
        org.junit.Assert.assertTrue(body.contains("\"content\":["))
        org.junit.Assert.assertTrue(body.contains("image_url"))
    }

    // P0.5 (2026-11-19): the device log showed "end reason: tool_calls" with zero tools offered.
    @org.junit.Test
    fun `tool_calls with no calls is inconsistent, not a handoff`() {
        val body = "{\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"tool_calls\"}]}"
        val out = ProviderStreamNormaliser(StreamProvider.OPENAI).feed(body)
        val ends = out.filterIsInstance<StreamEvent.Ended>()
        org.junit.Assert.assertEquals(1, ends.size)
        org.junit.Assert.assertEquals(StreamEnd.INCOMPLETE_TOOL_CALL, ends.first().reason)
    }

    @org.junit.Test
    fun `a real tool call still ends as tool_calls`() {
        val body = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\"," +
            "\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}"
        val out = ProviderStreamNormaliser(StreamProvider.OPENAI).feed(body)
        val ends = out.filterIsInstance<StreamEvent.Ended>()
        org.junit.Assert.assertEquals(1, ends.size)
        org.junit.Assert.assertEquals(StreamEnd.TOOL_CALLS, ends.first().reason)
    }

    @org.junit.Test
    fun `a plain stop still ends naturally`() {
        val body = "{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
        val out = ProviderStreamNormaliser(StreamProvider.OPENAI).feed(body)
        val ends = out.filterIsInstance<StreamEvent.Ended>()
        org.junit.Assert.assertEquals(StreamEnd.NATURAL, ends.first().reason)
    }
}
}
