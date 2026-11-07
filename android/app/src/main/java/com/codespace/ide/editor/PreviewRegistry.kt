package com.codespace.ide.editor

/**
 * Which file paths are currently open as a READ-ONLY PREVIEW (a partial slice of the real file).
 *
 * Why a registry rather than a flag: the consumers that must refuse a preview are not all UI.
 * The debugger (UniversalDebugManager.toggleBreakpoint) and the LSP manager (didChange, didSave)
 * hold no editor tabs and must not learn to. They ask this instead.
 *
 * A path is in the set only while a preview tab for it exists. EditorPane recomputes the set from
 * its tab list on every change, so closing, replacing or promoting a tab clears it.
 */
object PreviewRegistry {

    private val paths = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Replace the whole set: called with the preview paths of the current tab list. */
    fun replaceWith(newPaths: Set<String>) {
        synchronized(paths) {
            paths.clear()
            paths.addAll(newPaths)
        }
    }

    fun isPreview(path: String): Boolean = paths.contains(path)

    fun isEmpty(): Boolean = paths.isEmpty()
}
