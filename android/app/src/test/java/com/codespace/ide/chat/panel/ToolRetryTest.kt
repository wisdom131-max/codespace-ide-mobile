package com.codespace.ide.chat.panel

import com.codespace.ide.chat.panel.RetryDecision.EXECUTE
import com.codespace.ide.chat.panel.RetryDecision.RECONCILE_FIRST
import com.codespace.ide.chat.panel.RetryDecision.REFUSE
import com.codespace.ide.chat.panel.RetryDecision.REUSE_PERSISTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every cell of the matrix, both effects, plus the continuation guard. */
class ToolRetryTest {

    @Test fun neverStartedRetries() = assertEquals(EXECUTE, ToolRetry.decide(ToolOutcome.NEVER_STARTED, ToolEffect.SIDE_EFFECTING))
    @Test fun failedBeforeExecutionRetries() = assertEquals(EXECUTE, ToolRetry.decide(ToolOutcome.FAILED_BEFORE_EXECUTION, ToolEffect.SIDE_EFFECTING))
    @Test fun cancelledBeforeExecutionRetries() = assertEquals(EXECUTE, ToolRetry.decide(ToolOutcome.CANCELLED_BEFORE_EXECUTION, ToolEffect.SIDE_EFFECTING))

    @Test fun unknownOutcomeOnAWriteIsReconciledNotRetried() =
        assertEquals(RECONCILE_FIRST, ToolRetry.decide(ToolOutcome.UNKNOWN_AFTER_START, ToolEffect.SIDE_EFFECTING))

    @Test fun unknownOutcomeOnAReadRetries() =
        assertEquals(EXECUTE, ToolRetry.decide(ToolOutcome.UNKNOWN_AFTER_START, ToolEffect.READ_ONLY))

    @Test fun cancelDuringExecutionOnAWriteIsNotProof() =
        assertEquals(RECONCILE_FIRST, ToolRetry.decide(ToolOutcome.CANCELLED_DURING_EXECUTION, ToolEffect.SIDE_EFFECTING))

    @Test fun persistedResultIsReusedNotRerun() {
        assertEquals(REUSE_PERSISTED, ToolRetry.decide(ToolOutcome.RESULT_PERSISTED, ToolEffect.SIDE_EFFECTING))
        assertEquals(REUSE_PERSISTED, ToolRetry.decide(ToolOutcome.RESULT_PERSISTED, ToolEffect.READ_ONLY))
    }

    @Test fun aCompletedWriteIsNeverRerun() =
        assertEquals(REUSE_PERSISTED, ToolRetry.decide(ToolOutcome.COMPLETED_OK, ToolEffect.SIDE_EFFECTING))

    @Test fun aCompletedWriteThatErroredIsRefused() =
        assertEquals(REFUSE, ToolRetry.decide(ToolOutcome.COMPLETED_ERROR, ToolEffect.SIDE_EFFECTING))

    @Test fun aReadThatErroredRetries() =
        assertEquals(EXECUTE, ToolRetry.decide(ToolOutcome.COMPLETED_ERROR, ToolEffect.READ_ONLY))

    @Test fun lostResponseOnAWriteIsReconciledFirst() =
        assertEquals(RECONCILE_FIRST, ToolRetry.decide(ToolOutcome.COMPLETED_RESULT_LOST, ToolEffect.SIDE_EFFECTING))

    @Test fun lostResponseOnAReadRetries() =
        assertEquals(EXECUTE, ToolRetry.decide(ToolOutcome.COMPLETED_RESULT_LOST, ToolEffect.READ_ONLY))

    @Test fun aSideEffectingCallWithUnknownOutcomeIsDangerousToRepeat() {
        assertTrue(ToolRetry.dangerousToRepeat(ToolOutcome.UNKNOWN_AFTER_START, ToolEffect.SIDE_EFFECTING))
        assertTrue(ToolRetry.dangerousToRepeat(ToolOutcome.CANCELLED_DURING_EXECUTION, ToolEffect.SIDE_EFFECTING))
    }

    @Test fun aReadWithUnknownOutcomeIsNotDangerous() {
        assertFalse(ToolRetry.dangerousToRepeat(ToolOutcome.UNKNOWN_AFTER_START, ToolEffect.READ_ONLY))
    }

    @Test fun aReusedResultIsNotADangerousRepeat() {
        assertFalse(ToolRetry.dangerousToRepeat(ToolOutcome.RESULT_PERSISTED, ToolEffect.SIDE_EFFECTING))
    }
}
