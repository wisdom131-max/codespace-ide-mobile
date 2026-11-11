package com.codespace.ide.agent

import com.codespace.ide.terminal.TerminalSessionRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 1 Group C — terminal_list, terminal_kill, job_status.
 *
 * The safety property under test is narrow and important: only a session the app itself published
 * can be stopped, and only a run that really happened can be reported as a job.
 */
class JobToolsTest {

    @After fun tearDown() = TerminalSessionRegistry.clear()

    private fun session(
        id: String,
        running: Boolean = true,
        name: String = "bash",
        active: Boolean = false,
    ) = TerminalSessionRegistry.Session(
        id = id,
        name = name,
        running = running,
        cwd = "/data/projects/demo",
        pid = 4242,
        lockedRoot = null,
        isActive = active,
        updatedAtMs = 1_700_000_000_000L,
    )

    private fun run(
        id: Long,
        status: AgentTerminalFeed.Status,
        command: String = "gradle test",
        startedAt: Long = 1_700_000_000_000L,
        endedAt: Long? = null,
        exitCode: Int? = null,
        output: String? = null,
    ) = AgentTerminalFeed.Run(
        id = id, command = command, workdir = null, status = status, output = output,
        exitCode = exitCode, startedAtMs = startedAt, endedAtMs = endedAt,
    )

    // ── terminal_list ──────────────────────────────────────────────────────

    @Test fun `an empty registry says so instead of inventing sessions`() {
        val out = JobTools.terminalList()
        assertTrue(out.startsWith("No terminal sessions"))
        assertTrue(out.contains("publishes"))
    }

    @Test fun `one running session is listed with its id and state`() {
        TerminalSessionRegistry.publish(listOf(session("1", active = true)))
        val out = JobTools.terminalList()
        assertTrue(out.contains("id=1"))
        assertTrue(out.contains("running"))
        assertTrue(out.contains("active tab"))
        assertTrue(out.contains("cwd: /data/projects/demo"))
        assertTrue(out.contains("pid: 4242"))
    }

    @Test fun `multiple sessions are all listed, running and stopped`() {
        TerminalSessionRegistry.publish(listOf(session("1"), session("2", running = false, name = "logs")))
        val out = JobTools.terminalList()
        assertTrue(out.contains("Terminal sessions (2)"))
        assertTrue(out.contains("id=2"))
        assertTrue(out.contains("stopped"))
        assertTrue(out.contains("name=logs"))
    }

    @Test fun `output is bounded when many sessions exist`() {
        TerminalSessionRegistry.publish((1..60).map { session("t$it") })
        val out = JobTools.terminalList()
        assertTrue(out.contains("Terminal sessions (60)"))
        assertTrue(out.contains("more (not listed)"))
        assertEquals("rows must be capped at MAX_SESSIONS", JobTools.MAX_SESSIONS,
            out.lines().count { it.contains("  id=") })
    }

    @Test fun `identifiers are the pane's own tab ids, stable across publishes`() {
        TerminalSessionRegistry.publish(listOf(session("tab-a")))
        val first = JobTools.terminalList()
        TerminalSessionRegistry.publish(listOf(session("tab-a", running = false)))
        val second = JobTools.terminalList()
        assertTrue(first.contains("id=tab-a"))
        assertTrue("the same session keeps its id", second.contains("id=tab-a"))
    }

    @Test fun `a cleared registry leaves no stale rows`() {
        TerminalSessionRegistry.publish(listOf(session("1")))
        TerminalSessionRegistry.clear()
        assertTrue(JobTools.terminalList().startsWith("No terminal sessions"))
    }

    // ── terminal_kill ──────────────────────────────────────────────────────

    @Test fun `an unknown session is refused and the known ids are offered`() {
        TerminalSessionRegistry.publish(listOf(session("1")))
        val out = JobTools.terminalKill("99").render()
        assertTrue(out.contains("No managed terminal session"))
        assertTrue(out.contains("1"))
    }

    @Test fun `an already stopped session reports as stopped, not as killed`() {
        TerminalSessionRegistry.publish(listOf(session("1", running = false)))
        val out = JobTools.terminalKill("1")
        assertTrue(out is JobTools.KillOutcome.AlreadyStopped)
        assertTrue(out.render().contains("already stopped"))
    }

    @Test fun `a managed running session is stopped through the pane's teardown`() {
        var stopped: String? = null
        TerminalSessionRegistry.publish(listOf(session("1"))) { id -> stopped = id; true }
        val out = JobTools.terminalKill("1")
        assertTrue(out is JobTools.KillOutcome.Stopped)
        assertEquals("the registry must hand the pane the SAME id", "1", stopped)
        assertTrue(out.render().contains("Stopped terminal session"))
    }

    @Test fun `a refused teardown is reported as a failure`() {
        TerminalSessionRegistry.publish(listOf(session("1"))) { _ -> false }
        val out = JobTools.terminalKill("1")
        assertTrue(out is JobTools.KillOutcome.Failed)
        assertTrue(out.render().contains("did not accept the stop"))
    }

