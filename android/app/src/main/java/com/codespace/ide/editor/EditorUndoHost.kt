package com.codespace.ide.editor

import com.codespace.ide.editor.undo.SnapshotUndoManager
import com.codespace.ide.editor.undo.SharedFileUndo

/**
 * 2026-11-05 (device finding #16): the undo/redo MECHANICS, kept out of every composable.
 *
 * Pure and synchronous so the step can be unit tested without an editor, and so neither
 * `CodeEditor` (already at the 64KB method limit) nor the pane host has to grow: the caller
 * passes the current text and gets back the snapshot to apply, or null when the stack is empty.
 */
internal object EditorUndoHost {

    /** True when there is something to undo (redo = false) or redo for this file. */
    fun available(redo: Boolean, path: String): Boolean {
        val stack = SharedFileUndo.forFile(path)
        return if (redo) stack.canRedo() else stack.canUndo()
    }

    /**
     * One step through the file's shared stack.
     *
     * The cursor position is carried through the snapshot rather than invented here: VS Code
     * restores the selection with the model version (same reason), and a menu undo that jumped to
     * offset 0 would be worse than no undo.
     */
    fun step(redo: Boolean, path: String, current: SnapshotUndoManager.TextSnapshot): SnapshotUndoManager.TextSnapshot? {
        if (path.isBlank()) return null
        val stack = SharedFileUndo.forFile(path)
        return if (redo) stack.redo(current) else stack.undo(current)
    }
}
