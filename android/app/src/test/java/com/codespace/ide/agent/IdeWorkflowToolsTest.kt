package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the notebook, patch, and todo tools. */
class IdeWorkflowToolsTest {

    // --- apply_patch -------------------------------------------------------------------

    @Test
    fun patchReplacesOneLineWhenContextMatches() {
        val original = "one\ntwo\nthree\n"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
             one
            -two
            +TWO
             three
        """.trimIndent()
        val result = PatchApplier.apply(original, diff)
        assertTrue(result is EditResult.Ok)
        assertEquals("one\nTWO\nthree\n", (result as EditResult.Ok).text)
    }

    @Test
    fun patchIsRefusedWhenContextDoesNotMatch() {
        val original = "one\ntwo\nthree\n"
        val diff = """
            @@ -1,3 +1,3 @@
             one
            -WRONG
            +TWO
             three
        """.trimIndent()
        val result = PatchApplier.apply(original, diff)
        assertTrue("a wrong patch must never be applied", result is EditResult.Refused)
        assertTrue((result as EditResult.Refused).reason.contains("does not match"))
    }

    @Test
    fun patchAddsAndRemovesLines() {
        val original = "a\nb\n"
        val diff = """
            @@ -1,2 +1,2 @@
             a
            -b
            +b
            +c
        """.trimIndent()
        val result = PatchApplier.apply(original, diff)
        assertTrue(result is EditResult.Ok)
        assertEquals("a\nb\nc\n", (result as EditResult.Ok).text)
    }

    @Test
    fun patchWithNoHunksIsRefused() {
        assertTrue(PatchApplier.apply("x", "--- a\n+++ b\n") is EditResult.Refused)
    }

    // --- notebooks ---------------------------------------------------------------------

    private fun notebook(): String = NotebookModel.newNotebook(
        listOf("markdown" to "# Title", "code" to "print(1)"),
    )

    @Test
    fun newNotebookIsReadableAsCells() {
        val cells = NotebookModel.cells(notebook())
        assertTrue(cells != null)
        assertEquals(2, cells!!.size)
        assertEquals("markdown", cells[0].kind)
        assertEquals("# Title", cells[0].source)
        assertEquals("code", cells[1].kind)
    }

    @Test
    fun summaryListsEveryCellAndSaysWhenEmpty() {
        val text = notebook()
        val summary = NotebookModel.summary(text) as EditResult.Ok
        assertTrue(summary.text.contains("0 [markdown] # Title"))
        assertTrue(summary.text.contains("1 [code] print(1)"))

        val empty = NotebookModel.newNotebook(emptyList())
        assertTrue((NotebookModel.summary(empty) as EditResult.Ok).text.contains("cell"))
    }

    @Test
    fun editingAppendsWhenIndexIsMinusOneAndReplacesOtherwise() {
        val base = notebook()
        val appended = NotebookModel.editCell(base, -1, "code", "print(2)") as EditResult.Ok
        assertEquals(3, NotebookModel.cells(appended.text)!!.size)

        val replaced = NotebookModel.editCell(base, 0, "code", "print(9)") as EditResult.Ok
        val cells = NotebookModel.cells(replaced.text)!!
        assertEquals(2, cells.size)
        assertEquals("code", cells[0].kind)
        assertEquals("print(9)", cells[0].source)
    }

    @Test
    fun editingAPastTheEndCellIsRefusedNotSilentlyAppended() {
        val result = NotebookModel.editCell(notebook(), 7, "code", "x")
        assertTrue(result is EditResult.Refused)
        assertTrue((result as EditResult.Refused).reason.contains("does not exist"))
    }

    @Test
    fun aCellWithNoOutputsSaysSoInsteadOfInventingText() {
        val result = NotebookModel.outputsOf(notebook(), 1)
        assertTrue(result is EditResult.Ok)
        assertTrue((result as EditResult.Ok).text.contains("no outputs"))
    }

    @Test
    fun aPlainJsonFileIsNotANotebook() {
        assertTrue(!NotebookModel.isNotebook("{\"a\":1}"))
        assertTrue(NotebookModel.summary("{\"a\":1}") is EditResult.Refused)
    }

    // --- todo board --------------------------------------------------------------------

    @Test
    fun todoBoardRoundTripsAndRendersStatusMarks() {
        val saved = TodoBoard.write(
            """[{"id":"1","text":"ship","status":"completed"},{"id":"2","text":"test","status":"in_progress"},{"id":"3","text":"rest","status":"pending"}]""",
        )
        assertTrue(saved is EditResult.Ok)
        val rendered = TodoBoard.render((saved as EditResult.Ok).text)
        assertTrue(rendered.contains("[x] 1. ship"))
        assertTrue(rendered.contains("[>] 2. test"))
        assertTrue(rendered.contains("[ ] 3. rest"))
    }

    @Test
    fun todoBoardRefusesAnUnknownStatusInsteadOfStoringIt() {
        val result = TodoBoard.write("""[{"id":"1","text":"x","status":"doing"}]""")
        assertTrue(result is EditResult.Refused)
        assertTrue((result as EditResult.Refused).reason.contains("pending, in_progress, completed"))
    }

    @Test
    fun anEmptyBoardSaysItIsEmpty() {
        assertEquals("The todo list is empty.", TodoBoard.render(null))
        assertEquals("The todo list is empty.", TodoBoard.render("""{"todos":[]}"""))
    }
}
