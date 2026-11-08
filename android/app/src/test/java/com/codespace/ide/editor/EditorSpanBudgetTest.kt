package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Span budget (owner's ANR 2026-11-08, anr_2026-11-08-14-13-07-236, big.txt, 5,000 lines).
 *
 * The trace showed the cost is (visible lines x total spans) inside Layout.drawText, i.e. the text
 * paint itself, not our overlay. These tests pin the count, the coalescing, the cost model and the
 * tier at the sizes that matter: a plain .txt, a 5,000-line .js, and the ladder.
 */
class EditorSpanBudgetTest {

    private fun plainLines(n: Int): String =
        (1..n).joinToString("\n") { "line $it of the big file" }

    private fun jsLines(n: Int): String =
        (1..n).joinToString("\n") { "const v${"%05d".format(it)} = $it; // filler line" }

    @Test
    fun plainTextProducesNoSpansAtAll() {
        // big.txt is the file that ANR'd. A .txt must not be styled, so its span array is empty and
        // the per-line span walk costs nothing.
        val spans = EditorSpanCounter.count(plainLines(5_000), EditorSpanCounter.languageOf("big.txt"))
        println("[span] big.txt 5000 lines -> $spans spans")
        assertEquals(0, spans)
    }

    @Test
    fun plainTextIsEditableByTheCostRuleOnceItFitsHeight() {
        val plan = EditorSpanBudget.plan(
            lineCount = 5_000, visibleLines = 45, totalSpans = 0, fitsHeight = true, averageLineChars = 24,
        )
        println(EditorSpanBudget.describe(plan, 5_000, "big.txt"))
        assertEquals(EditorSpanBudget.Tier.EDIT, plan.tier)
    }

    @Test
    fun aFiveThousandLineJsIsPagedNotEditable() {
        val content = jsLines(5_000)
        val m = EditorSpanCounter.measure(content, EditorSpanCounter.languageOf("big.js"))
        val spans = m.cappedTotal
        val plan = EditorSpanBudget.plan(
            lineCount = 5_000, visibleLines = 45, totalSpans = spans, fitsHeight = true,
            averageLineChars = 32, sampledSpansPerLine = m.spansPerLine,
        )
        println("[span] big.js 5000 lines -> $spans spans")
        println(EditorSpanBudget.describe(plan, 5_000, "big.js"))
        assertTrue("a js file with $spans spans must be paged", spans > EditorSpanBudget.MIN_MEANINGFUL_SPANS)
        assertEquals(EditorSpanBudget.Tier.PAGED_PREVIEW, plan.tier)
        assertTrue(plan.pageLines in EditorSpanBudget.MIN_PAGE_LINES..EditorSpanBudget.MAX_PAGE_LINES)
        // The page itself must stay inside the budget: visible lines x the page's own spans.
        val spansOnPage = plan.spansPerLine * plan.pageLines * 45
        println("[span] one page of ${plan.pageLines} lines -> estimated ${spansOnPage.toInt()} cost units")
        assertTrue("a page must fit the draw budget, was $spansOnPage", spansOnPage <= EditorSpanBudget.DRAW_COST_BUDGET * 1.05)
    }

    @Test
    fun theLadderIsReportedSoTheNumbersAreOnTheRecord() {
        listOf(1_000, 2_000, 5_000, 13_000).forEach { n ->
            val content = jsLines(n)
            val m = EditorSpanCounter.measure(content, EditorSpanCounter.languageOf("ladder.js"))
            val plan = EditorSpanBudget.plan(
                lineCount = n, visibleLines = 45, totalSpans = m.cappedTotal, fitsHeight = true,
                averageLineChars = 32, sampledSpansPerLine = m.spansPerLine,
            )
            println(
                "[span] ladder ${n}js lines: ${m.cappedTotal} spans, ${"%.1f".format(plan.spansPerLine)}/line, " +
                    "cost ${plan.cost} -> ${plan.tier}${if (plan.tier == EditorSpanBudget.Tier.PAGED_PREVIEW) " ${plan.pageLines}/page" else ""}",
            )
        }
    }

    @Test
    fun aSpanHeavySmallFileIsStillPaged() {
        // Under 64 spans the array cannot be the problem; at 300 spans over 40 visible lines it can.
        val plan = EditorSpanBudget.plan(
            lineCount = 40, visibleLines = 40, totalSpans = 3_000, fitsHeight = true, averageLineChars = 200,
        )
        assertEquals(EditorSpanBudget.Tier.PAGED_PREVIEW, plan.tier)
        assertTrue(plan.reason.startsWith("cost"))
    }

    @Test
    fun adjacentIdenticalSpansAreCoalesced() {
        val runs = listOf(
            EditorSpanBudget.SpanRun(0, 5, styleKey = 1),
            EditorSpanBudget.SpanRun(5, 9, styleKey = 1),
            EditorSpanBudget.SpanRun(9, 12, styleKey = 2),
            EditorSpanBudget.SpanRun(12, 20, styleKey = 2),
            EditorSpanBudget.SpanRun(21, 30, styleKey = 1),
        )
        val merged = EditorSpanBudget.coalesce(runs)
        println("[span] coalesce ${runs.size} runs -> ${merged.size}: $merged")
        assertEquals(3, merged.size)
        assertEquals(0, merged[0].start)
        assertEquals(9, merged[0].end)
        assertEquals(9, merged[1].start)
        assertEquals(20, merged[1].end)
    }

    @Test
    fun spansAreCapped() {
        val many = (0 until 40_000).map { EditorSpanBudget.SpanRun(it, it + 1, it % 7) }
        assertEquals(EditorSpanBudget.MAX_SPANS, EditorSpanBudget.cap(many).size)
    }

    @Test
    fun theTemporaryNoteSaysWhatTheOwnerSees() {
        assertEquals("Large file: opened read-only in pages", EditorSpanBudget.temporaryNote())
    }
}
