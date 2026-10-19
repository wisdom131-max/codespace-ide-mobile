package com.codespace.ide.editor

/**
 * DEVICE DEFECT D2 FIX (advisor round 2026-10-19): the editor-side half of the
 * :line:col chain. "file:2:1" used to open line 2 with the caret at the START
 * of the line because the COLUMN was dropped at the editor-open callback; the
 * resolver had parsed it all along (BATCH11-S2 LinkCandidateBuilder).
 *
 * BASES, documented at every boundary (advisor spec):
 *  - LINE is 1-based at every seam (scrollTargetLine, :line tokens, LSP rows
 *    converted by callers): caret line index = line1 - 1.
 *  - COLUMN is 1-based at every seam (the gcc/ripgrep convention, :line:col
 *    tokens, LSP columns converted by callers): caret char offset within the
 *    line = col1 - 1. col1 <= 0 (0, negative, or absent) = START OF LINE —
 *    exactly the previous behavior, so 2-arg callers are unchanged.
 *  - The column is a CHARACTER column in the editor's UTF-16 space (the
 *    caret's own space). gcc counts bytes, but an ASCII column coincides with
 *    a character column, and clamping keeps any drift inside the line.
 *  - The result is CLAMPED to the line's text (a column beyond the line end
 *    lands at the line end, never in the next line).
 */
object CaretPlacement {

    /**
     * The caret char offset for a 1-based line and column in [content].
     *
     * @param content the full document text
     * @param line1   1-based target line
     * @param col1    1-based target column; <= 0 = start of line
     * @return UTF-16 char offset, clamped into [lineStart, lineEnd]; when the
     *         line does not exist, the end of the document.
     */
    fun caretOffsetForJump(content: String, line1: Int, col1: Int): Int {
        val len = content.length
        var lineStart = 0
        var lineIdx = 0
        // Walk lines until the target line start (or document end).
        while (lineIdx < line1 - 1) {
            var nl = content.indexOf('\n', lineStart)
            if (nl == -1) return len  // line1 beyond the document
            lineStart = nl + 1
            lineIdx++
        }
        if (lineStart >= len) return len.coerceAtMost(if (len == 0) 0 else len)
        // Line end (exclusive): the next '\n', or document end.
        var lineEnd = content.indexOf('\n', lineStart)
        if (lineEnd == -1) lineEnd = len
        if (lineEnd > len) lineEnd = len
        if (col1 <= 0) return lineStart
        val colOffset = col1 - 1
        return lineStart + (if (colOffset > lineEnd - lineStart) lineEnd - lineStart else colOffset)
    }
}
