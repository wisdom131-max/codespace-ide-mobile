package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision table for the Compose Constraints guard, across the densities and settings the owner
 * actually changes. The point of the table is regression: 5,000-line files must keep opening
 * (they always did), 13,000-line files must open as a preview instead of crashing, and a font
 * scale or wrap change must not silently push a file over the ceiling.
 */
class EditorLayoutLimitsTest {

    private val densities = listOf(2.0f, 2.75f, 3.0f, 3.5f)
    private val fontScales = listOf(1.0f, 1.3f)
    private val fontSizes = listOf(13f, 16f)

    private fun metrics(d: Float, scale: Float, font: Float) = EditorLayoutLimits.Metrics(
        fontSizeSp = font,
        density = d,
        fontScale = scale,
        widthPx = 1080,
    )

    @Test
    fun lineHeightMatchesTheEditorFormulaEverywhere() {
        for (d in densities) for (sc in fontScales) for (f in fontSizes) {
            val m = metrics(d, sc, f)
            val expected = f * EditorMetrics.LINE_HEIGHT_MULTIPLIER * d * sc
            assertEquals("d=$d scale=$sc font=$f", expected, EditorLayoutLimits.lineHeightPx(m), 0.01f)
        }
    }

    @Test
    fun withoutWrapThePredictionIsExact() {
        for (d in densities) for (sc in fontScales) for (f in fontSizes) {
            val m = metrics(d, sc, f)
            val lh = EditorLayoutLimits.lineHeightPx(m)
            val predicted = EditorLayoutLimits.predictHeightPx(1000, 40, m, wrap = false)
            assertEquals("d=$d scale=$sc font=$f", (1000 * lh + m.paddingPx).toLong(), predicted)
        }
    }

    @Test
    fun fiveThousandLinesOpenWhereverTheArithmeticAllowsIt() {
        // The defect this locks out: the first guard worked to 60% of the ceiling and refused
        // 5,000 lines outright (3,519 at density 2.0), although they had always opened.
        val d20 = metrics(2.0f, 1.0f, 13f)
        val d275 = metrics(2.75f, 1.0f, 13f)
        val d35 = metrics(3.5f, 1.0f, 16f)
        assertTrue("5000 lines at density 2.0 / 13sp (budget 7,259)", EditorLayoutLimits.fits(5000, 60, d20, false))
        assertTrue("5000 lines at density 2.75 / 13sp (budget 5,280)", EditorLayoutLimits.fits(5000, 60, d275, false))
        // Large fonts on a dense screen genuinely cannot hold 5,000 lines: the hard ceiling is
        // 262,143 px, i.e. 3,745 lines at 70 px each. A preview is the honest answer there.
        assertFalse("5000 lines at density 3.5 / 16sp (budget 3,370)", EditorLayoutLimits.fits(5000, 60, d35, false))
    }

    @Test
    fun thirteenThousandLinesOpenAsPreviewNotAsACrash() {
        for (d in densities) for (sc in fontScales) {
            val m = metrics(d, sc, 13f)
            assertFalse(
                "13000 lines must not go into one text field at density=$d scale=$sc",
                EditorLayoutLimits.fits(13_000, 60, m, wrap = false),
            )
            val budget = EditorLayoutLimits.previewLineBudget(60, m, wrap = false)
            assertTrue("preview budget must be positive", budget > 0)
            assertTrue(
                "the preview itself must fit (budget=$budget at density=$d scale=$sc)",
                EditorLayoutLimits.fits(budget, 60, m, wrap = false),
            )
        }
    }

    @Test
    fun wrapReducesTheBudgetAndNeverIncreasesIt() {
        for (d in densities) {
            val m = metrics(d, 1.0f, 13f)
            val noWrap = EditorLayoutLimits.previewLineBudget(200, m, wrap = false)
            val wrapped = EditorLayoutLimits.previewLineBudget(200, m, wrap = true)
            assertTrue("wrap must not give MORE lines (d=$d): $wrapped vs $noWrap", wrapped <= noWrap)
            assertTrue(
                "a wrapped 200-char line is more than one display line at density=$d",
                EditorLayoutLimits.predictHeightPx(1, 200, m, wrap = true) >
                    EditorLayoutLimits.predictHeightPx(1, 20, m, wrap = true),
            )
        }
    }

    @Test
    fun aFontScaleChangeCanPushAFileOverAndTheCheckNotices() {
        // 4,500 lines: fits at scale 1.0 and 13sp on a 3.0-density screen, not necessarily at 1.3.
        val normal = metrics(3.0f, 1.0f, 13f)
        val scaled = metrics(3.0f, 1.3f, 13f)
        val atNormal = EditorLayoutLimits.fits(4500, 60, normal, wrap = false)
        val atScaled = EditorLayoutLimits.fits(4500, 60, scaled, wrap = false)
        assertTrue("4500 lines fit at scale 1.0", atNormal)
        assertFalse("4500 lines do not fit at scale 1.3", atScaled)
    }

