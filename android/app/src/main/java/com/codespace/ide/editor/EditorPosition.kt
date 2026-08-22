package com.codespace.ide.editor

import org.json.JSONObject

/**
 * Phase A: Canonical editor position.
 *
 * Replaces the 5+ independent line/column/offset calculation methods that were
 * scattered throughout CodeEditor.kt (Methods A-E from the architecture audit).
 *
 * An EditorPosition is an immutable triple (offset, line, column) where:
 * - offset: absolute character index into the document text (0-based)
 * - line: document line number (0-based)
 * - column: character column within the line (0-based)
 *
 * A cursor is just a collapsed selection (start == end == cursor offset).
 */
data class EditorPosition(
    val offset: Int,
    val line: Int,
    val column: Int,
) {
    /** Convert to LSP JSON-RPC position: {"line": line, "character": column} */
    fun toLspPosition(): JSONObject = JSONObject()
        .put("line", line)
        .put("character", column)

    companion object {
        val ZERO = EditorPosition(0, 0, 0)
    }
}

/**
 * Phase A: The single canonical position mapper.
 *
 * All subsystems (cursor, gutter, LSP, diagnostics, hover, completion, scrolling,
 * overlays) should route through this mapper for offset <-> (line, column)
 * conversions. Never compute `text.take(n).count { it == '\n' }` or
 * `text.lastIndexOf('\n', ...)` inline again.
 *
 * The mapper caches newline offsets for O(log n) lookups and is rebuilt when
 * the text changes (via Compose remember or manual construction).
 *
 * Phase B: Added shiftOnInsert / shiftOnDelete for cached-position adjustment.
 * When text is edited, positions that were computed against the old text can
 * be adjusted without a full re-computation — positions after the edit point
 * shift by the delta, positions before stay put.
 *
 * Inspired by sora-editor's CachedIndexer (io.github.rosemoe.sora.text.CachedIndexer),
 * which is the single path between offset and (line, column) in that editor.
 */
class PositionMapper(text: String) {

    private val newlineOffsets: IntArray
    private val textLength: Int

    init {
        val list = mutableListOf<Int>()
        val len = text.length
        var i = 0
        while (i < len) {
            if (text[i] == '\n') list.add(i)
            i++
        }
        newlineOffsets = list.toIntArray()
        textLength = len
    }

