package com.codespace.ide.chat.provider

import com.codespace.ide.chat.panel.ParsedToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT FIVE, STAGE 5.2 — three wire formats in, one shape out, and one way to run a call. */
class Stage52CrossProviderTest {

    private val known = setOf("read_file", "write_file", "run_command")

    // ---- A. normalisation across the three provider dialects ---------------------------------

    @Test
    fun geminiFunctionCallBecomesANormalisedCall() {
        val json = JSONObject(
            """{"candidates":[{"content":{"parts":[{"text":"reading"},{"functionCall":
            {"name":"read_file","args":{"path":"a.kt"}}}]}}]}"""
        )
        val calls = ToolCallNormaliser.fromGemini(json)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("gemini", calls[0].provider)
        assertEquals("a.kt", JSONObject(calls[0].argumentsJson).getString("path"))
        assertTrue(calls[0].complete)
        assertTrue(calls[0].id.startsWith("call-gemini-"))
    }

    @Test
    fun openAiToolCallsKeepTheProviderIdAndArgumentsString() {
        val json = JSONObject(
            """{"choices":[{"message":{"tool_calls":[{"id":"call_9","function":
            {"name":"write_file","arguments":"{\"path\":\"b.kt\",\"text\":\"x\"}"}},
            {"id":"call_10","function":{"name":"read_file","arguments":"{\"path\":\"c.kt\"}"}}]}}]}"""
        )
        val calls = ToolCallNormaliser.fromOpenAI(json)
        assertEquals(listOf("call_9", "call_10"), calls.map { it.id })
        assertEquals("write_file", calls[0].name)
        assertEquals("c.kt", JSONObject(calls[1].argumentsJson).getString("path"))
    }

    @Test
    fun anthropicToolUseBlocksBecomeCallsAndTextBlocksDoNot() {
        val json = JSONObject(
            """{"content":[{"type":"text","text":"let me look"},
            {"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"d.kt"}}]}"""
        )
        val calls = ToolCallNormaliser.fromAnthropic(json)
        assertEquals(1, calls.size)
        assertEquals("toolu_1", calls[0].id)
        assertEquals("anthropic", calls[0].provider)
    }

    @Test
    fun aAndaHalfThreeProvidersAgreeOnTheSameIdentity() {
        val g = ToolCallNormaliser.fromGemini(
            JSONObject("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{"path":"x"}}}]}}]}""")
        ).single()
        val o = ToolCallNormaliser.fromOpenAI(
            JSONObject("""{"choices":[{"message":{"tool_calls":[{"id":"c1","function":{"name":"read_file","arguments":"{\"path\":\"x\"}"}}]}}]}""")
        ).single()
        assertEquals("same name and arguments, same verdict regardless of provider",
            ToolCallNormaliser.verdict(g, known), ToolCallNormaliser.verdict(o, known))
    }

    @Test
    fun multipleCallsInOneResponseAllArrive() {
        val calls = ToolCallNormaliser.fromWire("copilot", listOf(
            ParsedToolCall("read_file", """{"path":"a"}"""),
            ParsedToolCall("read_file", """{"path":"b"}"""),
        ))
        assertEquals(2, calls.size)
        assertEquals(2, calls.map { it.id }.toSet().size)
    }

    @Test
    fun anUnclosedArgumentObjectIsNotACompleteCall() {
        val call = ToolCallNormaliser.fromWire("copilot", listOf(ParsedToolCall("write_file", """{"path":"a","text":"x""")))
        assertFalse(call[0].complete)
        assertTrue(ToolCallNormaliser.verdict(call[0], known) is CallVerdict.Rejected)
    }

    @Test
    fun aCallWithNoNameIsRejected() {
        val call = ToolCallNormaliser.fromWire("copilot", listOf(ParsedToolCall("", "{}")))
        assertFalse(call[0].complete)
        assertTrue(ToolCallNormaliser.verdict(call[0], known) is CallVerdict.Rejected)
    }

    // ---- B. argument validation and the registry check ---------------------------------------

    @Test
    fun anUnknownToolNeverReachesTheExecutor() = runBlocking {
        var called = false
        val outcome = ToolExecutionGateway.run(
            ToolCallNormaliser.fromWire("copilot", listOf(ParsedToolCall("deploy_everything", "{}"))).single(),
            known, HashSet(),
        ) { _, _, _ -> called = true; "should not happen" }
        assertFalse(outcome.executed)
        assertFalse(called)
        assertTrue(outcome.refusal!!.contains("no such tool"))
    }

