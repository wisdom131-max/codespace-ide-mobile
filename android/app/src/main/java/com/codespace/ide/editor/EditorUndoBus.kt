package com.codespace.ide.editor

/**
 * 2026-11-04 (device finding #16): the Edit menu's Undo and Redo were placeholders.
 *
 * They toasted "Undo via editor toolbar" and did nothing. The working stack lived inside
 * [CodeEditor]'s key handler, which owned the text state, cursors and the programmatic-change
 * door - a menu action in the shell has none of those.
 *
 * VS Code does not have this problem because undo belongs to the MODEL, not to a view:
 * `src/vs/editor/common/model/textModel.ts:1604-1617` - `undo()` is
 * `_undoRedoService.undo(this.uri)`, `canUndo()` is per URI, and the Edit menu command runs the
 * same call as Ctrl+Z. This app already stores its stack per file ([SharedFileUndo], keyed by
 * canonical path), so the only missing piece is a way for a caller with no editor reference to
 * ask the mounted editor to run that stack. That is this bus - the same shape as
 * [EditorInsertBus], which solved the identical problem for snippet/text insertion.
 *
 * Deliberately NOT a silent success: [request] answers false when no editor is mounted or the
 * stack is empty, so the caller can say so instead of claiming work happened.
 */
internal object EditorUndoBus {

    @Volatile private var sink: ((Boolean) -> Boolean)? = null

    /** [fn] takes `redo` and returns true when the stack actually moved. */
    fun register(fn: (Boolean) -> Boolean) { sink = fn }

    /** Only unregister the instance that registered, so a split view cannot clear the other. */
    fun unregister(fn: (Boolean) -> Boolean) {
        if (sink === fn) sink = null
    }

    /** True when a mounted editor can serve the request at all. */
    fun canServe(): Boolean = sink != null

    /** Ask the mounted editor to undo (redo = false) or redo. False means nothing happened. */
    fun request(redo: Boolean): Boolean = sink?.invoke(redo) ?: false

    /** Test seam: forget any registered editor. */
    internal fun resetForTest() { sink = null }
}
