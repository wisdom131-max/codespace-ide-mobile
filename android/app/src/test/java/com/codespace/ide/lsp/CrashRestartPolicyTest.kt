package com.codespace.ide.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH9-P1c (advisor ruling + external review, 2026-10-18): the ROLLING crash
 * circuit breaker, pinned pure-JVM. The old consecutive-restart counter reset on
 * any momentary success — a server crashing every 40s forever resurrected. The
 * rolling window trips on the 5th attempt inside 3 minutes; only MANUAL retry
 * clears it (the only exit from FAILED_DISABLED).
 */
class CrashRestartPolicyTest {

    private val minute = 60_000L
    private val window = 3L * minute

    @Test fun emptyWindowAllowsRestart() {
        val p = CrashRestartPolicy(windowMs = window, maxAutoRestarts = 4)
        assertTrue(p.canAutoRestart(nowMs = 0L))
        assertEquals(0, p.attemptsInWindow(0L))
    }

    @Test fun capTripsAtMaxInsideTheWindow() {
        val p = CrashRestartPolicy(windowMs = window, maxAutoRestarts = 4)
        val t0 = 1_000_000L
        // 4 attempts inside the window: the 4th attempt is allowed
        repeat(4) { i ->
            assertTrue("attempt ${i + 1} must be allowed", p.canAutoRestart(t0 + i * 10_000L))
            p.recordAttempt(t0 + i * 10_000L)
        }
        // the 5th attempt inside the window: BLOCKED -> FAILED_DISABLED
        assertFalse("the 5th attempt inside the window must trip the cap", p.canAutoRestart(t0 + 45_000L))
        assertEquals(4, p.attemptsInWindow(t0 + 45_000L))
    }

    @Test fun oldAttemptsFallOutOfTheWindow() {
        val p = CrashRestartPolicy(windowMs = window, maxAutoRestarts = 4)
        val t0 = 1_000_000L
        repeat(4) { p.recordAttempt(t0) }
        assertFalse(p.canAutoRestart(t0 + 60_000L))
        // 3 minutes + 1ms after the burst: the window is empty again
        assertTrue(p.canAutoRestart(t0 + window + 1))
        assertEquals(0, p.attemptsInWindow(t0 + window + 1))
    }

    @Test fun manualRetryClearsEverything() {
        val p = CrashRestartPolicy(windowMs = window, maxAutoRestarts = 4)
        val t0 = 1_000_000L
        repeat(4) { p.recordAttempt(t0) }
        assertFalse(p.canAutoRestart(t0 + 10_000L))
        // the ONLY sanctioned exit: manual retry
        p.clear()
        assertTrue(p.canAutoRestart(t0 + 10_000L))
        assertEquals(0, p.attemptsInWindow(t0 + 10_000L))
    }

    @Test fun spreadOutAttemptsDoNotTripTheCap() {
        val p = CrashRestartPolicy(windowMs = window, maxAutoRestarts = 4)
        val t0 = 1_000_000L
        // one attempt per minute: at most ~3 ever sit inside a 3-min window
        repeat(6) { i ->
            assertTrue(p.canAutoRestart(t0 + i * minute))
            p.recordAttempt(t0 + i * minute)
        }
        // a slow-but-steady crasher keeps its (unhealthy) restarts — the cap
        // exists to stop tight crash LOOPS, not slow periodic failures
        assertTrue(p.canAutoRestart(t0 + 6 * minute))
    }
}