    @Test
    fun argumentsThatAreNotAJsonObjectAreRejected() {
        val call = NormalisedToolCall("id-1", "read_file", "not json", "copilot", complete = true)
        assertTrue(ToolCallNormaliser.verdict(call, known) is CallVerdict.Rejected)
    }

    @Test
    fun duplicateIdsInOneRoundAreDroppedSafely() {
        val a = NormalisedToolCall("dup", "read_file", """{"path":"a"}""", "copilot", true)
        val b = NormalisedToolCall("dup", "read_file", """{"path":"a"}""", "copilot", true)
        val (kept, dropped) = ToolCallNormaliser.uniqueById(listOf(a, b))
        assertEquals(1, kept.size)
        assertEquals(listOf("dup"), dropped)
    }

    // ---- C. the single execution gate --------------------------------------------------------

    @Test
    fun theGatewayRunsThroughTheExecutorItIsGivenAndNormalisesTheResult() = runBlocking {
        var seenArgs: String? = null
        val call = ToolCallNormaliser.fromWire("copilot", listOf(ParsedToolCall("read_file", """{"path":"a.kt"}"""))).single()
        val outcome = ToolExecutionGateway.run(call, known, HashSet()) { name, args, _ ->
            seenArgs = "$name:$args"; "file contents"
        }
        assertTrue(outcome.executed)
        assertEquals("read_file", outcome.call.name)
        assertEquals("""read_file:{"path":"a.kt"}""", seenArgs)
        assertEquals(ToolOutcome.SUCCESS, outcome.result!!.outcome)
        assertEquals("file contents", outcome.result!!.result)
        assertEquals(call.id, outcome.result!!.callId)
    }

    @Test
    fun aFailedExecutionStaysFailedAndCarriesItsError() = runBlocking {
        val call = NormalisedToolCall("id-2", "write_file", """{"path":"a","text":"b"}""", "copilot", true)
        val outcome = ToolExecutionGateway.run(call, known, HashSet()) { _, _, _ -> "FAILED: disk full" }
        assertEquals(ToolOutcome.FAILED, outcome.result!!.outcome)
        assertTrue(outcome.result!!.error!!.contains("disk full"))
    }

    @Test
    fun aThrowingExecutorBecomesAFailureNotASuccess() = runBlocking {
        val call = NormalisedToolCall("id-3", "run_command", """{"command":"x"}""", "copilot", true)
        val outcome = ToolExecutionGateway.run(call, known, HashSet()) { _, _, _ -> throw IllegalStateException("boom") }
        assertEquals(ToolOutcome.FAILED, outcome.result!!.outcome)
    }

    @Test
    fun aRepeatedCallInTheSameRoundRunsOnce() = runBlocking {
        var runs = 0
        val claimed = HashSet<String>()
        val call = NormalisedToolCall("id-4", "write_file", """{"path":"z","text":"t"}""", "copilot", true)
        ToolExecutionGateway.run(call, known, claimed) { _, _, _ -> runs++; "ok" }
        val second = ToolExecutionGateway.run(call, known, claimed) { _, _, _ -> runs++; "ok" }
        assertEquals(1, runs)
        assertFalse(second.executed)
        assertTrue(second.refusal!!.contains("already ran"))
    }

    // ---- D. streaming normalisation ----------------------------------------------------------

