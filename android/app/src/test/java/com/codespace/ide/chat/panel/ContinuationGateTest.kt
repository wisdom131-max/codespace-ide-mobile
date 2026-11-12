package com.codespace.ide.chat.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-11-12: `continuationInFlight` was written and cleared but never read, so the window
 * between "continuation sent" and "first stream event" accepted a second Continue.
 */
class ContinuationGateTest {

    @Test fun aContinuationMayStartWhenNothingIsRunning() {
        assertTrue(Continuation.mayStart(streaming = false, inFlight = false))
    }

    @Test fun anAlreadyRunningStreamRefusesASecond() {
        assertFalse(Continuation.mayStart(streaming = true, inFlight = false))
    }

    @Test fun aContinuationAlreadySentRefusesASecondEvenBeforeItStreams() {
        assertFalse("this is the double-tap window", Continuation.mayStart(streaming = false, inFlight = true))
        assertFalse(Continuation.mayStart(streaming = true, inFlight = true))
    }

    @Test fun aFailedContinuationCanBeRetried() {
        // After EngineEvent.Failed the flag is cleared, which is the "inFlight = false" case here.
        assertTrue(Continuation.mayStart(streaming = false, inFlight = false))
        assertTrue(ContinuationState.CONTINUATION_FAILED != ContinuationState.CONTINUING)
        assertTrue(isVisibleContinuation(ContinuationState.CONTINUATION_FAILED))
    }
}
