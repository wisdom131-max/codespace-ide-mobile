package com.codespace.ide.terminal

/**
 * BATCH6-P4 (advisor ruling 3, 2026-10-18): the cell-to-CHAR offset fix for the
 * terminal tap path.
 *
 * The drift (READ, TerminalBuffer.java): both getWordAtLocation and
 * getWrappedLineAtLocation reconstructed the wrapped-line TEXT and then
 * computed the tap index as `(y - y1) * mColumns + x` — a CELL count — against
 * a CHAR string. TerminalRow stores a width-2 code point (CJK, most emoji)
 * ONCE but it occupies TWO cells, and combining marks add java chars but ZERO
 * cells, so after any such character the cell count outruns the char index and
 * the tap lands past the real text (out-of-range → EMPTY token: the
 * `extracted '' span=false` signature). VS Code separates buffer-cell math
 * from string math; this converter is that separation for our tap path.
 *
 * Pure: no Android imports, same width table as the buffer (WcWidth), so it is
 * JVM-unit-testable and EXACTLY matches how TerminalRow accounts columns.
 * Identity for pure-ASCII rows (every code point width 1) — behavior unchanged
 * for the rows the owner's terminals almost always contain.
 */
object TerminalTapMap {

    @JvmStatic
    fun cellOffsetToCharOffset(text: String, cellOffset: Int): Int {
        if (cellOffset <= 0) return 0
        var cells = 0
        var charIndex = 0
        val len = text.length
        while (charIndex < len) {
            val cp = text.codePointAt(charIndex)
            val cpChars = Character.charCount(cp)
            // '\t' never appears in reconstructed row text (the emulator expands
            // tabs into cells); count it as width 1 so the function is total.
            val w = if (cp == '\t'.toInt()) 1 else com.termux.terminal.WcWidth.width(cp)
            if (w > 0) {
                if (cellOffset < cells + w) {
                    // The tapped cell is INSIDE this code point's cell span
                    // (either half of a wide char selects the char itself).
                    return charIndex
                }
                cells += w
            }
            // w == 0 (combining mark): extra java chars, no cell advance — the
            // tap keeps pointing at the base char, which the loop already
            // returned before reaching here.
            charIndex += cpChars
        }
        return len
    }
}
