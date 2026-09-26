package com.codespace.ide.editor

import androidx.compose.runtime.mutableStateMapOf
import com.codespace.ide.util.CanonicalPaths

/**
 * PLAN A (P3b, 2026-09-25): the per-file state store, keyed by CANONICAL PATH.
 *
 * Standing instruction: "Implement state store keyed by canonical path for
 * line-highlights, squiggles, and markers." Root cause it closes (G03,
 * MASTER-CONNECTIONS §2): one file reached under different raw path spellings
 * split identities — squiggles leaked across tabs (BUG-B patched the symptom by
 * clearing on tab switch; the store makes the ranges survive the switch keyed to
 * the RIGHT file), jump highlights were pane-local and died on tab switches,
 * bookmarks lived in a per-instance remember map.
 *
 * Key discipline: EVERY read and write goes through CanonicalPaths.canonicalKey —
 * never the raw path string. The map is a Compose SnapshotStateMap so any
 * @Composable reading stateFor(path) recomposes when that file's state changes.
 */
object PerFileStateStore {

    data class FileState(
        /** LSP squiggle ranges as of the last publish/shift for THIS file. */
        val squiggles: List<LintError> = emptyList(),
        /** 1-based jump highlight target; 0 = none. Set by onOpenFileAtLine / goToLine. */
        val lineHighlight: Int = 0,
        /** Gutter bookmark lines (0-based, matching the editor's convention). */
        val bookmarks: Set<Int> = emptySet(),
    )

    private val EMPTY = FileState()

    /** canonical path -> state. SnapshotStateMap: observable from composition. */
    val states = mutableStateMapOf<String, FileState>()

    private fun key(path: String): String = CanonicalPaths.canonicalKey(path)

    fun stateFor(path: String): FileState = states[key(path)] ?: EMPTY

    /**
     * TB06 (P4d): move a renamed file's persisted state (squiggles, jump
     * highlight, bookmarks) to its new canonical key — Explorer rename
     * previously rekeyed nothing, so the file's bookmarks and diagnostics
     * detached on rename.
     */
    fun rekey(oldPath: String, newPath: String) {
        val oldKey = key(oldPath)
        val newKey = key(newPath)
        if (oldKey == newKey) return
        val state = states.remove(oldKey) ?: return
        states[newKey] = state
    }

    /** Publish squiggle ranges for a file (LSP diagnostics handler + tab re-pull). */
    fun setSquiggles(path: String, squiggles: List<LintError>) {
        val k = key(path)
        states[k] = (states[k] ?: EMPTY).copy(squiggles = squiggles)
    }

    /** Set the pending jump highlight for a file (1-based; 0 clears). */
    fun setLineHighlight(path: String, line: Int) {
        val k = key(path)
        states[k] = (states[k] ?: EMPTY).copy(lineHighlight = line)
    }

    fun clearLineHighlight(path: String) = setLineHighlight(path, 0)

    /** Persist gutter bookmarks for a file. */
    fun setBookmarks(path: String, bookmarks: Set<Int>) {
        val k = key(path)
        states[k] = (states[k] ?: EMPTY).copy(bookmarks = bookmarks)
    }
}
