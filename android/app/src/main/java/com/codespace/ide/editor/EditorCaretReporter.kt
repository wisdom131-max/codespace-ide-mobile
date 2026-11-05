package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * 2026-11-05: reports the editor's live caret to [EditorSelectionMemory] so a menu undo can build its
 * snapshot with the real selection.
 *
 * A separate composable rather than a block inside `CodeEditor`: that function is at the 64 KB JVM
 * method limit (CI 3565-era: `Method too large ... CodeEditorKt.CodeEditor`), so every new lambda in
 * its body costs headroom the editor cannot spare. Kept to one call and no control flow.
 */
@Composable
internal fun EditorCaretReporter(path: String?, start: Int, end: Int) {
    LaunchedEffect(path, start, end) {
        EditorSelectionMemory.remember(path ?: "", start, end)
    }
}
