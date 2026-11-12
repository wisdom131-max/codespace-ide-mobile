package com.codespace.ide.chat.panel

import com.codespace.ide.engine.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Area 6 composer contract. The project has no Compose UI test harness, so the rule the composable
 * reads ([isVisibleContinuation]) and the session's own mapping are asserted directly.
 */
class ContinueBarStateTest {

    private val sessionMode = "agent"

    @Test fun visibleForAnOutputLimit() {
        assertTrue(isVisibleContinuation(ContinuationState.NEEDS_CONTINUATION))
        assertTrue(isVisibleContinuation(ContinuationState.CONTINUING))
    }

    @Test fun hiddenForCompleteUnknownCancelledAndInterrupted() {
        listOf(ContinuationState.COMPLETE, ContinuationState.UNKNOWN_OR_INTERRUPTED,
            ContinuationState.CONTINUED, ContinuationState.CONTINUATION_FAILED)
            .forEach { assertFalse("$it must not offer Continue", isVisibleContinuation(it)) }
    }

    @Test fun anOutputLimitMapsToTheVisibleState() {
        assertEquals(ContinuationState.NEEDS_CONTINUATION, ChatSession.continuationStateForTest(FinishReason.OUTPUT_LIMIT))
    }

    @Test fun aNaturalAnswerMapsToComplete() {
        assertEquals(ContinuationState.COMPLETE, ChatSession.continuationStateForTest(FinishReason.NATURAL))
    }

    @Test fun unknownMapsToUnknownNotToContinue() {
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, ChatSession.continuationStateForTest(FinishReason.UNKNOWN))
    }

    @Test fun cancellationAndInterruptionNeverOfferContinue() {
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, ChatSession.continuationStateForTest(FinishReason.CANCELLED))
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, ChatSession.continuationStateForTest(FinishReason.INTERRUPTED))
    }

    @Test fun repeatedCeilingStillOffersContinue() {
        assertEquals(ContinuationState.NEEDS_CONTINUATION, ChatSession.continuationStateForTest(FinishReason.OUTPUT_LIMIT))
        assertTrue(Continuation.plan(FinishReason.OUTPUT_LIMIT, "x", sessionMode).allowed)
    }

    @Test fun theSeamKeepsTheTwoAnswersDistinguishable() {
        assertTrue(ChatSession.SEAM.contains("continued"))
    }
}
