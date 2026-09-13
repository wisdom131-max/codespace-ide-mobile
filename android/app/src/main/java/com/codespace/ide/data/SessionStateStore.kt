package com.codespace.ide.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Workspace Memory System — per-project persistent IDE state.
 *
 * Saves and restores:
 *  - Open file paths (tabs)
 *  - Active file path
 *  - Pinned tab paths
 *  - Split-editor file path
 *  - Cursor offset per file
 *  - Scroll position per file (first visible line index)
 *  - Active side panel
 *  - Active bottom tab
 *  - Bottom panel visibility
 *  - Editor font size
 *  - Terminal working directory
 *  - Last N terminal commands (per tab, capped at 50)
 *
 * Safety:
 *  - All decode paths are wrapped in try/catch → returns null on corrupt data.
 *  - Callers must handle null (= start fresh).
 *  - Per-project isolation: keyed by projectId.
 *  - Restoration can be disabled via [workspaceRestoreEnabled].
 */
class SessionStateStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("session_state", Context.MODE_PRIVATE)

    // ── Last opened project ───────────────────────────────────────────────

    fun saveProjectId(projectId: String) {
        prefs.edit { putString(KEY_PROJECT_ID, projectId) }
    }

    fun lastProjectId(): String? = prefs.getString(KEY_PROJECT_ID, null)

    // ── Workspace restore toggle ──────────────────────────────────────────

    var workspaceRestoreEnabled: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_ENABLED, true)
        set(v) = prefs.edit { putBoolean(KEY_RESTORE_ENABLED, v) }

    // ── Shell / workspace state ──────────────────────────────────────────

    fun saveShellState(projectId: String, state: ShellState) {
        if (!workspaceRestoreEnabled) return
        prefs.edit { putString(shellKey(projectId), encodeShellState(state)) }
    }

    fun loadShellState(projectId: String): ShellState? {
        if (!workspaceRestoreEnabled) return null
        val raw = prefs.getString(shellKey(projectId), null) ?: return null
        return decodeShellState(raw)
    }

    /** Clear workspace memory for a specific project. */
    fun clearProjectState(projectId: String) {
        prefs.edit {
            remove(shellKey(projectId))
            remove(cursorKey(projectId))
            remove(scrollKey(projectId))
            remove(terminalKey(projectId))
            remove(splitKey(projectId))
            remove(lockKey(projectId))
            remove(foldKey(projectId))
            remove(blameKey(projectId))
        }
    }

    /** Clear workspace memory for ALL projects. */
    fun clearAllWorkspaceMemory() {
        val allKeys = prefs.all.keys.filter { it.startsWith("shell_state_") ||
                it.startsWith("cursors_") || it.startsWith("scrolls_") || it.startsWith("terminal_") ||
                it.startsWith("splits_") || it.startsWith("locks_") || it.startsWith("folds_") ||
                it.startsWith("blame_") }
        prefs.edit { allKeys.forEach { remove(it) } }
    }

    // ── Per-file cursor positions ─────────────────────────────────────────

    fun saveCursors(projectId: String, cursors: Map<String, Int>) {
        if (!workspaceRestoreEnabled) return
        val obj = JSONObject()
        cursors.forEach { (path, offset) -> obj.put(path, offset) }
        prefs.edit { putString(cursorKey(projectId), obj.toString()) }
    }

    fun loadCursors(projectId: String): Map<String, Int> {
        val raw = prefs.getString(cursorKey(projectId), null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap { obj.keys().forEach { k -> put(k, obj.optInt(k, 0)) } }
        } catch (_: Exception) { emptyMap() }
    }

    // ── Per-file scroll positions (first visible line index) ──────────────

    fun saveScrollPositions(projectId: String, scrolls: Map<String, Int>) {
        if (!workspaceRestoreEnabled) return
        val obj = JSONObject()
        scrolls.forEach { (path, line) -> obj.put(path, line) }
        prefs.edit { putString(scrollKey(projectId), obj.toString()) }
    }

    fun loadScrollPositions(projectId: String): Map<String, Int> {
        val raw = prefs.getString(scrollKey(projectId), null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap { obj.keys().forEach { k -> put(k, obj.optInt(k, 0)) } }
        } catch (_: Exception) { emptyMap() }
    }

    // ── Terminal state ────────────────────────────────────────────────────

    fun saveTerminalState(projectId: String, state: TerminalMemory) {
        if (!workspaceRestoreEnabled) return
        prefs.edit { putString(terminalKey(projectId), encodeTerminalState(state)) }
    }

    fun loadTerminalState(projectId: String): TerminalMemory? {
        val raw = prefs.getString(terminalKey(projectId), null) ?: return null
        return try { decodeTerminalState(raw) } catch (_: Exception) { null }
    }

    // ── PERSIST-A (2026-09-13): editor extras per project ─────────────────

    /** Split views of a project: their view ids + which view id was last ACTIVE
     *  (a tab path if the primary was active, null = primary/first tab). */
    fun saveSplitViews(projectId: String, viewIds: List<String>, activeViewId: String?) {
        if (!workspaceRestoreEnabled) return
        val arr = JSONArray(viewIds)
        val obj = JSONObject().put("ids", arr).put("active", activeViewId)
        prefs.edit { putString(splitKey(projectId), obj.toString()) }
    }

    data class SplitViewsMemory(
        val viewIds: List<String> = emptyList(),
        val activeViewId: String? = null,
    )

    fun loadSplitViews(projectId: String): SplitViewsMemory {
        val raw = prefs.getString(splitKey(projectId), null) ?: return SplitViewsMemory()
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("ids") ?: JSONArray()
            val ids = buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotBlank()) add(s)
                }
            }
            SplitViewsMemory(ids, if (obj.isNull("active")) null else obj.optString("active", null))
        } catch (_: Exception) { SplitViewsMemory() }
    }

    /** PAD-1 per-view scroll-lock flags: viewKey -> locked. */
    fun saveLocks(projectId: String, locks: Map<String, Boolean>) {
        if (!workspaceRestoreEnabled) return
        val obj = JSONObject()
        locks.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit { putString(lockKey(projectId), obj.toString()) }
    }

    fun loadLocks(projectId: String): Map<String, Boolean> {
        val raw = prefs.getString(lockKey(projectId), null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap { obj.keys().forEach { k -> put(k, obj.optBoolean(k, false)) } }
        } catch (_: Exception) { emptyMap() }
    }

    /** Code-folding state per FILE path: folded range start lines (0-based). */
    fun saveFolds(projectId: String, folds: Map<String, List<Int>>) {
        if (!workspaceRestoreEnabled) return
        val obj = JSONObject()
        folds.forEach { (path, lines) ->
            obj.put(path, JSONArray(lines))
        }
        prefs.edit { putString(foldKey(projectId), obj.toString()) }
    }

    fun loadFolds(projectId: String): Map<String, List<Int>> {
        val raw = prefs.getString(foldKey(projectId), null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { path ->
                    val arr = obj.optJSONArray(path)
                    val lines = buildList {
                        if (arr != null) for (i in 0 until arr.length()) add(arr.optInt(i, -1))
                    }.filter { it >= 0 }
                    put(path, lines)
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    /** Git-Blame strip toggle (session UI state restored per project). */
    fun saveBlameEnabled(projectId: String, enabled: Boolean) {
        prefs.edit { putBoolean(blameKey(projectId), enabled) }
    }

    fun loadBlameEnabled(projectId: String): Boolean =
        prefs.getBoolean(blameKey(projectId), false)

    // ── Data classes ──────────────────────────────────────────────────────

    data class ShellState(
        val projectId: String,
        val activePanel: String? = null,
        val bottomTab: String? = null,
        val showBottomPanel: Boolean = true,
        val activeFilePath: String? = null,
        val openFilePaths: List<String> = emptyList(),
        val pinnedFilePaths: List<String> = emptyList(),
        val editorFontSize: Int = 13,
    )

    data class TerminalMemory(
        val workingDirectory: String? = null,
        /** Last 50 commands typed in this project's terminal. */
        val recentCommands: List<String> = emptyList(),
    )

    // ── Keys ──────────────────────────────────────────────────────────────

    companion object {
        private const val KEY_PROJECT_ID     = "last_project_id"
        private const val KEY_RESTORE_ENABLED = "workspace_restore_enabled"

        private fun shellKey(id: String)    = "shell_state_$id"
        private fun cursorKey(id: String)   = "cursors_$id"
        private fun scrollKey(id: String)   = "scrolls_$id"
        private fun terminalKey(id: String) = "terminal_$id"
        private fun splitKey(id: String)   = "splits_$id"
        private fun lockKey(id: String)    = "locks_$id"
        private fun foldKey(id: String)    = "folds_$id"
        private fun blameKey(id: String)   = "blame_$id"

        // ── Encoders ──────────────────────────────────────────────────────

        fun encodeShellState(state: ShellState): String = JSONObject().apply {
            put("projectId",       state.projectId)
            put("activePanel",     state.activePanel)
            put("bottomTab",       state.bottomTab)
            put("showBottomPanel", state.showBottomPanel)
            put("activeFilePath",  state.activeFilePath)
            put("openFilePaths",   JSONArray(state.openFilePaths))
            put("pinnedFilePaths", JSONArray(state.pinnedFilePaths))
            put("editorFontSize",  state.editorFontSize)
        }.toString()

        fun decodeShellState(raw: String): ShellState? = try {
            val obj = JSONObject(raw)
            fun strList(key: String): List<String> = buildList {
                val arr = obj.optJSONArray(key) ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotBlank()) add(s)
                }
            }
            ShellState(
                projectId      = obj.optString("projectId", ""),
                activePanel    = obj.optString("activePanel").takeIf { !it.isNullOrBlank() },
                bottomTab      = obj.optString("bottomTab").takeIf { !it.isNullOrBlank() },
                showBottomPanel = obj.optBoolean("showBottomPanel", true),
                activeFilePath = obj.optString("activeFilePath").takeIf { !it.isNullOrBlank() },
                openFilePaths  = strList("openFilePaths"),
                pinnedFilePaths = strList("pinnedFilePaths"),
                editorFontSize = obj.optInt("editorFontSize", 13),
            )
        } catch (_: Exception) { null }

        private fun encodeTerminalState(state: TerminalMemory): String = JSONObject().apply {
            put("workingDirectory", state.workingDirectory)
            put("recentCommands",   JSONArray(state.recentCommands.takeLast(50)))
        }.toString()

        private fun decodeTerminalState(raw: String): TerminalMemory {
            val obj = JSONObject(raw)
            val cmds = buildList<String> {
                val arr = obj.optJSONArray("recentCommands") ?: JSONArray()
                for (i in 0 until arr.length()) { val s = arr.optString(i); if (s.isNotBlank()) add(s) }
            }
            return TerminalMemory(
                workingDirectory = obj.optString("workingDirectory").takeIf { !it.isNullOrBlank() },
                recentCommands   = cmds,
            )
        }
    }
}
