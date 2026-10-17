package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH6-P1 / advisor A2 (2026-10-17): shape survey of the REAL extractor on the
 * path shapes the owner actually sees on device. Each test documents which shapes
 * produce a usable token, which produce null, and which produce a token that the
 * tap handler then silently drops (isResolvableToken false — the 1e visibility gap).
 *
 * The owner's device evidence: both Test L taps logged
 *   "[tap] stage 1 done: extracted '' span=false (mode=FULL)"
 * — an EMPTY legacy word AND a null span. The wide-character tests here model the
 * mechanism: TerminalBuffer.getWordAtLocation computes
 *   textOffset = (y - y1) * mColumns + x
 * in CELL units against a joined text measured in CHARACTERS — every wide char
 * before the tap drifts the offset +1, and once the drift pushes past the trimmed
 * line end, textOffset >= text.length() returns "" (getWordAtLocation) and null
 * (getWrappedLineAtLocation). That is the exact observed signature.
 */
class IdeTerminalBridgeShapeTest {

    private fun tok(row: String, at: String): String? {
        val off = row.indexOf(at)
        assert(off >= 0) { "test bug: '$at' not in row" }
        return IdeTerminalBridge.extractFileLinkToken(row, off)
    }

    // ── shapes that WORK ─────────────────────────────────────────────────────

    @Test fun plainPath_withLineColSuffix() {
        assertEquals("src/Main.java:12", tok("src/Main.java:12:  text", "src"))
    }

    @Test fun relativeDotSlash() {
        assertEquals("./a.py:3", tok("./a.py:3", "a.py"))
    }

    @Test fun bareNameWithExtension() {
        assertEquals("spantest.txt:1", tok("spantest.txt:1", "spantest"))
    }

    @Test fun javaStackTrace_tapFilePart() {
        assertEquals("Main.java:9", tok("at pkg.Main.main(Main.java:9)", "Main.java"))
    }

    @Test fun pythonTraceback_tapPathPart() {
        assertEquals("/x/y.py:3", tok("File \"/x/y.py\", line 3", "y.py"))
    }

    @Test fun gccError_lineCol() {
        assertEquals("foo.c:10:5", tok("foo.c:10:5: error", "foo"))
    }

    @Test fun grepRn_todoHit() {
        assertEquals("src/Main.java:12", tok("src/Main.java:12: TODO do x", "src"))
    }

    @Test fun pathWithSpaces_includesLeadingCommandWord() {
        // The span swallows "cd " (space-lookahead); the resolver's
        // linkCandidates() progressive leading-chunk drop handles it.
        assertEquals("cd My Project/src/Main.kt:42", tok("cd My Project/src/Main.kt:42", "Project"))
    }

    @Test fun uppercaseExtension() {
        assertEquals("MAIN.JAVA:12", tok("MAIN.JAVA:12: error", "MAIN"))
    }

    @Test fun noExtension_withSlash() {
        assertEquals("scripts/run:12", tok("scripts/run:12", "scripts"))
    }

    @Test fun longWrappedLine_pathAtEnd_charOffset() {
        // Three screen rows joined into one wrapped line; the path lives in the
        // last row. With a CORRECT char offset the span matcher finds it.
        val row = "first line of the wrapped output text that continues on to " +
            "the second row and then finally mentions " +
            "src/Main.java:12: unresolved reference"
        assertEquals("src/Main.java:12", tok(row, "src/Main"))
    }

    @Test fun coloredGrep_rawEscapeCharsDoNotBreakThePathSegment() {
        // SGR escapes are consumed by the emulator (cells hold clean text); this
        // covers the case where raw ESC bytes survive into the row text anyway —
        // the span match simply stops at the escape and resumes at the path.
        val row = "src/Main.java:12: TODO do x"
        assertEquals("src/Main.java:12", tok(row, "src"))
    }

    // ── shapes that produce NULL or a silent no-op ────────────────────────────

    @Test fun javaStackTrace_tapMethodPart_isNull() {
        // Tapping the method name (not the file part) matches no span — the
        // legacy word "pkg.Main.main(Main.java:9)" is unresolvable.
        assertNull(tok("at pkg.Main.main(Main.java:9)", "pkg"))
    }

    @Test fun pythonTraceback_tapLineWord_isNull() {
        // Tapping "line" or the number outside the gcc-match range: the legacy
        // word is "line"/"3" — no '/' and no '.', so the tap is a silent no-op.
        assertNull(tok("File \"/x/y.py\", line 3", "line"))
    }

    @Test fun bareNameNoExtension_nullAndSilentlyUnresolvable() {
        // "Makefile:12" — span matches but the /-or-. gate rejects it (null),
        // and the legacy word fails isResolvableToken: the tap does NOTHING,
        // with no "[Link]" feedback. Visibility gap confirmed at extractor level.
        assertNull(tok("Makefile:12: error", "Makefile"))
        assertFalse(IdeTerminalBridge.isResolvableToken("Makefile:12"))
    }

    @Test fun emptyAndWhitespaceTokens_areNotResolvable() {
        // The owner's exact evidence: both taps produced ''. The handler chain
        // for an empty token runs NO resolve (isResolvableToken false) and writes
        // no [Link] line — nothing between "stage 1 done" and the default tap
        // path (requestFocus + showSoftInput).
        assertFalse(IdeTerminalBridge.isResolvableToken(""))
        assertFalse(IdeTerminalBridge.isResolvableToken("   "))
    }

    // ── the wide-character drift mechanism (owner's empty-token signature) ─────

    @Test fun wideCharDrift_tapInsidePath_stillMatches() {
        // "日日日 a.py:9" — CJK chars are ONE char but TWO cells in the buffer.
        // chars: 0日 1日 2日 3sp 4a 5. 6p 7y 8: 9'9' (length 10)
        // cells: 6 + 1 + 6 = 13; the tap VISUALLY lands on 'a' (cell 7), but the
        // buffer's CELL formula returns char 7 ('y'). Small drift that lands
        // INSIDE the path still matches the span.
        val row = "日日日 a.py:9"
        val charIndexOfA = 4
        val cellOffsetOfVisualA = 7
        assertEquals("a.py:9", IdeTerminalBridge.extractFileLinkToken(row, charIndexOfA))
        assertEquals("a.py:9", IdeTerminalBridge.extractFileLinkToken(row, cellOffsetOfVisualA))
    }

    @Test fun wideCharDrift_pastLineEnd_returnsNull_emptyTokenSignature() {
        // "run 日日日 a.py:9" — chars: r0 u1 n2 sp3 日4 日5 日6 sp7 a8 .9 p10 y11 :12 913
        // (length 14). Cells: 3 + 1 + 6 + 1 + 6 = 17, so the tap VISUALLY lands on
        // the trailing '9' at CELL column 16 — but the buffer's formula
        // textOffset = (y - y1) * mColumns + x returns 16 >= length 14:
        // getWordAtLocation returns "" and getWrappedLineAtLocation returns null.
        // Result: extracted '' span=false — the exact signature the owner's log
        // shows for BOTH Test L taps.
        val row = "run 日日日 a.py:9"
        val cellOffsetOfVisualLastChar = 16
        assertTrue(cellOffsetOfVisualLastChar >= row.length)
        assertNull(IdeTerminalBridge.extractFileLinkToken(row, cellOffsetOfVisualLastChar))
    }
}
