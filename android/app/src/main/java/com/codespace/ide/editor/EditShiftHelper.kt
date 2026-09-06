package com.codespace.ide.editor

/**
 * Shared helper for shifting secondary positions (extra cursors, decorations)
 * when text is edited outside the onValueChange handler.
 *
 * Before this helper existed, each direct-edit site in CodeEditor.kt (snippet
 * insertion, tab, extra keys, completion accept, format, undo/redo, duplicate
 * line, move line, etc.) only set `value = TextFieldValue(...)` with a
 * hand-computed cursor — but never shifted extraCursors or decorations.
 *
 * This meant multi-cursor positions and diagnostic highlight positions became
 * stale after any edit that bypassed onValueChange.
 *
 * Usage at every edit site:
 *   val (newExtras, newDecorations) = EditShiftHelper.shiftAll(
 *       oldText = value.text,
 *       newText = newText,
 *       extraCursors = extraCursors,
 *       decorationStore = decorationStore
 *   )
 *   extraCursors = newExtras
 *
 * The cursor itself (selection) is still computed by the edit site, since each
 * site knows the semantic intent (e.g., "place cursor after inserted snippet").
 * What this helper handles is the SECONDARY positions that the edit site
 * doesn't know about.
 */
object EditShiftHelper {

    /**
     * Shift extra cursors and decorations for a text edit.
     *
     * Computes the change region by finding the common prefix/suffix between
     * old and new text, then:
     * - Shifts each extra cursor by the delta if it's at or after the change start
     * - Calls decorationStore.shiftOnEdit for diagnostics/highlights
     *
     * Returns the new list of extra cursor positions.
     */
    fun shiftExtraCursors(
        oldText: String,
        newText: String,
        extraCursors: List<androidx.compose.ui.text.TextRange>,
    ): List<androidx.compose.ui.text.TextRange> {
        if (oldText == newText) return extraCursors
        if (extraCursors.isEmpty()) return extraCursors

        val changeStart = findChangeStart(oldText, newText)
        val delta = newText.length - oldText.length

        if (delta == 0) return extraCursors

        // Multi-cursor Plan A: extra cursors are full TextRange selections.
        // Shift each endpoint past the change start by the delta (insertions
        // inside a selection extend it, VS Code marker semantics).
        return extraCursors.map { r ->
            val start = if (r.start >= changeStart) r.start + delta else r.start
            val end = if (r.end >= changeStart) r.end + delta else r.end
            androidx.compose.ui.text.TextRange(
                start.coerceIn(0, newText.length),
                end.coerceIn(0, newText.length),
            )
        }
    }

    /**
     * Find where the text change starts (common prefix length).
     */
    private fun findChangeStart(oldText: String, newText: String): Int {
        val minLen = minOf(oldText.length, newText.length)
        var i = 0
        while (i < minLen && oldText[i] == newText[i]) i++
        return i
    }
}
