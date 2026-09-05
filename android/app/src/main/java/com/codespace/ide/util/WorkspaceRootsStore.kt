package com.codespace.ide.util

import android.content.Context

/**
 * Single source of truth for the persisted multi-root workspace roots.
 *
 * IMPORTANT: the prefs file name and key format must stay byte-identical to
 * what ExplorerPane.kt used before this store existed (workspace_prefs /
 * "workspace_roots_<projectId>" / "|||" separator) so existing saved roots
 * survive.
 *
 * [REPO-OPEN] Part 2 item 4: used by the shell when a repo cloned from the
 * Source Control pane is appended as a workspace root from OUTSIDE the
 * ExplorerPane — the Explorer reloads these persisted roots via its
 * rootsRefreshKey parameter.
 */
object WorkspaceRootsStore {

    private const val PREFS_WORKSPACE = "workspace_prefs"
    private const val KEY_WORKSPACE_ROOTS = "workspace_roots"
    private const val SEPARATOR = "|||"

    fun loadRoots(context: Context, projectId: String): List<String> {
        val raw = context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
            .getString("${KEY_WORKSPACE_ROOTS}_$projectId", null) ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    fun saveRoots(context: Context, projectId: String, roots: List<String>) {
        context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
            .edit().putString("${KEY_WORKSPACE_ROOTS}_$projectId", roots.joinToString(SEPARATOR)).apply()
    }

    /**
     * Appends a root if not already present. Returns true when the root was
     * newly added, false when it already existed (no write performed).
     */
    fun appendRoot(context: Context, projectId: String, hostPath: String): Boolean {
        if (hostPath.isBlank()) return false
        val roots = loadRoots(context, projectId)
        val normalized = hostPath.trimEnd('/')
        if (roots.any { it.trimEnd('/') == normalized }) return false
        saveRoots(context, projectId, roots + hostPath)
        return true
    }
}
