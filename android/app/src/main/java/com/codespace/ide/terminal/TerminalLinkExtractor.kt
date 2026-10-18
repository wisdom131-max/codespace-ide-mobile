package com.codespace.ide.terminal

/**
 * BATCH7-P1 (advisor 1c, 2026-10-18, device-evidence): the link-tap extractor was
 * REGEX-BASED on the main thread and its span pattern
 *     (?:[\w.\-/\\]+| (?=[\w.\-/\\]))+:\d+(?::\d+)?
 * is a nested repetition — the inner `+` and the outer `+` can partition one run of
 * token characters in exponentially many ways, so a long line with no `:digits`
 * sends java.util.regex into catastrophic backtracking. The owner's logs show FOUR
 * ANRs in three minutes with the main thread inside Matcher.find -> ICU
 * RegexMatcher::MatchChunkAt (BATCH7-P0 frames will name the caller) — the tap ran
 * this regex over whole wrapped lines on the MAIN thread. The tap must NEVER be
 * able to hang the main thread:
 *  - extraction works on a BOUNDED WINDOW around the tap offset (SPAN_WINDOW chars
 *    each side), not the whole line;
 *  - the span token is found by a LINEAR one-pass scanner (each character visited
 *    O(1) times — no backtracking, no nested repetition);
 *  - the only regex left (gcc `File "..." , line N` form) is linear and runs over the
 *    window wrapped in a DEADLINE CharSequence that aborts matching after
 *    EXTRACTION_DEADLINE_MS — a safety net even if a future edit reintroduces a
 *    dangerous pattern.
 *
 * Behavior-compatible with the old extractor (unit-tested): same token shapes
 * (path with spaces, :line[:col] suffix, gcc form), same '/'-or-'.' gate, same
 * trim of trailing ':' ',' ';', same null when the tap is not inside any token.
 */
object TerminalLinkExtractor {

    /** Bounded window around the tap: 256 chars each side (advisor 1c example). */
    const val SPAN_WINDOW = 256

    /** Safety net: any regex over the window aborts after this many ms. */
    const val EXTRACTION_DEADLINE_MS = 100L

    /** Thrown by [DeadlineCharSequence] when the safety-net deadline is hit. */
    class ExtractionDeadlineExceeded(message: String) : RuntimeException(message)

