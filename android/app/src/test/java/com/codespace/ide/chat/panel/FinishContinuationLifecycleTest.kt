package com.codespace.ide.chat.panel

import com.codespace.ide.engine.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Areas 5+6 together: a ceiling must produce an incomplete turn, a continuation that keeps the
 * request owned, and no second execution of a write that already ran.
 */
class FinishContinuationLifecycleTest {

    @Test
    fun aCeilingIsNeverAClosedTurn() {
        val f = FinishReason.OUTPUT_LIMIT
        assertFalse("a ceiling may not be persisted as complete", f.complete)
        assertTrue(Continuation.plan(f, "half an answer").allowed)
    }

    @Test
    fun theContinuationInheritsTheTurnAndKeepsItsOwnership() {
        val gate = RequestGate()
        val first = gate.bump()
        val second = gate.bump() // the continuation is a NEW request
        assertFalse("the first attempt's late bytes are dropped", gate.owns(first))
        assertTrue("the continuation owns the stream", gate.owns(second))
    }

    @Test
    fun aToolThatAlreadyRanIsNotReRunByContinuation() {
        val d = ToolRetry.decide(ToolOutcome.RESULT_PERSISTED, ToolEffect.SIDE_EFFECTING)
        assertEquals(RetryDecision.REUSE_PERSISTED, d)
        assertFalse(ToolRetry.dangerousToRepeat(ToolOutcome.RESULT_PERSISTED, ToolEffect.SIDE_EFFECTING))
    }

    @Test
    fun anUnknownWriteOutcomeStopsAContinuationFromRepeatingIt() {
        assertTrue(ToolRetry.dangerousToRepeat(ToolOutcome.UNKNOWN_AFTER_START, ToolEffect.SIDE_EFFECTING))
        assertEquals(RetryDecision.RECONCILE_FIRST, ToolRetry.decide(ToolOutcome.UNKNOWN_AFTER_START, ToolEffect.SIDE_EFFECTING))
    }

    @Test
    fun onlyAJustifiedCompletionClosesTheTurn() {
        assertTrue(FinishReason.NATURAL.complete)
        assertTrue(FinishReason.STOP_SEQUENCE.complete)
        assertTrue(FinishReason.TOOL_CALLS.complete)
        listOf(FinishReason.OUTPUT_LIMIT, FinishReason.CONTENT_FILTER, FinishReason.INTERRUPTED,
            FinishReason.CANCELLED, FinishReason.UNKNOWN).forEach {
            assertFalse("$it must not close a turn", it.complete)
        }
    }

    @Test
    fun aSilentStreamEndingCannotCloseATurn() {
        val f = FinishReason.normalize("openai", null)
        assertEquals(FinishReason.UNKNOWN, f)
        assertFalse(f.complete)
        assertFalse("unknown is not continued either", Continuation.plan(f, "x").allowed)
    }
}