    @Test
    fun openAiArgumentFragmentsAssembleIntoOneCompleteCall() {
        val n = ProviderStreamNormaliser(StreamProvider.OPENAI)
        val events = mutableListOf<StreamEvent>()
        events += n.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"write_file","arguments":"{\"path\":\"a\","}}]}}]}""")
        events += n.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"text\":\"hi\"}"}}]}}]}""")
        events += n.feed("""{"choices":[{"finish_reason":"tool_calls"}]}""")
        val completed = events.filterIsInstance<StreamEvent.ToolCallCompleted>().single()
        assertEquals("write_file", completed.call.name)
        assertTrue(completed.call.complete)
        assertEquals("a", JSONObject(completed.call.argumentsJson).getString("path"))
        assertEquals(StreamEnd.TOOL_CALLS, events.filterIsInstance<StreamEvent.Ended>().single().reason)
    }

    @Test
    fun aStreamCutMidArgumentsNeverCompletesTheCall() {
        val n = ProviderStreamNormaliser(StreamProvider.OPENAI)
        n.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_b","function":{"name":"write_file","arguments":"{\"path\":\"a\","}}]}}]}""")
        val tail = n.finish()
        assertTrue(tail.none { it is StreamEvent.ToolCallCompleted })
        assertEquals(StreamEnd.INCOMPLETE_TOOL_CALL, tail.filterIsInstance<StreamEvent.Ended>().single().reason)
        assertTrue(n.ended)
    }

    @Test
    fun anthropicPartialJsonDeltasCompleteOnBlockStop() {
        val n = ProviderStreamNormaliser(StreamProvider.ANTHROPIC)
        val events = mutableListOf<StreamEvent>()
        events += n.feed("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_2","name":"read_file","input":{}}}""")
        events += n.feed("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""")
        events += n.feed("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"e.kt\"}"}}""")
        events += n.feed("""{"type":"content_block_stop","index":0}""")
        events += n.feed("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        val call = events.filterIsInstance<StreamEvent.ToolCallCompleted>().single().call
        assertTrue(call.complete)
        assertEquals("e.kt", JSONObject(call.argumentsJson).getString("path"))
        assertEquals(StreamEnd.TOOL_CALLS, events.filterIsInstance<StreamEvent.Ended>().single().reason)
    }

    @Test
    fun geminiTextAndFinishReasonAreNormalised() {
        val n = ProviderStreamNormaliser(StreamProvider.GEMINI)
        val a = n.feed("""{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}""")
        val b = n.feed("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")
        assertEquals("hello", a.filterIsInstance<StreamEvent.TextDelta>().single().text)
        assertEquals(StreamEnd.OUTPUT_LIMIT, b.filterIsInstance<StreamEvent.Ended>().single().reason)
    }

    @Test
    fun anOutputLimitIsNotReportedAsANaturalFinish() {
        val n = ProviderStreamNormaliser(StreamProvider.OPENAI)
        n.feed("""{"choices":[{"delta":{"content":"half an ans"}}]}""")
        val end = n.feed("""{"choices":[{"finish_reason":"length"}]}""").filterIsInstance<StreamEvent.Ended>().single()
        assertEquals(StreamEnd.OUTPUT_LIMIT, end.reason)
    }

    @Test
    fun aReplayedToolCallStartIsNotExecutedTwice() {
        val n = ProviderStreamNormaliser(StreamProvider.GEMINI)
        val chunk = """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"fc1","name":"read_file","args":{"path":"a"}}}]}}]}"""
        val first = n.feed(chunk).filterIsInstance<StreamEvent.ToolCallCompleted>()
        val second = n.feed(chunk).filterIsInstance<StreamEvent.ToolCallCompleted>()
        assertEquals(1, first.size)
        assertTrue("a replayed chunk produces no second call", second.isEmpty())
    }

    @Test
    fun aProviderErrorBecomesAFailedEventNotAnAnswer() {
        val n = ProviderStreamNormaliser(StreamProvider.ANTHROPIC)
        val events = n.feed("""{"type":"error","error":{"message":"overloaded"}}""")
        assertTrue(events.single() is StreamEvent.Failed)
        assertFalse(n.ended)
    }

    @Test
    fun cancellationEndsTheStreamAndLateChunksAreIgnored() {
        val n = ProviderStreamNormaliser(StreamProvider.OPENAI)
        val cancelled = n.cancel()
        assertTrue(cancelled.any { it is StreamEvent.Cancelled })
        assertEquals(StreamEnd.CANCELLED, cancelled.filterIsInstance<StreamEvent.Ended>().single().reason)
        assertTrue(n.feed("""{"choices":[{"delta":{"content":"late"}}]}""").isEmpty())
    }

    @Test
    fun aStreamThatJustStopsIsUnknownNotComplete() {
        val n = ProviderStreamNormaliser(StreamProvider.GEMINI)
        n.feed("""{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}""")
        assertEquals(StreamEnd.UNKNOWN, n.finish().filterIsInstance<StreamEvent.Ended>().single().reason)
    }

    @Test
    fun absurdArgumentGrowthIsRefusedRatherThanBuffered() {
        val n = ProviderStreamNormaliser(StreamProvider.OPENAI)
        n.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"big","function":{"name":"write_file","arguments":"{"}}]}}]}""")
        var failed = false
        repeat(40) {
            if (n.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"${"a".repeat(4_000)}"}}]}}]}""")
                    .any { e -> e is StreamEvent.Failed }
            ) failed = true
        }
        assertTrue("the buffer is bounded", failed)
    }

    @Test
    fun usageMetadataSurvivesNormalisation() {
        val n = ProviderStreamNormaliser(StreamProvider.OPENAI)
        val u = n.feed("""{"choices":[{"delta":{"content":"x"}}],"usage":{"prompt_tokens":11,"completion_tokens":7}}""")
            .filterIsInstance<StreamEvent.Usage>().single()
        assertEquals(11, u.inputTokens)
        assertEquals(7, u.outputTokens)
    }
}
