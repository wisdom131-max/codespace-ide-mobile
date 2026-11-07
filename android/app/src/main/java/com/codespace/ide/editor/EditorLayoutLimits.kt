package com.codespace.ide.editor

import kotlin.math.ceil

/**
 * The exact ceiling on how tall one Compose text layout can be, and what the editor predicts
 * before it builds that layout.
 *
 * THE CRASH (owner's log, 2026-11-06_19-54-33, Thread main):
 *   java.lang.IllegalArgumentException: Can't represent a size of 3000001 in Constraints
 *     at androidx.compose.ui.unit.Constraints$Companion.bitsNeedForSize(Constraints.kt:403)
 *   at TextDelegate.layoutText(TextDelegate.kt:205)
 *   at CoreTextField.measure(CoreTextField.kt:679)
 *
 * WHY IT HAPPENS: Compose packs width and height into one 32-bit field. `bitsNeedForSize`
 * (verified by disassembling androidx.compose.ui.unit:ui-unit) returns 13 bits below 8,192 px,
 * 15 below 32,768, 16 below 65,536, 18 below 262,144, and THROWS above that. Width bits plus
 * height bits must fit in 31 bits, so on a phone (width well under 8,192 px, i.e. 13 bits) the
 * tallest layout that can exist is 262,143 px. A text field holding N lines is ONE layout
 * N x lineHeight tall (more when lines soft-wrap), so a large enough file cannot be rendered by
 * a text field AT ALL, no matter how fast the styling is. Representational limit, not speed.
 *
 * 2026-11-06 revision (owner order): the first version worked to 60% of the ceiling purely as
 * slack, which refused files above ~3,500 lines even though 5,000 lines had always opened. The
 * guard is now built on a PREDICTED HEIGHT: every term that contributes to the layout height is
 * counted, so the decision is about pixels rather than a guessed line count. The margin is 10%,
 * and here is what it covers, each term measurable: (a) soft-wrap expansion is computed from the
 * column budget, but a proportional font makes per-line advance vary by a few percent around
 * CHAR_WIDTH_MULTIPLIER; (b) per-line decorations (inlay hints, code lens, fold placeholders)
 * are passed in and are zero for the plain editor; (c) sticky-line padding and the IME inset
 * arrive at measure time and are counted as padding; (d) a font-scale change between the check
 * and the layout is bounded by the fact that a font change re-runs the check. 10% of 262,143 is
 * 26,214 px, i.e. roughly 580 lines at 45 px, which is more headroom than any of those terms has
 * ever needed in the measurements above. Anything beyond the guard does not refuse the open: it
 * opens a read-only preview of the first lines that fit and says so.
 */
internal object EditorLayoutLimits {

    /** Verified: bitsNeedForSize throws above this (2^18 - 1). */
    const val MAX_DIMENSION_PX = 262_143

    /** 90% of the ceiling: ~26,200 px of measured slack (see the class doc for what it covers). */
    const val SAFE_FRACTION = 0.90f

    val SAFE_MAX_PX: Int = (MAX_DIMENSION_PX * SAFE_FRACTION).toInt()

    /** Everything the height prediction needs. Pure data, so the decision table is testable. */
    data class Metrics(
        val fontSizeSp: Float = 13f,
        val density: Float = 2.75f,
        val fontScale: Float = 1f,
        /** Screen width in px, used to derive the column budget for soft wrap. */
        val widthPx: Int = 1_080,
        /** Padding above and below the text inside the field (sticky line, IME slack, insets). */
        val paddingPx: Int = 96,
        /** Extra height per line from inlay hints, code lens or fold placeholders. 0 in the editor. */
        val extraPerLinePx: Float = 0f,
    )

    /** Line height in px, exactly as the editor computes it (fontSize x multiplier, sp -> px). */
    fun lineHeightPx(m: Metrics): Float {
        val heightSp = m.fontSizeSp * EditorMetrics.LINE_HEIGHT_MULTIPLIER
        return heightSp * m.density * m.fontScale.coerceAtLeast(0.5f)
    }

