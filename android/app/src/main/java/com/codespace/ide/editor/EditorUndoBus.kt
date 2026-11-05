package com.codespace.ide.editor

/**
 * 2026-11-05 (device finding #16): the Edit menu's Undo and Redo were placeholders.
 *
 * They toasted "Undo via editor toolbar" and did nothing. The working stack lived inside
 * [CodeEditor]'s key handler, which owns the text state and the programmatic-change door, so a
 * menu action in the shell had no way to reach it.
 *
 * In VS Code undo belongs to the MODEL, not to a view: `src/vs/editor/common/model/textModel.ts`
 * :1604-1617 - `undo()` is `_undoRedoService.undo(this.uri)`, `canUndo()` is per URI, so the Edit
 * menu command and Ctrl+Z hit one stack. This app already keeps its stack per file
 * ([SharedFileUndo] by canonical path); what was missing is only a way for a caller with no editor
 * reference to ask the open editor to run it. Same shape as [EditorInsertBus], which solved the
 * identical problem for snippet insertion.
 *
 * IMPORTANT, and why this bus is registered by the editor TAB HOST rather than by CodeEditor:
 * `CodeEditor`'s composable method was already at the JVM 64KB method limit - adding the shared
 * undo lambda plus its registration to that body produced `Method too large:
 * CodeEditorKt.CodeEditor(...)` and broke every build (CI 3552). The undo work therefore lives in
 * [EditorUndoHost] and is driven from the pane that owns the tab content.
 */
internal object EditorUndoBus {

    @Volatile private var sink: ((Boolean) -> Boolean)? = null

    /** [fn] takes `redo` and returns true when the stack actually moved. */
    fun register(fn: (Boolean) -> Boolean) { sink = fn }

    /** Only unregister the instance that registered, so a split view cannot clear the other. */
    fun unregister(fn: (Boolean) -> Boolean) {
        if (sink === fn) sink = null
    }

    /** True when an open editor can serve the request at all. */
    fun canServe(): Boolean = sink != null

    /** Ask the open editor to undo (redo = false) or redo. False means nothing happened. */
    fun request(redo: Boolean): Boolean = sink?.invoke(redo) ?: false

    /** Test seam: forget any registered editor. */
    internal fun resetForTest() { sink = null }
}
