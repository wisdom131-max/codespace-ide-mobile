package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ask / Edit / Fix: which buttons exist, what they send, and where they land. */
class SelectionActionSpecTest {

    @Test
    fun `fix only appears when the selection has an error`() {
        val withoutError = SelectionActionSpec.entries(hasDiagnostic = false, selectionChars = 20)
        assertEquals(listOf(SelectionAction.ASK, SelectionAction.EDIT), withoutError.map { it.action })
        val withError = SelectionActionSpec.entries(hasDiagnostic = true, selectionChars = 20)
        assertEquals(SelectionAction.FIX, withError.first().action)
        assertTrue(withError.map { it.action }.containsAll(listOf(SelectionAction.ASK, SelectionAction.EDIT)))
    }

    @Test
    fun `a stub selection disables the actions and says why`() {
        val entries = SelectionActionSpec.entries(hasDiagnostic = false, selectionChars = 0)
        assertTrue(entries.none { it.enabled })
        assertTrue(entries.all { it.reason != null })
    }

    @Test
    fun `edit is the only action that needs an instruction`() {
        assertEquals("How should this change?", SelectionActionSpec.placeholder())
    }

    private fun ctx() = EditorActionMenuSpec.contextFor(
        text = "fun x() { y() }\n",
        selectionStart = 0,
        selectionEnd = 5,
        fileName = "/p/File.kt",
        languageName = "Kotlin",
        diagnosticMessage = "Unresolved reference: y",
        diagnosticLine = 1,
    )

    @Test
    fun `ask answers in chat and never asks for a write`() {
        val prompt = SelectionActionSpec.prompt(SelectionAction.ASK, "", ctx())
        assertFalse(prompt.contains("write_file"))
    }

    @Test
    fun `fix and edit are staged so the change is reviewable`() {
        listOf(SelectionAction.FIX, SelectionAction.EDIT).forEach { action ->
            val prompt = SelectionActionSpec.prompt(action, "rename y to z", ctx())
            assertTrue("staging for " + action, prompt.contains("write_file"))
        }
    }

    @Test
    fun `edit carries the typed instruction and the code`() {
        val prompt = SelectionActionSpec.prompt(SelectionAction.EDIT, "  rename y to z  ", ctx())
        assertTrue(prompt.contains("Instruction: rename y to z"))
        assertTrue(prompt.contains("fun x"))
    }

    @Test
    fun `an empty instruction still sends something usable`() {
        val prompt = SelectionActionSpec.prompt(SelectionAction.EDIT, "   ", ctx())
        assertTrue(prompt.contains("Instruction: Refactor this code."))
    }

    // ── Placement ───────────────────────────────────────────────────────────

    @Test
    fun `the bar sits below the line when there is room`() {
        val y = SelectionActionSpec.anchorY(
            selectionTopDp = 100f, lineHeightDp = 20f, toolbarHeightDp = 40f, viewportHeightDp = 600f,
        )
        assertTrue(y > 100f)
    }

    @Test
    fun `the bar flips above a line near the bottom`() {
        val y = SelectionActionSpec.anchorY(
            selectionTopDp = 560f, lineHeightDp = 20f, toolbarHeightDp = 40f, viewportHeightDp = 600f,
        )
        assertTrue(y < 560f)
    }

    @Test
    fun `the bar never leaves the editor`() {
        val top = SelectionActionSpec.anchorY(0f, 20f, 40f, 600f)
        val bottom = SelectionActionSpec.anchorY(599f, 20f, 40f, 600f)
        assertTrue(top >= 0f)
        assertTrue(bottom + 40f <= 600f)
    }
}
