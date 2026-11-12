package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate reply prevention. Each case here is a way the user could have seen the same answer twice,
 * expressed as the rule that decides it.
 */
class ReplyOwnershipTest {

    @Test fun aFirstSendIsFresh() {
        assertEquals(ReplyOwnership.Start.FRESH, ReplyOwnership.startAction(running = false, sameText = false, elapsedMs = 0))
    }

    @Test fun aDoubleTapOnSendDoesNotAskTwice() {
        assertEquals(
            ReplyOwnership.Start.IGNORE_DUPLICATE_TAP,
            ReplyOwnership.startAction(running = true, sameText = true, elapsedMs = 300),
        )
    }

    @Test fun theSameQuestionLaterIsADifferentQuestion() {
        assertEquals(
            ReplyOwnership.Start.REPLACE_RUNNING,
            ReplyOwnership.startAction(running = true, sameText = true, elapsedMs = 60_000),
        )
    }

    @Test fun aNewMessageReplacesTheRunningRequest() {
        assertEquals(
            ReplyOwnership.Start.REPLACE_RUNNING,
            ReplyOwnership.startAction(running = true, sameText = false, elapsedMs = 400),
        )
    }

    @Test fun aCompletionIsAppliedOnce() {
        assertTrue(ReplyOwnership.mayComplete(gen = 7, lastCompletedGen = 6, owns = true))
        assertFalse(ReplyOwnership.mayComplete(gen = 7, lastCompletedGen = 7, owns = true))
    }

    @Test fun aStaleCompletionIsNeverApplied() {
        assertFalse(ReplyOwnership.mayComplete(gen = 3, lastCompletedGen = 2, owns = false))
    }

    @Test fun retriesAndContinuationsAddNoUserBubble() {
        assertFalse(ReplyOwnership.addsUserBubble(isContinuation = true, isFailoverRetry = false, isFreshQuestion = true))
        assertFalse(ReplyOwnership.addsUserBubble(isContinuation = false, isFailoverRetry = true, isFreshQuestion = true))
    }

    @Test fun aGenuineSecondQuestionStillGetsItsBubble() {
        assertTrue(ReplyOwnership.addsUserBubble(isContinuation = false, isFailoverRetry = false, isFreshQuestion = true))
    }

    @Test fun identicalConsecutiveMessagesAreBothAnswered() {
        // Two real messages with the same text are two questions: the rule must not dedupe CONTENT.
        assertTrue(
            ReplyOwnership.startAction(running = false, sameText = true, elapsedMs = 0) == ReplyOwnership.Start.FRESH,
        )
        assertTrue(ReplyOwnership.addsUserBubble(false, false, true))
    }

    @Test fun aRestoreNeverOverwritesALiveStream() {
        assertFalse(ReplyOwnership.mayApplyRestore(restoreFinished = true, streaming = true, currentRowCount = 4, storedCount = 9))
    }

    @Test fun aRestoreNeverOverwritesNewerRows() {
        assertFalse(ReplyOwnership.mayApplyRestore(restoreFinished = true, streaming = false, currentRowCount = 9, storedCount = 4))
    }

    @Test fun anEmptyTranscriptAcceptsTheStoredHistory() {
        assertTrue(ReplyOwnership.mayApplyRestore(restoreFinished = true, streaming = false, currentRowCount = 0, storedCount = 4))
    }
}
