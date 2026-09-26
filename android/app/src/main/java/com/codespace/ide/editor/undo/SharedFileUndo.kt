package com.codespace.ide.editor.undo

import com.codespace.ide.util.CanonicalPaths
import java.util.concurrent.ConcurrentHashMap

/**
 * G06 (P4b): per-FILE undo history, shared across split views.
 *
 * SnapshotUndoManager was `remember {}`-scoped per mounted CodeEditor — each split
 * pane of the SAME file carried its own undo stack, so an edit in view A pushed
 * snapshots only into A's history while view B (sharing the tab content) could
 * not undo A's changes, and undoing in B replayed B's stale local view of the
 * text. VS Code keeps undo per MODEL (shared by all editors of that model);
 * this store gives the same semantics keyed by canonical path: every CodeEditor
 * mounted for a file resolves the SAME SnapshotUndoManager.
 *
 * Untitled/buffer-only editors (no path) keep a private per-view stack — there is
 * no shared identity to key on. Cross-session persistence is out of scope here.
 */
object SharedFileUndo {
    private val stacks = ConcurrentHashMap<String, SnapshotUndoManager>()

    /** The shared undo manager for a file path (canonical key), or a fresh
     *  view-local one when the path is blank. */
    fun forFile(path: String?): SnapshotUndoManager {
        if (path.isNullOrBlank() || !path.startsWith("/")) return SnapshotUndoManager()
        val key = CanonicalPaths.canonicalKey(path)
        return stacks.getOrPut(key) { SnapshotUndoManager() }
    }
}
