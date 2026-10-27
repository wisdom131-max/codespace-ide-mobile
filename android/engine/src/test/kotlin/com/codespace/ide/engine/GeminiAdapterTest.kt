package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A(b) commit 2: Gemini native adapter contract — endpoint shape, header auth,
 * contents payload with bounded thinking, thought-part filtering, no sentinel.
 */
class GeminiAdapterTest {

    private val a = GeminiAdapter()

    @Test
    fun endpointIsModelsPathWithSseFlag() {
        val c = EngineConfig("gemini-2.0-flash", providerId = "gemini",
            baseUrl = "https://generativelanguage.googleapis.com")
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse",
            a.endpoint(c)
        )
    }

    @Test
    fun endpointNormalizesTrailingSlash() {
        val c = EngineConfig("m", baseUrl = "https://example.test/")
        assertTrue(a.endpoint(c).startsWith("https://example.test/v1beta/"))
        assertFalse(a.endpoint(c).contains("test//"))
    }

    @Test
    fun apiKeyGoesInTheHeaderNeverTheUrl() {
        val c = EngineConfig("m", apiKey = "gk-1")
        assertEquals(mapOf("x-goog-api-key" to "gk-1"), a.headers(c))
        assertFalse(a.endpoint(c).contains("gk-1"))
    }

    @Test
    fun payloadUsesUserModelRolesAndBoundedThinking() {
        val window = listOf(
            ChatMessage("1", ChatRole.USER, "hi"),
            ChatMessage("2", ChatRole.ASSISTANT, "hello"),
            ChatMessage("3", ChatRole.USER, "again"),
        )
        val expected = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}," +
            "{\"role\":\"model\",\"parts\":[{\"text\":\"hello\"}]}," +
            "{\"role\":\"user\",\"parts\":[{\"text\":\"again\"}]}]," +
            "\"generationConfig\":{\"maxOutputTokens\":4096,\"thinkingConfig\":{\"thinkingBudget\":1024}}}"
        assertEquals(expected, a.payload(window, "again", EngineConfig("m")))
    }

    @Test
    fun thinkingBudgetIsBoundedNeverDynamic() {
        assertEquals(1024, a.thinkingBudget(EngineConfig("m")))
        assertEquals(0, a.thinkingBudget(EngineConfig("m", reasoningBudget = 0)))
        assertEquals(2048, a.thinkingBudget(EngineConfig("m", reasoningBudget = 2048)))
        assertEquals(1024, a.thinkingBudget(EngineConfig("m", reasoningBudget = -1)))
    }

    @Test
    fun extractDeltaConcatsPartsPerChunk() {
        assertEquals("Hel", a.extractDelta("""{"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]}}]}"""))
        assertEquals("lo!", a.extractDelta("""{"candidates":[{"content":{"parts":[{"text":"lo"},{"text":"!"}]}}]}"""))
    }

    @Test
    fun extractDeltaSkipsThoughtParts() {
        val data = """{"candidates":[{"content":{"parts":[{"text":"visible"},{"text":"hidden thought","thought":true}]}}]}"""
        assertEquals("visible", a.extractDelta(data))
    }

    @Test
    fun extractDeltaRejectsChunksWithoutParts() {
        assertNull(a.extractDelta("""{"candidates":[{"finishReason":"STOP"}]}"""))
        assertNull(a.extractDelta("not json"))
        assertNull(a.extractDelta(""))
    }

    @Test
    fun geminiHasNoDoneSentinel() {
        assertFalse(a.isDoneSentinel("[DONE]"))
        assertFalse(a.isDoneSentinel("""{"candidates":[]}"""))
    }
}
