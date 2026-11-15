package com.codespace.ide.agent.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codespace.ide.agent.docs.OcrStructure.Line

/**
 * PROJECT 2.12: the half of OCR an engine cannot do, checked without an engine.
 *
 * The cases are the ones that actually break: a two-column page, a code screenshot that has lost
 * its indentation, prose that must NOT be reformatted as code, and an engine that reports no
 * confidence at all.
 */
class OcrStructureTest {

    private fun line(text: String, left: Int, top: Int, w: Int = 400, h: Int = 30, conf: Float? = 0.9f) =
        Line(text, left, top, left + w, top + h, conf)

    @Test
    fun simpleProseKeepsItsOrderAndBreaksAtRealGaps() {
        val lines = listOf(
            line("The first sentence.", 40, 100),
            line("The second sentence.", 40, 140),
            line("A new paragraph starts here.", 40, 260),
        )
        val out = OcrStructure.assemble(lines)
        assertEquals(
            "The first sentence.\nThe second sentence.\n\nA new paragraph starts here.",
            out.text,
        )
        assertTrue(!out.looksLikeCode)
    }

    @Test
    fun aTwoColumnPageDoesNotInterleave() {
        val left = (0 until 4).map { line("left column line " + it, 40, 100 + it * 40) }
        val right = (0 until 4).map { line("right column line " + it, 500, 100 + it * 40) }
        val out = OcrStructure.assemble(left + right)
        val indexRight = out.text.indexOf("right column line 0")
        val indexLastLeft = out.text.indexOf("left column line 3")
        assertTrue(
            "column B must come after column A, got: " + out.text,
            indexRight > indexLastLeft,
        )
    }

    @Test
    fun aCodeScreenshotGetsItsIndentationBack() {
        val lines = listOf(
            line("fun main() {", 100, 100, w = 260),
            line("if (ready) {", 130, 140, w = 220),
            line("start()", 160, 180, w = 140),
            line("}", 130, 220, w = 20),
            line("}", 100, 260, w = 20),
        )
        val out = OcrStructure.assemble(lines)
        assertTrue("should be detected as code", out.looksLikeCode)
        val rows = out.text.split("\n")
        assertEquals("fun main() {", rows[0])
        assertTrue("nested if should be indented, got: " + rows[1], rows[1].startsWith(" "))
        assertTrue("inner call should be indented further", rows[2].startsWith("        ") || rows[2].startsWith("            "))
        assertEquals(5, out.lineCount)
    }

    @Test
    fun proseWithOneSemicolonIsNotTreatedAsCode() {
        val lines = (0 until 10).map {
            line("This is ordinary prose about a topic and it runs on for a while, sentence " + it + ".", 40, 100 + it * 40, w = 900)
        }
        assertTrue(!OcrStructure.looksLikeCode(lines))
    }

    @Test
    fun aQuietEngineReportsNoConfidenceRatherThanZero() {
        val lines = listOf(line("text", 10, 10, conf = null), line("more text", 10, 50, conf = null))
        assertNull(OcrStructure.assemble(lines).confidence)
    }

    @Test
    fun confidenceIsTheMeanOfWhatTheEngineGave() {
        val lines = listOf(line("a", 10, 10, conf = 0.8f), line("b", 10, 50, conf = 0.6f))
        val out = OcrStructure.assemble(lines)
        assertEquals(0.7f, out.confidence!!, 0.001f)
    }

    @Test
    fun nothingRecognisedIsNotAnError() {
        val out = OcrStructure.assemble(emptyList())
        assertEquals("", out.text)
        assertEquals(0, out.lineCount)
        assertNull(out.confidence)
    }

    @Test
    fun blankLinesFromTheEngineAreDropped() {
        val out = OcrStructure.assemble(listOf(Line("  ", 10, 10, 50, 40), line("real", 10, 50)))
        assertEquals("real", out.text)
        assertEquals(1, out.lineCount)
    }
}
