package com.codespace.ide.chat.panel

import com.codespace.ide.chat.provider.ToolOutcome
import com.codespace.ide.chat.ui.ChatRowModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT SEVEN 7.3 / 7.4 / 7.5 / 7.9, proved against the rows the session really emits.
 *
 * These are the rows the transcript renderer consumes, run through the same functions the composables
 * call. That is as close to UI integration as the JVM can get without Robolectric, and it is where the
 * dishonesty would otherwise live: a state shown that the row never claimed.
 */
class Stage72BindingsTest {

    private fun step(label: String, detail: String = "ok", ok: Boolean = true, id: String = "r1c1") =
        ChatRowModel.ToolActivity(label, detail, id = id, ok = ok)

    // ---------- 7.3 the tree ----------

    @Test
    fun `a completed step is never assumed from a successful flag alone`() {
        // ok = true means "did not report failure". It does NOT mean done, and the binding says so.
        val state = CopilotBindings.taskState(step("read_file"))
        assertEquals(CopilotPresentation.TaskState.ACTIVE, state)
        assertFalse("a non-failure must not read as done", state == CopilotPresentation.TaskState.DONE)
    }

    @Test
    fun `a step the runtime marked not-ok is failed`() {
        assertEquals(
            CopilotPresentation.TaskState.FAILED,
            CopilotBindings.taskState(step("write_file", "permission denied", ok = false)),
        )
    }

    @Test
    fun `a refused step is blocked and never "failed after running"`() {
        val refused = step("write_file", "REFUSED: ask mode needs approval")
        assertEquals(CopilotPresentation.TaskState.BLOCKED, CopilotBindings.taskState(refused))
        val advice = CopilotBindings.nextAction(refused)
        assertEquals(CopilotPresentation.Ran.NO, advice.ran)
        assertTrue("adjusting and retrying is offered", advice.retrySafe)
    }

    @Test
    fun `an uncertain step says so and refuses a plain retry`() {
        val unsure = step("run_command", "no response from the shell; outcome uncertain")
        assertEquals(CopilotPresentation.TaskState.UNKNOWN, CopilotBindings.taskState(unsure))
        val advice = CopilotBindings.nextAction(unsure)
        assertEquals(CopilotPresentation.Ran.UNKNOWN, advice.ran)
        assertFalse(advice.retrySafe)
        assertTrue(advice.headline.contains("may have run"))
    }

    @Test
    fun `task identity survives and never changes with position`() {
        val a = step("read_file", id = "r7c2")
        val nodes = CopilotBindings.taskNodes(listOf(ChatRowModel.InfoNote("noise"), a))
        assertEquals("r7c2", nodes.single().id)
        assertEquals("r7c2", CopilotBindings.taskNode(a, 99).id)
    }

    @Test
    fun `the group header counts only states the rows carry`() {
        val items = listOf(
            step("read_file", "ok", true, "a"),
            step("write_file", "permission denied", false, "b"),
            step("run_command", "outcome uncertain", true, "c"),
        )
        val header = CopilotBindings.groupHeader("3 steps", items)
        assertTrue("no step is done, so it cannot claim one: " + header, header.contains("0 of 3 done"))
        assertTrue(header.contains("1 with no state reported"))
    }

    @Test
    fun `the child line spells the state out so colour is not the only signal`() {
        assertTrue(CopilotBindings.childLine(step("read_file")).contains("[running]"))
        assertTrue(CopilotBindings.childLine(step("write_file", "no", ok = false)).contains("[failed]"))
        assertTrue(CopilotBindings.childLine(step("write_file", "REFUSED: ask mode")).contains("[refused]"))
        assertTrue(CopilotBindings.childLine(step("sh", "outcome uncertain")).contains("[outcome unknown]"))
    }

    // ---------- 7.4 / 7.5 the tool result row ----------

    @Test
    fun `a real long result is bounded at the presenter limit with the cut stated`() {
        val out = "line\n".repeat(2000)
        val row = CopilotBindings.resultRow(step("run_command"), out)
        assertTrue(row.cut)
        assertTrue(row.body.length <= CopilotPresentation.TOOL_ROW_CHARS + 80)
        assertTrue(row.body.contains("[cut "))
        assertTrue(row.body.contains(out.trim().length.toString()))
    }

    @Test
    fun `a failed step offers a retry while an unfinished step claims nothing`() {
        val failed = step("read_file", "missing file", ok = false)
        val row = CopilotBindings.resultRow(failed, "x")
        assertTrue("the row states the failure: " + row.state, row.state.contains("failed"))
        assertTrue(CopilotBindings.nextAction(failed).retrySafe)
        // A step that never reported a verdict is not a success: no "ran" claim, no retry offered.
        val running = CopilotBindings.nextAction(step("read_file"))
        assertEquals(CopilotPresentation.Ran.UNKNOWN, running.ran)
        assertFalse(running.retrySafe)
    }

    @Test
    fun `an active step's outcome is never reported as success`() {
        assertEquals(ToolOutcome.UNCERTAIN, CopilotBindings.outcomeOf(step("read_file")))
    }

    // ---------- 7.9 accessibility ----------

    @Test
    fun `talkback labels name the step, its state and the gesture`() {
        val label = CopilotAccessibility.toolLabel(step("write_file"), CopilotPresentation.TaskState.BLOCKED)
        assertTrue(label.contains("write_file"))
        assertTrue(label.contains("refused, did not run"))
        assertTrue(label.contains("Double tap"))
        val unknown = CopilotAccessibility.toolLabel(step("sh"), CopilotPresentation.TaskState.UNKNOWN)
        assertTrue(unknown.contains("may have run"))
    }

    @Test
    fun `the group announces whether it is expanded`() {
        val items = listOf(step("read_file"))
        assertTrue(CopilotAccessibility.groupLabel("2 steps", items, true).contains("Expanded"))
        assertTrue(CopilotAccessibility.groupLabel("2 steps", items, false).contains("Collapsed"))
    }

    @Test
    fun `a long message is described in a bounded way`() {
        val label = CopilotAccessibility.messageLabel(false, "x".repeat(500))
        assertTrue(label.startsWith("Assistant said: "))
        assertTrue(label.contains("longer message"))
        assertTrue(label.length < 260)
    }

    @Test
    fun `the touch target constant meets the stated minimum`() {
        // OWNER DECISION (2026-11-23): the "everything tappable is 48dp" rule was removed entirely -
        // 48dp is a recommended TOUCH TARGET, not a required drawn height, and the collapsed tree
        // header was wasting a third of a line of transcript. The compact header must still clear the
        // WCAG 2.2 minimum of 24dp, so this test now defends THAT instead of the old number.
        assertTrue(
            "the compact header clears the WCAG 24dp minimum",
            com.codespace.ide.chat.ui.ChatTokens.HEADER_MIN_DP >= 24,
        )
        assertTrue(
            "and it is genuinely compact, not 48dp",
            com.codespace.ide.chat.ui.ChatTokens.HEADER_MIN_DP < 48,
        )
    }

    @Test
    fun `status text differs per state so it never relies on colour`() {
        val words = CopilotPresentation.TaskState.values().map { CopilotAccessibility.statusText(it) }
        assertEquals("each state has its own word", words.size, words.toSet().size)
    }
}
