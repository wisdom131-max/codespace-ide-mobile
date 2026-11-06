package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner order 2026-11-06, item 2. The cursor-word highlight caused the ANR on big.txt because it
 * searched the WHOLE document and then did two layout lookups per occurrence on every frame.
 *
 * The golden contract: inside the window, the ranges must be exactly what the old whole-document
 * regex produced. These tests pin that, then pin the windowing, the cap and the measured cost.
 */
class OccurrenceScanTest {

    private fun oldRanges(text: String, word: String): List<IntRange> =
        Regex("\\b" + Regex.escape(word) + "\\b").findAll(text)
            .map { it.range.first until it.range.last + 1 }
            .toList()

    private val small = """
        alpha line
        beta line
        gamma lines
        aline
        line
    """.trimIndent()

    @Test
    fun golden_smallFixtureIsIdenticalToTheOldWholeDocumentRegex() {
        val caret = small.indexOf("line", 0) + 2
        val word = OccurrenceScan.wordAt(small, caret)!!
        assertEquals("line", word)
        val got = OccurrenceScan.wordRanges(small, word, 0, small.length, caret)
        assertEquals("same ranges, same order as the old regex", oldRanges(small, word), got)
    }

    @Test
    fun golden_aWordClippedByTheWindowEdgeIsNotCountedTwiceOrWrongly() {
        val text = "one line here\nline again\nno line for you\n"
        val start = text.indexOf("line again")
        val window = start..(start + "line ag".length)
        val got = OccurrenceScan.wordRanges(text, "line", window.first, window.last, caret = start)
        assertEquals("only the occurrence that starts inside the window", 1, got.size)
        assertEquals(start, got.single().first)
    }

    @Test
    fun golden_partialWordsAreNotOccurrences() {
        val text = "lines aline line lineno"
        val got = OccurrenceScan.wordRanges(text, "line", 0, text.length, caret = 14)
        assertEquals(oldRanges(text, "line"), got)
        assertEquals("only the standalone word", 1, got.size)
    }

    @Test
    fun aboveTheCapItKeepsTheRangesNearestTheCaretNotTheFirstOnes() {
        val text = (1..500).joinToString(" ") { "line" }
        val caret = text.length - 3
        val got = OccurrenceScan.wordRanges(text, "line", 0, text.length, caret, cap = 200)
        assertEquals(200, got.size)
        assertTrue("the caret's own occurrence is kept", got.any { caret in it })
        val kept = got.maxOf { OccurrenceScan.distance(it, caret) }
        val firstTwoHundred = oldRanges(text, "line").take(200)
        val wouldHaveBeen = firstTwoHundred.maxOf { OccurrenceScan.distance(it, caret) }
        assertTrue(
            "nearest-to-caret must beat taking the first $kept vs $wouldHaveBeen",
            kept < wouldHaveBeen,
        )
        assertEquals("document order is preserved for stable drawing", got.sortedBy { it.first }, got)
    }

    @Test
    fun theOwnersBigTxtFixtureStaysBoundedAndFast() {
        val text = (0 until 5000).joinToString("\n") { "line %05d of the big fixture".format(it) }
        val caret = text.indexOf("line 04000")
        // One screen is ~55 lines; the window is that plus one screen of overscan each way.
        val windowLines = 165
        val windowStart = text.indexOf("line 03918")
        val windowEnd = (text.indexOf("line 04083") + 30).coerceAtMost(text.length)

        val t0 = System.nanoTime()
        val got = OccurrenceScan.wordRanges(text, "line", windowStart, windowEnd, caret)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println("[perf] occurrencscan big.txt: window $windowLines lines, ${got.size} ranges, ${ms}ms")

        assertTrue("window is bounded by the cap", got.size <= OccurrenceScan.CAP)
        assertTrue("derivation stays under 50 ms, measured ${ms}ms", ms < 50.0)
        assertEquals(
            "the old whole-document search really did find one per line",
            5000,
            oldRanges(text, "line").size,
        )
        // 5,000 lines of "line NNNNN of the big fixture" is ~29 bytes each: the owner's 150 KB case.
        assertTrue(
            "fixture is in the owner's size class (${text.length} bytes)",
            text.length in 100_000..200_000,
        )
    }
}