    /**
     * CharSequence wrapper that aborts regex matching at a deadline. Java's Matcher
     * reads input through charAt(), so the check rides every character access; the
     * matcher cannot loop past the deadline.
     *
     * HONEST SCOPE (2026-10-18, local-JDK + CI evidence): the pathological RUNTIME
     * blowup is the ANDROID ICU engine's behavior (device: 4 ANRs, main in
     * Matcher.find -> ICU MatchChunkAt). On the JVM the JDK engine finishes small
     * inputs in ~2ms (study/prefilter) and dies by StackOverflowError on huge ones —
     * a live wall-clock deadline never fires there, and an engine that snapshots
     * its CharSequence via toString() before matching would bypass the wrapper
     * entirely. This deadline is therefore the BELT-AND-BRACES layer: the primary
     * guarantees are the bounded window (SPAN_WINDOW) and the linear, non-nested
     * patterns; the wrapper exists so that a future pattern edit on a
     * charAt-reading engine cannot hang the tap.
     */
    class DeadlineCharSequence(
        private val cs: CharSequence,
        private val deadlineNanos: Long,
    ) : CharSequence {
        private fun check() {
            if (System.nanoTime() > deadlineNanos) {
                throw ExtractionDeadlineExceeded("link extraction exceeded ${EXTRACTION_DEADLINE_MS}ms safety net")
            }
        }
        override val length: Int get() { check(); return cs.length }
        override fun get(index: Int): Char { check(); return cs[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            check()
            return DeadlineCharSequence(cs.subSequence(startIndex, endIndex), deadlineNanos)
        }
        override fun toString(): String = cs.toString()
    }

    /** ASCII token character class — EXACTLY the old regex's [\w.\-/\\] (Java \w is ASCII). */
    private fun isTokenChar(c: Char): Boolean {
        return (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
            c == '_' || c == '.' || c == '-' || c == '/' || c == '\\'
    }

    private val gccRegex = Regex("""File +"([^"]+)", *line +(\d+)""")

    /**
     * The gcc/clang traceback form, inside the bounded window, under the deadline
     * safety net. Linear pattern — `[^"]+` cannot backtrack catastrophically.
     */
    private fun matchGccForm(window: String, offsetInWindow: Int): String? {
        val guarded = DeadlineCharSequence(window, System.nanoTime() + EXTRACTION_DEADLINE_MS * 1_000_000)
        val matches = try {
            gccRegex.findAll(guarded).map { Triple(it.groupValues[1], it.groupValues[2], it.range) }.toList()
        } catch (_: ExtractionDeadlineExceeded) { return null } catch (_: Throwable) { return null }
        // Only a match COVERING the tap counts (same rule as the old code).
        for ((path, line, range) in matches) {
            if (offsetInWindow in range) return path + ":" + line
        }
        return null
    }

    /**
     * Linear one-pass span scanner. Finds the token-shaped match that CONTAINS
     * [offsetInWindow], or null. Old-regex semantics preserved:
     *  - chunks = runs of token chars, joined by single spaces that are followed
     *    by a token char (paths with spaces);
     *  - the match must end in `:` + digits, optionally `:` + digits;
     *  - the token must contain '/' or '.' to count as a file link;
     *  - the tap may sit anywhere inside the match (token, space, colon or digits).
     */
    fun scanSpanToken(window: String, offsetInWindow: Int): String? {
        val n = window.length
        if (offsetInWindow < 0 || offsetInWindow >= n) return null
        var i = 0
        while (i < n) {
            if (!isTokenChar(window[i])) { i++; continue }
            // Consume the token run: chunks joined by qualifying single spaces.
            var end = i
            while (end < n) {
                if (isTokenChar(window[end])) { end++; continue }
                if (window[end] == ' ' && end + 1 < n && isTokenChar(window[end + 1])) { end += 2; continue }
                break
            }
            // The match must continue with :digits (optionally :digits more).
            var mEnd = end
            if (mEnd < n && window[mEnd] == ':') {
                var j = mEnd + 1
                var digits1 = 0
                while (j < n && window[j] in '0'..'9') { j++; digits1++ }
                if (digits1 > 0) {
                    mEnd = j
                    // Optional second :digits (column).
                    if (mEnd < n && window[mEnd] == ':') {
                        var k = mEnd + 1
                        var digits2 = 0
                        while (k < n && window[k] in '0'..'9') { k++; digits2++ }
                        if (digits2 > 0) mEnd = k
                    }
                    // The tap must sit INSIDE this match [i, mEnd).
                    if (offsetInWindow >= i && offsetInWindow < mEnd) {
                        val token = window.substring(i, mEnd).trimEnd(':', ',', ';').trim()
                        if (token.contains('/') || token.contains('.')) return token
                    }
                }
            }
            // No match here: continue scanning AFTER this token run (never rescan
            // its interior — that is what makes the scan linear).
            i = end
        }
        return null
    }

    /**
     * Drop-in replacement for the old extractFileLinkToken(rowText, tapOffset):
     * bounded window + linear scanner + deadline safety net. Pure — JVM-testable.
     */
    fun extractFileLinkToken(rowText: String, tapOffset: Int): String? {
        if (tapOffset < 0 || tapOffset >= rowText.length) return null
        val winStart = maxOf(0, tapOffset - SPAN_WINDOW)
        val winEnd = minOf(rowText.length, tapOffset + SPAN_WINDOW + 1)
        val window = rowText.substring(winStart, winEnd)
        val off = tapOffset - winStart
        // gcc form first (same precedence as before).
        matchGccForm(window, off)?.let { return it }
        // span form: linear scan.
        return scanSpanToken(window, off)
    }
}
