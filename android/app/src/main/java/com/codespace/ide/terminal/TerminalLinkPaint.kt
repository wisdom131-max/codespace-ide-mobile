package com.codespace.ide.terminal

/**
 * DEVICE PAINT FIXES D1/D2/D3 (advisor round 2026-10-19) — the terminal's
 * visual link styling, extracted PURE from TerminalRenderer.drawFileLinkTokens
 * (BATCH-G-STYLE) so every device-observed paint defect is JVM-testable.
 *
 * WHAT THIS OBJECT DECIDES: which char ranges of a row to repaint in VS Code
 * link blue + underline. The RENDERER keeps the paint geometry (plain-width
 * gate, cell-column walk, drawTextRun) — this object only finds the ranges.
 *
 * Behavior changes vs the old single-row, single-col-group pattern:
 *  - D2: the :digits suffix allows TWO groups (:line:col) — the old pattern
 *    had one optional group, so "file.txt:2:1" was never highlighted.
 *  - D1: quoted ('...' / "...") and shell-escaped (backslash-space) spans are
 *    highlighted WHOLE at detection time — pure string work, NO file I/O
 *    (advisor: detection must stay lightweight; existence is only the tap
 *    resolver's oracle). Unquoted multi-chunk paths still show only their
 *    extension-bearing chunk at detection; the WHOLE span is shown AFTER
 *    resolution via the resolved-span overlay (overlayCells).
 *  - D3: a token cut by the row WRAP is painted on BOTH visual rows. The
 *    wrapping row joins its trailing run with the continuation row's head,
 *    matches the joined text, and hands the continuation segment to the next
 *    row's paint pass (paintForRow carriedSeam). The old per-row pattern left
 *    the piece before the seam unpainted ("café-test.txt" lost its 'c').
 */
object TerminalLinkPaint {

    /** Char range [start, end) in the given text. */
    data class Span(val start: Int, val end: Int)

    /** One row's paint decision: segments + the seam handoff. */
    data class RowPaint(
        val spans: List<Span>,
        /** Char end in the NEXT row's text of a token matched across the wrap
         *  (the next row paints [0, seamIntoNext)); -1 = no seam handoff. */
        val seamIntoNext: Int,
    )

    /** The resolved-span overlay mapped to terminal cells (row, cell range). */
    data class OverlayCell(val row: Int, val cellStart: Int, val cellEnd: Int)

    private const val EXT = "kt|kts|java|py|js|ts|md|json|xml|gradle|yml|yaml|txt|sh|c|cpp|rs|go|rb|php|css|html"

    /** D2: (:\\d+){0,2} — line and line:col both anchor the paint. */
    private val LINK_TOKEN = Regex("[^\\s]+\\.($EXT)(:\\d+){0,2}(?=\\s|$)")

    /** D1: a quoted span with path content (quotes included in the paint). */
    private val QUOTED_SPAN = Regex("[\"'][^\"'\\n]*[/.][^\"'\\n]*[\"']")

    // ── base (single-row) matching — parity with the old Java pass ─────────

    /** Trailing-whitespace-stripped copy's length (indices before it are raw). */
    private fun endTrimLen(rowText: String): Int {
        var end = rowText.length
        while (end > 0 && rowText[end - 1].isWhitespace()) end--
        return end
    }

    /**
     * The old per-row pattern matches, as char ranges in [rowText] (trailing
     * whitespace is never included — indices below the trim point are
     * identical in the raw text).
     */
    fun baseSpans(rowText: String): List<Span> {
        val n = endTrimLen(rowText)
        if (n == 0) return emptyList()
        val trimmed = if (n == rowText.length) rowText else rowText.substring(0, n)
        if (trimmed.indexOf('.') == -1 && trimmed.indexOf('/') == -1) return emptyList()
        return LINK_TOKEN.findAll(trimmed).map { Span(it.range.first, it.range.last + 1) }.toList()
    }

    // ── D1 detection-time explicit spans (no file I/O) ─────────────────────

    /**
     * Quoted + shell-escaped spans on this row, painted WHOLE (advisor:
     * explicit forms are unambiguous from the row text alone). Escaped spans
     * are maximal runs whose internal spaces are backslash-escaped.
     */
    private fun explicitPaintSpans(rowText: String): List<Span> {
        val n = endTrimLen(rowText)
        if (n == 0) return emptyList()
        val trimmed = if (n == rowText.length) rowText else rowText.substring(0, n)
        val out = mutableListOf<Span>()
        if (trimmed.indexOf('.') == -1 && trimmed.indexOf('/') == -1) return emptyList()
        QUOTED_SPAN.findAll(trimmed).forEach { m -> out.add(Span(m.range.first, m.range.last + 1)) }
        // Shell-escaped runs: whitespace-delimited chunks joined by backslash-
        // escaped spaces — the whole run is ONE span when it contains \ .
        var i = 0
        while (i < trimmed.length) {
            if (trimmed[i].isWhitespace()) { i++; continue }
            val runStart = i
            var j = i
            while (j < trimmed.length) {
                if (trimmed[j].isWhitespace()) {
                    if (trimmed[j] == ' ' && j > runStart && trimmed[j - 1] == '\\') j++ else break
                } else {
                    j++
                }
            }
            val raw = trimmed.substring(runStart, j)
            if (raw.contains("\\ ") && (raw.contains('/') || raw.contains('.'))) {
                out.add(Span(runStart, j))
            }
            i = j
        }
        return out
    }

