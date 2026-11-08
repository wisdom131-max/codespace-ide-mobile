package com.codespace.ide.editor

/**
 * SPAN BUDGET (owner's bug report 2026-11-08, bugreport-...14-13-17, FS/data/anr/anr_...14-13-07-236).
 *
 * THE ANR THIS EXISTS FOR, read from the trace: "Input dispatching timed out ... Waited 5005ms for
 * MotionEvent", main thread, utm=10981, innermost frames
 *
 *   SpannableStringInternal.getSpans(350) <- SpanSet.init <- TextLine.set <- Layout.drawText(744)
 *     <- Layout.draw <- TextLayout.paint <- AndroidParagraph.paint <- MultiParagraph.paint
 *     <- TextPainter.paint <- TextFieldDelegate.draw <- CoreTextField drawModifier
 *
 * Our own frame is only the outer WordHighlightRects.kt:150 (it calls drawContent). Everything
 * inside is the TEXT PAINT itself: Android re-walks the whole span array for every drawn line, so
 * the cost of drawing is (visible lines x total spans), not (visible lines). A plain 5,000-line
 * .txt produced 350 spans and still timed out, which is the span-count multiplier confirmed on a
 * file with no syntax at all.
 *
 * WHY HEIGHT ALONE WAS NOT ENOUGH: EditorLayoutLimits counts lines, and a 5,000-line file fits the
 * height ceiling, so it was declared editable and then died while PAINTING. The height ceiling is
 * real (262,143 px) but it is not the binding constraint. Cost is.
 *
 * WHAT THIS DOES: predicts drawing cost, and when it is over budget sends the file to the existing
 * read-only paged preview with a page size chosen so a page's own span count stays under budget.
 * The writer guards, the banner and the notes are the ones already shipped; this only chooses the
 * tier.
 *
 * TEMPORARY, BY DESIGN. The virtualized viewer (chunked store + line index + lazy line layout)
 * removes the multiplier instead of budgeting around it, and this class goes away with it. Marked
 * TEMPORARY here so nobody mistakes it for the destination.
 */
object EditorSpanBudget {

    /**
     * Cost units a page may spend. Cost is visibleLines x totalSpans, so this is "how many span
     * visits a single draw may perform".
     *
     * THE FACTOR: CI and the dev JVM are 10-30x faster than this phone, and the ANR took 5005 ms of
     * hard timeout before it was even caught, so the shipped number is deliberately pessimistic.
     * 90,000 units is roughly 2,000 span visits per visible line across ~45 visible lines, which is
     * about one span per 3 characters on a full-width line.
     */
    const val DRAW_COST_BUDGET = 90_000

    /** Below this, a span array cannot be the reason a draw is slow; keep small files fully live. */
    const val MIN_MEANINGFUL_SPANS = 64

    /** Hard ceiling on spans produced for one buffer, whatever the source. */
    const val MAX_SPANS = 24_000

    /** Pages are never smaller than this, or paging becomes absurd. */
    const val MIN_PAGE_LINES = 200

    /** Pages are never larger than this; the preview's own render caps also apply. */
    const val MAX_PAGE_LINES = 2_000

    enum class Tier {
        /** Fully editable: typing and highlighting on the whole buffer. */
        EDIT,

        /** The existing read-only paged preview, with its writer guards. */
        PAGED_PREVIEW,
    }

    data class Plan(
        val tier: Tier,
        /** Lines per page when [tier] is PAGED_PREVIEW; ignored otherwise. */
        val pageLines: Int,
        /** Predicted draw cost for the whole buffer (visibleLines x totalSpans). */
        val cost: Long,
        val totalSpans: Int,
        val spansPerLine: Double,
        val reason: String,
    )

    /** One run of text sharing a style. The pipeline's own unit; coalescing works on these. */
    data class SpanRun(val start: Int, val end: Int, val styleKey: Int)

