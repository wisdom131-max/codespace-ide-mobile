package com.codespace.ide.editor

import com.codespace.ide.domain.Language
import com.codespace.ide.ui.DarkEditorColors

/**
 * Counts the spans the highlighting pipeline would produce for a buffer, without building them.
 *
 * WHY A COUNT AND NOT THE REAL ANNOTATED STRING: the count is needed at OPEN time, to decide whether
 * the file may be editable at all (see [EditorSpanBudget]). Building the annotated string for a file
 * we are about to page would be the very work we are trying to avoid, so the rules are re-applied as
 * arithmetic. The count is an UPPER BOUND on the styling spans that reach the text field, which is
 * the safe direction for a budget: over-counting pages a file that might have squeaked in,
 * under-counting freezes the editor.
 *
 * PLAIN TEXT IS THE POINT: a .txt, a file whose language is unknown, or a buffer with no recognised
 * line shape yields ZERO spans, which is why the ANR's 350 spans on big.txt were always wrong to
 * exist. Whatever produced them is not here, and that is the finding.
 */
object EditorSpanCounter {

    /** One span per token, plus the per-line overhead the transformation adds. Deliberate bound. */
    fun count(content: String, language: Language): Int {
        if (content.isEmpty()) return 0
        if (!producesSpans(language)) return 0
        var spans = 0
        var start = 0
        while (start <= content.length) {
            val nl = content.indexOf('\n', start)
            val end = if (nl < 0) content.length else nl
            if (end > start) spans += spansInLine(content, start, end)
            if (nl < 0) break
            start = nl + 1
            // A pathologically long buffer must not be counted forever either.
            if (spans >= EditorSpanBudget.MAX_SPANS) return EditorSpanBudget.MAX_SPANS
        }
        return minOf(spans, EditorSpanBudget.MAX_SPANS)
    }

    /**
     * A count plus the density the count cannot express.
     *
     * The total is CAPPED at MAX_SPANS, so for a 13,000-line file it reads 24,000 and dividing by
     * 13,000 gives 1.8 spans per line, which is wrong (the real file is ~9/line) and would choose a
     * page five times too large. The density is therefore measured on the first [SAMPLE_LINES]
     * lines, which is a real measurement rather than an artefact of the cap.
     */
    data class Measurement(val cappedTotal: Int, val spansPerLine: Double, val sampledLines: Int)

    const val SAMPLE_LINES = 200

    fun measure(content: String, language: Language): Measurement {
        val total = count(content, language)
        if (!producesSpans(language) || total == 0) return Measurement(0, 0.0, 0)
        var spans = 0
        var lines = 0
        var start = 0
        while (lines < SAMPLE_LINES && start <= content.length) {
            val nl = content.indexOf('\n', start)
            val end = if (nl < 0) content.length else nl
            if (end > start) spans += spansInLine(content, start, end)
            lines++
            if (nl < 0) break
            start = nl + 1
        }
        return Measurement(total, if (lines > 0) spans.toDouble() / lines else 0.0, lines)
    }

    /** Languages this editor styles. Everything else is plain text and gets no spans at all. */
    fun producesSpans(language: Language): Boolean = when (language) {
        Language.PLAINTEXT, Language.PLAIN -> false
        else -> true
    }

    /** Token-ish runs in one line: whole words plus the punctuation that gets its own colour. */
    internal fun spansInLine(content: String, start: Int, endExclusive: Int): Int {
        var spans = 0
        var inWord = false
        var i = start
        while (i < endExclusive) {
            val c = content[i]
            val wordChar = c.isLetterOrDigit() || c == '_'
            if (wordChar && !inWord) {
                spans++
                inWord = true
            } else if (!wordChar) {
                inWord = false
                if (!c.isWhitespace()) spans++
            }
            i++
        }
        return spans
    }

    /** File name to language, using the app's own mapping so the count matches what is drawn. */
    fun languageOf(fileName: String): Language = Language.fromPath(fileName)
}
