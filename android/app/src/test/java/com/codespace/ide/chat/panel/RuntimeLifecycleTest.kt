package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 2: closing the last window must never be the thing that ends a turn.
 *
 * The device symptom was a reply that stopped when the panel was toggled, and a replay-looking
 * duplicate when it was reopened. Both come from closing a live session on dispose, so the test
 * that matters is the one asserting PARK while a turn is in flight.
 */
class RuntimeLifecycleTest {

    @Test fun anotherWindowStillOpenIsNeverAClose() {
        assertEquals(RuntimeLifecycle.Action.KEEP,
            RuntimeLifecycle.onRelease(remainingHosts = 1, anyStreaming = false))
        assertEquals(RuntimeLifecycle.Action.KEEP,
            RuntimeLifecycle.onRelease(remainingHosts = 2, anyStreaming = true))
    }

    @Test fun lastWindowWithATurnInFlightParksInsteadOfClosing() {
        assertEquals(RuntimeLifecycle.Action.PARK,
            RuntimeLifecycle.onRelease(remainingHosts = 0, anyStreaming = true))
    }

    @Test fun lastWindowWithNothingRunningCloses() {
        assertEquals(RuntimeLifecycle.Action.CLOSE,
            RuntimeLifecycle.onRelease(remainingHosts = 0, anyStreaming = false))
    }

    @Test fun parkedRuntimeIsClosedAsSoonAsItsWorkEnds() {
        assertTrue(RuntimeLifecycle.canCloseWhileParked(0, anyStreaming = false))
        assertFalse(RuntimeLifecycle.canCloseWhileParked(0, anyStreaming = true))
    }

    @Test fun aWindowThatCameBackBeforeTheWorkEndedKeepsTheRuntime() {
        assertFalse("reopening the panel must not trigger the reaper",
            RuntimeLifecycle.canCloseWhileParked(1, anyStreaming = true))
        assertFalse("even an idle session must stay while a window is open",
            RuntimeLifecycle.canCloseWhileParked(1, anyStreaming = false))
    }
}
