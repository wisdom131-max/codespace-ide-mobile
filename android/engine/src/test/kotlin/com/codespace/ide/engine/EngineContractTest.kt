package com.codespace.ide.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end contract tests: the real HttpTransport against the hand-rolled
 * fake server over a real localhost socket — streaming, DONE handling,
 * error classification, budget refusal, and cancellation mid-stream.
 */
class EngineContractTest {

    private lateinit var server: FakeChatServer
    private lateinit var engine: ChatEngineImpl

    @Before fun setUp() {
        server = FakeChatServer()
        server.start()
        engine = ChatEngineImpl("s1", FakeUrlTransport(server.port))
    }

    @After fun tearDown() { server.stop() }

    /** Blank line after each `data:` line so the parser sees event boundaries. */
    private fun flat(lines: List<String>) = lines.flatMap { listOf(it, "") }

    @Test fun `streams deltas then completed`() = runTest {
        server.lastScript = FakeChatServer.Script(200, emptyList(), flat(listOf(
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"}}]}""",
            "data: [DONE]"
        )))
        val events = engine.send("hi", EngineConfig("m1")).toList()
        assertEquals(2, events.filterIsInstance<EngineEvent.Delta>().size)
        assertEquals("Hello", events.filterIsInstance<EngineEvent.Delta>().joinToString("") { it.text })
        val done = events.filterIsInstance<EngineEvent.Completed>().single()
        assertEquals(5, done.totalChars)
        assertEquals("Hello", engine.transcript().last().text)
    }

