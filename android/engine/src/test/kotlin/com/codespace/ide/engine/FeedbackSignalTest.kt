package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackSignalTest {
    @Test fun ignoresNoneAndScores() {
        val t = FeedbackSignal.Tally()
        t.record(FeedbackSignal.Signal.NONE)
        assertEquals(0.0, t.score(), 0.0001)
        t.record(FeedbackSignal.Signal.GOOD)
        t.record(FeedbackSignal.Signal.GOOD)
        t.record(FeedbackSignal.Signal.BAD)
        assertEquals(2.0 / 3.0, t.score(), 0.0001)
    }
}
