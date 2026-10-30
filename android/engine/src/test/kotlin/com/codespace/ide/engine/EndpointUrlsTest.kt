package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URL contract, as a matrix. Every case here is a string a user can actually type into the
 * custom endpoint form, and each one previously either failed on the wire or was silently
 * mangled. Nothing in this file touches the network.
 */
class EndpointUrlsTest {

    // ── OpenAI-compatible chat ────────────────────────────────────────────

    @Test
    fun chatFromPlainBase() {
        assertEquals("https://api.example.com/chat/completions",
            EndpointUrls.chatCompletions("https://api.example.com"))
    }

    @Test
    fun chatFromVersionedBase() {
        assertEquals("https://api.example.com/v1/chat/completions",
            EndpointUrls.chatCompletions("https://api.example.com/v1"))
    }

    @Test
    fun chatFromPrefixedBase() {
        assertEquals("https://api.groq.com/openai/v1/chat/completions",
            EndpointUrls.chatCompletions("https://api.groq.com/openai/v1"))
    }

    @Test
    fun chatTrailingSlashDoesNotDouble() {
        assertEquals("https://api.example.com/v1/chat/completions",
            EndpointUrls.chatCompletions("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/v1/chat/completions",
            EndpointUrls.chatCompletions("https://api.example.com/v1///"))
    }

    @Test
    fun chatFullPathIsLeftAlone() {
        val full = "https://api.example.com/v1/chat/completions"
        assertEquals(full, EndpointUrls.chatCompletions(full))
    }

    @Test
    fun chatFromPastedModelsUrlIsCorrected() {
        assertEquals("https://api.example.com/v1/chat/completions",
            EndpointUrls.chatCompletions("https://api.example.com/v1/models"))
    }

    @Test
    fun chatCarriesQueryInsteadOfStraddlingIt() {
        assertEquals("https://gw.example.com/v1/chat/completions?api-version=2026-01-01",
            EndpointUrls.chatCompletions("https://gw.example.com/v1?api-version=2026-01-01"))
    }

    @Test
    fun chatDropsFragment() {
        assertEquals("https://api.example.com/v1/chat/completions",
            EndpointUrls.chatCompletions("https://api.example.com/v1#section"))
    }

    @Test
    fun chatIsIdempotent() {
        val once = EndpointUrls.chatCompletions("https://api.example.com/v1")
        assertEquals(once, EndpointUrls.chatCompletions(once))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", EndpointUrls.chatCompletions(""))
        assertEquals("", EndpointUrls.chatCompletions("   "))
    }

    // ── OpenAI-compatible discovery ──────────────────────────────────────

    @Test
    fun modelsFromBaseAndFromFullChatPath() {
        assertEquals("https://api.example.com/v1/models",
            EndpointUrls.models("https://api.example.com/v1"))
        assertEquals("https://api.example.com/v1/models",
            EndpointUrls.models("https://api.example.com/v1/chat/completions"))
        assertEquals("https://api.example.com/v1/models",
            EndpointUrls.models("https://api.example.com/v1/models"))
    }

    @Test
    fun modelsCarriesQuery() {
        assertEquals("https://gw.example.com/v1/models?api-version=1",
            EndpointUrls.models("https://gw.example.com/v1?api-version=1"))
    }

    /** The exact pair that used to be impossible to satisfy with one stored string. */
    @Test
    fun chatAndDiscoveryAcceptTheSameStoredBase() {
        val base = "https://api.groq.com/openai/v1"
        assertEquals("https://api.groq.com/openai/v1/chat/completions",
            EndpointUrls.chatCompletions(base))
        assertEquals("https://api.groq.com/openai/v1/models", EndpointUrls.models(base))
    }

    // ── Anthropic Messages ───────────────────────────────────────────────

    @Test
    fun messagesPaths() {
        assertEquals("https://api.anthropic.com/v1/messages",
            EndpointUrls.messages("https://api.anthropic.com"))
        assertEquals("https://api.anthropic.com/v1/messages",
            EndpointUrls.messages("https://api.anthropic.com/v1"))
        assertEquals("https://api.anthropic.com/v1/messages",
            EndpointUrls.messages("https://api.anthropic.com/v1/messages"))
        assertEquals("https://api.anthropic.com/v1/messages",
            EndpointUrls.messages("https://api.anthropic.com/v1/messages/"))
    }

    @Test
    fun messagesDoesNotInheritOpenAiSegments() {
        assertFalse(EndpointUrls.messages("https://api.anthropic.com").contains("chat/completions"))
    }

    // ── Gemini ───────────────────────────────────────────────────────────

    @Test
    fun geminiDoesNotDoubleTheVersionSegment() {
        val expected = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash" +
            ":streamGenerateContent?alt=sse"
        assertEquals(expected,
            EndpointUrls.geminiStream("https://generativelanguage.googleapis.com", "gemini-3.8-flash"))
        assertEquals(expected,
            EndpointUrls.geminiStream("https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash"))
        assertEquals(expected,
            EndpointUrls.geminiStream("https://generativelanguage.googleapis.com/v1beta/", "gemini-3.8-flash"))
    }

    @Test
    fun geminiUsesOnlyItsOwnQuery() {
        val url = EndpointUrls.geminiStream("https://gen.example.com?key=SECRET", "m1")
        assertEquals("https://gen.example.com/v1beta/models/m1:streamGenerateContent?alt=sse", url)
        assertTrue(url.indexOf('?') == url.lastIndexOf('?'))
    }

    // ── Validation ───────────────────────────────────────────────────────

    @Test
    fun validBaseAcceptsRealUrls() {
        assertTrue(EndpointUrls.validBase("https://api.example.com/v1"))
        assertTrue(EndpointUrls.validBase("http://localhost:11434/v1"))
        assertTrue(EndpointUrls.validBase("https://gw.example.com/v1?api-version=1"))
        assertTrue(EndpointUrls.validBase("  https://api.example.com/v1/  "))
    }

    @Test
    fun validBaseRejectsJunk() {
        assertFalse(EndpointUrls.validBase(""))
        assertFalse(EndpointUrls.validBase("api.example.com/v1"))
        assertFalse(EndpointUrls.validBase("ftp://api.example.com"))
        assertFalse(EndpointUrls.validBase("https://"))
        assertFalse(EndpointUrls.validBase("https:///v1"))
        assertFalse(EndpointUrls.validBase("https://api example.com"))
    }

    // ── Error text safety ────────────────────────────────────────────────

    @Test
    fun redactedPathNeverCarriesTheQuery() {
        val path = EndpointUrls.redactedPath("https://gw.example.com/v1/chat/completions?key=SECRET123")
        assertEquals("https://gw.example.com/v1/chat/completions", path)
        assertFalse(path.contains("SECRET123"))
    }

    @Test
    fun redactedPathIsBounded() {
        val long = "https://h.example.com/" + "a".repeat(300)
        assertTrue(EndpointUrls.redactedPath(long).length <= 120)
    }
}
