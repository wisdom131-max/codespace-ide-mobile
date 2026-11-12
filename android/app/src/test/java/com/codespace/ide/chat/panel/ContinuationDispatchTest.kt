package com.codespace.ide.chat.panel

import com.codespace.ide.engine.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Area 6 dispatch contract: what continueTurn() may and may not do. The session calls these
 * rules directly, so they are asserted against the same plan object dispatch uses.
 */
class ContinuationDispatchTest {

    private fun plan(f: FinishReason, text: String = "half") = Continuation.plan(f, text, "agent")

    @Test fun onlyACeilingIsDispatchable() {
        assertTrue(plan(FinishReason.OUTPUT_LIMIT).allowed)
        listOf(FinishReason.NATURAL, FinishReason.UNKNOWN, FinishReason.CANCELLED,
            FinishReason.INTERRUPTED, FinishReason.CONTENT_FILTER, FinishReason.STOP_SEQUENCE,
            FinishReason.TOOL_CALLS)
            .forEach { assertFalse("$it must not dispatch a continuation", plan(it).allowed) }
    }

    @Test fun theSeamIsVisibleSoTwoAnswersAreNotPassedOffAsOne() {
        assertTrue(ChatSession.SEAM.isNotBlank())
        assertTrue(ChatSession.SEAM.contains("continued"))
    }

    @Test fun aContinuationNeverRepeatsDeliveredText() {
        val p = plan(FinishReason.OUTPUT_LIMIT, "the first half of the answer")
        assertTrue(p.instruction.contains("Do not repeat"))
        assertTrue("the delivered tail travels as context", p.instruction.contains("the first half"))
    }

    @Test fun aContinuationNeverReRunsATool() {
        val p = plan(FinishReason.OUTPUT_LIMIT)
        assertTrue(p.instruction.contains("already run"))
    }

    @Test fun continuationItselfHittingACeilingIsStillNotComplete() {
        // A continuation that ends at the ceiling again is another incomplete turn.
        assertFalse(FinishReason.OUTPUT_LIMIT.complete)
        assertTrue(plan(FinishReason.OUTPUT_LIMIT).allowed)
    }

    @Test fun aProviderWithoutContinuationGetsAnInstructionNotSilence() {
        val p = Continuation.plan(FinishReason.OUTPUT_LIMIT, "x", "agent", providerSupportsContinuation = false)
        assertEquals(ContinuationState.NEEDS_CONTINUATION, p.state)
        assertTrue(p.instruction.isNotBlank())
        assertFalse(p.allowed)
    }
}
