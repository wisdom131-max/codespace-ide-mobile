package com.codespace.ide.editor

/**
 * Which file paths are currently open as a READ-ONLY PREVIEW, and what slice each one holds.
 *
 * Why a registry rather than a flag: the consumers that must refuse a preview are not all UI.
 * The debugger (UniversalDebugManager.toggleBreakpoint), the LSP manager (didChange, didSave) and
 * the disk writers hold no editor tabs and must not learn to. They ask this instead.
 *
 * The slice content is kept, not just the path, because the last-line write guard has to answer a
 * harder question than "is this path a preview": the SAME path may legitimately be written by the
 * agent with the real, whole content read from disk. Only a write whose content IS the slice is a
 * data-loss event, so the guard compares content, not membership.
 *
 * A path is in the set only while a preview tab for it exists. EditorPane recomputes the set from
 * its tab list on every change, so closing, replacing or promoting a tab clears it.
 */
object PreviewRegistry {

    private val slices = java.util.Collections.synchronizedMap(HashMap<String, String>())

    /** Replace the whole set: called with path -> the slice currently shown in that preview tab. */
    fun replaceWith(newSlices: Map<String, String>) {
        synchronized(slices) {
            slices.clear()
            slices.putAll(newSlices)
        }
    }

    fun isPreview(path: String): Boolean = slices.containsKey(path)

    /** The slice a preview tab holds, or null when the path is not a preview. */
    fun sliceOf(path: String): String? = slices[path]

    /**
     * True when [content] is exactly the slice this preview tab is showing. That is the write the
     * guard must refuse: persisting it would replace the real file with the part that fit.
     */
    fun isSliceOf(path: String, content: String): Boolean {
        val slice = slices[path] ?: return false
        return slice.isNotEmpty() && slice == content
    }

    fun isEmpty(): Boolean = slices.isEmpty()

    fun clear() = replaceWith(emptyMap())
}
