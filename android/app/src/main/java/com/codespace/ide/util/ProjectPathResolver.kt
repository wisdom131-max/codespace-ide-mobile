package com.codespace.ide.util

import android.content.Context
import com.codespace.ide.diagnostics.AppOutputLog
import org.json.JSONArray
import java.io.File

/**
 * Central resolver for the real on-disk project root directory.
 *
 * ## Background
 *
 * Projects created via the Project Wizard are scaffolded at a user-chosen
 * location on shared/external storage (e.g. /sdcard/My codespace app/testing/).
 * That path is stored in the project's `pathOrUrl` field inside the
 * "projects" SharedPreferences "list" JSON.
 *
 * ## Resolution order
 *
 * 1. **User-overridden workspace path** - if the user explicitly changed
 *    the workspace root in the Explorer pane, that choice is saved in
 *    `workspace_prefs` and takes priority.
 * 2. **pathOrUrl from project metadata** - the canonical location chosen
 *    at project-creation time, read from the "projects" SharedPreferences.
 * 3. **Legacy fallback** - `filesDir/projects/$projectId`, for projects
 *    created by the "New Project Window" command (which stores files in
 *    app-private storage and sets pathOrUrl to that same path).
 *
 * ## Deleted-folder handling
 *
 * When `pathOrUrl` is non-blank but the folder doesn't exist on disk (deleted
 * externally, moved, etc.), `resolveProjectRoot` returns null instead of
 * silently falling through to the legacy app-private path. This prevents
 * downstream components from operating on the wrong directory.
 *
 * Use `isProjectFolderMissing()` to distinguish "folder was set but deleted"
 * from "project has no folder configured" - only the former should trigger
 * the "folder can't be found" banner in ProjectShellScreen.
 */
object ProjectPathResolver {

    private const val TAG = "ProjectPathResolver"
    private const val PREFS_WORKSPACE = "workspace_prefs"
    private const val KEY_WORKSPACE = "workspace_path"
    private const val KEY_WORKSPACE_ROOTS = "workspace_roots"
    private const val PREFS_PROJECTS = "projects"

    /**
     * Returns the absolute host-path of the real project root directory.
     *
     * Resolution order:
     *   1. workspace_prefs (user override from Explorer pane)
     *   2. pathOrUrl from project metadata
     *   3. filesDir/projects/$projectId (legacy fallback - only when
     *      pathOrUrl key is missing, i.e. old project with no pathOrUrl)
     *
     * **Deleted folder handling:** When pathOrUrl is non-blank but the
     * folder doesn't exist, returns null instead of falling through to
     * the legacy path. This ensures downstream components see null
     * (which they handle gracefully) rather than silently operating
     * on the wrong directory.
     *
     * @param context  Android context
     * @param projectId  the project's unique ID (timestamp string or UUID)
     * @return absolute path String, or null if:
     *   - projectId is blank
     *   - pathOrUrl was explicitly blank (Empty Project)
     *   - pathOrUrl was non-blank but folder doesn't exist (deleted/moved)
     *   - no project metadata found
     */
    fun resolveProjectRoot(context: Context, projectId: String?): String? {
        if (projectId.isNullOrBlank()) return null

        try {
            // 1. User-overridden workspace path (Explorer pane "Open Folder")
            val wsPath = context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
                .getString("${KEY_WORKSPACE}_$projectId", null)
            if (wsPath != null && File(wsPath).exists()) return wsPath

            // 2. pathOrUrl from project metadata
            val pathOrUrl = readPathOrUrl(context, projectId)
            if (pathOrUrl != null && pathOrUrl.isNotBlank()) {
                if (File(pathOrUrl).exists()) return pathOrUrl
                // pathOrUrl was set to a real path but folder is gone.
                // Return null instead of falling through to legacy path.
                AppOutputLog.log("[PATH] Project folder missing: '$pathOrUrl' (projectId=$projectId) - was it moved or deleted?", "lsp")
                return null
            }
            // If pathOrUrl is explicitly blank (e.g. Empty Project), return null.
            if (pathOrUrl != null && pathOrUrl.isBlank()) return null

            // 3. Legacy fallback: filesDir/projects/$projectId
            // Only reached when pathOrUrl key is missing entirely (old project).
            return File(context.filesDir, "projects/$projectId").absolutePath
        } catch (e: Exception) {
            android.util.Log.e(TAG, "resolveProjectRoot threw for projectId=$projectId", e)
            return null
        }
    }

    /**
     * MULTI-ROOT: Returns ALL workspace roots configured for a project
     * (the pipe-delimited `workspace_roots_$projectId` list written by the
     * Explorer's multi-root UI), as host paths, in stored order.
     *
     * Only folders that currently exist on disk are returned. The active
     * root (workspace_path_$projectId) is NOT forced first — callers that
     * need VS Code convention (active root = first folder = rootUri) should
     * order it themselves.
     *
     * Returns an empty list when no multi-root list is stored (classic
     * single-root project) — callers fall back to the single active root.
     */
    fun getAllWorkspaceRoots(context: Context, projectId: String?): List<String> {
        if (projectId.isNullOrBlank()) return emptyList()
        return try {
            val raw = context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
                .getString(KEY_WORKSPACE_ROOTS + "_" + projectId, null) ?: return emptyList()
            raw.split("|||")
                .map { it.trim() }
                .filter { it.isNotBlank() && File(it).exists() }
                .distinct()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getAllWorkspaceRoots threw for projectId=$projectId", e)
            emptyList()
        }
    }

    /**
     * Returns the project root as a [File], or null if projectId is blank
     * or the resolved path does not exist on disk.
     *
     * Use this when the caller needs to check `.exists()` or list files.
     */
    fun resolveProjectRootFile(context: Context, projectId: String?): File? {
        val path = resolveProjectRoot(context, projectId) ?: return null
        val dir = File(path)
        return if (dir.exists()) dir else null
    }

    /**
     * Checks whether a project's folder was configured (pathOrUrl was set to
     * a non-blank path) but the folder no longer exists on disk.
     *
     * This distinguishes "folder was deleted/moved" from:
     *   - "project has no folder configured" (pathOrUrl is blank -> false)
     *   - "project not found in metadata" (-> false)
     *   - "project has a valid folder" (-> false)
     *
     * When true, ProjectShellScreen should show a "folder can't be found"
     * banner instead of letting individual components fail in confusing ways.
     *
     * @return true if pathOrUrl was non-blank but File(pathOrUrl) doesn't exist
     */
    fun isProjectFolderMissing(context: Context, projectId: String?): Boolean {
        if (projectId.isNullOrBlank()) return false

        try {
            // Check workspace_prefs first - if user overrode to a missing path,
            // that's also "folder missing"
            val wsPath = context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
                .getString("${KEY_WORKSPACE}_$projectId", null)
            if (wsPath != null && !File(wsPath).exists()) return true

            val pathOrUrl = readPathOrUrl(context, projectId) ?: return false
            // Only report "missing" if pathOrUrl was explicitly set to a real path
            // (non-blank) but the folder doesn't exist. Blank pathOrUrl = Empty Project
            // by design, not "missing."
            return pathOrUrl.isNotBlank() && !File(pathOrUrl).exists()
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Reads the `pathOrUrl` field for a project from SharedPreferences.
     */
    private fun readPathOrUrl(context: Context, projectId: String): String? {
        return try {
            val str = context.getSharedPreferences(PREFS_PROJECTS, Context.MODE_PRIVATE)
                .getString("list", null) ?: return null
            val arr = JSONArray(str)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id") == projectId) {
                    return obj.optString("pathOrUrl", null)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
