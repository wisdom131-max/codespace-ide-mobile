package com.codespace.ide.editor

/**
 * BATCH 7 — the seam that lets a chat code block reach the ACTIVE editor.
 *
 * There was no such path: the editor's TextFieldValue lives inside the CodeEditor composable
 * and nothing outside could write to it. Rather than invent a second text model, the editor
 * registers its own insert lambda here while it is on screen (same pattern the panel
 * already uses for PendingChangesStore and AgentWriteJournal), and the action returns a
 * boolean so the caller can tell "inserted" from "no editor open".
 *
 * INSERTION IS AT THE CARET AND REPLACES ONLY THE SELECTION: that is the one behaviour which
 * cannot destroy work the user did not select. Nothing here writes to disk - the buffer is
 * still dirty, still undoable, still the user's to save.
 *
 * Null function = no editor open (Explorer focused, no tab, binary file, or a project with
 * no text editor). Chat then says so instead of pretending.
 */
internal object EditorInsertBus {

    @Volatile private var sink: ((String) -> Boolean)? = null

    fun register(fn: (String) -> Boolean) { sink = fn }

    /** Only unregister the instance that registered, so a split view cannot clear the other. */
    fun unregister(fn: (String) -> Boolean) {
        if (sink === fn) sink = null
    }

    fun canInsert(): Boolean = sink != null

    /** Returns false when there is no editor, or the editor refused/failed. */
    fun insert(code: String): Boolean {
        if (code.isEmpty()) return false
        val fn = sink ?: return false
        return try { fn(code) } catch (_: Throwable) { false }
    }

    /** Test seam. */
    fun clear() { sink = null }
}
