package com.codespace.ide.editor

/**
 * Occurrence search for the cursor-word highlight, bounded and pure.
 *
 * WHY THIS EXISTS (owner order 2026-11-06, item 2): the highlight used to run
 * `Regex("\\bword\\b").findAll(text)` over the WHOLE document and then, inside `drawWithContent`,
 * call `getHorizontalPosition` twice per occurrence on EVERY FRAME. Each of those lookups walks
 * the paragraph's span array (`Layout.getParagraphLeadingMargin` -> `nextSpanTransition`), and our
 * highlighter emits one span per token, so cost was occurrences x spans per frame. On a 5,000-line
 * file that repeated word 5,000 times, and the main thread missed its 5,000 ms input-window:
 * the ANR in the owner's bug report, frames ending in CursorBehaviorsKt$wordHighlightModifier$1.
 *
 * The search is therefore windowed (visible lines plus overscan), and the result is CAPPED. Above
 * the cap we keep the occurrences NEAREST THE CARET rather than drawing nothing, because an empty
 * highlight over a file full of the word reads as a bug.
 *
 * Pure Kotlin: JVM-testable against fixtures, no Compose, no Android.
 */
internal object OccurrenceScan {

    /**
     * Safety cap. Derived from measurement, not taste: one screen on the owner's phone (TECNO
     * KL4, 6.6", font 13) is about 55 lines, the window is that plus one screen of overscan each
     * way (~165 lines), and the densest realistic case is a short word on every line, so ~165
     * visible occurrences is the natural ceiling. 200 keeps that whole case exact and bounds the
     * rect computation to 200 x 2 layout lookups per text or scroll change instead of per frame.
     */
    const val CAP: Int = 200

    /** Java's `\b` boundary, spelled out: a word character is a letter, digit or underscore. */
    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** The word under the caret, or null. Same rule the old modifier used: 2+ word characters. */
    fun wordAt(text: String, cursor: Int): String? {
        if (text.isEmpty() || cursor < 0 || cursor > text.length) return null
        var start = cursor
        var end = cursor
        // A caret sitting just past the end of a word still highlights that word.
        if (start == text.length || !isWordChar(text[start])) start = (cursor - 1).coerceAtLeast(0)
        if (start >= text.length || !isWordChar(text[start])) return null
        while (start > 0 && isWordChar(text[start - 1])) start--
        while (end < text.length && isWordChar(text[end])) end++
        val word = text.substring(start, end)
        return if (word.length < 2) null else word
    }

    /**
     * Occurrences of [word] that START inside [windowStart]..[windowEnd), word-boundary checked
     * against the surrounding text (so a word clipped by the window edge is not miscounted).
     *
     * Boundary checks read outside the window on purpose: the window is a cost bound, not a
     * definition of the document.
     */
    fun wordRanges(
        text: String,
        word: String,
        windowStart: Int,
        windowEnd: Int,
        caret: Int,
        cap: Int = CAP,
    ): List<IntRange> {
        if (word.isEmpty() || text.isEmpty()) return emptyList()
        val from = windowStart.coerceIn(0, text.length)
        val to = windowEnd.coerceIn(from, text.length)
        if (from >= to) return emptyList()

        val hits = ArrayList<IntRange>()
        var i = from
        while (i < to) {
            val at = text.indexOf(word, i)
            if (at < 0 || at >= to) break
            val endExclusive = at + word.length
            val okBefore = at == 0 || !isWordChar(text[at - 1])
            val okAfter = endExclusive >= text.length || !isWordChar(text[endExclusive])
            if (okBefore && okAfter) hits.add(at until endExclusive)
            i = at + 1
        }
        if (hits.size <= cap) return hits

        // Over the cap: the ones nearest the caret, then back into document order so drawing
        // order is stable frame to frame.
        val nearest = hits.sortedBy { distance(it, caret) }.take(cap)
        return nearest.sortedBy { it.first }
    }

    /** Distance from [caret] to a range: 0 inside it, otherwise to the closest edge. */
    fun distance(range: IntRange, caret: Int): Int = when {
        caret < range.first -> range.first - caret
        caret > range.last -> caret - range.last
        else -> 0
    }
}
