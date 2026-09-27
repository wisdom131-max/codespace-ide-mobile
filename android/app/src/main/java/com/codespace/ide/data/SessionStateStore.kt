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
 *  - Terminal working directory (COSMETIC-ONLY, RG11: cwd + last 50 commands —
 *    terminal PROCESSES do not resume; consumers must not present this as a live session)
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

    // TB02 (2026-09-27): split ownership. The editor pane and the shell screen used
    // to REPLACE one shared JSON blob, each default-filling the fields it does NOT
    // own — whichever partial writer ran last silently erased the other's fields
    // (rapid tab/panel/font updates could race; a happy-path launch never showed it).
    // Each writer now persists ONLY its own group under its own key. The two keys
    // have disjoint writers, so interleaved updates cannot lose fields.
    fun saveShellPanelState(projectId: String, activePanel: String?, bottomTab: String?, showBottomPanel: Boolean, editorFontSize: Int) {
        if (!workspaceRestoreEnabled) return
        prefs.edit {
            putString(shellPanelKey(projectId), JSONObject().apply {
                put("activePanel", activePanel)
                put("bottomTab", bottomTab)
                put("showBottomPanel", showBottomPanel)
                put("editorFontSize", editorFontSize)
            }.toString())
        }
    }

    fun saveShellEditorState(projectId: String, activeFilePath: String?, openFilePaths: List<String>, pinnedFilePaths: List<String>) {
        if (!workspaceRestoreEnabled) return
        prefs.edit {
            putString(shellEditorKey(projectId), JSONObject().apply {
                put("activeFilePath", activeFilePath)
                put("openFilePaths", JSONArray(openFilePaths))
                put("pinnedFilePaths", JSONArray(pinnedFilePaths))
            }.toString())
        }
    }

    /** Full-state write — only for writers that legitimately own ALL fields (session import). */
    fun saveShellState(projectId: String, state: ShellState) {
        saveShellPanelState(projectId, state.activePanel, state.bottomTab, state.showBottomPanel, state.editorFontSize)
        saveShellEditorState(projectId, state.activeFilePath, state.openFilePaths, state.pinnedFilePaths)
    }

    fun loadShellState(projectId: String): ShellState? {
        if (!workspaceRestoreEnabled) return null
        val panelRaw = prefs.getString(shellPanelKey(projectId), null)
        val editorRaw = prefs.getString(shellEditorKey(projectId), null)
        // Legacy migration: pre-TB02 single-blob saves still restore.
        if (panelRaw == null && editorRaw == null)
            return prefs.getString(shellKey(projectId), null)?.let { decodeShellState(it) }
        val panel = panelRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val editor = editorRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun str(o: JSONObject?, k: String): String? = o?.takeIf { it.has(k) }?.optString(k)
        fun bool(o: JSONObject?, k: String): Boolean = o?.takeIf { it.has(k) }?.optBoolean(k) ?: true
        fun int(o: JSONObject?, k: String): Int = o?.takeIf { it.has(k) }?.optInt(k, 13) ?: 13
        fun strList(o: JSONObject?, k: String): List<String> = buildList {
            val a = o?.optJSONArray(k) ?: return@buildList
            for (i in 0 until a.length()) { val e = a.optString(i, ""); if (e.isNotBlank()) add(e) }
        }
        return ShellState(
            projectId = projectId,
            activePanel = str(panel, "activePanel"),
            bottomTab = str(panel, "bottomTab"),
            showBottomPanel = bool(panel, "showBottomPanel"),
            activeFilePath = str(editor, "activeFilePath"),
            openFilePaths = strList(editor, "openFilePaths"),
            pinnedFilePaths = strList(editor, "pinnedFilePaths"),
            editorFontSize = int(panel, "editorFontSize"),
        )
    }

    /** Clear workspace memory for a specific project. */
    fun clearProjectState(projectId: String) {
        prefs.edit {
            remove(shellKey(projectId))
            remove(shellPanelKey(projectId))
            remove(shellEditorKey(projectId))
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
        private fun shellPanelKey(id: String) = "shell_panel_$id"
        private fun shellEditorKey(id: String) = "shell_editor_$id"
        private fun cursorKey(id: String)   = "cursors_$id"
        private fun scrollKey(id: String)   = "scrolls_$id"
        private fun terminalKey(id: String) = "terminal_$id"
        private fun splitKey(id: String)   = "splits_$id"
        private fun lockKey(id: String)    = "locks_$id"
        private fun foldKey(id: String)    = "folds_$id"
        private fun blameKey(id: String)   = "blame_$id"

        // ── Encoders ──────────────────────────────────────────────────────

        // TB02 (2026-09-27): the legacy single-blob SHELL encoder is deleted — the
        // two split writers serialize their own groups inline, and the decoder
        // below survives only as the legacy-blob migration reader.
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
