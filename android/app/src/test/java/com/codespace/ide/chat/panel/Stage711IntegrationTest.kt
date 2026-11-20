package com.codespace.ide.chat.panel

import com.codespace.ide.agent.research.ResearchSources
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.chat.ui.ResearchPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT SEVEN 7.11 - the seams, through the real production objects.
 *
 * RECONCILED 2026-11-20: this file was deleted whole by `a015d0f` (P2 Voice Mode removal) because
 * it imported the removed voice turn machine. Four of its cases never touched voice: they cover the
 * two defects 7.11 fixed (a refused step presented as a failure, a successful step presented as
 * "may have run") plus the research source bound. Those are restored here, unchanged, so the
 * removal of Voice Mode does not quietly take the 7.11 regression evidence with it.
 *
 * The voice seam cases (turn machine, speaking pulse) are NOT restored: those classes were removed
 * by owner decision, and testing them would mean reintroducing Voice Mode.
 */
class Stage711IntegrationTest {

    @Test
    fun `a tool that only reports ok is never rendered as done`() {
        val row = ChatRowModel.ToolActivity("read_file", "the manifest", "r1c1", ok = true)
        assertNotEquals(CopilotPresentation.TaskState.DONE, CopilotBindings.taskState(row))
    }

    @Test
    fun `refused, failed and unanswered results stay three different answers`() {
        val refused = CopilotBindings.resultRow(
            ChatRowModel.ToolActivity("git_push", "REFUSED by policy", "r1c1", ok = false), "")
        val failed = CopilotBindings.resultRow(
            ChatRowModel.ToolActivity("git_push", "fatal: no upstream", "r1c2", ok = false), "")
        val unknown = CopilotBindings.resultRow(
            ChatRowModel.ToolActivity("git_push", "sent, no reply", "r1c3", ok = true), "")
        assertNotEquals(refused.state, failed.state)
        assertNotEquals(failed.state, unknown.state)
    }

    @Test
    fun `an unanswered tool call is never described as a success`() {
        val unanswered = CopilotBindings.nextAction(
            ChatRowModel.ToolActivity("read_file", "sent, no reply", "r1c3", ok = true))
        assertEquals(CopilotPresentation.Ran.UNKNOWN, unanswered.ran)
        assertFalse(unanswered.retrySafe)
        val ok = CopilotBindings.nextAction(
            ChatRowModel.ToolActivity("read_file", "read 12 lines", "r1c4", ok = true), "12 lines of Gradle")
        assertEquals(CopilotPresentation.Ran.YES, ok.ran)
        assertFalse("a success must not offer a retry", ok.retrySafe)
    }

    @Test
    fun `sources are bounded and the omitted count is exact`() {
        val items = (1..55).map {
            ResearchSources.Item("S" + it, "Source " + it, "example.com", "https://example.com/d/" + it)
        }
        val bounded = ResearchPresentation.bound(items)
        assertEquals(40, bounded.shown.size)
        assertEquals(15, bounded.hidden)
        val line = ResearchPresentation.overflowLine(bounded.hidden)
        assertNotNull(line)
        assertTrue("the omitted count is stated: " + line, (line ?: "").contains("15"))
    }
}