    /** Get the document line (0-based) for a character offset. O(log n). */
    fun offsetToLine(offset: Int): Int {
        val safeOffset = offset.coerceIn(0, textLength)
        if (newlineOffsets.isEmpty() || safeOffset == 0) return 0
        if (safeOffset > newlineOffsets.last()) return newlineOffsets.size
        var lo = 0
        var hi = newlineOffsets.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (newlineOffsets[mid] < safeOffset) lo = mid + 1
            else hi = mid - 1
        }
        return hi + 1
    }

    /** Get the character column (0-based) for a character offset. O(log n). */
    fun offsetToColumn(offset: Int): Int {
        val safeOffset = offset.coerceIn(0, textLength)
        if (safeOffset == 0) return 0
        val line = offsetToLine(safeOffset)
        return safeOffset - lineStart(line)
    }

    /** Get a full EditorPosition for a character offset. O(log n). */
    fun offsetToPosition(offset: Int): EditorPosition {
        val safeOffset = offset.coerceIn(0, textLength)
        val line = offsetToLine(safeOffset)
        val column = safeOffset - lineStart(line)
        return EditorPosition(safeOffset, line, column)
    }

    /**
     * Get the LSP position (line, character) for a character offset.
     * This is the canonical way to build LSP request positions.
     * O(log n).
     */
    fun offsetToLspPosition(offset: Int): JSONObject {
        return offsetToPosition(offset).toLspPosition()
    }

    /**
     * Convert an LSP (line, character) pair to a character offset.
     * Used when applying LSP responses (diagnostics, definitions, edits) back to the document.
     * O(1) for line lookup, O(1) for offset computation.
     */
    fun lspToOffset(line: Int, character: Int): Int {
        val safeLine = line.coerceIn(0, lineCount() - 1)
        val lineStart = lineStart(safeLine)
        val lineEnd = lineEnd(safeLine)
        return (lineStart + character.coerceIn(0, lineEnd - lineStart)).coerceIn(0, textLength)
    }

    /** Get the character offset where a document line starts (0-based). */
    fun lineStart(line: Int): Int {
        if (line <= 0) return 0
        val safeLine = line.coerceAtMost(newlineOffsets.size)
        return if (safeLine == 0) 0 else newlineOffsets[safeLine - 1] + 1
    }

    /** Get the character offset where a document line ends (exclusive). */
    fun lineEnd(line: Int): Int {
        val safeLine = line.coerceIn(0, newlineOffsets.size)
        return if (safeLine < newlineOffsets.size) newlineOffsets[safeLine] else textLength
    }

    /**
     * Get the text content of a specific document line.
     * Replaces `value.text.split('\n').getOrNull(line)` throughout the codebase.
     */
    fun getLineText(text: String, line: Int): String {
        val safeLine = line.coerceIn(0, lineCount() - 1)
        val start = lineStart(safeLine)
        val end = lineEnd(safeLine)
        return text.substring(start, end)
    }

    /**
     * Convert (line, column) to a character offset.
     * O(1).
     */
    fun lineColumnToOffset(line: Int, column: Int): Int {
        val safeLine = line.coerceIn(0, newlineOffsets.size)
        val start = lineStart(safeLine)
        val end = lineEnd(safeLine)
        return (start + column.coerceIn(0, end - start)).coerceIn(0, textLength)
    }

    /** Total number of document lines. */
    fun lineCount(): Int = newlineOffsets.size + 1

    /** Total document length. */
    fun length(): Int = textLength

    /** Whether the offset is at the start of a line. */
    fun isLineStart(offset: Int): Boolean {
        val safeOffset = offset.coerceIn(0, textLength)
        if (safeOffset == 0) return true
        return newlineOffsets.contains(safeOffset - 1)
    }

    // -------------------------------------------------------------------------
    // Phase B: Position auto-shifting on text edits
    // -------------------------------------------------------------------------

    /**
     * Phase B: Shift an offset to account for text inserted at [insertPos].
     *
     * Offsets before the insertion point are unchanged.
     * Offsets at or after the insertion point shift by [insertLength].
     *
     * Inspired by sora-editor's MappedSpans.adjustOnInsert() which shifts
     * span positions when text is inserted.
     */
    fun shiftOnInsert(offset: Int, insertPos: Int, insertLength: Int): Int {
        if (insertLength == 0) return offset
        return if (offset >= insertPos) offset + insertLength else offset
    }

    /**
     * Phase B: Shift an EditorPosition to account for text inserted at [insertPos].
     */
    fun shiftPositionOnInsert(pos: EditorPosition, insertPos: Int, insertLength: Int): EditorPosition {
        val newOffset = shiftOnInsert(pos.offset, insertPos, insertLength)
        return offsetToPosition(newOffset)
    }

    /**
     * Phase B: Shift an offset to account for text deleted at [deletePos].
     *
     * Offsets before the deletion point are unchanged.
     * Offsets inside the deleted range clamp to [deletePos].
     * Offsets after the deleted range shift back by [deleteLength].
     *
     * Inspired by sora-editor's MappedSpans.adjustOnDelete().
     */
    fun shiftOnDelete(offset: Int, deletePos: Int, deleteLength: Int): Int {
        if (deleteLength == 0) return offset
        val deleteEnd = deletePos + deleteLength
        return when {
            offset <= deletePos -> offset
            offset >= deleteEnd -> offset - deleteLength
            else -> deletePos
        }
    }

    /**
     * Phase B: Shift an EditorPosition to account for text deleted at [deletePos].
     */
    fun shiftPositionOnDelete(pos: EditorPosition, deletePos: Int, deleteLength: Int): EditorPosition {
        val newOffset = shiftOnDelete(pos.offset, deletePos, deleteLength)
        return offsetToPosition(newOffset)
    }

    /**
     * Phase B: Batch-shift a list of offsets for an insertion.
     * Returns a new list with shifted offsets.
     */
    fun shiftOffsetsOnInsert(offsets: List<Int>, insertPos: Int, insertLength: Int): List<Int> {
        if (insertLength == 0) return offsets
        return offsets.map { shiftOnInsert(it, insertPos, insertLength) }
    }

    /**
     * Phase B: Batch-shift a list of offsets for a deletion.
     * Returns a new list with shifted offsets.
     */
    fun shiftOffsetsOnDelete(offsets: List<Int>, deletePos: Int, deleteLength: Int): List<Int> {
        if (deleteLength == 0) return offsets
        return offsets.map { shiftOnDelete(it, deletePos, deleteLength) }
    }
}
