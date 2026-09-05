package com.codespace.ide.util

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Single source of truth for the persisted multi-root workspace roots —
 * REACTIVE, matching VS Code's actual mechanism (verified from source
 * 2026-09-05: explorerModel.ts:43-47 derives ExplorerModel._roots from
 * contextService.getWorkspace().folders and listens to the generic
 * onDidChangeWorkspaceFolders event; explorerService.ts:132 then calls
 * view.setTreeInput(). No manual refresh/reveal command exists anywhere in
 * the Explorer or the remotehub extension — the tree is reactively bound
 * to the workspace folder list).
 *
 * Compose equivalent: the roots live in ONE observable state holder here.
 * ExplorerPane OBSERVES via observeRoots(); every mutation (the Explorer's
 * own add/remove UI OR an external GitHub-clone append from the Source
 * Control pane) goes through addRoot/removeRoot — mutating the snapshot
 * state recomposes any observer automatically. No refresh keys, no manual
 * reload effects.
 *
 * Prefs format is byte-identical to what ExplorerPane.kt used before this
 * store existed (workspace_prefs / "workspace_roots_<projectId>" / "|||"
 * separator) so existing saved roots survive.
 */
object WorkspaceRootsStore {

    private const val PREFS_WORKSPACE = "workspace_prefs"
    private const val KEY_WORKSPACE_ROOTS = "workspace_roots"
    private const val SEPARATOR = "|||"

    // Reactive cache, keyed by projectId. Compose snapshot state: any
    // composable that reads observeRoots() recomposes when this map changes.
    private val rootsByProject = mutableStateOf<Map<String, List<String>>>(emptyMap())

    /**
     * Observe the roots for a project. Read-through: loads from prefs on
     * first access per project, then serves the in-memory reactive value.
     * MUST be called from composition to subscribe to changes.
     */
    fun observeRoots(context: Context, projectId: String): List<String> {
        val cached = rootsByProject.value[projectId]
        if (cached != null) return cached
        val loaded = loadRoots(context, projectId)
        rootsByProject.value = rootsByProject.value.toMutableMap().apply { put(projectId, loaded) }
        return loaded
    }

    /**
     * Appends a root if not already present. Returns true when the root was
     * newly added, false when it already existed (no write performed).
     * Persists to prefs and notifies observers (snapshot state write).
     */
    fun addRoot(context: Context, projectId: String, hostPath: String): Boolean {
        if (hostPath.isBlank()) return false
        val roots = observeRoots(context, projectId)
        val normalized = hostPath.trimEnd('/')
        if (roots.any { it.trimEnd('/') == normalized }) return false
        val updated = roots + hostPath
        saveRoots(context, projectId, updated)
        rootsByProject.value = rootsByProject.value.toMutableMap().apply { put(projectId, updated) }
        return true
    }

    /** Removes a root if present. Persists and notifies observers. */
    fun removeRoot(context: Context, projectId: String, hostPath: String) {
        val roots = observeRoots(context, projectId)
        if (roots.none { it == hostPath }) return
        val updated = roots - hostPath
        saveRoots(context, projectId, updated)
        rootsByProject.value = rootsByProject.value.toMutableMap().apply { put(projectId, updated) }
    }

    private fun loadRoots(context: Context, projectId: String): List<String> {
        val raw = context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
            .getString("${KEY_WORKSPACE_ROOTS}_$projectId", null) ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    private fun saveRoots(context: Context, projectId: String, roots: List<String>) {
        context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
            .edit().putString("${KEY_WORKSPACE_ROOTS}_$projectId", roots.joinToString(SEPARATOR)).apply()
    }
}
