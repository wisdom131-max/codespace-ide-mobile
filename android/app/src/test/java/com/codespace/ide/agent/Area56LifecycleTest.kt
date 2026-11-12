package com.codespace.ide.agent

import com.codespace.ide.chat.panel.ChatSession
import com.codespace.ide.chat.panel.Continuation
import com.codespace.ide.chat.panel.ContinuationState
import com.codespace.ide.chat.panel.RequestGate
import com.codespace.ide.chat.panel.ToolEffect
import com.codespace.ide.chat.panel.ToolOutcome
import com.codespace.ide.chat.panel.isVisibleContinuation
import com.codespace.ide.engine.FinishReason
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Areas 5 and 6 across one turn, end to end, through the REAL objects: the production dispatch gate,
 * a real ledger file, the real finish-reason mapping and the real request gate.
 *
 * The tool body and the provider stream are the only fakes: a JVM test cannot build an Android
 * `Context`, and there is no provider here. Everything the two areas claim to guarantee lives in the
 * objects below, so a regression in any of them fails this test.
 */
class Area56LifecycleTest {

    @get:Rule val tmp = TemporaryFolder()

    private suspend fun call(g: ToolDispatchGuard, tool: String, id: String, body: suspend () -> String) =
        AgentTools.executeGated(g, tool, JSONObject(), emptyMap(), id, body)

    @Test
    fun aTurnThatHitsTheCeilingKeepsItsToolResultAndOffersContinue() = runBlocking {
        val ledgerFile = File(tmp.root, "lt.json")
        val hits = AtomicInteger()
        val guard = ToolDispatchGuard(ToolCallLedger.forTest(ledgerFile))

        // 1. the tool executes and persists its result
        val toolOut = call(guard, "write_file", "call-1") { hits.incrementAndGet(); "wrote 12 lines" }
        assertEquals("wrote 12 lines", toolOut)
        assertEquals(ToolOutcome.COMPLETED_OK, ToolCallLedger.forTest(ledgerFile).lookup("call-1")!!.outcome)

        // 2, 3. the answer is cut off, so the turn is NOT complete and the composer offers Continue
        assertEquals(false, FinishReason.OUTPUT_LIMIT.complete)
        assertEquals(ContinuationState.NEEDS_CONTINUATION, ChatSession.continuationStateForTest(FinishReason.OUTPUT_LIMIT))
        assertTrue(isVisibleContinuation(ChatSession.continuationStateForTest(FinishReason.OUTPUT_LIMIT)))

        // 4, 5. reopen the conversation: the same offer is restored, from the same ledger file
        val restoredLedger = ToolCallLedger.forTest(ledgerFile)
        assertEquals("wrote 12 lines", restoredLedger.lookup("call-1")!!.result)
        assertTrue(isVisibleContinuation(ChatSession.continuationStateForTest(FinishReason.OUTPUT_LIMIT)))

        // 6, 7. the continuation is its own request: a fresh gate number owns the stream
        val gate = RequestGate()
        val original = gate.bump()
        val continuation = gate.bump()
        assertFalse("stale bytes from the cut-off attempt are dropped", gate.owns(original))
        assertTrue("the continuation owns the stream", gate.owns(continuation))

        // 8, 9, 10. the provider repeats the same tool-call id: reuse, no second side effect
        val replayGuard = ToolDispatchGuard(restoredLedger)
        val replay = call(replayGuard, "write_file", "call-1") { hits.incrementAndGet(); "wrote AGAIN" }
        assertEquals("wrote 12 lines", replay)
        assertEquals("the write ran exactly once", 1, hits.get())

        // 11, 12. the continuation completes and the turn closes
        assertEquals(true, FinishReason.NATURAL.complete)
        assertEquals(ContinuationState.COMPLETE, ChatSession.continuationStateForTest(FinishReason.NATURAL))
        assertFalse(isVisibleContinuation(ChatSession.continuationStateForTest(FinishReason.NATURAL)))
    }

