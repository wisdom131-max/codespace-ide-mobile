package com.codespace.ide.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH11-S1 (advisor report gap 2): ONE resolve per physical tap.
 * The vendored GestureAndScaleRecognizer routes BOTH onSingleTapUp (at UP) and
 * onSingleTapConfirmed (~300ms later, same gesture) to the same handler — both
 * carry the same downTime, so the gate must swallow exactly the second one.
 */
class LinkTapDedupeTest {

    @Test fun sameDownTime_withinWindow_isDuplicate() {
        val g = LinkTapDedupe.Gate()
        assertTrue(g.shouldHandle(1000L, 1000L))
        // The confirm callback arrives ~300ms later, same downTime.
        assertFalse(g.shouldHandle(1000L, 1300L))
    }

    @Test fun secondPhysicalTap_differentDownTime_isHandled() {
        val g = LinkTapDedupe.Gate()
        assertTrue(g.shouldHandle(1000L, 1000L))
        assertFalse(g.shouldHandle(1000L, 1300L))
        assertTrue(g.shouldHandle(2000L, 2100L))
        assertFalse(g.shouldHandle(2000L, 2400L))
    }

    @Test fun sameDownTime_afterWindowExpiry_isHandledAgain() {
        val g = LinkTapDedupe.Gate()
        assertTrue(g.shouldHandle(1000L, 1000L))
        // Far past the confirm window: treat as a fresh gesture (defensive —
        // the recognizer never does this, but the gate must not wedge forever).
        assertTrue(g.shouldHandle(1000L, 1000L + LinkTapDedupe.CONFIRM_WINDOW_MS + 10))
    }

    @Test fun doubleTap_bothTapsHandled_exactlyOnceEach() {
        val g = LinkTapDedupe.Gate()
        assertTrue(g.shouldHandle(1000L, 1000L))   // tap 1 UP
        assertTrue(g.shouldHandle(1200L, 1200L))    // tap 2 UP (double-tap: no confirm fires)
        assertFalse(g.shouldHandle(1200L, 1500L))
    }
}
