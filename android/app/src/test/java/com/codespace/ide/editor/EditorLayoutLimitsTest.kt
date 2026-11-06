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
