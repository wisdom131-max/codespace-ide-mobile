package com.codespace.ide.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply that ended on the ceiling is a CUT reply. The text is kept, and one line says so.
 * The cut is also visible in the Chat Log, because "why was the answer short" is a diagnostic.
 */
class TruncationTest {

    private fun openAiLine(reason: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":\"partial text\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"" + reason + "\"}]}\n\n"

    private class FixedTransport(private val chunks: List<String>) : ChatTransport {
        val payloads = mutableListOf<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            payloads.add(payloadJson)
            chunks.forEach { emit(it) }
        }
    }

    private fun cfg() = EngineConfig(
        modelId = "gpt-test", providerId = "openai", baseUrl = "https://api.openai.com/v1",
        apiKey = "fake-key-for-tests-only",
    )

    @Test
    fun aLengthFinishIsReportedAsCut() = runBlocking {
        ChatLog.clear()
        val t = FixedTransport(listOf(openAiLine("length")))
        val events = ChatEngineImpl("s-cut", t).send("hi", emptyList(), cfg()).toList()
        val cut = events.filterIsInstance<EngineEvent.Truncated>()
        assertEquals("exactly one cut row", 1, cut.size)
        println("[cut] " + cut.first().visibleText)
        assertTrue(cut.first().visibleText.contains("cut off"))
        assertEquals("the text is preserved", "partial text",
            events.filterIsInstance<EngineEvent.Delta>().joinToString("") { it.text })
        assertTrue("and the log says so", ChatLog.recent(1).first().outcome.contains("cut off"))
        assertTrue(ChatLog.recent(1).first().endReason == "length")
    }

    @Test
    fun anOrdinaryStopIsNotACut() = runBlocking {
        val t = FixedTransport(listOf(openAiLine("stop"), "data: [DONE]\n\n"))
        val events = ChatEngineImpl("s-stop", t).send("hi", emptyList(), cfg()).toList()
        assertTrue(events.none { it is EngineEvent.Truncated })
        assertTrue(events.any { it is EngineEvent.Completed })
    }

    @Test
    fun aSafetyStopIsACutWithItsOwnWording() = runBlocking {
        val t = FixedTransport(listOf(openAiLine("content_filter")))
        val events = ChatEngineImpl("s-safe", t).send("hi", emptyList(), cfg()).toList()
        val cut = events.filterIsInstance<EngineEvent.Truncated>().first()
        assertTrue(cut.visibleText, cut.visibleText.contains("safety filter"))
    }

    @Test
    fun anthropicMaxTokensIsRecognisedInMessageDelta() {
        val line = "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\",\"stop_sequence\":null}}"
        assertEquals("max_tokens", AnthropicAdapter().endReason(line))
        assertTrue(ChatEngineImpl("x", FixedTransport(emptyList())).isCutOff("max_tokens"))
        assertFalse(ChatEngineImpl("x", FixedTransport(emptyList())).isCutOff("end_turn"))
    }

    @Test
    fun geminiMaxTokensIsRecognised() {
        val line = "{\"candidates\":[{\"content\":{},\"finishReason\":\"MAX_TOKENS\"}]}"
        assertEquals("max_tokens", GeminiAdapter().endReason(line))
    }

    @Test
    fun theDefaultOutputCeilingIsStated() {
        // What we actually send today, per provider, so a short answer has a number to blame.
        val defaults = EngineConfig(modelId = "m")
        assertEquals("one flat default", 4096, defaults.maxResponseTokens)
        val anthropic = AnthropicAdapter().payload(emptyList(), "hi",
            defaults.copy(providerId = "anthropic", baseUrl = "https://api.anthropic.com", apiKey = "k"))
        println("[cut] anthropic payload max_tokens: " + anthropic.take(120))
        assertTrue(anthropic.contains("\"max_tokens\":4096"))
    }
}
