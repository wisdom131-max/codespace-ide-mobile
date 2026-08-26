package com.codespace.ide.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.codespace.ide.editor.undo.SnapshotUndoManager

/**
 * Extracted handler for toolbar Undo/Redo key inserts.
 * Lives in a separate file to keep CodeEditor.kt under the JVM 64KB bytecode limit.
 */
internal fun handleToolbarUndoRedo(
    key: String,
    snapshotUndo: SnapshotUndoManager,
    value: TextFieldValue,
    extraCursors: List<Int>,
    onUndoRedoStart: () -> Unit,
    onUndoRedoEnd: () -> Unit,
    onTextChange: (String, TextRange, String) -> Unit,
    onExtraCursorsChange: (List<Int>) -> Unit
): Boolean {
    if (key != "\u21A9" && key != "\u21AA") return false

    if (key == "\u21A9" && snapshotUndo.canUndo()) {
        onUndoRedoStart()
        val current = SnapshotUndoManager.TextSnapshot(value.text, value.selection, extraCursors)
        val snapshot = snapshotUndo.undo(current)
        if (snapshot != null) {
            val newExtra = EditShiftHelper.shiftExtraCursors(value.text, snapshot.text, snapshot.extraCursors)
            onExtraCursorsChange(newExtra)
            onTextChange(snapshot.text, snapshot.selection, "undo_toolbar")
        }
        onUndoRedoEnd()
        com.codespace.ide.diagnostics.AppOutputLog.log("UNDO: toolbar undo applied", "lsp")
        return true
    }

    if (key == "\u21AA" && snapshotUndo.canRedo()) {
        onUndoRedoStart()
        val current = SnapshotUndoManager.TextSnapshot(value.text, value.selection, extraCursors)
        val snapshot = snapshotUndo.redo(current)
        if (snapshot != null) {
            val newExtra = EditShiftHelper.shiftExtraCursors(value.text, snapshot.text, snapshot.extraCursors)
            onExtraCursorsChange(newExtra)
            onTextChange(snapshot.text, snapshot.selection, "redo_toolbar")
        }
        onUndoRedoEnd()
        com.codespace.ide.diagnostics.AppOutputLog.log("REDO: toolbar redo applied", "lsp")
        return true
    }

    return false
}