    @Test fun `no stopper wired means no kill`() {
        TerminalSessionRegistry.publish(listOf(session("1")), stop = null)
        val out = JobTools.terminalKill("1")
        assertTrue("an unwired pane must not claim success", out is JobTools.KillOutcome.Failed)
    }

    @Test fun `an empty target is refused without touching anything`() {
        TerminalSessionRegistry.publish(listOf(session("1"))) { _ -> true }
        val out = JobTools.terminalKill("   ")
        assertTrue(out is JobTools.KillOutcome.Unknown)
    }

    @Test fun `publishing new sessions replaces the old stopper`() {
        var killed = ""
        TerminalSessionRegistry.publish(listOf(session("1"))) { id -> killed = "first:$id"; true }
        TerminalSessionRegistry.publish(listOf(session("1"))) { id -> killed = "second:$id"; true }
        JobTools.terminalKill("1")
        assertEquals("second:1", killed)
    }

    // ── job_status ─────────────────────────────────────────────────────────

    @Test fun `each feed status maps to an honest job status`() {
        val jobs = JobTools.jobsFrom(
            listOf(
                run(1, AgentTerminalFeed.Status.RUNNING, startedAt = 400),
                run(2, AgentTerminalFeed.Status.DONE, startedAt = 300, endedAt = 350, exitCode = 0),
                run(3, AgentTerminalFeed.Status.FAILED, startedAt = 200, endedAt = 250, exitCode = 2),
                run(4, AgentTerminalFeed.Status.CANCELLED, startedAt = 100, endedAt = 150),
            ),
            emptyList(),
        )
        assertEquals(listOf("running", "completed", "failed", "cancelled"), jobs.map { it.status })
        assertEquals(listOf("run:1", "run:2", "run:3", "run:4"), jobs.map { it.id })
    }

    @Test fun `jobs are newest first from both sources`() {
        val jobs = JobTools.jobsFrom(
            listOf(run(1, AgentTerminalFeed.Status.DONE, startedAt = 100, endedAt = 120)),
            listOf(AgentScheduler.LastRun("nightly", "0 3 * * *", 500, 600, false, false, 0)),
        )
        assertEquals(listOf("task:nightly", "run:1"), jobs.map { it.id })
        assertEquals("scheduled", jobs[0].kind)
        assertEquals("completed", jobs[0].status)
    }

    @Test fun `a scheduled timeout is reported as cancelled, not completed`() {
        val jobs = JobTools.jobsFrom(emptyList(),
            listOf(AgentScheduler.LastRun("slow", "@once", 10, 20, true, true, null)))
        assertEquals("cancelled", jobs[0].status)
        assertEquals("Timed out after 60s.", jobs[0].outputTail)
    }

    @Test fun `a failed scheduled run keeps its exit code`() {
        val jobs = JobTools.jobsFrom(emptyList(),
            listOf(AgentScheduler.LastRun("nightly", "0 3 * * *", 10, 20, true, false, 3)))
        assertEquals("failed", jobs[0].status)
        assertEquals(3, jobs[0].exitCode)
    }

    @Test fun `no jobs at all is stated plainly`() {
        val out = JobTools.jobStatus(null, null)
        assertTrue(out.startsWith("No jobs have run"))
    }

    @Test fun `an unknown job id lists what does exist`() {
        val out = JobTools.jobStatus(null, "run:999")
        assertTrue(out.contains("No job with id"))
        assertFalse(out.contains("exit code"))
    }

    @Test fun `an id lookup renders timings and the output tail`() {
        JobTools.jobsFrom(
            listOf(run(7, AgentTerminalFeed.Status.FAILED, startedAt = 1000, endedAt = 1500,
                exitCode = 1, output = "error: nope")),
            emptyList(),
        )
        val direct = JobTools.jobsFrom(
            listOf(run(7, AgentTerminalFeed.Status.FAILED, startedAt = 1000, endedAt = 1500,
                exitCode = 1, output = "error: nope")), emptyList())
        val job = direct.first { it.id == "run:7" }
        assertEquals(500L, job.endedAtMs!! - job.startedAtMs)
        assertTrue(job.renderContainsExit())
    }

    @Test fun `the list form is bounded by the requested limit`() {
        val many = (1..50L).map { run(it, AgentTerminalFeed.Status.DONE, startedAt = it) }
        val jobs = JobTools.jobsFrom(many, emptyList())
        assertEquals(50, jobs.size)
        assertEquals(JobTools.MAX_JOBS, jobs.take(999).take(JobTools.MAX_JOBS).size)
    }
}

/** Small helper so the assertion above reads as intent rather than string plumbing. */
private fun JobTools.Job.renderContainsExit(): Boolean =
    exitCode != null && outputTail?.contains("error: nope") == true
