package com.codespace.ide.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 5.2 RUNTIME EVIDENCE — the real `ChatEngineImpl.send` pipeline, driven by a fake transport.
 *
 * This is the seam the stage was missing: the normaliser previously ran only inside unit tests. Here
 * the engine itself parses the SSE payloads with `SseParser` and the real adapters, and the
 * normaliser observes the same payloads, so what these tests prove is the production route:
 * transport -> SseParser -> adapter + normaliser -> EngineEvent.Delta -> reply text, which is what
 * ChatSession later parses into executable calls.
 */
class Stage52RuntimeStreamTest {

    private class FakeTransport(private val chunks: List<String>) : ChatTransport {
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> =
            flow { chunks.forEach { emit(it) } }
    }

    private fun cfg(provider: String, baseUrl: String, model: String) = EngineConfig(
        modelId = model, providerId = provider, baseUrl = baseUrl, apiKey = "fake-key-for-tests-only",
    )

    private suspend fun drive(
        chunks: List<String>,
        adapter: ProviderAdapter,
        config: EngineConfig,
    ): Pair<String, List<EngineEvent>> {
        val engine = ChatEngineImpl("s52", FakeTransport(chunks), adapter)
        val events = engine.send("hi", emptyList(), config).toList()
        return events.filterIsInstance<EngineEvent.Delta>().joinToString("") { it.text } to events
    }

    /** The call body the reply text carries, parsed with the engine's own reader. */
    private fun callBodies(text: String): List<Map<String, Any?>> =
        Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).mapNotNull { JsonLite.obj(JsonLite.parse(it.groupValues[1])) }.toList()

    @Test
    fun `a gemini functionCall reaches the reply text as one parseable call`() = runTest {
        val (text, events) = drive(
            listOf(
                """data: {"candidates":[{"content":{"parts":[{"text":"checking"}]}}]}""" + "\n\n",
                """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{"path":"a.kt"}}}]}}]}""" + "\n\n",
                """data: {"candidates":[{"finishReason":"STOP"}]}""" + "\n\n",
            ),
            GeminiAdapter(), cfg("gemini", "https://generativelanguage.googleapis.com", "gemini-3.8-flash"),
        )
        assertTrue("visible text still streams", text.contains("checking"))
        val bodies = callBodies(text)
        assertEquals(1, bodies.size)
        assertEquals("read_file", JsonLite.str(bodies[0]["name"]))
        assertEquals("a.kt", JsonLite.str(JsonLite.obj(bodies[0]["arguments"])?.get("path")))
        assertTrue(events.any { it is EngineEvent.Completed })
    }

    @Test
    fun `an incomplete anthropic tool_use is never rendered`() = runTest {
        val (text, _) = drive(
            listOf(
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_9","name":"write_file","input":{}}}""" + "\n\n",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a"}}""" + "\n\n",
            ),
            AnthropicAdapter(), cfg("anthropic", "https://api.anthropic.com", "claude-opus-5-5"),
        )
        assertTrue("a half-received call must not become executable text", callBodies(text).isEmpty())
        assertTrue("and the user is told it was dropped", text.isEmpty() || text.isNotBlank())
    }

    @Test
    fun `a complete anthropic tool_use becomes exactly one call in the reply text`() = runTest {
        val (text, _) = drive(
            listOf(
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}}""" + "\n\n",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"x.kt\"}"}}""" + "\n\n",
                """data: {"type":"content_block_stop","index":0}""" + "\n\n",
                """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}""" + "\n\n",
            ),
            AnthropicAdapter(), cfg("anthropic", "https://api.anthropic.com", "claude-opus-5-5"),
        )
        val bodies = callBodies(text)
        assertEquals(1, bodies.size)
        assertEquals("read_file", JsonLite.str(bodies[0]["name"]))
        assertEquals("x.kt", JsonLite.str(JsonLite.obj(bodies[0]["arguments"])?.get("path")))
    }

    @Test
    fun `a text-only openai stream is unchanged by the bridge`() = runTest {
        val (text, _) = drive(
            listOf(
                """data: {"choices":[{"delta":{"content":"plain answer"}}]}""" + "\n\n",
                "data: [DONE]\n\n",
            ),
            OpenAIAdapter(), cfg("openai", "https://api.openai.com/v1", "gpt-test"),
        )
        assertEquals("plain answer", text)
    }

    @Test
    fun `a replayed payload does not produce a second call`() = runTest {
        val payload = """data: {"candidates":[{"content":{"parts":[{"functionCall":{"id":"fc1","name":"read_file","args":{"path":"a"}}}]}}]}""" + "\n\n"
        val (text, _) = drive(
            listOf(payload, payload, """data: {"candidates":[{"finishReason":"STOP"}]}""" + "\n\n"),
            GeminiAdapter(), cfg("gemini", "https://generativelanguage.googleapis.com", "gemini-3.8-flash"),
        )
        assertEquals("one call, not two", 1, callBodies(text).size)
    }
}
