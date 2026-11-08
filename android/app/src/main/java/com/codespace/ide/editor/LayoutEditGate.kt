package com.codespace.ide.editor

/**
 * The gate every text change passes before it becomes the editor's content.
 *
 * WHY THIS EXISTS: the open path refuses to build a text field taller than the Compose
 * Constraints ceiling, but the same ceiling can be crossed WHILE editing: paste a big block, undo a
 * large deletion, apply a format/AI/refactor result, insert a snippet, sync an external change, or
 * raise the font size so the same lines no longer fit. Every one of those ends in the same layout,
 * so every one is checked here instead of being trusted.
 *
 * The check is a PREDICTION, not a measurement: the text field's real height is not known until it
 * lays out, and by then the throw has already happened. [fits] therefore runs the same arithmetic
 * the open guard uses (EditorLayoutLimits), including soft wrap and the system font scale.
 */
internal object LayoutEditGate {

    /** What to do with a change that does not fit. */
    enum class Verdict {
        /** Commit the change: the predicted height is under the guard. */
        ALLOW,

        /** Refuse the change and say why. The editor keeps its previous content. */
        REFUSE,
    }

    /** Refusal sentence for the note. One line, no jargon, states the limit. */
    const val REFUSAL_MESSAGE =
        "Edit refused: this file would grow past the editor's height limit. Nothing was changed."

    /**
     * Decides whether [candidate] (the text the editor is about to hold) may be committed.
     *
     * @param lineCount lines in the candidate text
     * @param avgLineChars average line length, which is what soft wrap expands
     */
    fun verdict(
        lineCount: Int,
        avgLineChars: Int,
        metrics: EditorLayoutLimits.Metrics,
        wrap: Boolean,
    ): Verdict = if (EditorLayoutLimits.fits(lineCount, avgLineChars, metrics, wrap)) {
        Verdict.ALLOW
    } else {
        Verdict.REFUSE
    }

    /** Convenience: run the gate straight off candidate text. */
    fun verdictForText(
        candidate: String,
        metrics: EditorLayoutLimits.Metrics,
        wrap: Boolean,
    ): Verdict {
        val lines = EditorLayoutLimits.lineCount(candidate)
        val avg = EditorLayoutLimits.averageLineChars(candidate, lines)
        return verdict(lines, avg, metrics, wrap)
    }

    /**
     * The largest font size that still fits [lineCount] lines at [metrics], never below [min] and
     * never above [requested]. Used when a zoom/font change would push the document over the
     * ceiling: clamping keeps the document readable instead of refusing the user's gesture.
     */
    fun clampFontSize(
        requested: Int,
        min: Int,
        lineCount: Int,
        avgLineChars: Int,
        metrics: EditorLayoutLimits.Metrics,
        wrap: Boolean,
    ): Int {
        var size = requested.coerceAtLeast(min)
        while (size > min) {
            val candidate = metrics.copy(fontSizeSp = size.toFloat())
            if (EditorLayoutLimits.fits(lineCount, avgLineChars, candidate, wrap)) break
            size--
        }
        return size
    }
}