    // ── the row paint decision (renderer calls this once per row) ──────────

    /**
     * The spans to paint on ONE visual row.
     *
     * @param carriedSeam  >= 0 when the PREVIOUS row matched a token across
     *        the wrap into this row: the leading segment [0, carriedSeam) is
     *        painted here (the previous row computed it from the join).
     * @param rowWraps  the buffer's wrap flag for THIS row (getLineWrap).
     * @param nextRowText  the continuation row's text, when [rowWraps] (the
     *        renderer reads it from the buffer; null at the visible edge).
     */
    fun paintForRow(rowText: String, carriedSeam: Int, rowWraps: Boolean, nextRowText: String?): RowPaint {
        val out = mutableListOf<Span>()
        if (carriedSeam >= 0) out.add(Span(0, carriedSeam))
        val explicit = explicitPaintSpans(rowText)
        for (s in baseSpans(rowText)) {
            // Skip base matches swallowed by an explicit span (double paint).
            if (explicit.none { s.start >= it.start && s.end <= it.end }) out.add(s)
        }
        out.addAll(explicit)
        var seamIntoNext = -1
        if (rowWraps && nextRowText != null) {
            val seam = seamSpans(rowText, nextRowText)
            if (seam != null) {
                out.add(seam.first)
                seamIntoNext = seam.second
            }
        }
        out.sortBy { it.start }
        return RowPaint(out, seamIntoNext)
    }

    /**
     * D3 seam join: this row's trailing run + the next row's leading run form
     * ONE token match spanning the wrap → (segment on THIS row, segment end
     * in the NEXT row's text), or null when the join produces no match.
     */
    private fun seamSpans(rowText: String, nextRowText: String): Pair<Span, Int>? {
        val n = endTrimLen(rowText)
        if (n <= 0) return null
        // Trailing non-whitespace run of this row.
        var tailEnd = n
        while (tailEnd > 0 && rowText[tailEnd - 1].isWhitespace()) tailEnd--
        if (tailEnd <= 0) return null
        var tailStart = tailEnd
        while (tailStart > 0 && !rowText[tailStart - 1].isWhitespace()) tailStart--
        if (tailStart >= tailEnd) return null
        // Leading non-whitespace run of the next row.
        var headStart = 0
        while (headStart < nextRowText.length && nextRowText[headStart].isWhitespace()) headStart++
        var headEnd = headStart
        while (headEnd < nextRowText.length && !nextRowText[headEnd].isWhitespace()) headEnd++
        if (headStart >= headEnd) return null
        val tail = rowText.substring(tailStart, tailEnd)
        val head = nextRowText.substring(headStart, headEnd)
        val joined = tail + head
        val seamOffset = tail.length
        // The match must START inside this row's tail and END beyond the seam.
        val m = LINK_TOKEN.findAll(joined).firstOrNull { it.range.first < seamOffset && it.range.last + 1 > seamOffset }
            ?: return null
        return Pair(Span(tailStart + m.range.first, tailEnd), m.range.last + 1 - seamOffset)
    }

    // ── the resolved-span overlay (D1 "for others, show the resolved span
    //    after resolution") — logical char range → terminal cell pieces ────

    /**
     * Maps a char range [charStart, charEnd) of a WRAPPED LOGICAL LINE (which
     * starts at column 0 of its first row) to per-row cell pieces. Widths are
     * terminal cell widths (WcWidth), NOT UTF-16 char counts — the device D3
     * lesson: cell math must use cell widths, not char indexes.
     */
    fun overlayCells(text: String, charStart: Int, charEnd: Int, columns: Int): List<OverlayCell> {
        if (columns <= 0 || charEnd <= charStart) return emptyList()
        val pieces = mutableListOf<OverlayCell>()
        var cell = 0
        var i = 0
        val len = text.length
        var curRow = -1
        var curStart = -1
        var curEnd = -1
        while (i < len) {
            val cp = text.codePointAt(i)
            val cpChars = Character.charCount(cp)
            val w = if (cp == '\t'.toInt()) 1 else com.termux.terminal.WcWidth.width(cp)
            val inRange = i >= charStart && i < charEnd
            if (inRange && w > 0) {
                val row = cell / columns
                val rowStart = cell - row * columns
                if (curRow == row && curEnd == rowStart) {
                    curEnd = rowStart + w
                } else {
                    if (curRow >= 0) pieces.add(OverlayCell(curRow, curStart, curEnd))
                    curRow = row
                    curStart = rowStart
                    curEnd = rowStart + w
                }
                cell += w
            } else if (w > 0) {
                cell += w
            }
            i += cpChars
        }
        if (curRow >= 0) pieces.add(OverlayCell(curRow, curStart, curEnd))
        return pieces
    }
}
