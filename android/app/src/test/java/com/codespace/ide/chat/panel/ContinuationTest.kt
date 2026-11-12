package com.codespace.ide.chat.panel

import com.codespace.ide.engine.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuationTest {

    @Test fun aFinishedAnswerNeedsNoContinuation() {
        val p = Continuation.plan(FinishReason.NATURAL, "done")
        assertFalse(p.allowed)
        assertEquals(ContinuationState.COMPLETE, p.state)
        assertEquals("", p.instruction)
    }

    @Test fun aCeilingProducesAContinuationThatForbidsRepetition() {
        val p = Continuation.plan(FinishReason.OUTPUT_LIMIT, "the first half")
        assertTrue(p.allowed)
        assertEquals(ContinuationState.CONTINUING, p.state)
        assertTrue(p.instruction.contains("Do not repeat"))
        assertTrue(p.instruction.contains("the first half"))
    }

    @Test fun aCeilingContinuationForbidsRepeatingTools() {
        val p = Continuation.plan(FinishReason.OUTPUT_LIMIT, "x")
        assertTrue(p.instruction.contains("already run"))
    }

    @Test fun theCarriedTailIsBoundedPerMode() {
        val long = "A".repeat(20_000)
        assertTrue(Continuation.plan(FinishReason.OUTPUT_LIMIT, long, "ask").carriedContext.length <= 600)
        assertTrue(Continuation.plan(FinishReason.OUTPUT_LIMIT, long, "edit").carriedContext.length <= 900)
        assertTrue(Continuation.plan(FinishReason.OUTPUT_LIMIT, long, "agent").carriedContext.length <= Continuation.TAIL_CHARS)
    }

    @Test fun aFilterIsNotContinuedAround() {
        val p = Continuation.plan(FinishReason.CONTENT_FILTER, "x")
        assertFalse(p.allowed)
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, p.state)
    }

    @Test fun anInterruptedStreamIsNotContinued() {
        assertFalse(Continuation.plan(FinishReason.INTERRUPTED, "x").allowed)
    }

    @Test fun unknownTerminationIsNotContinued() {
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, Continuation.plan(FinishReason.UNKNOWN, "x").state)
    }

    @Test fun aProviderWithoutContinuationSaysSoInsteadOfPretending() {
        val p = Continuation.plan(FinishReason.OUTPUT_LIMIT, "x", providerSupportsContinuation = false)
        assertFalse(p.allowed)
        assertEquals(ContinuationState.NEEDS_CONTINUATION, p.state)
        assertTrue(p.instruction.contains("continue"))
    }

    @Test fun theCarriedContextIsTheTailNotTheHead() {
        val text = "HEAD" + "m".repeat(200) + "TAIL"
        assertTrue(Continuation.plan(FinishReason.OUTPUT_LIMIT, text).carriedContext.endsWith("TAIL"))
    }
}
