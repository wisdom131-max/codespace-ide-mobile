package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Defect 2 (owner order 2026-11-07): EVERY text-changing path must be refused when it would push
 * the document past the Compose layout ceiling.
 *
 * The paths differ only in how they produce a candidate string. They all funnel through the same
 * gate, so each path is tested by handing the gate the candidate that path would have produced:
 * typing a newline, pasting a block, undoing a large deletion, a multi-cursor insert, replace-all,
 * a format/AI/refactor result, a snippet, and an external change from disk.
 */
class LayoutEditGateTest {

    private fun metrics(
        density: Float = 2.75f,
        fontScale: Float = 1f,
        fontSizeSp: Float = 13f,
    ) = EditorLayoutLimits.Metrics(
        fontSizeSp = fontSizeSp,
        density = density,
        fontScale = fontScale,
        widthPx = 1080,
    )

    private fun text(lines: Int, charsPerLine: Int = 60): String =
        (1..lines).joinToString("\n") { "x".repeat(charsPerLine) }

    @Test
    fun typingANewlineNearTheCeilingIsAllowedUntilItIsNot() {
        val m = metrics()
        // 5,000 lines of 60 chars: under the guard at density 2.75.
        assertEquals(LayoutEditGate.Verdict.ALLOW, LayoutEditGate.verdictForText(text(5000), m, false))
        // The same document with the font doubled: over.
        assertEquals(
            LayoutEditGate.Verdict.REFUSE,
            LayoutEditGate.verdictForText(text(5000), metrics(fontSizeSp = 26f), false),
        )
    }

    @Test
    fun pastingABlockThatCrossesTheLimitIsRefused() {
        val m = metrics()
        val before = text(4000)
        assertEquals(LayoutEditGate.Verdict.ALLOW, LayoutEditGate.verdictForText(before, m, false))
        val afterPaste = before + "\n" + text(4000)
        assertEquals(LayoutEditGate.Verdict.REFUSE, LayoutEditGate.verdictForText(afterPaste, m, false))
    }

    @Test
    fun undoRedoMultiCursorReplaceAllAndSnippetsShareTheSameRule() {
        val m = metrics()
        // Each path hands the gate a candidate document; the gate cannot tell them apart, and that
        // is the point: one rule, no path-specific holes.
        val candidate = text(9000)
        assertEquals(LayoutEditGate.Verdict.REFUSE, LayoutEditGate.verdictForText(candidate, m, false))
    }

    @Test
    fun externalSyncFromDiskIsRefusedToo() {
        val m = metrics()
        assertEquals(LayoutEditGate.Verdict.REFUSE, LayoutEditGate.verdictForText(text(12000), m, false))
    }

    @Test
    fun wrapAndFontScaleReduceTheBudget() {
        val noWrap = LayoutEditGate.verdictForText(text(5000, 200), metrics(), false)
        val wrapped = LayoutEditGate.verdictForText(text(5000, 200), metrics(), true)
        assertEquals(LayoutEditGate.Verdict.ALLOW, noWrap)
        assertEquals("200-char lines wrap and multiply the display height", LayoutEditGate.Verdict.REFUSE, wrapped)
        val scaled = LayoutEditGate.verdictForText(text(5000), metrics(fontScale = 1.3f), false)
        assertEquals(LayoutEditGate.Verdict.REFUSE, scaled)
    }

    @Test
    fun zoomIsClampedToTheLargestSizeThatStillFits() {
        val clamped = LayoutEditGate.clampFontSize(
            requested = 32,
            min = 8,
            lineCount = 5000,
            avgLineChars = 60,
            metrics = metrics(),
            wrap = false,
        )
        assertTrue("clamp must actually reduce the request (was $clamped)", clamped < 32)
        assertTrue("clamp must stay above the minimum", clamped >= 8)
        // At the clamped size the document fits; one step larger it does not.
        val fitsClamped = EditorLayoutLimits.fits(
            5000, 60, metrics(fontSizeSp = clamped.toFloat()), false,
        )
        assertTrue("clamped size must fit", fitsClamped)
        assertFalse(
            "one sp larger must overflow",
            EditorLayoutLimits.fits(5000, 60, metrics(fontSizeSp = (clamped + 1).toFloat()), false),
        )
    }

    @Test
    fun theRefusalSentenceIsPlainAndSaysNothingChanged() {
        assertTrue(LayoutEditGate.REFUSAL_MESSAGE.contains("refused"))
        assertTrue(LayoutEditGate.REFUSAL_MESSAGE.contains("Nothing was changed"))
    }
}
