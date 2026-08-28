package com.codespace.ide.editor.undo

import androidx.compose.ui.text.TextRange

/**
 * O(1) snapshot-based undo/redo manager.
 *
 * Stores full TextSnapshot entries (text + selection + extra cursors) instead
 * of diffs. Undo/redo is a single assignment — no string reconstruction, no
 * diff computation, no merge logic.
 *
 * Coalescing: push() calls within [coalesceMs] of the last push are merged
 * into a single undo step — the FIRST snapshot in the group is kept (not
 * replaced), so undo restores to the state at the start of the typing burst.
 * pushForce() always starts a fresh group (lastPushTime reset to 0L).
 *
 * Max depth: [maxStackSize] snapshots (default 200).
 */
class SnapshotUndoManager(
    private val maxStackSize: Int = 200,
    private val coalesceMs: Long = 500,
) {
    data class TextSnapshot(
        val text: String,
        val selection: TextRange,
        val extraCursors: List<Int>,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val undoStack = ArrayDeque<TextSnapshot>()
    private val redoStack = ArrayDeque<TextSnapshot>()
    private var lastPushTime = 0L

    /**
     * Push a snapshot onto the undo stack.
     *
     * If called within [coalesceMs] of the previous push, coalesces into the
     * existing entry (keeps the first snapshot, discards subsequent ones).
     * This means undo restores to the state at the START of the typing burst.
     */
    fun push(snapshot: TextSnapshot) {
        val now = System.currentTimeMillis()
        if (undoStack.isNotEmpty() && now - lastPushTime < coalesceMs) {
            // Coalesce: keep the FIRST entry in this group (state to restore TO at
            // the start of typing). Do NOT replace it — the first push in a
            // coalescing group is the correct undo target, not the latest.
            lastPushTime = now
            redoStack.clear()
            return
        }
        if (undoStack.size >= maxStackSize) undoStack.removeFirst()
        undoStack.addLast(snapshot)
        redoStack.clear()
        lastPushTime = now
    }

    /**
     * Force-push without coalescing (for programmatic edits that should always
     * be a separate undo step).
     */
    fun pushForce(snapshot: TextSnapshot) {
        if (undoStack.size >= maxStackSize) undoStack.removeFirst()
        undoStack.addLast(snapshot)
        redoStack.clear()
        // Reset to 0L so the next push() call can NEVER coalesce with this
        // force-pushed entry. Force-pushed entries (initial state, paste,
        // programmatic edits) must always start a fresh coalescing group.
        lastPushTime = 0L
    }

    /**
     * Undo: returns the previous snapshot, or null if nothing to undo.
     * [current] is pushed to the redo stack.
     */
    fun undo(current: TextSnapshot): TextSnapshot? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        return previous
    }

    /**
     * Redo: returns the next snapshot, or null if nothing to redo.
     * [current] is pushed to the undo stack.
     */
    fun redo(current: TextSnapshot): TextSnapshot? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        return next
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastPushTime = 0L
    }
}
