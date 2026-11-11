package com.codespace.ide.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * SPAN CENSUS — the measurement the ANR demanded and this code never had.
 *
 * THE DEFECT THIS EXISTS FOR (owner bug report 2026-11-10, anr_2026-11-10-13-48-27-402, build 3703):
 * opening bench/big5000.txt (5,000 lines) hung the app. The main-thread stack is a WIDTH pass, not a
 * paint over visible lines:
 *
 *   SpannableStringInternal.getSpanEnd <- SpanSet.init(SpanSet.java:68) <- TextLine.handleRun
 *     <- TextLine.measureRun <- TextLine.metrics <- Layout.measurePara
 *     <- Layout.getDesiredWidthWithLimit <- LayoutIntrinsics.getMaxIntrinsicWidth
 *     <- MultiParagraphIntrinsics.getMaxIntrinsicWidth <- TextDelegate.layoutText(TextDelegate.kt:200)
 *
 * EditorSpanBudget modelled cost as (visible lines x total spans) and therefore predicted a 5,000-line
 * plain file as CHEAP. The stack says the cost is proportional to ALL lines x ALL spans, because
 * intrinsics measure every line before anything is drawn. Two things were missing to prove it: a real
 * span count of the AnnotatedString handed to CoreTextField, and a measurement on the device.
 *
 * The shipped comment claiming "a plain 5,000-line .txt produced 350 spans" was never reproducible
 * from this code path (a plain line emits ZERO spans here); 350 is what Android reported at paint
 * time in that older trace, which means the spans come from somewhere else in the pipeline. Guessing
 * which producer it is would be the same mistake twice, so this class MEASURES instead: it counts what
 * the transformation actually hands over, per kind, so the next trace can be attributed to a producer.
 *
 * TEMPORARY instrumentation for the virtualized-viewer decision; it does no work when debug is off.
 */
object SpanCensus {

    data class Report(
        val totalSpans: Int,
        val spanStyles: Int,
        val paragraphStyles: Int,
        /** Always 0 for now: AnnotatedString.annotations is internal in this Compose version. */
        val annotations: Int,
        val distinctSpanStyles: Int,
        val lines: Int,
        val chars: Int,
    ) {
        val spansPerLine: Double get() = if (lines <= 0) 0.0 else totalSpans.toDouble() / lines

        /** Predicted intrinsics cost under the CORRECTED model: all lines x all spans. */
        val widthPassCost: Long get() = lines.toLong() * totalSpans.toLong()
    }

    /** Count every kind of span Compose will convert to an Android span for this text. */
    fun count(text: AnnotatedString): Report {
        val styleKeys = HashSet<String>()
        text.spanStyles.forEach { r ->
            styleKeys.add(styleKey(r.item))
        }
        val lines = if (text.isEmpty()) 0 else text.text.count { it == '\n' } + 1
        return Report(
            // AnnotatedString.annotations is INTERNAL in this Compose version, so it is not counted
            // rather than guessed at. It is empty for every string this pipeline builds (we only use
            // withStyle / addStyle), and the census says so explicitly instead of reporting a number
            // nobody can verify.
            totalSpans = text.spanStyles.size + text.paragraphStyles.size,
            spanStyles = text.spanStyles.size,
            paragraphStyles = text.paragraphStyles.size,
            annotations = 0,
            distinctSpanStyles = styleKeys.size,
            lines = lines,
            chars = text.length,
        )
    }

    /** One stable identity per distinct style, so "10,000 spans" can be told from "10,000 styles". */
    private fun styleKey(s: SpanStyle): String =
        s.color.value.toString(16) + "/" + s.fontSize.value + "/" + s.fontWeight?.weight +
            "/" + s.fontStyle + "/" + s.background.value.toString(16) + "/" + s.textDecoration

    /** The single line to grep in Output. Prefix [perf] so existing log tools already find it. */
    fun perfLine(r: Report, label: String): String =
        "[perf] spans " + label + ": total=" + r.totalSpans + " (styles=" + r.spanStyles +
            " para=" + r.paragraphStyles + " ann=" + r.annotations + " distinct=" + r.distinctSpanStyles +
            ") lines=" + r.lines + " chars=" + r.chars + " spansPerLine=" + String.format("%.2f", r.spansPerLine) +
            " widthPassCost=" + r.widthPassCost
}
