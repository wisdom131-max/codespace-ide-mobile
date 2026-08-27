package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.TextFieldValue
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
    value: TextFieldValue,
    editorEvent: EditorEvent?
) {
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
