package com.codespace.ide.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U6-c — agent commands visible in the terminal.
 *
 * These cover the parts that can go wrong without a device: output attached to the WRONG
 * run, a row stuck on "running" forever, a failure reported as success, and duplicate rows
 * when the pane recomposes or reopens.
 */
class AgentTerminalFeedTest {

    @After
    fun tearDown() {
        AgentTerminalFeed.clear()
    }

    @Test
    fun aSuccessfulRunCarriesItsOwnOutput() {
        val id = AgentTerminalFeed.begin("ls -la", "/root/proj")
        AgentTerminalFeed.finish(id, "total 0\ndrwxr-xr-x 2 root root")
        val run = AgentTerminalFeed.runs.single()
        assertEquals(AgentTerminalFeed.Status.DONE, run.status)
        assertEquals("total 0\ndrwxr-xr-x 2 root root", run.output)
        assertTrue(run.isFinished)
        assertTrue(AgentTerminalFeed.showsOutput(run))
        // The row exists while running too: begin() is what makes long commands visible.
        assertEquals("/root/proj", run.workdir)
    }

    @Test
    fun aRunIsVisibleAsRunningBeforeItFinishes() {
        val id = AgentTerminalFeed.begin("sleep 300", null)
        val running = AgentTerminalFeed.runs.single()
        assertEquals(AgentTerminalFeed.Status.RUNNING, running.status)
        assertNull(running.endedAtMs)
        assertEquals("running", AgentTerminalFeed.statusLabel(running))
        assertFalse(AgentTerminalFeed.showsOutput(running))
        AgentTerminalFeed.finish(id, "done after a while")
        assertEquals(AgentTerminalFeed.Status.DONE, AgentTerminalFeed.runs.single().status)
    }

    @Test
    fun aFailureIsNeverRecordedAsSuccess() {
        val id = AgentTerminalFeed.begin("exit 1 && nope", null)
        AgentTerminalFeed.finish(id, "bash: nope: command not found", failed = true)
        val run = AgentTerminalFeed.runs.single()
        assertEquals(AgentTerminalFeed.Status.FAILED, run.status)
        assertEquals("failed", AgentTerminalFeed.statusLabel(run))
    }

    @Test
    fun aCancelledRunIsDistinctFromAFailure() {
        val id = AgentTerminalFeed.begin("npm run dev", null)
        AgentTerminalFeed.cancel(id, "approval refused")
        val run = AgentTerminalFeed.runs.single()
        assertEquals(AgentTerminalFeed.Status.CANCELLED, run.status)
        assertEquals("approval refused", run.output)
        assertEquals("cancelled", AgentTerminalFeed.statusLabel(run))
    }

    @Test
    fun outputStaysWithTheRunThatProducedIt() {
        // The association bug this guards: two overlapping runs, finishing out of order.
        val first = AgentTerminalFeed.begin("cmd-one", null)
        val second = AgentTerminalFeed.begin("cmd-two", null)
        AgentTerminalFeed.finish(second, "out-two")
        AgentTerminalFeed.finish(first, "out-one")
        val a = AgentTerminalFeed.runs.first { it.id == first }
        val b = AgentTerminalFeed.runs.first { it.id == second }
        assertEquals("cmd-one", a.command)
        assertEquals("out-one", a.output)
        assertEquals("cmd-two", b.command)
        assertEquals("out-two", b.output)
    }

    @Test
    fun finishingAnUnknownIdChangesNothing() {
        val id = AgentTerminalFeed.begin("only", null)
        AgentTerminalFeed.finish(id, "real")
        AgentTerminalFeed.finish(999_999L, "stale output from an older UI")
        assertEquals(1, AgentTerminalFeed.runs.size)
        assertEquals("real", AgentTerminalFeed.runs.single().output)
    }

    @Test
    fun theListIsCappedSoALongSessionCannotGrowForever() {
        repeat(AgentTerminalFeed.MAX_RUNS + 5) { i ->
            val id = AgentTerminalFeed.begin("run-$i", null)
            AgentTerminalFeed.finish(id, "out-$i")
        }
        assertEquals(AgentTerminalFeed.MAX_RUNS, AgentTerminalFeed.runs.size)
        // The OLDEST fell off; the newest is still there.
        assertEquals("run-" + (AgentTerminalFeed.MAX_RUNS + 4), AgentTerminalFeed.runs.last().command)
    }

    @Test
    fun hugeOutputIsClippedButSaysSo() {
        val id = AgentTerminalFeed.begin("cat big.log", null)
        val big = "x".repeat(AgentTerminalFeed.MAX_OUTPUT_CHARS + 500)
        AgentTerminalFeed.finish(id, big)
        val out = AgentTerminalFeed.runs.single().output ?: ""
        assertTrue(out.length < big.length)
        assertTrue("the clip must be announced, not silent", out.contains("truncated"))
    }

    @Test
    fun durationIsReadableAndNeverNegative() {
        assertEquals("running", AgentTerminalFeed.durationLabel(0L, null))
        assertEquals("2.4s", AgentTerminalFeed.durationLabel(1_000L, 3_400L))
        assertEquals("41.0s", AgentTerminalFeed.durationLabel(1_000L, 42_000L))
        assertEquals("3m", AgentTerminalFeed.durationLabel(0L, 180_000L))
        assertEquals("3m 12s", AgentTerminalFeed.durationLabel(0L, 192_000L))
        // A clock that went backwards must not render a negative duration.
        assertEquals("0.0s", AgentTerminalFeed.durationLabel(5_000L, 1_000L))
    }

    @Test
    fun aNonZeroExitIsShownRatherThanHiddenBehindDone() {
        val id = AgentTerminalFeed.begin("grep x file", null)
        AgentTerminalFeed.finish(id, "", exitCode = 2)
        val run = AgentTerminalFeed.runs.single()
        assertEquals(AgentTerminalFeed.Status.DONE, run.status)
        assertEquals("exit 2", AgentTerminalFeed.statusLabel(run))
    }
}
