package com.codespace.ide.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH9-P1b (advisor ruling 2026-10-18): the BACKGROUND idle-stop policy, pinned
 * pure-JVM. Owner ruling: servers stay alive UNCONDITIONALLY while the app is
 * FOREGROUND; the idle clock counts BACKGROUND time only. Exceptions (addition 4):
 * in-flight request, active $/progress token, active debug session POSTPONE the
 * stop — bounded by the hard cap so a stuck exception signal cannot keep a
 * backgrounded server alive forever.
 */
class BackgroundIdlePolicyTest {

    private val minute = BackgroundIdlePolicy.MS_PER_MINUTE

    @Test fun foregroundIsNeverEligible() {
        // backgroundSinceMs == 0 means the app is foreground — alive unconditionally
        val d = BackgroundIdlePolicy.decide(
            minutes = 30, backgroundSinceMs = 0L, nowMs = 10L * minute,
            eligibleSinceMs = 10L * minute, requestInFlight = false, progressActive = false, debugSessionActive = false,
        )
        assertTrue(d is IdleStopDecision.NotEligible)
    }

    @Test fun neverSettingDisablesThePolicy() {
        val d = BackgroundIdlePolicy.decide(
            minutes = 0, backgroundSinceMs = 1L, nowMs = 10L * minute,
            eligibleSinceMs = 10L * minute, requestInFlight = false, progressActive = false, debugSessionActive = false,
        )
        assertTrue("minutes=0 is the Never option", d is IdleStopDecision.NotEligible)
    }

    @Test fun backgroundedServerStopsOnlyAfterTheTimeout() {
        val base = 1_000_000L
        // 29 min background with a 30-min setting: not yet
        val early = BackgroundIdlePolicy.decide(
            minutes = 30, backgroundSinceMs = base, nowMs = base + 29L * minute,
            eligibleSinceMs = 0L, requestInFlight = false, progressActive = false, debugSessionActive = false,
        )
        assertTrue(early is IdleStopDecision.NotEligible)
        // 30 min: stop
        val due = BackgroundIdlePolicy.decide(
            minutes = 30, backgroundSinceMs = base, nowMs = base + 30L * minute,
            eligibleSinceMs = base + 30L * minute, requestInFlight = false, progressActive = false, debugSessionActive = false,
        )
        assertEquals("idle-background", (due as IdleStopDecision.Stop).reason)
    }

    @Test fun workInFlightPostponesTheStop() {
        val base = 1_000_000L
        val eligible = base + 30L * minute
        // request in flight -> postpone
        val r = BackgroundIdlePolicy.decide(
            minutes = 30, backgroundSinceMs = base, nowMs = eligible, eligibleSinceMs = eligible,
            requestInFlight = true, progressActive = false, debugSessionActive = false,
        )
        assertTrue((r as IdleStopDecision.Postpone).exception.contains("request"))
        // indexing progress -> postpone
        val p = BackgroundIdlePolicy.decide(
            minutes = 30, backgroundSinceMs = base, nowMs = eligible, eligibleSinceMs = eligible,
            requestInFlight = false, progressActive = true, debugSessionActive = false,
        )
        assertTrue((p as IdleStopDecision.Postpone).exception.contains("progress"))
        // debug session -> postpone
        val dbg = BackgroundIdlePolicy.decide(
            minutes = 30, backgroundSinceMs = base, nowMs = eligible, eligibleSinceMs = eligible,
            requestInFlight = false, progressActive = false, debugSessionActive = true,
        )
        assertTrue((dbg as IdleStopDecision.Postpone).exception.contains("debug"))
    }

    @Test fun postponementIsCapped() {
        val base = 1_000_000L
        val eligible = base + 30L * minute
        // exception persists past the hard cap -> stop ANYWAY, with the cap reason
        val capped = BackgroundIdlePolicy.decide(
            minutes = 30, backgroundSinceMs = base,
            nowMs = eligible + BackgroundIdlePolicy.POSTPONE_HARD_CAP_MS,
            eligibleSinceMs = eligible,
            requestInFlight = true, progressActive = false, debugSessionActive = false,
        )
        assertEquals("idle-postpone-cap", (capped as IdleStopDecision.Stop).reason)
        // one ms before the cap: still postponed
        val notYet = BackgroundIdlePolicy.decide(
            minutes = 30, backgroundSinceMs = base,
            nowMs = eligible + BackgroundIdlePolicy.POSTPONE_HARD_CAP_MS - 1,
            eligibleSinceMs = eligible,
            requestInFlight = true, progressActive = false, debugSessionActive = false,
        )
        assertTrue(notYet is IdleStopDecision.Postpone)
    }
}
