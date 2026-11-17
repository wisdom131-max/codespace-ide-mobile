package com.codespace.ide.chat.panel

import com.codespace.ide.chat.provider.ToolOutcome
import com.codespace.ide.engine.SummaryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT SEVEN — presentation derived from runtime state, and never ahead of it. */
class Stage71CopilotPresentationTest {

    // 7.5: the retry question is an honesty question.
    @Test
    fun `an uncertain write is never called failed or safe to retry`() {
        val v = CopilotPresentation.outcome("write_file", ToolOutcome.UNCERTAIN)
        assertEquals(CopilotPresentation.Ran.UNKNOWN, v.ran)
        assertFalse(v.retrySafe)
        assertTrue(v.headline.contains("may have run"))
        assertFalse("the action must not be a plain retry", v.actionLabel == "Retry")
    }

    @Test
    fun `a cancelled call is unknown, not a failure`() {
        val v = CopilotPresentation.outcome("run_command", ToolOutcome.CANCELLED)
        assertEquals(CopilotPresentation.Ran.UNKNOWN, v.ran)
        assertFalse(v.retrySafe)
        assertTrue(v.whyNot!!.contains("does not prove"))
    }

    @Test
    fun `a refusal says it never ran and can be retried after adjusting`() {
        val v = CopilotPresentation.outcome("write_file", ToolOutcome.FAILED, refusal = true)
        assertEquals(CopilotPresentation.Ran.NO, v.ran)
        assertTrue(v.retrySafe)
    }

    @Test
    fun `a failed call is retryable and a success offers no action`() {
        assertTrue(CopilotPresentation.outcome("read_file", ToolOutcome.FAILED).retrySafe)
        val ok = CopilotPresentation.outcome("read_file", ToolOutcome.SUCCESS)
        assertEquals(CopilotPresentation.Ran.YES, ok.ran)
        assertNull(ok.actionLabel)
        assertFalse(ok.retrySafe)
    }

    // 7.4: bounded rows.
    @Test
    fun `a huge tool output is cut with the cut stated`() {
        val big = "x".repeat(5000)
        val row = CopilotPresentation.toolRow("run_command", ToolOutcome.SUCCESS, big)
        assertTrue(row.cut)
        assertTrue(row.body.length < 1200)
        assertTrue(row.body.contains("[cut "))
        assertTrue(row.body.contains("5000"))
    }

    @Test
    fun `a small tool output is left alone`() {
        val row = CopilotPresentation.toolRow("read_file", ToolOutcome.SUCCESS, "ok")
        assertFalse(row.cut)
        assertEquals("ok", row.body)
    }

    // 7.3: states are read, never assumed.
    @Test
    fun `an unreadable task state is unknown rather than done`() {
        assertEquals(CopilotPresentation.TaskState.UNKNOWN, CopilotPresentation.taskState("weird"))
        assertEquals(CopilotPresentation.TaskState.UNKNOWN, CopilotPresentation.taskState(null))
        assertEquals(CopilotPresentation.TaskState.DONE, CopilotPresentation.taskState("completed"))
    }

    @Test
    fun `progress counts only states the runtime reported`() {
        val nodes = listOf(
            CopilotPresentation.TaskNode("a", null, "plan", CopilotPresentation.TaskState.DONE),
            CopilotPresentation.TaskNode("b", null, "edit", CopilotPresentation.TaskState.ACTIVE),
            CopilotPresentation.TaskNode("c", null, "verify", CopilotPresentation.TaskState.UNKNOWN),
        )
        val p = CopilotPresentation.progress(nodes)
        assertTrue(p.startsWith("1 of 3 done"))
        assertTrue(p.contains("1 with no state reported"))
    }

    @Test
    fun `a child is grouped under its parent with a stable key`() {
        val parent = CopilotPresentation.TaskNode("t1", null, "Plan", CopilotPresentation.TaskState.ACTIVE)
        val child = CopilotPresentation.TaskNode("t1.1", "t1", "Read", CopilotPresentation.TaskState.DONE)
        val tree = CopilotPresentation.taskTree(listOf(parent, child))
        assertEquals(1, tree.size)
        assertEquals("t1", tree.single().first.key)
        assertEquals("t1.1", tree.single().second.single().key)
    }

    // 7.7: carried context is a report of what is actually carried.
    @Test
    fun `an empty carry says so instead of implying continuity`() {
        val c = CopilotPresentation.carriedContext("Session 2 from git", emptyList(), 0, false)
        assertTrue(c.empty)
        assertTrue(c.headline.contains("nothing was carried"))
    }

    @Test
    fun `a carry counts kinds and surfaces unresolved work and inert exchanges`() {
        val kinds = listOf(
            SummaryKind.DECISION to "decided x",
            SummaryKind.OPEN_TASK to "remaining: device test",
            SummaryKind.FAILURE to "FAILED: ci red",
            SummaryKind.FACT to "sha b9b4008",
        )
        val c = CopilotPresentation.carriedContext("Session 2 from git", kinds, exchangeCount = 2, retainedOnDisk = false)
        assertFalse(c.empty)
        assertEquals(2, c.unresolved)
        assertEquals(4, c.inspectedIds)
        assertTrue(c.headline.contains("4 labelled line(s)"))
        assertTrue(c.detail.any { it.contains("1 open task") })
        assertTrue(c.detail.any { it.contains("never re-run") })
        assertTrue(c.detail.any { it.contains("memory-only") })
        assertTrue(CopilotPresentation.carriedContextLine(c).endsWith("."))
    }

    // 7.7: the carried labels are read back from the runtime's own rendered summary.
    @Test
    fun `labels are recovered from a rendered summary`() {
        val rendered = "GENERATED SUMMARY of earlier turns\n- [DECISION] keep stage-5.3 unmerged\n" +
            "- [OPEN_TASK] remaining: device test [unresolved]\n- [NOT_A_KIND] junk\n"
        val kinds = CopilotPresentation.kindsFromRenderedSummary(rendered)
        assertEquals(3, kinds.size)
        assertEquals(SummaryKind.DECISION, kinds[0].first)
        assertEquals(SummaryKind.OPEN_TASK, kinds[1].first)
        assertEquals("an unreadable label is OTHER, never invented", SummaryKind.OTHER, kinds[2].first)
        assertTrue(CopilotPresentation.kindsFromRenderedSummary("").isEmpty())
    }

    @Test
    fun `a handoff notice states what was carried, not merely that it continued`() {
        val rendered = "- [DECISION] keep stage-5.3 unmerged\n- [OPEN_TASK] remaining: device test\n"
        val c = CopilotPresentation.carriedContext(
            "Session 2 from git",
            CopilotPresentation.kindsFromRenderedSummary(rendered),
            exchangeCount = 0,
            retainedOnDisk = false,
        )
        val line = CopilotPresentation.carriedContextLine(c)
        assertTrue(line.startsWith("Session 2 from git carries 2 labelled line(s)"))
        assertTrue(line.contains("1 unresolved"))
        assertTrue(line.contains("memory-only"))
    }
}