    @Test fun `anthropic adapter streams text deltas through event lines`() = runTest {
        val an = ChatEngineImpl("a1", FakeUrlTransport(server.port), AnthropicAdapter())
        server.lastScript = FakeChatServer.Script(200, emptyList(), flat(listOf(
            """data: {"type":"message_start","message":{"role":"assistant","content":[]}}""",
            """event: content_block_delta""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
            """data: {"type":"thinking_delta","delta":{"type":"thinking_delta","thinking":"internal"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo!"}}""",
            """data: {"type":"message_stop"}"""
        )))
        val events = an.send("hi", EngineConfig(
            "claude-opus-5-5", providerId = "anthropic",
            baseUrl = "http://127.0.0.1:" + server.port + "/v1/messages"
        )).toList()
        assertEquals(2, events.filterIsInstance<EngineEvent.Delta>().size)
        assertEquals("Hello!", events.filterIsInstance<EngineEvent.Delta>().joinToString("") { it.text })
        assertEquals(6, events.filterIsInstance<EngineEvent.Completed>().single().totalChars)
        assertEquals("Hello!", an.transcript().last().text)
    }

    @Test fun `gemini adapter streams deltas then completes without sentinel`() = runTest {
        val g = ChatEngineImpl("g1", FakeUrlTransport(server.port), GeminiAdapter())
        server.lastScript = FakeChatServer.Script(200, emptyList(), flat(listOf(
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]}}]}""",
            """data: {"candidates":[{"content":{"parts":[{"text":"lo"},{"text":"!"}]}}]}""",
            """data: {"candidates":[{"finishReason":"STOP"}]}"""
        )))
        val events = g.send("hi", EngineConfig("gemini-2.0-flash", providerId = "gemini")).toList()
        assertEquals(2, events.filterIsInstance<EngineEvent.Delta>().size)
        assertEquals("Hello!", events.filterIsInstance<EngineEvent.Delta>().joinToString("") { it.text })
        assertEquals(6, events.filterIsInstance<EngineEvent.Completed>().single().totalChars)
        assertEquals("Hello!", g.transcript().last().text)
    }

    @Test fun `402 classifies to out of credits`() = runTest {
        server.lastScript = FakeChatServer.Script(402, emptyList(), flat(listOf(
            """data: {"error":{"message":"insufficient credits"}}"""
        )))
        val events = engine.send("hi", EngineConfig("m1")).toList()
        val failed = events.filterIsInstance<EngineEvent.Failed>().single()
        assertTrue(failed.error is ChatError.OutOfCredits)
        assertTrue(failed.error.userVerdict.contains("credits"))
    }

    @Test fun `429 keeps Retry-After`() = runTest {
        server.lastScript = FakeChatServer.Script(429, listOf("Retry-After: 30"), flat(listOf(
            """data: {"error":"slow down"}"""
        )))
        val events = engine.send("hi", EngineConfig("m1")).toList()
        val failed = events.filterIsInstance<EngineEvent.Failed>().single()
        assertEquals(30, (failed.error as ChatError.RateLimited).retryAfterSec)
    }

    @Test fun `oversized payload refused before any network`() = runTest {
        server.served = 0
        val events = engine.send("x".repeat(200_000), EngineConfig("m1", maxPayloadChars = 1_000)).toList()
        assertTrue(events.single() is EngineEvent.Failed)
        assertTrue((events.single() as EngineEvent.Failed).error is ChatError.OverBudget)
        assertEquals(0, server.served)
    }

    @Test fun `history window rides along on the second send`() = runTest {
        server.lastScript = FakeChatServer.Script(200, emptyList(), flat(listOf(
            """data: {"choices":[{"delta":{"content":"ok"}}]}""", "data: [DONE]"
        )))
        engine.send("first", EngineConfig("m1")).toList()
        engine.send("second", EngineConfig("m1")).toList()
        assertTrue(server.receivedBody.contains("\"first\""))
        assertTrue(server.receivedBody.contains("\"second\""))
        // Window = user1 + assistant1("ok") + user2 = 3 messages.
        assertTrue(server.receivedBody.contains("\"ok\""))
        assertEquals(3, server.receivedBody.split("\"role\"").size - 1)
    }

    @Test fun `confirmOverBudget override sends anyway`() = runTest {
        server.lastScript = FakeChatServer.Script(200, emptyList(), flat(listOf(
            """data: {"choices":[{"delta":{"content":"sent"}}]}""", "data: [DONE]"
        )))
        server.served = 0
        val events = engine.send(
            "x".repeat(200_000),
            EngineConfig("m1", maxPayloadChars = 1_000, confirmOverBudget = true)
        ).toList()
        assertEquals(1, server.served)
        assertEquals("sent", events.filterIsInstance<EngineEvent.Delta>().joinToString("") { it.text })
        assertTrue(events.last() is EngineEvent.Completed)
    }

    @Test fun `read timeout classifies as network error`() = runTest {
        server.stallMs = 5_000 // hold the socket silent; transport must time out
        val slowEngine = ChatEngineImpl("s2", FakeUrlTransport(server.port, readTimeoutMs = 500))
        val t0 = System.currentTimeMillis()
        val events = slowEngine.send("hi", EngineConfig("m1")).toList()
        val elapsed = System.currentTimeMillis() - t0
        val failed = events.filterIsInstance<EngineEvent.Failed>().single()
        assertTrue(failed.error is ChatError.Network)
        assertTrue(failed.error.retryable)
        assertTrue("timeout fired in ${(elapsed)}ms", elapsed < 4_000)
        assertTrue(events.none { it is EngineEvent.Completed })
    }

    @Test fun `cancelled mid-stream keeps partial text in the transcript`() = runTest {
        val lines = (1..400).map { """data: {"choices":[{"delta":{"content":"ab"}}]}""" }
        server.lastScript = FakeChatServer.Script(200, emptyList(), flat(lines))
        // Real concurrency: the collector MUST run on a real dispatcher. On
        // the test's single-threaded virtual dispatcher the collector can
        // never run while the test thread spins in Thread.sleep — it would
        // block its own dispatcher and see zero events.
        val received = java.util.concurrent.CopyOnWriteArrayList<EngineEvent>()
        val job = launch(Dispatchers.IO) {
            engine.send("hi", EngineConfig("m1")).collect { received.add(it) }
        }
        // Wait for some deltas then cancel (capped spin, real server timing).
        var spins = 0
        while (received.size < 3 && spins < 600) { spins++; Thread.sleep(5) }
        job.cancelAndJoin()
        assertTrue(received.filterIsInstance<EngineEvent.Delta>().isNotEmpty())
        // Engine is cancelled — no Completed event may arrive afterwards.
        assertTrue(received.none { it is EngineEvent.Completed })
    }

    /** Transport pointing at the fake server; timeouts injectable for the stall test. */
    private class FakeUrlTransport(
        private val port: Int,
        private val connectTimeoutMs: Int = 20_000,
        private val readTimeoutMs: Int = 120_000,
    ) : ChatTransport {
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String) =
            HttpTransport(connectTimeoutMs, readTimeoutMs)
                .stream("http://127.0.0.1:$port/chat/completions", headers, payloadJson)
    }
}
