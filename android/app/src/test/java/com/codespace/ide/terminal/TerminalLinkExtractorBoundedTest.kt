package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH7-P1 (advisor 1d, 2026-10-18): adversarial tests for the link-tap extractor.
 * The OLD span regex `(?:[\w.\-/\\]+| (?=[\w.\-/\\]))+:\d+(?::\d+)?` is a nested
 * repetition: the inner `+` and the outer `+` can partition a joined run of token
 * characters in ~2^(tokenChars) ways, and java.util.regex must exhaust them all
 * before giving up at the missing `:digits`. Device evidence: FOUR ANRs in three
 * minutes with main inside Matcher.find -> ICU MatchChunkAt (BATCH7-P0 frames
 * pending). The ANR'd lines were hundreds-thousands of chars — no horizon at all.
 *
 * OLD blowup tests: the legacy pattern on the FULL-SIZE paired inputs
 * (25-30k chars — the owner's real wrapped-line sizes). The legacy engine has
 * ~2^(tokenChars) paths (no completion horizon) and its greedy pass is ~10-15k
 * recursion levels deep — on a default-stacked JVM it dies of StackOverflowError
 * inside Pattern.java (observed: CI 39124431659), and on deep-stacked JVMs it
 * simply hangs. Either way it cannot return an answer in 100ms — a catastrophic
 * death is a FAILURE, not a completion. FAIL if old returns to the tap path.
 * NEW tests: the FIX, synchronously, on the FULL-SIZE real inputs — both the
 * token and a 100ms wall-clock bound. Reverting the fix fails them.
 * PARITY tests: short inputs where BOTH terminate — identical answers.
 */
class TerminalLinkExtractorBoundedTest {

    /** The EXACT legacy span pattern, kept for the comparison tests only. */
    private val oldSpanRegex = Regex("""(?:[\w.\-/\\]+| (?=[\w.\-/\\]))+:\d+(?::\d+)?""")

    /**
     * True ONLY when the block RETURNED normally (with an answer) within [ms].
     * CI taught us (39124431659): on a fast JVM the legacy pattern can die of
     * StackOverflowError (java.util.regex backtracking is recursive — a deep
     * greedy match overflows the stack at Pattern.java) INSTEAD of hanging. A
     * catastrophic death is a FAILURE of the old extractor, never a completion —
     * so a thrown Throwable counts as "did not complete".
     */
    private fun completesWithAnAnswer(ms: Long, block: () -> Unit): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        val returnedNormally = booleanArrayOf(false)
        Thread {
            try { block(); returnedNormally[0] = true } catch (_: Throwable) {}
            done.countDown()
        }.apply { isDaemon = true; start() }
        return done.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS) && returnedNormally[0]
    }

    // Paired adversarial inputs — the SAME input for the OLD (dies) and NEW (fast)
    // member of each pair. Full-size (25-30k chars) on purpose: the legacy engine
    // has ~2^(tokenChars) backtracking paths (no completion horizon) and its
    // greedy pass is already ~10k recursion levels deep (stack overflow on
    // default-stacked JVMs) — either way it cannot return an answer in 100ms.
    private val inputWords = "word ".repeat(5000) + "end"
    private val inputRun = "a".repeat(30000) + " b"
    private val inputHeredoc = "cat > zz_tapcheck_a.py << 'EOF' " + "word ".repeat(5000)
    private val inputProse = "The quick brown fox jumps over the lazy dog and keeps going " +
        "without any colons or numbers at all just words words words " + "word ".repeat(5000)

    private fun oldExtractorResult(rowText: String, tapOffset: Int): String? {
        for (m in oldSpanRegex.findAll(rowText)) {
            if (!m.range.contains(tapOffset)) continue
            val token = m.value.trimEnd(':', ',', ';').trim()
            if (!token.contains('/') && !token.contains('.')) continue
            return token
        }
        return null
    }

    private fun assertFastToken(input: String, tapOffset: Int, expected: String?, limitMs: Long = 100) {
        val t0 = System.nanoTime()
        val got = TerminalLinkExtractor.extractFileLinkToken(input, tapOffset)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("extraction took ${ms}ms (limit ${limitMs}ms) on '${input.take(40)}...'", ms < limitMs)
        assertEquals("token mismatch on '${input.take(60)}' @ $tapOffset", expected, got)
    }

    // ── OLD: the adversarial shapes blow past the 100ms deadline ──

    @Test fun OLD_wordsNoColon_cannotAnswerIn100ms() {
        // 2^(3*5000+2) partition paths, no colon anywhere; ~15k-deep greedy recursion.
        val done = completesWithAnAnswer(100) { oldExtractorResult(inputWords, inputWords.length / 2) }
        assertTrue("old regex must not return an answer in 100ms on words without a colon", !done)
    }

    @Test fun OLD_longTokenRun_cannotAnswerIn100ms() {
        val done = completesWithAnAnswer(100) { oldExtractorResult(inputRun, 13) }
        assertTrue("old regex must not return an answer in 100ms on a 30k-char token run", !done)
    }

    @Test fun OLD_heredocPasteLine_cannotAnswerIn100ms() {
        val done = completesWithAnAnswer(100) { oldExtractorResult(inputHeredoc, 30) }
        assertTrue("old regex must not return an answer in 100ms on the heredoc paste line", !done)
    }

    @Test fun OLD_proseLine_cannotAnswerIn100ms() {
        val done = completesWithAnAnswer(100) { oldExtractorResult(inputProse, inputProse.length / 2) }
        assertTrue("old regex must not return an answer in 100ms on a prose line", !done)
    }

    // ── NEW: the FULL-SIZE real inputs resolve under the fix, under the same 100ms ──

    @Test fun NEW_wordsNoColon_resolvesFastNull() {
        assertFastToken(inputWords, inputWords.length / 2, null)
        assertFastToken(inputWords, 0, null)
    }

    @Test fun NEW_longTokenRun_resolvesFastNull() {
        assertFastToken(inputRun, 15000, null)
        assertFastToken(inputRun, 29999, null)
    }

    @Test fun NEW_longWrappedLine_resolvesFast() {
        val input = ("src/Main.kt ".repeat(200)) + "src/Main.kt:42"
        // Tap far from the token: null, fast.
        assertFastToken(input, 500, null)
        // Tap ON the token: the match spans back through the joined run
        // (window-bounded) and must end with the real token — fast.
        val t0 = System.nanoTime()
        val got = TerminalLinkExtractor.extractFileLinkToken(input, input.length - 10)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("tap on token took ${ms}ms", ms < 100)
        assertTrue("expected the token at the end, got '${got?.take(60)}...'",
            got != null && got.endsWith("src/Main.kt:42"))
    }

    @Test fun NEW_heredocPasteLine_resolvesFastNull() {
        assertFastToken(inputHeredoc, 30, null)
        assertFastToken(inputHeredoc, 18, null)
    }

    @Test fun NEW_proseLine_resolvesFastNull() {
        assertFastToken(inputProse, inputProse.length / 2, null)
        assertFastToken(inputProse, 0, null)
    }

    @Test fun NEW_overlongTokenPath_staysBounded() {
        // A 600-char path is wider than the 256-char window: extraction must still
        // complete under the bound and return at most a window-sized token.
        val input = ("a/b".repeat(200)) + ".py:7"
        val t0 = System.nanoTime()
        val got = TerminalLinkExtractor.extractFileLinkToken(input, input.length - 2)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("overlong token took ${ms}ms", ms < 100)
        assertTrue("token must stay window-bounded, got len=${got?.length}",
            got == null || got.length <= TerminalLinkExtractor.SPAN_WINDOW * 2 + 2)
    }

    // ── PARITY: short inputs where BOTH extractors terminate — same answers ──
    // (gcc-form cases assert the fix directly: the old FULL extractor had the
    // identical gcc branch — verified in git history — but the span-only legacy
    // helper here cannot produce gcc answers, so no old-vs-new line there.)

    @Test fun parity_compileErrorLine() {
        val input = "error: zz_tapcheck_a.py:3: unresolved reference zz"
        assertEquals(oldExtractorResult(input, 12), TerminalLinkExtractor.extractFileLinkToken(input, 12))
        assertEquals("zz_tapcheck_a.py:3", TerminalLinkExtractor.extractFileLinkToken(input, 12))
        // Tap the line digit itself ('3' is at offset 24).
        assertEquals("zz_tapcheck_a.py:3", TerminalLinkExtractor.extractFileLinkToken(input, 24))
    }

    @Test fun parity_gccTracebackForm() {
        val input = "  File \"/root/x.py\", line 2"
        assertEquals("/root/x.py:2", TerminalLinkExtractor.extractFileLinkToken(input, 12))
    }

    @Test fun parity_gccFormWithSpacesInPath() {
        val input = "  File \"My codespace app 3/1.py\", line 12"
        assertEquals("My codespace app 3/1.py:12", TerminalLinkExtractor.extractFileLinkToken(input, 30))
    }

    @Test fun parity_spanFormWithSpaces() {
        val span = "cd My codespace app 3/1.py:12 done"
        // Both extractors match the JOINED run from the start ("cd My ... :12") —
        // the resolver's linkCandidates() drops the leading chunks, as before.
        assertEquals(oldExtractorResult(span, 20), TerminalLinkExtractor.extractFileLinkToken(span, 20))
        assertEquals("cd My codespace app 3/1.py:12", TerminalLinkExtractor.extractFileLinkToken(span, 20))
    }

    @Test fun parity_plainWord_noSlashOrDot_isNull() {
        val input = "error notfound:42 done"
        assertEquals(oldExtractorResult(input, 15), TerminalLinkExtractor.extractFileLinkToken(input, 15))
        assertNull(TerminalLinkExtractor.extractFileLinkToken(input, 15))
    }

    @Test fun parity_lineColumnForm() {
        val input = "src/Main.kt:42:9 problem"
        assertEquals("src/Main.kt:42:9", TerminalLinkExtractor.extractFileLinkToken(input, 8))
        assertEquals(oldExtractorResult(input, 8), TerminalLinkExtractor.extractFileLinkToken(input, 8))
    }

    @Test fun parity_shortNoSuffix_isNull() {
        // Kept SHORT on purpose: with no ':digits' the old regex must exhaust its
        // backtracking paths (~2^tokenChars) — a longer version of this line is
        // exactly the freeze input, see OLD_proseLine above.
        val input = "just Main.kt in it"
        assertEquals(oldExtractorResult(input, 8), TerminalLinkExtractor.extractFileLinkToken(input, 8))
        assertNull(TerminalLinkExtractor.extractFileLinkToken(input, 8))
    }

    // ── Deadline safety net ─────────────────────────────────────────────────────
    // CI lesson (run 39125449524, one red): a LIVE budget cannot be proven by
    // wall-clock on the JVM — JDK's engine completes the 20-word input in ~2ms
    // (1295 charAt calls; the study/prefilter kills the blowup there), so the
    // deadline never fires. The pathological RUNTIME blowup is the ANDROID ICU
    // engine's behavior (device evidence: 4 ANRs, main in Matcher.find ->
    // ICU MatchChunkAt); on the JVM the same pattern family dies by
    // StackOverflowError instead (see the OLD pairs above). The MECHANISM is
    // therefore proven deterministically: a deadline already in the past aborts
    // matching on the FIRST character access — engine-independent.

    @Test fun deadlineCharSequence_pastBudget_abortsOnFirstAccess() {
        val input = "word ".repeat(20) + "end"
        val guarded = TerminalLinkExtractor.DeadlineCharSequence(input, System.nanoTime() - 1)
        var threw = false
        try {
            oldSpanRegex.findAll(guarded).any()
        } catch (_: TerminalLinkExtractor.ExtractionDeadlineExceeded) { threw = true }
        assertTrue("a past deadline must abort matching immediately", threw)
    }

    @Test fun deadlineCharSequence_generousBudget_doesNotFalseAbort() {
        // The wrapper must be TRANSPARENT when the budget is generous — no false
        // aborts on inputs the engine matches quickly.
        val input = "word ".repeat(20) + "end"
        val guarded = TerminalLinkExtractor.DeadlineCharSequence(input, System.nanoTime() + 1_000_000_000L)
        var threw = false
        try {
            oldSpanRegex.findAll(guarded).any()
        } catch (_: TerminalLinkExtractor.ExtractionDeadlineExceeded) { threw = true }
        assertTrue("a generous budget must not abort", !threw)
    }
}
