package com.codespace.ide.terminal

/**
 * DEVICE DEFECT D1 FIX (advisor round 2026-10-19): quoted and shell-escaped
 * spans containing the tap are detected as ONE unit BEFORE any chunking.
 *
 * Detection is PURE STRING WORK — no file lookups (advisor design item: the
 * explicit forms are unambiguous from the row text alone, so the whole span
 * becomes the candidate instead of growing windows chunk by chunk).
 *
 * Two forms are recognized:
 *  - QUOTED:   '...' or "..." fully containing the tap (inclusive of the quote
 *              chars themselves — the device tap was ON the opening quote).
 *  - ESCAPED:  a maximal run around the tap where EVERY internal space is
 *              backslash-escaped (  /sdcard/My\ codespace\ app\ 3/f.py  ).
 *
 * A plain unquoted token is NOT an explicit span (null) — it keeps flowing
 * through the space-free scanner + existence-gated windows (LinkExpansion).
 */
object LinkSpanFinder {

    data class ExplicitSpan(
        /** Char offset of the span start in the row text (inclusive). */
        val start: Int,
        /** Char offset of the span end in the row text (exclusive). */
        val end: Int,
        /** The span EXACTLY as it appears in the row (quotes/escapes intact). */
        val raw: String,
        /** Quotes stripped / escapes resolved — for diagnostics + tests. The
         *  candidate pipeline (LinkCandidateBuilder) does its own cleaning of
         *  [raw]; this value is NOT used for resolution. */
        val cleaned: String,
    )

    /**
     * The explicit span containing [tapOffset], or null when the tap is not
     * inside a quoted/escaped form. Quoted is tried first (a quote may enclose
     * an escaped run). Bounds are inclusive of the quote characters; [cleaned]
     * excludes them.
     */
    fun explicitSpanAt(rowText: String, tapOffset: Int): ExplicitSpan? {
        val n = rowText.length
        if (n == 0) return null
        val off = tapOffset.coerceIn(0, n - 1)
        quotedSpanAt(rowText, off)?.let { return it }
        return escapedSpanAt(rowText, off)
    }

    /** Nearest quote pair (single or double) enclosing the tap, inclusive. */
    private fun quotedSpanAt(rowText: String, off: Int): ExplicitSpan? {
        val direct = quotedSpanFromOpenerAtOrBefore(rowText, off)
        if (direct != null) return direct
        // Tap sitting ON the CLOSING quote: the first scan found that quote as
        // the "opener" (nothing matches to its right). Rescan from just before
        // the tap - the real opener sits left of the closing quote, and the
        // tap offset itself equals q2 (off == q2 passes the enclosing check).
        if (off > 0 && (rowText[off] == '"' || rowText[off] == '\'')) {
            return quotedSpanFromOpenerAtOrBefore(rowText, off - 1)
        }
        return null
    }

    /** The quote pair whose opener is the last quote at or before [off]. */
    private fun quotedSpanFromOpenerAtOrBefore(rowText: String, off: Int): ExplicitSpan? {
        // Scan left for the LAST quote char at or before the tap. The tap may
        // sit ON the opening quote (device D1: tap at the very start of the
        // path), so the left scan starts AT the tap offset.
        var q1 = -1
        var i = off
        while (i >= 0) {
            val c = rowText[i]
            if (c == '"' || c == '\'') { q1 = i; break }
            i--
        }
        if (q1 < 0) return null
        val q = rowText[q1]
        // Matching quote to the right of the opener. Inside a double-quoted
        // span an escaped \" is skipped (pathological for a terminal row, but
        // cheap to honor).
        var j = q1 + 1
        var q2 = -1
        while (j < rowText.length) {
            val c = rowText[j]
            if (c == '\\' && q == '"' && j + 1 < rowText.length) { j += 2; continue }
            if (c == q) { q2 = j; break }
            j++
        }
        if (q2 < 0 || off > q2) return null
        val content = rowText.substring(q1 + 1, q2)
        // Must be path-shaped (the resolver gate) and non-empty.
        if (content.isBlank()) return null
        if (!content.contains('/') && !content.contains('.')) return null
        return ExplicitSpan(q1, q2 + 1, rowText.substring(q1, q2 + 1), content)
    }

    /**
     * Maximal backslash-escaped run around the tap: extends left and right
     * while every whitespace crossing is escaped (\ ). Requires at least one
     * escaped space (a single chunk is NOT an explicit span — the plain scanner
     * already handles it) and a path shape.
     */
    private fun escapedSpanAt(rowText: String, off: Int): ExplicitSpan? {
        val n = rowText.length
        var s = off.coerceIn(0, n - 1)
        var e = s + 1
        // Grow left: a space is passable only when escaped by a backslash.
        while (s > 0) {
            val c = rowText[s - 1]
            if (c == ' ') {
                if (s - 2 >= 0 && rowText[s - 2] == '\\') s-- else break
            } else {
                s--
            }
        }
        // Grow right under the same rule.
        while (e < n) {
            val c = rowText[e]
            if (c == ' ') {
                if (rowText[e - 1] == '\\') e++ else break
            } else {
                e++
            }
        }
        val raw = rowText.substring(s, e)
        // At least one escaped space — otherwise it is a plain single chunk.
        if (!raw.contains("\\ ")) return null
        if (!raw.contains('/') && !raw.contains('.')) return null
        val cleaned = raw.replace("\\ ", " ")
        return ExplicitSpan(s, e, raw, cleaned)
    }
}
