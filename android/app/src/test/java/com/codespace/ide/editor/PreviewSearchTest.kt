package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding inside a file opened as a read-only preview (owner order 2026-11-08, large-file viewer).
 *
 * The failure this prevents: the editor's Find works on the text field, which holds one page, so
 * searching a 13,000-line file searched the first 5,280 lines and said "no matches" for a line that
 * exists.
 */
class PreviewSearchTest {

    private val content = buildString {
        for (i in 1..13_000) append("const v").append(i).append(" = ").append(i).append(";\n")
    }

    @Test
    fun aHitOnTheThirdPageIsFoundAndReportsItsAbsoluteLine() {
        val r = PreviewSearch.find(content, "const v12000 ")
        assertEquals(1, r.totalMatches)
        assertEquals(12_000, r.hits.first().line)
        assertFalse(r.capped)
        assertTrue(PreviewSearch.summary(r).contains("12000"))
    }

    @Test
    fun searchIsCaseInsensitiveOnPurpose() {
        val r = PreviewSearch.find(content, "CONST V9000 ")
        assertEquals(1, r.totalMatches)
        assertEquals(9_000, r.hits.first().line)
    }

    @Test
    fun everyHitIsFoundNotJustThePage() {
        // "const v1" appears on lines 1, 10-19, 100-199, 1000-1999, 10000-13000 ...
        val r = PreviewSearch.find(content, "const v1", maxHits = 10_000)
        // Lines containing "const v1": 1; 10-19; 100-199; 1000-1999; 10000-13000 = 4112 exactly.
        assertEquals("must search the whole file, not one page", 4_112, r.totalMatches)
        assertTrue("and include lines past page one", r.hits.any { it.line > 5_280 })
    }

    @Test
    fun theCapIsReportedAndNeverSilent() {
        val r = PreviewSearch.find(content, "const", maxHits = 25)
        assertEquals(25, r.hits.size)
        assertEquals(13_000, r.totalMatches)
        assertTrue(r.capped)
        val summary = PreviewSearch.summary(r)
        assertTrue(summary, summary.contains("13000"))
        assertTrue(summary, summary.contains("first 25"))
    }

    @Test
    fun noMatchSaysSoWithTheQuery() {
        val r = PreviewSearch.find(content, "zzz-not-here")
        assertTrue(r.isEmpty)
        assertEquals("No matches for \"zzz-not-here\" in this file.", PreviewSearch.summary(r))
    }

    @Test
    fun anEmptyQueryFindsNothingRatherThanEverything() {
        val r = PreviewSearch.find(content, "")
        assertTrue(r.isEmpty)
        assertEquals("", PreviewSearch.summary(r))
    }

    @Test
    fun theHitTextIsTrimmedAndBounded() {
        val long = "x".repeat(400) + "needle"
        val r = PreviewSearch.find(long, "needle")
        assertTrue("preview text must be bounded", r.hits.first().text.length <= 160)
    }
}
