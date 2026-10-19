package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DEVICE DEFECT D2 (advisor round 2026-10-19): the :line:col chain end to
 * end. The device tap "file:2:1" opened line 2 with the caret at the line
 * START — the column existed in the resolver (BATCH11-S2) but was dropped at
 * the editor-open callback. CaretPlacement owns the base conversion exactly
 * once, documented at every boundary:
 *   LINE 1-based, COLUMN 1-based (gcc/ripgrep convention), col <= 0 or missing
 *   = start of line (the previous behavior — 2-arg callers are unchanged).
 */
class CaretPlacementTest {

    private val doc = listOf(
        "line one",        // 0..8   (line 1)
        "1234567890",      // 9..19  (line 2)
        "third",           // 20..25 (line 3)
    ).joinToString("\n")
    // offsets: l=0 'line one'(8) \n=8, '1234567890' 9..18, \n=19, 'third' 20..24

    // ── the device case: "file:2:1" → caret AFTER the first char of line 2 ──

    @Test fun line2_col1_landsAfterFirstChar() {
        // column 1 = the caret sits BEFORE char 1 → offset = line start + 0
        assertEquals(9, CaretPlacement.caretOffsetForJump(doc, 2, 1))
    }

    @Test fun line2_col3_landsBeforeThirdChar() {
        // 'file:2:3' → caret at offset 9 + (3-1) = 11 (after "12")
        assertEquals(11, CaretPlacement.caretOffsetForJump(doc, 2, 3))
    }

    @Test fun line2_col5() {
        assertEquals(13, CaretPlacement.caretOffsetForJump(doc, 2, 5))
    }

    @Test fun line2_col8() {
        assertEquals(16, CaretPlacement.caretOffsetForJump(doc, 2, 8))
    }

    @Test fun line1_col1() {
        assertEquals(0, CaretPlacement.caretOffsetForJump(doc, 1, 1))
    }

    // ── col <= 0 or missing = start of line (parity with the old behavior) ──

    @Test fun colZero_isLineStart() {
        assertEquals(9, CaretPlacement.caretOffsetForJump(doc, 2, 0))
    }

    @Test fun colNegative_isLineStart() {
        assertEquals(9, CaretPlacement.caretOffsetForJump(doc, 2, -1))
    }

    // ── clamping (advisor: "clamped to line length") ───────────────────────

    @Test fun colBeyondLineEnd_clampsToLineEnd() {
        // line 2 has 10 chars; col 50 → caret at the line end (offset 19 = the \n)
        assertEquals(19, CaretPlacement.caretOffsetForJump(doc, 2, 50))
    }

    @Test fun lineBeyondDocument_clampsToDocumentEnd() {
        assertEquals(doc.length, CaretPlacement.caretOffsetForJump(doc, 99, 1))
    }

    @Test fun lastLine_colBeyondEnd_clampsToDocumentEnd() {
        assertEquals(doc.length, CaretPlacement.caretOffsetForJump(doc, 3, 99))
    }

    // ── unicode line: columns are EDITOR CHARACTERS (UTF-16), documented ───

    @Test fun unicodeLine_colIsCharacterColumn() {
        val doc2 = "修复abc\nsecond"
        // col 3 → after 'b' → UTF-16 offset 0 + (3-1) = 2
        assertEquals(2, CaretPlacement.caretOffsetForJump(doc2, 1, 3))
    }

    // ── empty/short documents ───────────────────────────────────────────────

    @Test fun emptyDoc_isZero() {
        assertEquals(0, CaretPlacement.caretOffsetForJump("", 1, 1))
    }

    @Test fun singleLineNoNewline() {
        assertEquals(3, CaretPlacement.caretOffsetForJump("abcdef", 1, 4))
    }
}
