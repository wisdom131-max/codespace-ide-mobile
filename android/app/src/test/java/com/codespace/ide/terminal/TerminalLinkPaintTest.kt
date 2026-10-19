package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DEVICE DEFECT PAINT PINS (advisor round 2026-10-19, D1/D2/D3).
 *
 * WHAT DRAWS THE "HIGHLIGHT" (READ): TerminalRenderer.drawFileLinkTokens — a
 * BATCH-G-STYLE pass that runs after each row's normal rendering and repaints
 * matched tokens in VS Code link blue + underline. The pattern was (Java):
 *     [^\s]+\.(kt|py|txt|...)(:\d+)?(?=\s|$)
 * matched PER VISUAL ROW over the row's raw text. It is INDEPENDENT of the tap
 * resolver — it never sees the resolver's candidate — which is why a spaces
 * path showed only its last chunk underlined while the tap opened the file.
 *
 * The extraction (TerminalLinkPaint, pure) makes every device paint defect
 * JVM-testable. The pins marked [RED] reproduce the device observations and
 * fail against the current single-row, single-col-group pattern; they go
 * GREEN in the fix commit.
 */
class TerminalLinkPaintTest {

    // ── parity pins (current behavior, GREEN before AND after the fix) ────

    @Test fun plainToken_isPainted() {
        val spans = TerminalLinkPaint.baseSpans("cat src/Main.kt:42 more")
        assertEquals(listOf(TerminalLinkPaint.Span(4, 18)), spans)
    }

    @Test fun plainTxtWithLine_isPainted() {
        val spans = TerminalLinkPaint.baseSpans("err file.txt:2 tail")
        assertEquals(listOf(TerminalLinkPaint.Span(4, 14)), spans)
    }

    @Test fun rowWithoutDotOrSlash_paintsNothing() {
        assertTrue(TerminalLinkPaint.baseSpans("just some words here").isEmpty())
    }

    @Test fun trailingWhitespaceIsNotStyled() {
        val spans = TerminalLinkPaint.baseSpans("1.py   ")
        assertEquals(listOf(TerminalLinkPaint.Span(0, 4)), spans)
    }

    @Test fun relativeUnquotedSpacesPath_onlyTheExtensionChunkIsPainted() {
        // D1 observation (stays after the fix too): DETECTION cannot know that
        // "relative space test.txt" is one path without file I/O — only the
        // extension-bearing chunk matches the paint pattern. The WHOLE span is
        // shown AFTER resolution via the resolved-span overlay (advisor: "for
        // others, show the resolved span after resolution").
        val spans = TerminalLinkPaint.baseSpans("cat relative space test.txt")
        assertEquals(listOf(TerminalLinkPaint.Span(19, 27)), spans)
    }

    // ── [RED] D2: :line:col must be painted (device: "file:2:1 is not
    //    highlighted"; the old pattern allows only ONE :digits group) ───────

    @Test fun lineColForm_isPainted() {
        val spans = TerminalLinkPaint.baseSpans("err file.txt:2:1 tail")
        assertEquals(listOf(TerminalLinkPaint.Span(4, 16)), spans)
    }

    @Test fun lineColForm_fiveDigits_isPainted() {
        val spans = TerminalLinkPaint.baseSpans("err file.txt:12:34 tail")
        assertEquals(listOf(TerminalLinkPaint.Span(4, 18)), spans)
    }

    // ── [RED] D3: a token cut by the wrap is painted on BOTH visual rows ────
    //    Device: python3 -c 'print("x"*180 + " café-test.txt")' highlights
    //    "afé-test.txt" — the first 'c' sits on the PREVIOUS wrapped row and
    //    the single-row pattern cannot match it (no '.' on that row piece).
    //    The fix joins the seam: the wrapping row's tail + the continuation
    //    row's head form ONE match, painted on both rows.

    @Test fun wrapSeam_bothSidesPainted() {
        // Row N ends with the token's first char; row N+1 carries the rest.
        val prev = "start of a deliberately very long line of text so it wraps and the file name c"
        val next = "afé-test.txt now"
        val prevPaint = TerminalLinkPaint.paintForRow(prev, -1, true, next)
        val nextPaint = TerminalLinkPaint.paintForRow(next, prevPaint.seamIntoNext, false, null)
        // The 'c' (the last char of row N) must be painted on row N...
        assertTrue("row N must paint the wrapped token's start", prevPaint.spans.any { it.end == prev.length && it.start == prev.length - 1 })
        // ...and the continuation "afé-test.txt" on row N+1.
        assertTrue("row N+1 must paint the token continuation", nextPaint.spans.any { it.start == 0 && it.end == 12 })
    }

    @Test fun shortRow_unicodeTokenStillPaintedWhole() {
        // Device: the same name on a short line is fully highlighted (no wrap).
        val spans = TerminalLinkPaint.baseSpans("ls café-test.txt")
        assertEquals(listOf(TerminalLinkPaint.Span(3, 16)), spans)
    }

    // ── [RED] D1 paint: quoted + escaped spans highlighted WHOLE at
    //    detection time (advisor: "quoted and escaped spans can be
    //    highlighted whole at detection time" — pure detection, no file I/O) ─

    @Test fun quotedSpacesPath_paintedWhole() {
        val row = "echo '/sdcard/My codespace app 1/absolute space test.txt' done"
        val spans = TerminalLinkPaint.paintForRow(row, -1, false, null).spans
        val whole = spans.firstOrNull { row.substring(it.start, it.end) == "'/sdcard/My codespace app 1/absolute space test.txt'" }
        assertTrue("quoted span must be painted whole at detection: $spans", whole != null)
    }

    @Test fun escapedSpacesPath_paintedWhole() {
        val row = "echo /sdcard/My\\ codespace\\ app\\ 1/f.py done"
        val spans = TerminalLinkPaint.paintForRow(row, -1, false, null).spans
        val whole = spans.firstOrNull { row.substring(it.start, it.end) == "/sdcard/My\\ codespace\\ app\\ 1/f.py" }
        assertTrue("escaped span must be painted whole at detection: $spans", whole != null)
    }

    // ── the resolved-span overlay mapping (D1 "for others") — pure mapping
    //    of the resolver's char span to the tapped row's cell range ────────

    @Test fun overlay_charSpanMapsToCells() {
        // The wrapped logical line "cat zz file.py more" with a 10-col terminal:
        // row 0 = "cat zz fil", row 1 = "e.py more". The token "zz file.py"
        // spans chars 4..15 in the LOGICAL text; the overlay must map it to
        // row 0 cells 4..10 and row 1 cells 0..5.
        val logical = "cat zz file.py more"
        val span = TerminalLinkPaint.overlayCells(logical, 4, 14, 10)
        assertEquals(2, span.size)
        assertEquals(TerminalLinkPaint.OverlayCell(0, 4, 10), span[0])
        assertEquals(TerminalLinkPaint.OverlayCell(1, 0, 4), span[1])
    }

    @Test fun overlay_unicodeChars_countCellsNotChars() {
        // é in the logical line: ONE UTF-16 char, ONE terminal cell (width 1).
        val logical = "cat café.txt done"
        val span = TerminalLinkPaint.overlayCells(logical, 4, 12, 16)
        assertEquals(1, span.size)
        assertEquals(TerminalLinkPaint.OverlayCell(0, 4, 12), span[0])
    }
}
