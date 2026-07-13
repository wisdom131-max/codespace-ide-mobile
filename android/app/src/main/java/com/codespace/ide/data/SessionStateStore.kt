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
        }
    }

    /** Clear workspace memory for ALL projects. */
    fun clearAllWorkspaceMemory() {
        val allKeys = prefs.all.keys.filter { it.startsWith("shell_state_") ||
                it.startsWith("cursors_") || it.startsWith("scrolls_") || it.startsWith("terminal_") }
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

    // ── Data classes ──────────────────────────────────────────────────────

    data class ShellState(
        val projectId: String,
        val activePanel: String? = null,
        val bottomTab: String? = null,
        val showBottomPanel: Boolean = true,
        val activeFilePath: String? = null,
        val openFilePaths: List<String> = emptyList(),
        val pinnedFilePaths: List<String> = emptyList(),
        val splitFilePath: String? = null,
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

        // ── Encoders ──────────────────────────────────────────────────────

        fun encodeShellState(state: ShellState): String = JSONObject().apply {
            put("projectId",       state.projectId)
            put("activePanel",     state.activePanel)
            put("bottomTab",       state.bottomTab)
            put("showBottomPanel", state.showBottomPanel)
            put("activeFilePath",  state.activeFilePath)
            put("openFilePaths",   JSONArray(state.openFilePaths))
            put("pinnedFilePaths", JSONArray(state.pinnedFilePaths))
            put("splitFilePath",   state.splitFilePath)
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
                activePanel    = obj.optString("activePanel", null).takeIf { !it.isNullOrBlank() },
                bottomTab      = obj.optString("bottomTab",  null).takeIf { !it.isNullOrBlank() },
                showBottomPanel = obj.optBoolean("showBottomPanel", true),
                activeFilePath = obj.optString("activeFilePath", null).takeIf { !it.isNullOrBlank() },
                openFilePaths  = strList("openFilePaths"),
                pinnedFilePaths = strList("pinnedFilePaths"),
                splitFilePath  = obj.optString("splitFilePath", null).takeIf { !it.isNullOrBlank() },
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
                workingDirectory = obj.optString("workingDirectory", null).takeIf { !it.isNullOrBlank() },
                recentCommands   = cmds,
            )
        }
    }
}
