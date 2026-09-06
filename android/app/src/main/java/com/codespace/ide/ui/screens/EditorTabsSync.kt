package com.codespace.ide.ui.screens

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * B1 REACTIVE-SYNC (2026-09-06): applies EditorPane's authoritative
 * (openPaths, activePath) report to the shell's mirror state.
 *
 * Previously the shell's editorTabs/activeEditorTab were mutated ad-hoc at a
 * few open-file sites and NEVER updated when the user closed/switched tabs
 * inside EditorPane — so the Problems badge kept counting a closed file's
 * lint errors, Open Editors listed ghosts, and the root-removal branch could
 * mis-decide. Keeping the mirror in sync at ONE point fixes all of them
 * without a single manual refresh trigger.
 */
internal fun syncEditorTabsFromPane(
    editorTabs: SnapshotStateList<String>,
    activeEditorTabState: MutableState<String?>,
    paths: List<String>,
    activePath: String?,
) {
    // Replace contents only when they actually differ — preserves list identity
    // (ExplorerPane and the badge produceStates observe this exact instance).
    if (editorTabs.toList() != paths) {
        editorTabs.clear()
        editorTabs.addAll(paths)
    }
    if (activeEditorTabState.value != activePath) {
        activeEditorTabState.value = activePath
    }
}