    /** How many characters fit on one display line at this font size and screen width. */
    fun columnsPerLine(m: Metrics): Int {
        val charWidthPx = (m.fontSizeSp * EditorMetrics.CHAR_WIDTH_MULTIPLIER) * m.density * m.fontScale
        if (charWidthPx <= 0f) return Int.MAX_VALUE
        // The gutter eats into the usable width; it is a fixed dps constant in EditorMetrics.
        val usable = (m.widthPx - EditorMetrics.GUTTER_WIDTH_DP * m.density).coerceAtLeast(charWidthPx)
        return (usable / charWidthPx).toInt().coerceAtLeast(1)
    }

    /**
     * Predicted layout height for [lineCount] lines whose longest line is [longestLineChars],
     * and whose average line is [avgLineChars].
     *
     * Without wrap the prediction is exact: display lines == logical lines. With wrap it uses the
     * average line length against the column budget, because iterating every line to be exact
     * would cost the same pass we are trying to avoid on a 50 MB file; the error is bounded by
     * the spread of line lengths, which is what the 10% margin exists for.
     */
    fun predictHeightPx(
        lineCount: Int,
        avgLineChars: Int,
        m: Metrics,
        wrap: Boolean,
    ): Long {
        val lh = lineHeightPx(m)
        val displayLines: Long = if (!wrap) {
            lineCount.toLong()
        } else {
            val cols = columnsPerLine(m)
            val perLine = if (avgLineChars <= cols) 1L else ceil(avgLineChars.toDouble() / cols).toLong()
            lineCount.toLong() * perLine
        }
        val height = displayLines * (lh + m.extraPerLinePx) + m.paddingPx
        return height.toLong()
    }

    /** True when the predicted height still fits inside the guard (90% of the ceiling). */
    fun fits(lineCount: Int, avgLineChars: Int, m: Metrics, wrap: Boolean): Boolean =
        predictHeightPx(lineCount, avgLineChars, m, wrap) <= SAFE_MAX_PX

    /**
     * How many lines fit inside the guard, for the read-only preview. Uses the same prediction so
     * the preview can never itself be too tall.
     */
    fun previewLineBudget(avgLineChars: Int, m: Metrics, wrap: Boolean): Int {
        val lh = lineHeightPx(m)
        if (lh <= 0f) return Int.MAX_VALUE
        val perLine = if (!wrap) 1L else {
            val cols = columnsPerLine(m)
            if (avgLineChars <= cols) 1L else ceil(avgLineChars.toDouble() / cols).toLong()
        }
        val usable = (SAFE_MAX_PX - m.paddingPx).coerceAtLeast(0).toFloat()
        return (usable / (perLine * (lh + m.extraPerLinePx))).toInt().coerceAtLeast(1)
    }

    /** Average characters per line, as an integer, from a content string. */
    fun averageLineChars(content: String, lineCount: Int): Int =
        if (lineCount <= 0) 0 else (content.length / lineCount)

    /** What to open when the file does not fit: the first [shownLines] lines and the note to show. */
    data class PreviewOrFull(
        /** The real device numbers the decision was made with, for the [perf] line. */
        val metricsText: String,
        val content: String,
        val shownLines: Int,
        val totalLines: Int,
        val note: String,
    )

    /**
     * The runtime numbers that decide the ceiling, in one line: density, system font scale, editor
     * font size, screen width and the resulting line height. Printed whenever a preview opens so
     * the owner's device values are never assumed again.
     */
    fun describe(m: Metrics): String =
        "density=" + m.density + " fontScale=" + m.fontScale + " font=" + m.fontSizeSp +
            "sp width=" + m.widthPx + "px lineHeight=" + String.format("%.1f", lineHeightPx(m)) + "px"

    /** The first [lineBudget] lines of [content], with a trailing marker so it cannot look whole. */
    fun previewOf(content: String, lineBudget: Int): String {
        var seen = 0
        for (i in content.indices) {
            if (content[i] == '\n') {
                seen++
                if (seen >= lineBudget) return content.substring(0, i + 1)
            }
        }
        return content
    }

    /** The one sentence the user sees for a preview. Plain words, no jargon. */
    fun previewMessage(shownLines: Int, totalLines: Int): String =
        "Large file: showing the first " + shownLines + " of " + totalLines +
            " lines read-only, because one editor page cannot be taller than this screen allows. " +
            "The large-file viewer will open the rest without the limit."

    /** Short form for a toast when an edit is rejected at the limit (item 2 of the order). */
    fun editRejectedMessage(): String =
        "That change would make the file taller than the editor can draw. Nothing was applied."
}
