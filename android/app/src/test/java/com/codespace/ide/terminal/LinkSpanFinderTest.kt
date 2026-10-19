package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DEVICE DEFECT D1 FIX (advisor round 2026-10-19): the explicit-span pre-pass.
 * Quoted and shell-escaped spans containing the tap are ONE unit BEFORE any
 * chunking — detection is pure string work, NO file lookups (advisor design).
 * Taps at the BEGINNING (on the opening quote), MIDDLE and END are covered.
 */
class LinkSpanFinderTest {

    private val appDir = "/sdcard/My codespace app 1"
    private val quotedAbs = "$appDir/absolute space test.txt"

    private fun at(row: String, sub: String): Int =
        row.indexOf(sub).let { if (it >= 0) it else 0 }

    // ── quoted form ────────────────────────────────────────────────────────

    @Test fun quoted_tapOnOpeningQuote_findsWholeSpan() {
        val row = "echo '" + quotedAbs + "'"
        val span = LinkSpanFinder.explicitSpanAt(row, at(row, "'" + quotedAbs))
        assertEquals(quotedAbs, span?.cleaned)
    }

    @Test fun quoted_tapFirstCharInside_findsWholeSpan() {
        val row = "echo '" + quotedAbs + "'"
        val span = LinkSpanFinder.explicitSpanAt(row, at(row, quotedAbs))
        assertEquals(quotedAbs, span?.cleaned)
    }

    @Test fun quoted_tapMiddle_findsWholeSpan() {
        val row = "echo '" + quotedAbs + "'"
        val span = LinkSpanFinder.explicitSpanAt(row, at(row, "space test"))
        assertEquals(quotedAbs, span?.cleaned)
    }

    @Test fun quoted_tapOnClosingQuote_findsWholeSpan() {
        val row = "echo '" + quotedAbs + "'"
        val span = LinkSpanFinder.explicitSpanAt(row, row.length - 1)
        assertEquals(quotedAbs, span?.cleaned)
    }

    @Test fun quoted_doubleQuotes_findsWholeSpan() {
        val row = "echo \"" + quotedAbs + "\""
        val span = LinkSpanFinder.explicitSpanAt(row, at(row, quotedAbs))
        assertEquals(quotedAbs, span?.cleaned)
    }

    @Test fun quoted_spanRange_isExact() {
        val row = "echo '" + quotedAbs + "' done"
        val span = LinkSpanFinder.explicitSpanAt(row, at(row, "absolute"))!!
        // raw includes the quotes; start = index of opening quote
        assertEquals(row.indexOf("'"), span.start)
        assertEquals(row.indexOf("' done") + 1, span.end)
        assertEquals("'$quotedAbs'", span.raw)
    }

    // ── escaped form ────────────────────────────────────────────────────────

    private fun escaped(p: String): String = p.replace(" ", "\\ ")

    @Test fun escaped_tapAtStart_findsWholeSpan() {
        val row = "echo " + escaped(quotedAbs)
        val span = LinkSpanFinder.explicitSpanAt(row, at(row, "/sdcard"))
        assertEquals(quotedAbs, span?.cleaned)
    }

    @Test fun escaped_tapMiddle_findsWholeSpan() {
        val row = "echo " + escaped(quotedAbs)
        val span = LinkSpanFinder.explicitSpanAt(row, at(row, "space"))
        assertEquals(quotedAbs, span?.cleaned)
    }

    @Test fun escaped_tapEnd_findsWholeSpan() {
        val row = "echo " + escaped(quotedAbs)
        val span = LinkSpanFinder.explicitSpanAt(row, at(row, "test.txt"))
        assertEquals(quotedAbs, span?.cleaned)
    }

    @Test fun escaped_growStopsAtUnescapedSpace() {
        // row: "ls /sdcard\ dir\ file.txt plain.txt" — the growth must stop at
        // the unescaped space before "plain", never swallow the second token.
        val row = "ls /sdcard\\ dir\\ file.txt plain.py"
        val span = LinkSpanFinder.explicitSpanAt(row, at(row, "file.txt"))
        assertEquals("/sdcard\\ dir\\ file.txt", span?.raw)
        assertEquals("/sdcard dir file.txt", span?.cleaned)
    }

    // ── non-explicit forms return null (the plain scanner path) ────────────

    @Test fun plainSingleChunk_isNotExplicit() {
        val row = "cat plainfile.py:2"
        assertNull(LinkSpanFinder.explicitSpanAt(row, at(row, "plainfile")))
    }

    @Test fun quotedNonPathContent_isNotExplicit() {
        val row = "echo 'hello world' plain.py"
        assertNull(LinkSpanFinder.explicitSpanAt(row, at(row, "hello")))
    }

    @Test fun unquotedSpacesPath_isNotExplicit() {
        // Spaces WITHOUT escapes or quotes — this is the windows() case.
        val row = "cat zz dir/1.py"
        assertNull(LinkSpanFinder.explicitSpanAt(row, at(row, "zz")))
    }
}
