package com.codespace.ide.editor

import androidx.compose.runtime.mutableStateMapOf
import com.codespace.ide.util.CanonicalPaths

/**
 * PLAN A canonical file identity for diagnostics and bookmarks.
 * Round 1 item 3: rendering reads this model's diagnostics directly. Transient
 * reveals instead live in FileOwnedJumpState as resource-addressed requests;
 * model changes clear them (VS Code RangeHighlightDecorations pattern).
 * No saved positive line number is replayed into a different editor instance.
 * EVERY read/write uses CanonicalPaths.canonicalKey, never basename matching.
 */
object PerFileStateStore {

    data class FileState(
        /** LSP squiggle ranges as of the last publish/shift for THIS file. */
        val squiggles: List<LintError> = emptyList(),
        /** Gutter bookmark lines (0-based, matching the editor's convention). */
        val bookmarks: Set<Int> = emptySet(),
    )

    private val EMPTY = FileState()

    /** canonical path -> state. SnapshotStateMap: observable from composition. */
    val states = mutableStateMapOf<String, FileState>()

    private fun key(path: String): String = CanonicalPaths.canonicalKey(path)

    fun stateFor(path: String): FileState = states[key(path)] ?: EMPTY

    /**
     * TB06 (P4d): move a renamed file's persisted state (squiggles and bookmarks) to its new canonical key — Explorer rename
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

    /** Persist gutter bookmarks for a file. */
    fun setBookmarks(path: String, bookmarks: Set<Int>) {
        val k = key(path)
        states[k] = (states[k] ?: EMPTY).copy(bookmarks = bookmarks)
    }
}
