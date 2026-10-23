package com.codespace.ide.terminal

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C12-U6-b item 3 (advisor ruling 2026-10-23): the anti crash-loop guard's
 * PURE decision core. The guard exists because the historical 16-kill/90 s
 * crash loop (CodeSpaceApplication.kt:137-146) was TECNO HiOS SIGKILLing a
 * process that acquired 2 WakeLocks + FGS within 1 s of PROCESS START — and
 * a SIGKILL loop leaves NO crash file, so the guard must ALSO fingerprint
 * rapid process restarts. These tests pin the decision only; the timer
 * (5 s, one-shot, visible-only, cancel-on-pause) and the Android file side
 * are device-verified on the keep-alive round.
 */
class AppKeepAliveGuardTest {

    private val now = 1_000_000L

    @Test fun freshCrashFileTripsTheGuard() {
        // (a) a crash file written 30 s ago = the previous run crashed
        val why = AppKeepAlive.tripReasonFor(now, recentStarts = listOf(now - 10_000),
            crashTimes = listOf(now - 30_000))
        assertNotNull(why)
        // Honest reason: names what it saw.
        assertTrue(why!!.contains("previous run crashed"))
    }

    @Test fun crashLoopInsideTheWindowTripsTheGuard() {
        // (b) two crashes inside the 5-minute window = a crash loop
        val why = AppKeepAlive.tripReasonFor(now, recentStarts = listOf(now - 10_000),
            crashTimes = listOf(now - 2 * 60_000, now - 3 * 60_000))
        assertNotNull(why)
        assertTrue(why!!.contains("crash files inside the"))
    }

    @Test fun rapidRestartsTripTheGuardEvenWithNoCrashFile() {
        // (c) the SIGKILL family: 3 process starts inside the 120 s window,
        // ZERO crash files — the guard must still trip on the fingerprint.
        val why = AppKeepAlive.tripReasonFor(now,
            recentStarts = listOf(now - 5_000, now - 60_000, now - 100_000),
            crashTimes = emptyList())
        assertNotNull(why)
        assertTrue(why!!.contains("process starts inside the"))
    }

    @Test fun healthyHistoryAllowsTheStart() {
        // An old start + an old crash = a normal, healthy session.
        assertNull(AppKeepAlive.tripReasonFor(now,
            recentStarts = listOf(now - 3_600_000),
            crashTimes = listOf(now - 2 * 3_600_000)))
    }

    @Test fun aSingleOldCrashDoesNotTripForever() {
        // One crash 10 minutes ago: outside the 60 s skip and below the
        // 2-in-window loop threshold — the user recovered, keep-alive allowed.
        assertNull(AppKeepAlive.tripReasonFor(now, recentStarts = listOf(now - 10_000),
            crashTimes = listOf(now - 10 * 60_000)))
    }

    @Test fun twoStartsInsideTheWindowStillAllowed() {
        // 2 of the 3 required restarts: a normal relaunch, not a loop.
        assertNull(AppKeepAlive.tripReasonFor(now,
            recentStarts = listOf(now - 5_000, now - 60_000),
            crashTimes = emptyList()))
    }

    @Test fun freshCrashBeatsEveryOtherAllowance() {
        // Even with a perfectly healthy start history, one fresh crash file
        // (previous run crashed seconds ago) trips the guard.
        assertNotNull(AppKeepAlive.tripReasonFor(now,
            recentStarts = listOf(now - 3_600_000),
            crashTimes = listOf(now - 5_000)))
    }
}
