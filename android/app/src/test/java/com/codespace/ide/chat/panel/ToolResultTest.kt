package com.codespace.ide.chat.panel

import com.codespace.ide.chat.panel.ToolResultPresentation.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Area 4: what a tool result LOOKS like - classification, summary and bounding. */
class ToolResultTest {

    @Test
    fun aNormalResultReadsAsAResult() {
        val p = ToolResultPresentation.present("read_file", true, "MainActivity.kt\nval x = 1")
        assertEquals(Kind.RESULT, p.kind)
        assertEquals("read_file", p.title)
        assertEquals("MainActivity.kt", p.summary)
    }

    @Test
    fun aFailedToolReadsAsAnErrorAndSaysSoInTheTitle() {
        val p = ToolResultPresentation.present("read_file", false, "ENOENT: no such file")
        assertEquals(Kind.ERROR, p.kind)
        assertEquals("read_file failed", p.title)
        assertTrue(p.summary.contains("ENOENT"))
    }

    @Test
    fun anEmptyResultIsNotEmptyLooking() {
        val p = ToolResultPresentation.present("grep_search", true, "")
        assertEquals(Kind.EMPTY, p.kind)
        assertEquals("No output", p.summary)
    }

    @Test
    fun aCappedResultIsBrandedPartial() {
        val p = ToolResultPresentation.present("run_command", true, "line\n... [truncated at 4000 chars]")
        assertEquals(Kind.PARTIAL, p.kind)
        assertEquals("run_command (partial)", p.title)
    }

    @Test
    fun theWordTruncatedInTheMiddleDoesNotBrandAResult() {
        val body = (1..200).joinToString("\n") { "src/file$it.kt" } + "\nall files listed above"
        val p = ToolResultPresentation.present("list_files", true, "files\n" + body.replace("src/file5.kt", "truncated_name.kt"))
        assertEquals(Kind.RESULT, p.kind)
    }

    @Test
    fun aLongResultCollapsesButKeepsItsHead() {
        val lines = (1..40).joinToString("\n") { "line $it" }
        val p = ToolResultPresentation.present("run_command", true, "ok\n" + lines)
        assertTrue(p.collapsible)
        assertEquals("ok", p.summary)
        assertTrue(p.body.isNotEmpty())
    }

    @Test
    fun aShortResultDoesNotCollapse() {
        val p = ToolResultPresentation.present("read_file", true, "a\nb")
        assertFalse(p.collapsible)
    }

    @Test
    fun aHugeResultIsBoundedAndSaysWhatItWithheld() {
        val lines = (1..500).joinToString("\n") { "row $it" }
        val p = ToolResultPresentation.present("csv_parse", true, "header\n" + lines)
        assertTrue(p.bodyTruncated)
        assertTrue("body must stay bounded", p.body.size <= 60)
    }

    @Test
    fun theSummaryIsBounded() {
        val p = ToolResultPresentation.present("run_command", true, "x".repeat(5000))
        assertTrue(p.summary.length <= 160)
    }

    // --- the turn header ---------------------------------------------------------

    @Test
    fun aFinishedTurnSaysHowManyToolsItUsed() {
        assertEquals("Done, 3 tools used.", ToolResultPresentation.turnSummary(3, Kind.RESULT, false))
        assertEquals("Done, 1 tool used.", ToolResultPresentation.turnSummary(1, Kind.RESULT, false))
    }

    @Test
    fun aTruncatedTurnNeverReadsAsDone() {
        val s = ToolResultPresentation.turnSummary(2, Kind.RESULT, true)
        assertTrue(s, s.contains("Stopped early"))
        assertFalse(s, s.contains("Done"))
    }

    @Test
    fun aFailedTurnSaysSoWithoutADoneClaim() {
        val s = ToolResultPresentation.turnSummary(1, Kind.ERROR, false)
        assertTrue(s, s.contains("error"))
        assertFalse(s, s.contains("Done"))
    }
}
