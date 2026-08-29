package com.codespace.ide.util

import android.content.Context
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
 * Historically, many components (LSP, FileIndexer, SourceControlPane,
 * PreviewPane, search-in-project, TODO scanner, test discovery, etc.)
 * constructed the project root as `File(context.filesDir, "projects/$projectId")`
 * — an app-private directory that is typically EMPTY for projects created
 * via the wizard.  This caused the LSP to see zero files ("URI is excluded"),
 * git operations to operate on the wrong directory, file search to find
 * nothing, and more.
 *
 * ## Resolution order
 *
 * 1. **User-overridden workspace path** — if the user explicitly changed
 *    the workspace root in the Explorer pane, that choice is saved in
 *    `workspace_prefs` and takes priority.
 * 2. **pathOrUrl from project metadata** — the canonical location chosen
 *    at project-creation time, read from the "projects" SharedPreferences.
 * 3. **Legacy fallback** — `filesDir/projects/$projectId`, for projects
 *    created by the "New Project Window" command (which stores files in
 *    app-private storage and sets pathOrUrl to that same path).
 *
 * ## Usage
 *
 * ```kotlin
 * val projectRoot = ProjectPathResolver.resolveProjectRoot(context, projectId)
 * // projectRoot is a String — the absolute host path to the real project dir
 * ```
 *
 * For the guest (proot) path, wrap with:
 * ```kotlin
 * val guestPath = ProotInstaller.hostToGuestPath(context, projectRoot)
 * ```
 */
object ProjectPathResolver {

    private const val PREFS_WORKSPACE = "workspace_prefs"
    private const val KEY_WORKSPACE = "workspace_path"
    private const val PREFS_PROJECTS = "projects"

    /**
     * Returns the absolute host-path of the real project root directory.
     *
     * Resolution order:
     *   1. workspace_prefs (user override from Explorer pane)
     *   2. pathOrUrl from project metadata
     *   3. filesDir/projects/$projectId (legacy fallback)
     *
     * @param context  Android context
     * @param projectId  the project's unique ID (timestamp string or UUID)
     * @return absolute path String, or null if projectId is blank
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
            if (pathOrUrl != null && File(pathOrUrl).exists()) return pathOrUrl

            // 3. Legacy fallback: filesDir/projects/$projectId
            return File(context.filesDir, "projects/$projectId").absolutePath
        } catch (e: Exception) {
            // Should never happen, but guard against filesystem/prefs errors
            android.util.Log.e("ProjectPathResolver", "resolveProjectRoot threw for projectId=$projectId", e)
            return null
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
