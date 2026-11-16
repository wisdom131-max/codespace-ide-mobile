package com.codespace.ide.chat.provider

import com.codespace.ide.engine.ChatEngineImpl
import com.codespace.ide.engine.ChatMessage
import com.codespace.ide.engine.ChatRole
import com.codespace.ide.engine.ChatTransport
import com.codespace.ide.engine.EngineConfig
import com.codespace.ide.engine.ExchangeStatus
import com.codespace.ide.engine.OpenAIAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 5.4 PART 2 — the producer, from the result text the user sees to the request the model gets.
 *
 * [ToolExchangeLog] is written by `ChatSession.noteAndRecord`, which is the single point where a tool
 * result is recorded, and its snapshot is handed to `ChatEngineImpl` when the next request is built.
 * The last test here closes the loop: a transcript-derived log drives a request put on the transport.
 */
class Stage54bProducerTest {

    private fun log(vararg rows: Triple<String, String, String>) = ToolExchangeLog().also { l ->
        rows.forEach { (id, name, out) -> l.record(id, name, """{"path":"a.kt"}""", out) }
    }

    private fun env() = EngineConfig(modelId = "m", providerId = "openai", baseUrl = "https://x.example", apiKey = "k")

    @Test
    fun `a done result becomes OK`() {
        val log = log(Triple("call-1", "read_file", "here is the file"))
        assertEquals(ExchangeStatus.OK, log.snapshot().single().status)
    }

    @Test
    fun `a failed result stays failed`() {
        val log = log(Triple("call-1", "read_file", "FAILED: no such file"))
        assertEquals(ExchangeStatus.FAILED, log.snapshot().single().status)
    }

    @Test
    fun `an unsure result stays uncertain and is never OK`() {
        val log = log(Triple("call-1", "write_file", ToolResultNormaliser.UNCERTAIN_PREFIX + "socket closed"))
        val ex = log.snapshot().single()
        assertEquals(ExchangeStatus.UNCERTAIN, ex.status)
        assertTrue(ex.resultText().contains("UNSURE"))
    }

    @Test
    fun `a cancelled result is not a clean failure`() {
        assertEquals(ExchangeStatus.UNCERTAIN, ToolExchangeLog.statusOf("CANCELLED by the user"))
    }

    @Test
    fun `an empty result is not evidence of success`() {
        assertEquals(ExchangeStatus.UNCERTAIN, ToolExchangeLog.statusOf("   "))
    }

    @Test
    fun `a refused call is recorded as refused, not as a failure of the tool`() {
        val log = ToolExchangeLog().also { it.recordRefused("call-9", "write_file", "{}", "not approved in Ask mode") }
        val ex = log.snapshot().single()
        assertEquals(ExchangeStatus.REFUSED, ex.status)
        assertTrue(ex.resultText().startsWith("REFUSED"))
    }

    @Test
    fun `a replayed call id is recorded once`() {
        val log = ToolExchangeLog()
        log.record("call-1", "read_file", "{}", "first")
        log.record("call-1", "read_file", "{}", "second")
        assertEquals(1, log.size)
        assertEquals("first", log.snapshot().single().output)
    }

    @Test
    fun `the provider's own id survives`() {
        val log = log(Triple("gemini-fn-77", "run_command", "ok"))
        assertEquals("gemini-fn-77", log.snapshot().single().callId)
    }

    @Test
    fun `an incomplete call is carried but marked incomplete`() {
        val log = ToolExchangeLog().also { it.record("call-1", "read_file", "{}", "ok", complete = false) }
        assertTrue(!log.snapshot().single().complete)
    }

    @Test
    fun `the log is bounded and keeps the newest calls`() {
        val log = ToolExchangeLog(limit = 3)
        repeat(6) { log.record("call-$it", "read_file", "{}", "ok") }
        assertEquals(3, log.size)
        assertEquals(listOf("call-3", "call-4", "call-5"), log.snapshot().map { it.callId })
    }

    @Test
    fun `order is the order the calls ran`() {
        val log = ToolExchangeLog()
        log.record("b", "read_file", "{}", "ok")
        log.record("a", "run_command", "{}", "ok")
        assertEquals(listOf("b", "a"), log.snapshot().map { it.callId })
    }

    @Test
    fun `the transcript-derived log drives the next request`() = runTest {
        val log = ToolExchangeLog()
        log.record("call-1", "read_file", "{}", "file contents")
        log.record("call-2", "write_file", "{}", ToolResultNormaliser.UNCERTAIN_PREFIX + "connection closed")
        var seen = ""
        val transport = object : ChatTransport {
            override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> {
                seen = payloadJson
                return flow { emit("data: [DONE]\n\n") }
            }
        }
        val engine = ChatEngineImpl("s54b", transport, OpenAIAdapter()) { log.snapshot() }
        engine.send("carry on", emptyList(), env()).toList()
        assertTrue(seen.contains(""""tool_call_id":"call-1""""))
        assertTrue(seen.contains(""""tool_call_id":"call-2""""))
        assertTrue("the uncertain write is not reported as a success", seen.contains("UNSURE"))
        assertEquals("each call appears exactly once", 2, Regex(""""tool_call_id"""").findAll(seen).count())
        // A plain user message still sends, and the message list is intact.
        assertTrue(seen.contains(""""role":"user""""))
        assertTrue(ChatMessage("1", ChatRole.USER, "x").text == "x")
    }

    @Test
    fun `a call the gate refused to run is REFUSED, not a failed tool`() {
        assertEquals(ExchangeStatus.REFUSED, ToolExchangeLog.statusOf("NOT RUN (you declined)"))
    }

    @Test
    fun `a refusal recorded first is not overwritten by a later result`() {
        val log = ToolExchangeLog()
        log.recordRefused("call-1", "write_file", "{}", "Ask mode cannot write")
        log.record("call-1", "write_file", "{}", "written anyway")
        assertEquals(ExchangeStatus.REFUSED, log.snapshot().single().status)
    }

    @Test
    fun `a refusal the model sees says the tool never ran`() = runTest {
        val log = ToolExchangeLog()
        log.recordRefused("call-1", "write_file", "{}", "NOT RUN (you declined)")
        log.record("call-2", "read_file", "{}", "contents")
        var seen = ""
        val transport = object : ChatTransport {
            override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> {
                seen = payloadJson
                return flow { emit("data: [DONE]\n\n") }
            }
        }
        ChatEngineImpl("s54c", transport, OpenAIAdapter()) { log.snapshot() }.send("go", emptyList(), env()).toList()
        assertTrue(seen.contains("REFUSED"))
        assertTrue("the refusal did not stop the real result going too", seen.contains("contents"))
    }
}
