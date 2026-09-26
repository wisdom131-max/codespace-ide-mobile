package com.codespace.ide.editor

import androidx.compose.runtime.mutableStateListOf
import com.codespace.ide.domain.EditorTab
import com.codespace.ide.util.CanonicalPaths

/**
 * SPLIT-VIEW STORE (2026-09-11; PAD-2 multi-view 2026-09-13, user-approved):
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
 * PAD-2 MULTI-VIEW: up to [MAX_VIEWS_PER_FILE] split views per file (cap per
 * user decision D3, 2026-09-13). View ids:
 *   1st view: "split::<path>"            (legacy single-view id, unchanged)
 *   2nd..4th:  "split::<path>::2" .. "::4"
 * [pathOf] strips a trailing "::<n>" suffix when resolving a view id back to its
 * file path. The legacy first-view id keeps every existing idFor()/hasFor()
 * call site working unchanged.
 *
 * DEPENDENT LIFECYCLE: a split view cannot outlive its primary tab -
 * closeEditorTabInternal cascades removal (removeForPath drops ALL views of a
 * path). Each individual view closes via removeById from its strip entry.
 * Session persistence ships in PERSIST-A.
 */
object SplitViewStore {

    const val SPLIT_ID_PREFIX = "split::"

    /** PAD-2 (D3): max simultaneous split views per file. */
    const val MAX_VIEWS_PER_FILE = 4

    private val SUFFIX = Regex("::\\d+$")

    data class SplitView(
        val id: String,
        val path: String,
    )

    /** All live split views. Reads are snapshot-reactive in composition. */
    val views = mutableStateListOf<SplitView>()

    /** Legacy first-view id for a path (also what idFor() always meant). */
    fun idFor(path: String): String = SPLIT_ID_PREFIX + path

    // TB08 (P4d): view<->path matching is canonical IDENTITY, not raw string
    // equality — one physical file reached as host/guest/relative spellings used
    // to count as different files (G03's tab-level manifestation).
    private fun matchesPath(candidate: String, path: String): Boolean =
        CanonicalPaths.sameFileIdentity(candidate, path)

    fun hasFor(path: String): Boolean = views.any { matchesPath(it.path, path) }

    /** Id of the MOST RECENTLY created view for a path (what auto-focus targets). */
    fun latestIdFor(path: String): String? = views.lastOrNull { matchesPath(it.path, path) }?.id

    /** 1-based position of a view among its siblings (plain id = 1, "::N" = N). */
    fun viewNumber(id: String): Int {
        val rest = id.removePrefix(SPLIT_ID_PREFIX)
        val m = SUFFIX.find(rest) ?: return 1
        return m.value.removePrefix("::").toIntOrNull() ?: 1
    }

    /**
     * PAD-2: create a split view for the path — the first one if none exists,
     * otherwise one more, up to [MAX_VIEWS_PER_FILE]. Returns the NEW view id,
     * or null when the cap is already reached (callers surface feedback).
     * (Replaces v1 toggleFor: closing is done per-view from the strip X or by
     * closing the primary tab, which cascades.)
     */
    fun add(path: String): String? {
        val existing = views.filter { matchesPath(it.path, path) }
        return when {
            existing.isEmpty() -> {
                val id = idFor(path)
                views.add(SplitView(id, path))
                id
            }
            existing.size >= MAX_VIEWS_PER_FILE -> null
            else -> {
                // TB04 (P4d): the old `existing.size + 1` formula RECREATED a live
                // sibling's number after a removal (create 1..4, remove #2, add ->
                // "::4" twice — one id, two strip views). Allocate the LOWEST UNUSED
                // number instead, so every live view id is unique by construction.
                val used = existing.map { viewNumber(it.id) }.toSet()
                var n = 2
                while (n in used) n++
                if (n > MAX_VIEWS_PER_FILE) return null
                val id = idFor(path) + "::" + n
                views.add(SplitView(id, path))
                id
            }
        }
    }

    /**
     * PERSIST-A: disk-restore — recreate the saved views. Only ids whose file is
     * currently open survive; ids keep their original form (legacy first-view id
     * or ::N sibling) so viewNumber() labels stay stable across restarts.
     */
    fun restore(viewIds: List<String>, openPaths: Set<String>) {
        // TB05 (P4d): an EMPTY saved list is a REAL project state ("no splits"),
        // not "leave whatever is live" — the store is process-global, so views
        // from the PREVIOUS project used to survive into a project that saved
        // no splits (the old caller skipped restore entirely on an empty list,
        // and this prune never ran).
        if (viewIds.isEmpty()) {
            views.clear()
            return
        }
        // TB08 (P4d): prune against open tabs by IDENTITY, not raw membership —
        // a view whose path spelling differs from the tab's still resolves.
        views.removeAll { v -> openPaths.none { CanonicalPaths.sameFileIdentity(v.path, it) } }
        viewIds.forEach { id ->
            val p = pathOf(id) ?: return@forEach
            val openMatch = openPaths.firstOrNull { CanonicalPaths.sameFileIdentity(p, it) } ?: return@forEach
            if (views.none { it.id == id }) {
                views.add(SplitView(id, openMatch))
            }
        }
    }

    fun removeById(id: String) {
        views.removeAll { it.id == id }
    }

    fun removeForPath(path: String) {
        views.removeAll { matchesPath(it.path, path) }
    }

    /**
     * TB06 (P4d): rekey every view of a renamed file — the id EMBEDS the path
     * ("split::<path>[::N]"), so an Explorer rename must re-derive both the id
     * and the path or the split view dangles against the old, deleted path.
     * Returns oldId -> newId so callers can fix an active split id.
     */
    fun rekeyPath(oldPath: String, newPath: String): Map<String, String> {
        val remap = mutableMapOf<String, String>()
        val updated = views.mapNotNull { v ->
            if (!matchesPath(v.path, oldPath)) return@mapNotNull null
            val suffix = if (viewNumber(v.id) > 1) "::" + viewNumber(v.id) else ""
            val newId = idFor(newPath) + suffix
            remap[v.id] = newId
            v.copy(id = newId, path = newPath)
        }
        if (updated.isNotEmpty()) {
            views.removeAll { it.id in remap.keys }
            views.addAll(updated)
        }
        return remap
    }

    fun isSplitId(id: String?): Boolean = id != null && id.startsWith(SPLIT_ID_PREFIX)

    /** Path a split id refers to (null if not a split id). Strips "::N" suffixes. */
    fun pathOf(id: String?): String? {
        if (!isSplitId(id)) return null
        val rest = id!!.removePrefix(SPLIT_ID_PREFIX)
        val m = SUFFIX.find(rest) ?: return rest
        return rest.removeSuffix(m.value)
    }
}

/**
 * Resolve the ACTIVE content buffer. Any split id ("split::path" or
 * "split::path::N") maps to its primary EditorTab - the single shared buffer all
 * views of that file edit. A normal tab id resolves directly. Used by every
 * `active` lookup so features (save, format, breakpoints, LSP effects, AI hooks)
 * keep working identically whether the active view is the primary tab or one of
 * its split views.
 */
internal fun resolveActiveTab(activeId: String?, tabs: List<EditorTab>): EditorTab? {
    if (activeId == null) return null
    val splitPath = SplitViewStore.pathOf(activeId)
    return if (splitPath != null) {
        // TB08 (P4d): canonical identity, not raw equality — a guest/relative
        // split id still resolves to its primary tab.
        tabs.firstOrNull { CanonicalPaths.sameFileIdentity(it.path, splitPath) }
    } else {
        tabs.firstOrNull { it.id == activeId }
    }
}
