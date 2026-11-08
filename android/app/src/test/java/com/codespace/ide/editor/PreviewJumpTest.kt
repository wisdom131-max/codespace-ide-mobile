package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation into a preview (owner order 2026-11-07, item e).
 *
 * Every route - search result, go to definition, terminal file:line, Source Control open-at-line,
 * Problems, agent/Files Changed links - arrives as one owned jump carrying an ABSOLUTE line. A
 * preview holds one page and numbers it from 1, so the line must be converted or the reader lands on
 * page 1 of a 13,000-line file.
 */
class PreviewJumpTest {

    private val page = 5_280

    @Test
    fun onANormalTabTheLineIsPassedStraightThrough() {
        val t = PreviewJump.plan(requestedLine = 9_000, pageLines = 0, isPreview = false)
        assertEquals(9_000, t.pageLine)
        assertFalse("no page to swap on an editable tab", t.needsPageSwap)
        assertEquals(0, t.pageStart)
    }

    @Test
    fun aSearchHitAtLineNineThousandOpensThePageThatHoldsIt() {
        val t = PreviewJump.plan(requestedLine = 9_000, pageLines = page, isPreview = true, currentPageStart = 0)
        assertEquals("page 1 covers 5281-10560", 5_280, t.pageStart)
        assertEquals("line 9000 is page line 3720", 3_720, t.pageLine)
        assertTrue("the reader is currently on page 0", t.needsPageSwap)
    }

    @Test
    fun jumpingWithinThePageAlreadyShownNeedsNoSwap() {
        val t = PreviewJump.plan(requestedLine = 5_200, pageLines = page, isPreview = true, currentPageStart = 0)
        assertEquals(0, t.pageStart)
        assertEquals(5_200, t.pageLine)
        assertFalse(t.needsPageSwap)
    }

    @Test
    fun theFirstLineOfTheFileIsPageOneLineOne() {
        val t = PreviewJump.plan(requestedLine = 1, pageLines = page, isPreview = true)
        assertEquals(0, t.pageStart)
        assertEquals(1, t.pageLine)
    }

    @Test
    fun aBoundaryLineBelongsToTheNextPage() {
        val t = PreviewJump.plan(requestedLine = 5_281, pageLines = page, isPreview = true, currentPageStart = 0)
        assertEquals(5_280, t.pageStart)
        assertEquals(1, t.pageLine)
        assertTrue(t.needsPageSwap)
    }

    @Test
    fun conversionRoundTripsToTheAbsoluteLine() {
        for (line in listOf(1, 5_280, 5_281, 9_000, 13_000)) {
            val t = PreviewJump.plan(requestedLine = line, pageLines = page, isPreview = true)
            assertEquals(
                "round trip for $line",
                line,
                PreviewNavigation.absoluteLineFor(t.pageStart, t.pageLine),
            )
        }
    }

    @Test
    fun aPreviewWithNoPageSizedBudgetFallsBackToTheAbsoluteLine() {
        val t = PreviewJump.plan(requestedLine = 500, pageLines = 0, isPreview = true)
        assertEquals(500, t.pageLine)
        assertFalse(t.needsPageSwap)
    }
}
