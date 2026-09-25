package com.codespace.ide.editor

/**
 * R6-PENDING-EDITS: global registry of open editor tab buffers.
 *
 * PendingChangesStore stages chat edits against the OPEN BUFFER when the file
 * is open (R6_PREPLAN v2 decision #3, buffer-centric) and against disk
 * otherwise. EditorPane publishes here from its existing workspace-memory
 * effect (fires on every tab mutation) — a String-reference map, no copies.
 *
 * Deliberately tiny like EditorSelectionStore: latest buffer per open path,
 * no history, removed on tab close.
 */
object EditorBufferStore {

    @Volatile
    private var buffers: Map<String, String> = emptyMap()

    /**
     * G03 (P3b, PLAN A): keys are CANONICAL paths — one physical file reached as
     * raw string, relative, or host/guest spelling used to split buffer identity.
     * PendingChangesStore stages against the OPEN BUFFER; a mismatched spelling
     * meant the staged edit silently fell back to disk content (C-CH09).
     */
    private fun key(path: String): String = com.codespace.ide.util.CanonicalPaths.canonicalKey(path)

    /** Publish the full open-tab set (path -> current buffer content). */
    fun sync(paths: List<String>, contents: List<String>) {
        val map = HashMap<String, String>(paths.size)
        paths.forEachIndexed { i, p ->
            if (i < contents.size) map[key(p)] = contents[i]
        }
        buffers = map
    }

    /** Current buffer content of an open tab, or null when the file is not open. */
    fun contentOf(path: String): String? = buffers[key(path)]

    fun isOpen(path: String): Boolean = buffers.containsKey(key(path))

    fun clear() { buffers = emptyMap() }
}
