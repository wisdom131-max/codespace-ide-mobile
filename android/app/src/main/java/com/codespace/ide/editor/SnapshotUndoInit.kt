package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.TextRange
import com.codespace.ide.editor.undo.SnapshotUndoManager

/**
 * Extracted from CodeEditor.kt to avoid JVM 64KB bytecode limit.
 * Handles undo stack initialization: clears + pushes initial state on file switch,
 * and pushes initial state on first load.
 */
@Composable
internal fun SnapshotUndoInit(
    snapshotUndo: SnapshotUndoManager,
    content: String,
    value: androidx.compose.ui.text.TextFieldValue,
    editorEvent: EditorEvent?
) {
    // Clear undo stack on file switch AND push initial state for the new file.
    LaunchedEffect(content) {
        if (value.text != content && editorEvent !is EditorEvent.UserTyping && editorEvent !is EditorEvent.ProgrammaticTextChange) {
            snapshotUndo.clear()
            snapshotUndo.pushForce(
                SnapshotUndoManager.TextSnapshot(
                    content, TextRange(content.length), emptyList()
                )
            )
        }
    }
    // Push initial state on first load.
    LaunchedEffect(Unit) {
        if (snapshotUndo.canUndo().not()) {
            snapshotUndo.pushForce(
                SnapshotUndoManager.TextSnapshot(
                    value.text, value.selection, emptyList()
                )
            )
        }
    }
}
