package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The constraints audit for NON-editor surfaces (owner order 2026-11-07, item c).
 *
 * A chat code block and a diff pane both draw a whole document in one layout, so both can throw in
 * Constraints exactly like the editor did. The cap keeps them under the ceiling and states what it
 * did, because a silently shortened block would be a lie about the content.
 */
class TextRenderLimitsTest {

    @Test
    fun shortContentIsUntouchedAndCarriesNoNote() {
        val text = (1..10).joinToString("\n") { "line $it" }
        val (capped, note) = TextRenderLimits.cap(text)
        assertEquals(text, capped)
        assertNull("nothing to explain", note)
    }

    @Test
    fun aHugeBlockIsCappedAndSaysSo() {
        val text = (1..9000).joinToString("\n") { "line $it" }
        val (capped, note) = TextRenderLimits.cap(text)
        assertEquals(TextRenderLimits.MAX_RENDERED_LINES, EditorLayoutLimits.lineCount(capped))
        assertTrue("the head is kept exactly", capped.startsWith("line 1\n"))
        assertTrue("the note names both numbers", note!!.contains("2000") && note.contains("9000"))
        assertTrue(note, note.contains("Copy or open the file"))
    }

    @Test
    fun cappingNeverDropsPartOfALine() {
        val text = (1..5000).joinToString("\n") { "value=$it" }
        val (capped, _) = TextRenderLimits.cap(text)
        // The slice ends on a line break, so split() yields one empty trailing element.
        val lines = capped.split("\n").dropLastWhile { it.isEmpty() }
        assertEquals(TextRenderLimits.MAX_RENDERED_LINES, lines.size)
        assertTrue("the last kept line is whole: ${lines.last()}", lines.last() == "value=2000")
    }

    @Test
    fun theCapSitsUnderTheComposeCeilingAtTheDensestProfile() {
        val tallestLinePx = 70f // 16sp x 1.25 x density 3.5
        val worstCasePx = TextRenderLimits.MAX_RENDERED_LINES * tallestLinePx
        assertTrue(
            "2000 x 70px = $worstCasePx must stay under ${EditorLayoutLimits.MAX_DIMENSION_PX}",
            worstCasePx < EditorLayoutLimits.MAX_DIMENSION_PX,
        )
    }
}