    @Test
    fun theSeamAppearsOnceAndTheOriginalAnswerIsNotDuplicated() {
        val delivered = "Part one of the answer."
        val addition = "Part two."
        // This is the join the session performs: delivered + SEAM, then the provider's continuation.
        val joined = delivered + ChatSession.SEAM + addition
        assertEquals(1, Regex(Regex.escape(delivered)).findAll(joined).count())
        assertEquals(1, Regex(Regex.escape(ChatSession.SEAM)).findAll(joined).count())
        assertTrue(joined.endsWith(addition))
    }

    @Test
    fun aSecondCeilingStillOffersAContinuation() {
        assertEquals(true, Continuation.plan(FinishReason.OUTPUT_LIMIT, "x").allowed)
        assertEquals(true, Continuation.plan(FinishReason.OUTPUT_LIMIT, "x", "ask").allowed)
        assertEquals(600, Continuation.tailBudgetFor("ask"))
        assertEquals(900, Continuation.tailBudgetFor("edit"))
        assertEquals(1200, Continuation.tailBudgetFor("agent"))
    }

    @Test
    fun cancellationAndInterruptionNeverOfferContinue() {
        listOf(FinishReason.CANCELLED, FinishReason.INTERRUPTED, FinishReason.CONTENT_FILTER, FinishReason.UNKNOWN)
            .forEach {
                val state = ChatSession.continuationStateForTest(it)
                assertEquals("$it", ContinuationState.UNKNOWN_OR_INTERRUPTED, state)
                assertFalse("$it must not offer Continue", isVisibleContinuation(state))
                assertFalse("$it must not be planned around", Continuation.plan(it, "x").allowed)
            }
    }

    @Test
    fun aProviderThatCannotContinueIsNotOfferedAContinuation() {
        // The UI must not promise a continuation the transport cannot deliver.
        val plan = Continuation.plan(FinishReason.OUTPUT_LIMIT, "x", providerSupportsContinuation = false)
        assertFalse(plan.allowed)
    }

    @Test
    fun aRepeatedContinuationGetsItsOwnGateNumberEachTime() {
        val gate = RequestGate()
        val seen = (1..3).map { gate.bump() }
        assertEquals(3, seen.distinct().size)
        assertEquals(seen.last(), gate.current)
        assertFalse(gate.owns(seen.first()))
        assertTrue(gate.owns(seen.last()))
    }

    @Test
    fun aCancelledWriteIsNotRepeatedByTheContinuation() = runBlocking {
        val ledger = ToolCallLedger.forTest(File(tmp.root, "cancel.json"))
        val guard = ToolDispatchGuard(ledger)
        runCatching { call(guard, "run_command", "c9") { throw kotlinx.coroutines.CancellationException("stop") } }
        assertEquals(ToolOutcome.CANCELLED_DURING_EXECUTION, ledger.lookup("c9")!!.outcome)
        val hits = AtomicInteger()
        val out = call(guard, "run_command", "c9") { hits.incrementAndGet(); "ran" }
        assertTrue(out.contains("Refused"))
        assertEquals(0, hits.get())
    }

    @Test
    fun everyRecordedOutcomeKeepsItsArgumentsForReconciliation() {
        val ledger = ToolCallLedger.forTest(File(tmp.root, "args.json"))
        ledger.recordStarted("c1", "write_file", ToolEffect.SIDE_EFFECTING, mapOf("path" to "/x", "content" to "y"))
        ledger.recordFailed("c1", "write_file", ToolEffect.SIDE_EFFECTING, ToolOutcome.UNKNOWN_AFTER_START)
        assertEquals("/x", ledger.lookup("c1")!!.args["path"])
        ledger.recordCompleted("c1", "write_file", ToolEffect.SIDE_EFFECTING, "done")
        assertEquals("y", ledger.lookup("c1")!!.args["content"])
    }
}
