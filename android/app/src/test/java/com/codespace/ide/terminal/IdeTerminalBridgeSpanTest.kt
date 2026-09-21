package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A5-SPAN (audit #2854): span extraction for terminal file-link taps. Cases ported
 * from the xterm.js link-provider behavior (match against the whole buffer line,
 * not a whitespace-delimited word) plus the on-device failure forms.
 */
class IdeTerminalBridgeSpanTest {

    private fun tok(row: String, at: String): String? {
        val off = row.indexOf(at)
        assert(off >= 0) { "test bug: '$at' not in row" }
        return IdeTerminalBridge.extractFileLinkToken(row, off)
    }

    @Test fun spacedPath_tapFirstChunk() {
        assertEquals("src/My Project/Main.kt:42",
            tok("src/My Project/Main.kt:42: error", "My"))
    }

    @Test fun spacedPath_tapAfterSpace() {
        assertEquals("src/My Project/Main.kt:42",
            tok("src/My Project/Main.kt:42: error", "Project"))
    }

    @Test fun spacedPath_tapLineSuffix() {
        assertEquals("src/My Project/Main.kt:42",
            tok("src/My Project/Main.kt:42: error", "42"))
    }

    @Test fun trailingProse_neverSwallowed() {
        val r = tok("src/My Project/Main.kt:42: unresolved reference", "reference")
        assertNull(r)
    }

    @Test fun plainPath_singleWord_unchanged() {
        assertEquals("src/Main.kt:42",
            tok("src/Main.kt:42 unresolved reference", "Main"))
    }

    @Test fun lineAndCol_form() {
        assertEquals("/sdcard/My Project/src/Main.kt:3:5",
            tok("/sdcard/My Project/src/Main.kt:3:5: error", "Main"))
    }

    @Test fun gccFileQuoteLineForm() {
        assertEquals("My Project/src/main.c:42",
            tok("main.c: In function 'x':\nFile \"My Project/src/main.c\", line 42", "src/main.c"))
    }

    @Test fun brackets_excluded() {
        assertEquals("My Project/Main.kt:42",
            tok("[My Project/Main.kt:42]", "Main"))
    }

    @Test fun plainWords_notLinks() {
        assertNull(tok("error foo bar:12", "bar"))
    }

    @Test fun tapOutsideMatch_returnsNull() {
        assertNull(IdeTerminalBridge.extractFileLinkToken("src/Main.kt:42 error", 999))
        assertNull(IdeTerminalBridge.extractFileLinkToken("hello world", 3))
    }

    @Test fun versionStrings_withoutLineSuffix_doNotMatch() {
        assertNull(tok("kotlin 1.9.0: done", "1.9.0"))
    }

    @Test fun leadingFillerWord_isPartOfSpan() {
        // Extraction matches the whole span (xterm.js behavior); leading-chunk
        // dropping is the RESOLVER's job, via linkCandidates below.
        assertEquals("at My Project/Main.kt:42",
            tok("  at My Project/Main.kt:42 in foo", "My"))
    }

    @Test fun linkCandidates_progressiveLeadingChunkDrop() {
        assertEquals(
            listOf("at My Project/Main.kt:42", "My Project/Main.kt:42",
                   "Project/Main.kt:42", "Main.kt:42"),
            IdeTerminalBridge.linkCandidates("at My Project/Main.kt:42"))
    }

    @Test fun linkCandidates_noSpace_singleCandidate() {
        assertEquals(listOf("src/Main.kt:42"),
            IdeTerminalBridge.linkCandidates("src/Main.kt:42"))
    }
}
