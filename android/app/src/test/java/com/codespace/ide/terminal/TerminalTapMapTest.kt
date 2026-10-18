package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BATCH6-P4 (advisor ruling 3): JVM tests for the pure cell-to-char converter.
 * Widths come from the SAME table the buffer uses (com.termux.terminal.WcWidth),
 * so each test documents the buffer's real accounting:
 *  - width-2 code point stored once, occupies two cells;
 *  - combining mark stored as extra java chars, occupies ZERO cells;
 *  - surrogate pairs (emoji) = 2 java chars, width 2;
 *  - ASCII: char index == cell index (the fix is an identity there).
 */
class TerminalTapMapTest {

    // READ (WcWidth.width): CJK ideographs are width 2.
    private val cjk = "日"

    @Test fun ascii_identity_offsetEqualsCell() {
        val row = "error: src/Main.java:12: not found"
        // Tap each cell position; converter must return exactly that index.
        for (i in row.indices) {
            assertEquals("ascii tap at cell $i", i, TerminalTapMap.cellOffsetToCharOffset(row, i))
        }
    }

    @Test fun ascii_tapPastEnd_clampsToLength() {
        // "src/Main.kt:42" is 14 chars; a far-past tap clamps, never negative.
        assertEquals(14, TerminalTapMap.cellOffsetToCharOffset("src/Main.kt:42", 100))
    }

    @Test fun wideCjk_eachHalfSelectsTheWideChar() {
        val row = cjk + "a.py:9"
        // Cells: [日][日][a][.][p][y][:][9] = 8 cells, 7 chars.
        // Tap cells 0 and 1 (both halves of 日) -> char 0.
        assertEquals(0, TerminalTapMap.cellOffsetToCharOffset(row, 0))
        assertEquals(0, TerminalTapMap.cellOffsetToCharOffset(row, 1))
        // Tap cell 2 ('a') -> char 1: WITHOUT the fix, cell 2 read as char 2
        // (the '.'), i.e. every tap after the wide char landed one past.
        assertEquals(1, TerminalTapMap.cellOffsetToCharOffset(row, 2))
        assertEquals(2, TerminalTapMap.cellOffsetToCharOffset(row, 3))
        // Last cell ('9' occupies cell 7) -> last char 6.
        assertEquals(6, TerminalTapMap.cellOffsetToCharOffset(row, 7))
    }

    @Test fun wideCjkMany_tapAtFarEnd_landsOnTheLastChars() {
        // The A2 mechanism shape: 4 wide chars then a path. 17 cells, 13 chars.
        val row = cjk + cjk + cjk + cjk + "test.py:9"
        // Tap cell 15 (the ':' before the 9) -> char 11.
        assertEquals(11, TerminalTapMap.cellOffsetToCharOffset(row, 15))
        // Tap cell 16 (the '9') -> char 12, the last char.
        assertEquals(12, TerminalTapMap.cellOffsetToCharOffset(row, 16))
        // OLD arithmetic: cell 16 >= length 13 -> out of range -> EMPTY token.
        // Now the tap resolves.
    }

    @Test fun emojiSurrogatePair_countsTwoCharsTwoCells() {
        val emoji = "\uD83D\uDE00" // U+1F600, surrogate pair, WcWidth width 2.
        val row = emoji + "x.kt:3"
        // 8 cells, 8 java chars.
        assertEquals(0, TerminalTapMap.cellOffsetToCharOffset(row, 0))
        // Tap the second half of the emoji (cell 1) -> the emoji's char 0.
        assertEquals(0, TerminalTapMap.cellOffsetToCharOffset(row, 1))
        // Tap cell 2 ('x') -> char 2 (past BOTH java chars of the pair).
        assertEquals(2, TerminalTapMap.cellOffsetToCharOffset(row, 2))
        // Tap the LAST cell (7, the '3') -> the last char 7.
        assertEquals(7, TerminalTapMap.cellOffsetToCharOffset(row, 7))
    }

    @Test fun combiningMark_zeroCells_keepsTapOnBaseChar() {
        val precomp = "caf\u00E9_tap.py:1" // precomposed é: width 1, no shift.
        assertEquals(12, TerminalTapMap.cellOffsetToCharOffset(precomp, 12))
        // Decomposed base+combining: "x" + U+0301 then a path. 11 cells, 13 chars.
        val decomp = "x\u0301src/t.kt:2"
        // Tap cell 0 -> char 0 (the base 'x'; the mark owns zero cells).
        assertEquals(0, TerminalTapMap.cellOffsetToCharOffset(decomp, 0))
        // Tap cell 1 ('s') -> char 2 (past the mark's extra java char).
        assertEquals(2, TerminalTapMap.cellOffsetToCharOffset(decomp, 1))
        // Tap cells 10 and 11 (':' and '2') -> chars 10 and 11.
        assertEquals(10, TerminalTapMap.cellOffsetToCharOffset(decomp, 10))
        assertEquals(11, TerminalTapMap.cellOffsetToCharOffset(decomp, 11))
    }

    @Test fun tab_treatedAsOneCell_neverCorrupts() {
        // Reconstructed row text never contains '\t' (the emulator expands tabs
        // into cells), but the converter must stay total if one ever appears.
        val row = "a\tb:12"
        assertEquals(2, TerminalTapMap.cellOffsetToCharOffset(row, 2))
        assertEquals(5, TerminalTapMap.cellOffsetToCharOffset(row, 5))
    }

    @Test fun mixedRow_tapOnThePathDigits_landsExactly() {
        // The realistic device line: a wide char, an emoji, then a path.
        val row = cjk + "\uD83D\uDE00" + " src/TapProbe.java:9: error"
        // 31 cells, 30 java chars. The '9' in the path occupies cell 23
        // (2 wide cells + 2 emoji cells + 19 width-1 cells before it).
        assertEquals(22, TerminalTapMap.cellOffsetToCharOffset(row, 23))
        // OLD arithmetic: 23 -> char 23 (the 'a' of "java" — one past the '9');
        // on shorter rows the same arithmetic lands OUT of range -> EMPTY.
    }
}
