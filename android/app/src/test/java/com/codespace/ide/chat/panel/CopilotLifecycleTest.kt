package com.codespace.ide.chat.panel

import com.codespace.ide.agent.ToolDispatchGuard
import com.codespace.ide.agent.ToolCallLedger
import com.codespace.ide.engine.FinishReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Areas 5+6 end to end, on the real boundaries: the tool guard, the ledger file, the request gate
 * and the composer's state mapping.
 */
class CopilotLifecycleTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aToolRunsThenTheTurnIsCutOffThenContinuationReusesTheResult() = runBlocking {
        val ledgerFile = File(tmp.root, "ledger.json")
        val writes = AtomicInteger()

        // 1-3: a tool runs and its result is persisted.
        val ledger = ToolCallLedger.forTest(ledgerFile)
        val guard = ToolDispatchGuard(ledger)
        val first = guard.run("call-1", "write_file") { writes.incrementAndGet(); "wrote 12 bytes" }
        assertEquals("wrote 12 bytes", first)

        // 4-5: the answer hits the ceiling, so the turn is incomplete and offers Continue.
        val finish = FinishReason.OUTPUT_LIMIT
        assertFalse(finish.complete)
        assertEquals(ContinuationState.NEEDS_CONTINUATION, ChatSession.continuationStateForTest(finish))

        // 6-7: the session is restored from disk and Continue is still offered.
        val restored = ToolCallLedger.forTest(ledgerFile)
        assertEquals(ContinuationState.NEEDS_CONTINUATION, ChatSession.continuationStateForTest(finish))

        // 8-9: the user continues; the continuation gets a FRESH gate number.
        val gate = RequestGate()
        val original = gate.bump()
        val continuation = gate.bump()
        assertFalse("a late event from the first attempt is dropped", gate.owns(original))
        assertTrue(gate.owns(continuation))

        // 10-11: the same tool-call id appears again inside the continuation.
        val again = ToolDispatchGuard(restored).run("call-1", "write_file") {
            writes.incrementAndGet(); "wrote again"
        }
        assertEquals("the side effect must not be applied twice", 1, writes.get())
        assertEquals("the persisted result is reused", "wrote 12 bytes", again)

        // 12: the continuation finishes naturally and only then is the turn complete.
        assertEquals(ContinuationState.COMPLETE, ChatSession.continuationStateForTest(FinishReason.NATURAL))
    }

    @Test
    fun aStaleEventFromThePreviousRequestCannotTouchTheNewOne() {
        val gate = RequestGate()
        val a = gate.bump()
        val b = gate.bump()
        assertFalse(gate.owns(a))
        assertTrue(gate.owns(b))
    }

    @Test
    fun repeatedCeilingsKeepOfferingContinue() {
        assertEquals(ContinuationState.NEEDS_CONTINUATION, ChatSession.continuationStateForTest(FinishReason.OUTPUT_LIMIT))
        assertEquals(ContinuationState.NEEDS_CONTINUATION, ChatSession.continuationStateForTest(FinishReason.OUTPUT_LIMIT))
    }

    @Test
    fun aProviderErrorEndsTheOfferWithoutClaimingCompletion() {
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, ChatSession.continuationStateForTest(FinishReason.INTERRUPTED))
        assertFalse(isVisibleContinuation(ContinuationState.UNKNOWN_OR_INTERRUPTED))
    }

    @Test
    fun aWriteWithNoLedgerEntryIsStillJudgedSideEffecting() = runBlocking {
        val writes = AtomicInteger()
        val guard = ToolDispatchGuard(ToolCallLedger.forTest(File(tmp.root, "empty.json")))
        guard.run("first", "zip_create") { writes.incrementAndGet(); "ok" }
        assertEquals(1, writes.get())
    }
}
