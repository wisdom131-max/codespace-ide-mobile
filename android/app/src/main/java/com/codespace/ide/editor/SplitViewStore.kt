package com.codespace.ide.editor

import androidx.compose.runtime.mutableStateListOf
import com.codespace.ide.domain.EditorTab

/**
 * SPLIT-VIEW STORE (2026-09-11, user-approved design):
 *
 * "Split" in this app = a LIVE-SYNCED SECOND VIEW of a file, presented as its own
 * entry in the editor tab strip (tab-based split - full-width editor regardless of
 * orientation, no on-screen second pane).
 *
 * WHY A SEPARATE STORE (vs. just opening the file twice as an EditorTab):
 * 1. TAB DEDUPE: every open path guards with `tabs.none { it.path == filePath }`
 *    and EditorTab.id == path - a second EditorTab for the same path is
 *    impossible by construction; the split view must live OUTSIDE that list.
 * 2. LSP OWNERSHIP: LSP documents are tracked per FILE PATH (lspOpenedFiles map,
 *    didOpen/didChange/didClose). The primary tab OWNS the LSP document. Split
 *    views share the SAME buffer (the primary tab's EditorTab.content), so edits
 *    from a split view flow through the primary tab's existing didChange path -
 *    no second didOpen, no duplicate registration, no divergent-buffer thrash.
 * 3. LIVE SYNC: because both views bind to one EditorTab, CodeEditor's
 *    external-content sync (ProgrammaticTextChange with cursor mapping) makes
 *    edits appear instantly in the peer view - VS Code's "one model, two views".
 *
 * SPLIT VIEW IDS: "split::<path>". Distinct from tab ids (= path) so the
 * editor area's key(activeId) remount gives each view its own independent
 * cursor/scroll/selection state, while resolveActiveTab() maps a split id back
 * to its primary EditorTab (the single content buffer).
 *
 * DEPENDENT LIFECYCLE (v1): a split view cannot outlive its primary tab -
 * closeEditorTabInternal cascades removal. Session persistence intentionally
 * NOT implemented for split views (v1; see changelog roadmap).
 */
object SplitViewStore {

    const val SPLIT_ID_PREFIX = "split::"

    data class SplitView(
        val id: String,
        val path: String,
    )

    /** All live split views. Reads are snapshot-reactive in composition. */
    val views = mutableStateListOf<SplitView>()

    fun idFor(path: String): String = SPLIT_ID_PREFIX + path

    fun hasFor(path: String): Boolean = views.any { it.path == path }

    /** Register (if absent) a split view for the path and return its id. */
    fun add(path: String): String {
        val id = idFor(path)
        if (!hasFor(path)) views.add(SplitView(id, path))
        return id
    }

    fun removeById(id: String) {
        views.removeAll { it.id == id }
    }

    fun removeForPath(path: String) {
        views.removeAll { it.path == path }
    }

    /** Toggle: create the split view (returns its id) or remove it (returns null). */
    fun toggleFor(path: String): String? {
        return if (hasFor(path)) {
            removeForPath(path)
            null
        } else {
            add(path)
        }
    }

    fun isSplitId(id: String?): Boolean = id != null && id.startsWith(SPLIT_ID_PREFIX)

    /** Path a split id refers to (null if not a split id). */
    fun pathOf(id: String?): String? =
        if (isSplitId(id)) id!!.removePrefix(SPLIT_ID_PREFIX) else null
}

/**
 * Resolve the ACTIVE content buffer. A split id ("split::path") maps to its
 * primary EditorTab - the single shared buffer both views edit. A normal tab id
 * resolves directly. Used by every `active` lookup so features (save, format,
 * breakpoints, LSP effects, AI hooks) keep working identically whether the
 * active view is the primary tab or its split view.
 */
internal fun resolveActiveTab(activeId: String?, tabs: List<EditorTab>): EditorTab? {
    if (activeId == null) return null
    val splitPath = SplitViewStore.pathOf(activeId)
    return if (splitPath != null) {
        tabs.firstOrNull { it.path == splitPath }
    } else {
        tabs.firstOrNull { it.id == activeId }
    }
}
