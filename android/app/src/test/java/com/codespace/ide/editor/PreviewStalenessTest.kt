package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stale preview (owner order 2026-11-07, item d): a preview's slice was read at open time, so an
 * external change must either refresh the same page or say that the page is gone.
 */
class PreviewStalenessTest {

    private val a = PreviewStaleness.Stamp(1_000L, 5_000L)
    private val b = PreviewStaleness.Stamp(2_000L, 6_000L)

    @Test
    fun anUnchangedFileDoesNothing() {
        assertEquals(
            PreviewStaleness.Action.NOTHING,
            PreviewStaleness.decide(a, a, pageStartLine = 0, currentLineCount = 13_000),
        )
    }

    @Test
    fun aChangedFileReloadsTheSamePage() {
        assertEquals(
            PreviewStaleness.Action.RELOAD,
            PreviewStaleness.decide(a, b, pageStartLine = 5_280, currentLineCount = 13_400),
        )
    }

    @Test
    fun aFileThatBecameShorterThanThePageNotifiesInsteadOfJumping() {
        assertEquals(
            "page starts at 5280 but the file now has 4000 lines",
            PreviewStaleness.Action.NOTIFY,
            PreviewStaleness.decide(a, b, pageStartLine = 5_280, currentLineCount = 4_000),
        )
    }

    @Test
    fun edgeCaseExactlyAtThePageStartStillReloads() {
        assertEquals(
            PreviewStaleness.Action.RELOAD,
            PreviewStaleness.decide(a, b, pageStartLine = 5_279, currentLineCount = 5_280),
        )
        assertEquals(
            PreviewStaleness.Action.NOTIFY,
            PreviewStaleness.decide(a, b, pageStartLine = 5_280, currentLineCount = 5_280),
        )
    }

    @Test
    fun aDeletedFileLeavesTheTabAlone() {
        assertEquals(
            "no stamp means the file is gone; the tab keeps what it has",
            PreviewStaleness.Action.NOTHING,
            PreviewStaleness.decide(a, null, pageStartLine = 0, currentLineCount = 0),
        )
    }

    @Test
    fun theFirstObservationAfterOpenReloadsOnce() {
        assertEquals(
            PreviewStaleness.Action.RELOAD,
            PreviewStaleness.decide(null, a, pageStartLine = 0, currentLineCount = 13_000),
        )
    }

    @Test
    fun bothSentencesNameTheFile() {
        assertTrue(PreviewStaleness.staleMessage("big13k.js").contains("big13k.js"))
        assertTrue(PreviewStaleness.staleMessage("big13k.js").contains("Reload"))
        val reloaded = PreviewStaleness.reloadedMessage("big13k.js", 5281, 10_560)
        assertTrue(reloaded.contains("5281-10560"))
    }
}
