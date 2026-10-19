package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH11-S2 (advisor approved design, stage 2): the CHEAP CANDIDATE pipeline —
 * pure string work, no file lookups. Shapes are the advisor's S2 list, pinned
 * with the owner's exact device evidence:
 *   - prompt row "root@localhost:/sdcard/My codespace app 1#" produced the token
 *     "root@localhost:/sdcard/My" — the prompt prefix must be stripped;
 *   - quoted and backslash-escaped paths with spaces;
 *   - :line, :line:col, (line), ", line N" suffix grammars;
 *   - git diff prefixes and trailing sentence junk.
 */
class LinkCandidateBuilderTest {

    private fun first(raw: String): LinkCandidateBuilder.Candidate = LinkCandidateBuilder.build(raw).first()

    private fun paths(raw: String): List<String> = LinkCandidateBuilder.build(raw).map { it.path }

    // ── suffix grammars ────────────────────────────────────────────────────

    @Test fun lineOnlySuffix() {
        val c = first("src/Main.kt:42")
        assertEquals("src/Main.kt", c.path); assertEquals(42, c.line); assertEquals(-1, c.col)
    }

    @Test fun lineColSuffix_noLongerEatsTheLineIntoThePath() {
        // The legacy parse left ":42" inside the path — the FIXED shape.
        val c = first("Main.kt:42:5")
        assertEquals("Main.kt", c.path); assertEquals(42, c.line); assertEquals(5, c.col)
    }

    @Test fun parenLineForm() {
        val c = first("src/Main.kt(42)")
        assertEquals("src/Main.kt", c.path); assertEquals(42, c.line)
    }

    @Test fun parenLineColForm() {
        val c = first("src/Main.kt(42, 5)")
        assertEquals("src/Main.kt", c.path); assertEquals(42, c.line); assertEquals(5, c.col)
    }

    @Test fun commaLineForm() {
        val c = first("\"My codespace app 1/1.py\", line 12")
        assertEquals("My codespace app 1/1.py", c.path); assertEquals(12, c.line)
    }

    @Test fun spaceLineForm() {
        val c = first("1.py line 7")
        assertEquals("1.py", c.path); assertEquals(7, c.line)
    }

    @Test fun noSuffix() {
        val c = first("My codespace app 1/1.py")
        assertEquals("My codespace app 1/1.py", c.path); assertEquals(-1, c.line)
    }

    // ── quotes and escapes ─────────────────────────────────────────────────

    @Test fun doubleQuotedPathWithSpaces_isExplicit() {
        val c = first("\"My codespace app 1/1.py\"")
        assertEquals("My codespace app 1/1.py", c.path); assertTrue(c.explicit)
    }

    @Test fun singleQuotedPathWithSpaces_isExplicit() {
        val c = first("'My codespace app 1/1.py'")
        assertEquals("My codespace app 1/1.py", c.path); assertTrue(c.explicit)
    }

    @Test fun backslashEscapedSpaces_areUnescaped_andExplicit() {
        val c = first("My\\ codespace\\ app\\ 1/1.py")
        assertEquals("My codespace app 1/1.py", c.path); assertTrue(c.explicit)
    }

    @Test fun unquotedPathWithSpaces_isNotExplicit() {
        val c = first("My codespace app 1/1.py")
        assertFalse(c.explicit)
    }

    // ── prompt rows (owner evidence shape) ────────────────────────────────

    @Test fun promptPrefixUserAtHost_isStripped() {
        // The owner's prompt row: root@localhost:/sdcard/My codespace app 1#
        // The raw candidate is emitted first (it never exists as a path), the
        // prompt-stripped one right after — the resolver tries in order.
        val p = paths("root@localhost:/sdcard/My")
        assertTrue(p.contains("/sdcard/My"))
        assertEquals("/sdcard/My", p[1])
    }

    @Test fun promptPrefix_tildeForm() {
        val p = paths("root@localhost:~/src/1.py")
        assertTrue(p.contains("~/src/1.py"))
    }

    @Test fun promptPrefix_neverEatsUrls() {
        // No '@' before the colon -> no strip; URL taps never reach the resolver anyway.
        val c = first("https://example.com/x")
        assertEquals("https://example.com/x", c.path)
    }

    // ── junk, diff prefixes, enclosure ────────────────────────────────────

    @Test fun trailingJunk_isStripped() {
        // The raw form is emitted first (filenames CAN contain these chars);
        // the junk-stripped candidate follows — the resolver tries in order.
        val comma = paths("1.py,")
        assertEquals("1.py", comma[1])
        val dot = paths("1.py.")
        assertEquals("1.py", dot[1])
        val excited = paths("(1.py!)")
        assertTrue(excited.contains("1.py"))
    }

    @Test fun parentRefDots_areProtected() {
        assertEquals("..", first("..").path)
        // Rank 0 keeps the raw form ("../x.kt." with its sentence period); the
        // junk-stripped candidate is what existence selects at runtime. The pin:
        // the parent ref SURVIVES stripping intact (never "../x" or "/x.kt.").
        assertTrue(paths("../x.kt.").contains("../x.kt"))
    }

    @Test fun gitDiffPrefix_isStrippedAsCandidate() {
        val p = paths("b/src/Main.kt")
        assertTrue(p.contains("src/Main.kt"))
        assertTrue(p.contains("b/src/Main.kt"))  // base form kept: existence decides
    }

    @Test fun enclosingPunctuation_twoLayers() {
        val c = first("[{src/Main.kt:42}]")
        assertEquals("src/Main.kt", c.path); assertEquals(42, c.line)
    }

    // ── ranking, drops, caps ──────────────────────────────────────────────

    @Test fun explicitForms_rankFirst() {
        val list = LinkCandidateBuilder.build("My\\ codespace\\ app\\ 1/1.py")
        assertTrue(list.first().explicit)
    }

    @Test fun leadingChunkDrop_bounded() {
        val p = paths("error   at spantest.txt:1")
        assertTrue(p.contains("spantest.txt"))
        assertTrue(p.contains("at spantest.txt"))
    }

    @Test fun candidatesCappedAtTwelve() {
        val list = LinkCandidateBuilder.build("a b c d e f g h My codespace app 1/1.py:3")
        assertTrue(list.size <= LinkCandidateBuilder.MAX_CANDIDATES)
    }

    @Test fun plainSingleWordToken_singleCandidate_compatibleWithOldBehavior() {
        val list = LinkCandidateBuilder.build("spantest.txt:1")
        assertEquals(1, list.size)
        assertEquals("spantest.txt", list[0].path); assertEquals(1, list[0].line)
    }

    @Test fun duplicateCandidates_deduped() {
        val list = LinkCandidateBuilder.build("Main.kt")
        // clean/junk/prompt/diff variants all parse identically -> ONE candidate.
        assertEquals(1, list.size)
    }

    @Test fun hugeLineNumber_isNotALine() {
        val c = first("Main.kt:999999999999")
        assertEquals("Main.kt:999999999999", c.path); assertEquals(-1, c.line)
    }

    @Test fun unicodePaths_survive() {
        val c = first("日本 dir/ファイル.txt:3")
        assertEquals("日本 dir/ファイル.txt", c.path); assertEquals(3, c.line)
    }
}
