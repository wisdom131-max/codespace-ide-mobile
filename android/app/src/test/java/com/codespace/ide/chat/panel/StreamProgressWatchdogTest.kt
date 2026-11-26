package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 5: keepalive traffic must not hold a reply open, and a slow provider must not be killed.
 *
 * These are the two halves of the same rule, and getting one without the other is how a watchdog
 * becomes worse than the bug: a clock on bytes kills a thinking model, a clock on nothing lets a
 * keepalive loop run for ever. Only new visible text counts.
 */
class StreamProgressWatchdogTest {

    @Test
    fun `a stream that only sends keepalives is given up on`() {
        val w = StreamProgressWatchdog(timeoutMs = 120_000L)
        w.arm(nowMs = 1_000L)
        // Keepalives are not progress, so nothing re-arms the clock across ten minutes.
        assertFalse(w.expired(60_000L))
        assertTrue(w.expired(121_000L))
        assertTrue(w.expired(601_000L))
    }

    @Test
    fun `a legitimately slow stream is never cut off while text keeps arriving`() {
        val w = StreamProgressWatchdog(timeoutMs = 120_000L)
        w.arm(1_000L)
        // One character every 90 s for ten minutes: slow, but always progress.
        var now = 1_000L
        repeat(6) {
            now += 90_000L
            assertFalse("must not fire while text still arrives", w.expired(now))
            w.noteProgress(now)
        }
        assertFalse(w.expired(now + 119_000L))
    }

    @Test
    fun `a stall after progress is still detected`() {
        val w = StreamProgressWatchdog(timeoutMs = 120_000L)
        w.arm(1_000L)
        w.noteProgress(50_000L)
        assertFalse(w.expired(100_000L))
        assertTrue(w.expired(170_000L))
    }

    @Test
    fun `an unarmed watchdog never fires`() {
        val w = StreamProgressWatchdog(timeoutMs = 1L)
        assertFalse("nothing started, nothing to give up on", w.expired(10_000_000L))
        assertEquals(0L, w.silentForMs(10_000_000L))
    }

    @Test
    fun `disarming on a terminal path stops it reporting a stall`() {
        val w = StreamProgressWatchdog(timeoutMs = 1_000L)
        w.arm(0L)
        assertTrue(w.expired(5_000L))
        w.disarm()
        assertFalse("a finished attempt can never be a stall", w.expired(5_000L))
    }
}
