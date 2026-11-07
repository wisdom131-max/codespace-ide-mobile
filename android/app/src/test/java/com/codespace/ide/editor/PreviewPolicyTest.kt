package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner order 2026-11-07, addition 3. A preview tab holds a SLICE of a file, so every capability
 * that assumes the tab is the whole document must refuse it, and every capability keyed on a line
 * number must either refuse it or convert through [PreviewNavigation].
 */
class PreviewPolicyTest {

    @Test
    fun aPreviewIsRefusedEveryWholeDocumentCapability() {
        for (readOnly in listOf(true, false)) {
            val expected = !readOnly
            assertEquals("lsp sync", expected, PreviewPolicy.shouldSyncToLsp(readOnly))
            assertEquals("lint", expected, PreviewPolicy.shouldPublishLint(readOnly))
            assertEquals("language features", expected, PreviewPolicy.shouldServeLanguageFeatures(readOnly))
            assertEquals("write to disk", expected, PreviewPolicy.shouldWriteToDisk(readOnly))
            assertEquals("go dirty", expected, PreviewPolicy.canGoDirty(readOnly))
            assertEquals("line-keyed edits", expected, PreviewPolicy.shouldApplyLineKeyedEdits(readOnly))
            assertEquals("document-wide edits", expected, PreviewPolicy.shouldApplyDocumentWideEdit(readOnly))
        }
        // The one that would destroy data if it were ever true.
        assertFalse(
            "an autosave must never write a preview slice over the real file",
            PreviewPolicy.shouldWriteToDisk(true),
        )
        assertNull("an editable tab carries no note", PreviewPolicy.note(false, "lines 1-10"))
        assertTrue(
            "a preview always carries a note",
            PreviewPolicy.note(true, "lines 1-10")?.contains("read-only") == true,
        )
    }

    @Test
    fun navigationLandsOnThePageHoldingTheLine() {
        val per = 1000
        // First line of the file is page 0, line 1.
        assertEquals(0, PreviewNavigation.pageStartFor(1, per))
        assertEquals(1, PreviewNavigation.pageLineFor(1, per))
        // A line in the middle of page 3 (lines 2001-3000) is page start 2000.
        assertEquals(2000, PreviewNavigation.pageStartFor(2456, per))
        assertEquals(456, PreviewNavigation.pageLineFor(2456, per))
        // A page boundary is the FIRST line of the next page, not the last of the previous one.
        assertEquals(2000, PreviewNavigation.pageStartFor(2001, per))
        assertEquals(1, PreviewNavigation.pageLineFor(2001, per))
        assertEquals("line 1000 is the LAST line of page 0", 0, PreviewNavigation.pageStartFor(1000, per))
        assertEquals(1000, PreviewNavigation.pageLineFor(1000, per))
        // Last page can be short: the conversion still maps into it.
        assertEquals(12000, PreviewNavigation.pageStartFor(12500, per))
        assertEquals(500, PreviewNavigation.pageLineFor(12500, per))
    }

    @Test
    fun absoluteAndPageLinesRoundTrip() {
        val per = 5280
        for (absolute in listOf(1, 5280, 5281, 10560, 13000)) {
            val start = PreviewNavigation.pageStartFor(absolute, per)
            val pageLine = PreviewNavigation.pageLineFor(absolute, per)
            assertEquals(
                "round trip for absolute line $absolute",
                absolute,
                PreviewNavigation.absoluteLineFor(start, pageLine),
            )
        }
    }

    @Test
    fun degeneratePageSizesNeverThrowOrInvert() {
        assertEquals(0, PreviewNavigation.pageStartFor(10, 0))
        assertEquals(1, PreviewNavigation.pageLineFor(10, 0))
        assertEquals(0, PreviewNavigation.pageStartFor(0, 100))
        assertEquals(1, PreviewNavigation.pageLineFor(0, 100))
    }
}