    @Test
    fun previewKeepsTheFirstLinesAndStopsAtTheBudget() {
        val content = (1..10).joinToString("\n") { "line $it" }
        val preview = EditorLayoutLimits.previewOf(content, 3)
        assertEquals("line 1\nline 2\nline 3\n", preview)
        assertEquals(content, EditorLayoutLimits.previewOf(content, 999))
    }

    @Test
    fun theGuardSitsAtNinetyPercentOfTheRepresentationalCeiling() {
        assertEquals(262_143, EditorLayoutLimits.MAX_DIMENSION_PX)
        assertEquals((262_143 * 0.9f).toInt(), EditorLayoutLimits.SAFE_MAX_PX)
        assertTrue(EditorLayoutLimits.SAFE_MAX_PX < EditorLayoutLimits.MAX_DIMENSION_PX)
    }
}

/**
 * Preview paging: every line of a too-tall file must stay reachable, and a page boundary must not
 * invent, drop or duplicate a line. Owner order 2026-11-07, addition 2.
 */
class PreviewPagingTest {

    private fun pages(totalLines: Int, per: Int): List<Pair<Int, String>> {
        val text = (1..totalLines).joinToString("") { "line $it\n" }
        val out = ArrayList<Pair<Int, String>>()
        var start = 0
        while (start < totalLines) {
            out.add(start to EditorLayoutLimits.previewSlice(text, start, per))
            start += per
        }
        return out
    }

    @Test
    fun lineCountIgnoresATrailingNewline() {
        assertEquals(1, EditorLayoutLimits.lineCount(""))
        assertEquals(1, EditorLayoutLimits.lineCount("one"))
        assertEquals(1, EditorLayoutLimits.lineCount("one\n"))
        assertEquals(2, EditorLayoutLimits.lineCount("one\ntwo"))
        assertEquals(2, EditorLayoutLimits.lineCount("one\ntwo\n"))
    }

    @Test
    fun firstMiddleAndLastPagesHoldTheRightLines() {
        val all = (1..10).joinToString("") { "line $it\n" }
        assertEquals("line 1\nline 2\nline 3\nline 4\n", EditorLayoutLimits.previewSlice(all, 0, 4))
        assertEquals("line 5\nline 6\nline 7\nline 8\n", EditorLayoutLimits.previewSlice(all, 4, 4))
        // last page is short: 2 of the 4 requested lines
        assertEquals("line 9\nline 10\n", EditorLayoutLimits.previewSlice(all, 8, 4))
    }

    @Test
    fun aTotalThatIsNotDivisibleLeavesAShortLastPageAndNoLostLine() {
        val joined = pages(totalLines = 13, per = 5)
        assertEquals(3, joined.size)
        assertEquals(5, joined[0].second.count { it == '\n' })
        assertEquals(3, joined[2].second.count { it == '\n' })
        val rebuilt = joined.joinToString("") { it.second }
        assertEquals((1..13).joinToString("") { "line $it\n" }, rebuilt)
    }

    @Test
    fun crlfKeepsItsCarriageReturnsInsideTheLine() {
        val crlf = "a\r\nb\r\nc\r\n"
        assertEquals("a\r\nb\r\n", EditorLayoutLimits.previewSlice(crlf, 0, 2))
        assertEquals("c\r\n", EditorLayoutLimits.previewSlice(crlf, 2, 2))
    }

    @Test
    fun oneHugeLineIsOnePageAndIsNotSplit() {
        val huge = "x".repeat(500_000) + "\nsecond\n"
        val slice = EditorLayoutLimits.previewSlice(huge, 0, 1)
        assertEquals(500_001, slice.length)
        assertTrue("the huge line is returned whole", slice.endsWith("\n"))
        assertEquals("second\n", EditorLayoutLimits.previewSlice(huge, 1, 1))
    }

    @Test
    fun aStartPastTheEndClampsToTheLastPageInsteadOfBlank() {
        val all = (1..6).joinToString("") { "line $it\n" }
        assertEquals("line 6\n", EditorLayoutLimits.previewSlice(all, 99, 2))
    }

    @Test
    fun theBannerTextNamesAbsoluteLineNumbers() {
        val note = EditorLayoutLimits.pageNote(5281, 10560, 13000)
        assertTrue(note, note.contains("5281-10560"))
        assertTrue(note, note.contains("13000"))
        assertTrue(note, note.contains("read-only"))
    }
}
