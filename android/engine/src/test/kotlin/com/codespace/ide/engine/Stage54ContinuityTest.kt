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
 * STAGE 5.4 — a tool call and its result reach the next provider request as a pair.
 *
 * Before this stage the history was role + text only, so the model never received the pairing a
 * provider needs (Anthropic requires it, OpenAI keys on ids) and a lost call looked like prose.
 */
class Stage54ContinuityTest {

    private val ok = ToolExchange("call-1", "read_file", """{"path":"a.kt"}""", ExchangeStatus.OK, "contents")
    private val failed = ToolExchange("call-2", "write_file", """{"path":"b.kt"}""", ExchangeStatus.FAILED, "denied")
    private val unsure = ToolExchange("call-3", "write_file", """{"path":"c.kt"}""", ExchangeStatus.UNCERTAIN, "socket closed")
    private val refused = ToolExchange("call-4", "run_command", """{"command":"rm"}""", ExchangeStatus.REFUSED, "not approved")
    private val incomplete = ToolExchange("call-5", "read_file", """{"path":"d""", ExchangeStatus.OK, "x", complete = false)
    private val env = EngineConfig(modelId = "m", providerId = "p", baseUrl = "https://x.example", apiKey = "k")

    private fun ok(r: RenderedHistory) = (r as RenderedHistory.Ok).json

    @Test
    fun `gemini gets a functionCall turn and a functionResponse turn`() {
        val json = ok(ConversationContinuity.gemini(listOf(ok)))
        assertTrue(json.contains(""""functionCall":{"name":"read_file""""))
        assertTrue(json.contains(""""functionResponse":{"name":"read_file""""))
        assertTrue("the call turn comes before the response turn", json.indexOf("functionCall") < json.indexOf("functionResponse"))
    }

    @Test
    fun `openai gets tool_calls and one tool message per id`() {
        val json = ok(ConversationContinuity.openAI(listOf(ok, failed)))
        assertTrue(json.contains(""""tool_calls":"""))
        assertTrue(json.contains(""""id":"call-1""""))
        assertTrue(json.contains(""""role":"tool","tool_call_id":"call-1""""))
        assertTrue(json.contains(""""role":"tool","tool_call_id":"call-2""""))
        assertEquals("call ids are not dropped or merged", 2, Regex(""""role":"tool"""").findAll(json).count())
    }

    @Test
    fun `anthropic pairs tool_use with tool_result and flags an error`() {
        val json = ok(ConversationContinuity.anthropic(listOf(ok, failed)))
        assertTrue(json.contains(""""tool_use_id":"call-1""""))
        assertTrue(json.contains(""""is_error":true"""))
        assertEquals("only the failed result is an error",
            1, Regex(""""is_error":true""").findAll(json).count())
    }

    @Test
    fun `a failed or uncertain result never reads as success`() {
        assertTrue(failed.resultText().startsWith("FAILED"))
        assertTrue(unsure.resultText().contains("UNSURE"))
        assertTrue(refused.resultText().startsWith("REFUSED"))
        val json = ok(ConversationContinuity.openAI(listOf(unsure)))
        assertTrue(json.contains("UNSURE"))
    }

    @Test
    fun `an incomplete call is never rendered as a call`() {
        val rendered = ConversationContinuity.openAI(listOf(incomplete))
        assertFalse(ok(rendered).contains("tool_calls"))
        assertTrue((rendered as RenderedHistory.Ok).notes.isNotEmpty())
    }

    @Test
    fun `an unknown endpoint refuses rather than dropping the results`() {
        val rendered = ConversationContinuity.render(null, listOf(ok))
        assertTrue(rendered is RenderedHistory.Refused)
        assertTrue((rendered as RenderedHistory.Refused).refusal.reason.contains("unknown"))
    }

    @Test
    fun `an unknown endpoint with nothing to send is not refused`() {
        assertTrue(ConversationContinuity.render(null, emptyList()) is RenderedHistory.Ok)
    }

    @Test
    fun `duplicate ids collapse to the first occurrence`() {
        val dup = ok.copy(output = "second")
        val out = ConversationContinuity.dedupe(listOf(ok, dup))
        assertEquals(1, out.size)
        assertEquals("contents", out[0].output)
    }

    @Test
    fun `splice appends at the end of the array even when text contains a bracket`() {
        val payload = """{"messages":[{"role":"user","content":"a ] b"}]}"""
        val out = ConversationContinuity.splice(payload, "messages", """{"role":"tool","content":"x"}""")
        assertTrue(out.endsWith("""{"role":"user","content":"a ] b"},{"role":"tool","content":"x"}]}"""))
    }

    @Test
    fun `splice does nothing when the key is absent`() {
        val payload = """{"contents":[]}"""
        assertEquals(payload, ConversationContinuity.splice(payload, "messages", """{"a":1}"""))
    }

    // ---- the same thing through the adapters the engine actually uses ------------------------

    @Test
    fun `the openai adapter body carries the pair`() {
        val body = OpenAIAdapter().payloadWithHistory(listOf(ChatMessage("1", ChatRole.USER, "hi")), "next", emptyList(), env, listOf(ok))
        assertTrue(body.contains(""""tool_calls":"""))
        assertTrue(body.contains(""""tool_call_id":"call-1""""))
    }

    @Test
    fun `the anthropic adapter body carries the pair`() {
        val body = AnthropicAdapter().payloadWithHistory(listOf(ChatMessage("1", ChatRole.USER, "hi")), "next", emptyList(), env, listOf(ok))
        assertTrue(body.contains(""""type":"tool_use""""))
        assertTrue(body.contains(""""type":"tool_result""""))
    }

    @Test
    fun `the gemini adapter body carries the pair`() {
        val body = GeminiAdapter().payloadWithHistory(listOf(ChatMessage("1", ChatRole.USER, "hi")), "next", emptyList(), env, listOf(ok))
        assertTrue(body.contains(""""functionCall""""))
        assertTrue(body.contains(""""functionResponse""""))
    }

    @Test
    fun `an adapter that has not opted in sends the plain history, unchanged`() {
        val plain = object : ProviderAdapter {
            override val providerId: String = "plain"
            override fun endpoint(config: EngineConfig) = "https://x.example/v1"
            override fun headers(config: EngineConfig) = emptyMap<String, String>()
            override fun payload(window: List<ChatMessage>, userText: String, config: EngineConfig) = """{"messages":[]}"""
            override fun extractDelta(data: String): String? = null
            override fun endReason(data: String): String? = null
            override fun isDoneSentinel(data: String) = false
        }
        assertEquals("""{"messages":[]}""",
            plain.payloadWithHistory(emptyList(), "x", emptyList(), env, listOf(ok)))
    }

    @Test
    fun `the engine sends the pair on the wire`() = runTest {
        var seen = ""
        val transport = object : ChatTransport {
            override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> {
                seen = payloadJson
                return flow { emit("data: [DONE]\n\n") }
            }
        }
        val engine = ChatEngineImpl("s54", transport, OpenAIAdapter()) { listOf(ok, unsure) }
        engine.send("next", emptyList(), env).toList()
        assertTrue("the request carries the calls", seen.contains(""""tool_call_id":"call-1""""))
        assertTrue("and the unsure one says so", seen.contains("UNSURE"))
    }
}