    /**
     * MERGE ADJACENT IDENTICAL RUNS.
     *
     * Highlighters emit one span per token, and neighbouring tokens of the same colour are separate
     * spans that Android walks separately. Coalescing them is free accuracy-wise (the same
     * characters end up the same colour) and it is the cheapest span reduction available. Runs that
     * touch AND share a style become one run; anything else is left exactly as it was.
     */
    fun coalesce(runs: List<SpanRun>): List<SpanRun> {
        if (runs.size < 2) return runs
        val sorted = runs.sortedWith(compareBy({ it.start }, { it.end }))
        val out = ArrayList<SpanRun>(sorted.size)
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            current = if (next.styleKey == current.styleKey && next.start <= current.end) {
                SpanRun(current.start, maxOf(current.end, next.end), current.styleKey)
            } else {
                out.add(current)
                next
            }
        }
        out.add(current)
        return out
    }

    /** Drops every run past [max], which is only ever reached by pathological input. */
    fun cap(runs: List<SpanRun>, max: Int = MAX_SPANS): List<SpanRun> =
        if (runs.size <= max) runs else runs.subList(0, max)

    /** The cost model, in one line, so the tests can pin it: visible lines x total spans. */
    fun costOf(visibleLines: Int, totalSpans: Int): Long =
        visibleLines.coerceAtLeast(0).toLong() * totalSpans.coerceAtLeast(0).toLong()

    /**
     * Chooses the tier for a buffer.
     *
     * @param lineCount lines in the whole buffer
     * @param visibleLines lines that fit on screen at the current font and density
     * @param totalSpans spans the pipeline produced for the whole buffer
     * @param fitsHeight whether the existing height rule says the whole buffer fits
     */
    fun plan(
        lineCount: Int,
        visibleLines: Int,
        totalSpans: Int,
        fitsHeight: Boolean,
        averageLineChars: Int,
        sampledSpansPerLine: Double = 0.0,
    ): Plan {
        val spansPerLine = if (sampledSpansPerLine > 0.0) {
            sampledSpansPerLine
        } else if (lineCount > 0) {
            totalSpans.toDouble() / lineCount
        } else {
            0.0
        }
        val cost = (visibleLines.toLong() * (spansPerLine * lineCount)).toLong()

        // The height ceiling still counts: a buffer that does not fit the height ceiling cannot be
        // one text field at all, whatever its span count.
        if (!fitsHeight) return pagePlan(lineCount, visibleLines, totalSpans, spansPerLine, cost, "height")

        // A plain-text buffer with almost no spans is exactly the case the height rule got right.
        if (totalSpans < MIN_MEANINGFUL_SPANS) {
            return Plan(
                tier = Tier.EDIT, pageLines = 0, cost = cost, totalSpans = totalSpans,
                spansPerLine = spansPerLine,
                reason = "span-light (${totalSpans} spans over $lineCount lines)",
            )
        }

        if (cost <= DRAW_COST_BUDGET) {
            return Plan(
                tier = Tier.EDIT, pageLines = 0, cost = cost, totalSpans = totalSpans,
                spansPerLine = spansPerLine,
                reason = "cost ${cost} within $DRAW_COST_BUDGET",
            )
        }
        return pagePlan(lineCount, visibleLines, totalSpans, spansPerLine, cost, "cost")
    }

    private fun pagePlan(
        lineCount: Int,
        visibleLines: Int,
        totalSpans: Int,
        spansPerLine: Double,
        cost: Long,
        cause: String,
    ): Plan {
        // A page must satisfy visibleLines x (spansPerLine x pageLines) <= budget, i.e. the page's
        // own spans, times the lines on screen. When a page would have to be absurdly small, keep
        // MIN_PAGE_LINES and let the preview's render caps do the rest.
        val perPageSpanBudget = DRAW_COST_BUDGET.toDouble() / visibleLines.coerceAtLeast(1)
        val pageLines = if (spansPerLine <= 0.0) {
            MAX_PAGE_LINES
        } else {
            (perPageSpanBudget / spansPerLine).toInt()
        }.coerceIn(MIN_PAGE_LINES, MAX_PAGE_LINES)

        return Plan(
            tier = Tier.PAGED_PREVIEW, pageLines = pageLines, cost = cost, totalSpans = totalSpans,
            spansPerLine = spansPerLine,
            reason = "$cause: cost $cost over $DRAW_COST_BUDGET, ${"%.1f".format(spansPerLine)} spans/line",
        )
    }

    /** The banner for a file this rule sent to the preview, so the reason is visible and honest. */
    fun temporaryNote(): String = "Large file: opened read-only in pages"

    /** A [perf] line the owner can copy, naming the numbers the decision used. */
    fun describe(plan: Plan, lineCount: Int, fileName: String): String =
        "[perf] span budget $fileName: $lineCount lines, ${plan.totalSpans} spans " +
            "(${"%.1f".format(plan.spansPerLine)}/line), cost ${plan.cost} vs $DRAW_COST_BUDGET -> " +
            (if (plan.tier == Tier.EDIT) "editable" else "paged preview, ${plan.pageLines} lines/page") +
            " (${plan.reason})"
}
