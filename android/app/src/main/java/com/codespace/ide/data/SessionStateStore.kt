package com.codespace.ide.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class SessionStateStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("session_state", Context.MODE_PRIVATE)

    fun saveProjectId(projectId: String) {
        prefs.edit { putString(KEY_PROJECT_ID, projectId) }
    }

    fun lastProjectId(): String? = prefs.getString(KEY_PROJECT_ID, null)

    fun saveShellState(projectId: String, state: ShellState) {
        prefs.edit { putString("shell_state_$projectId", encodeShellState(state)) }
    }

    fun loadShellState(projectId: String): ShellState? {
        val raw = prefs.getString("shell_state_$projectId", null) ?: return null
        return decodeShellState(raw)
    }

    fun clearProjectState(projectId: String) {
        prefs.edit { remove("shell_state_$projectId") }
    }

    data class ShellState(
        val projectId: String,
        val activePanel: String? = null,
        val bottomTab: String? = null,
        val showBottomPanel: Boolean = true,
        val activeFilePath: String? = null,
        val openFilePaths: List<String> = emptyList(),
        val editorFontSize: Int = 13,
    )

    companion object {
        private const val KEY_PROJECT_ID = "last_project_id"

        fun encodeShellState(state: ShellState): String = JSONObject().apply {
            put("projectId", state.projectId)
            put("activePanel", state.activePanel)
            put("bottomTab", state.bottomTab)
            put("showBottomPanel", state.showBottomPanel)
            put("activeFilePath", state.activeFilePath)
            put("openFilePaths", JSONArray(state.openFilePaths))
            put("editorFontSize", state.editorFontSize)
        }.toString()

        fun decodeShellState(raw: String): ShellState? = try {
            val obj = JSONObject(raw)
            ShellState(
                projectId = obj.optString("projectId", ""),
                activePanel = obj.optString("activePanel", null).takeIf { it.isNotBlank() },
                bottomTab = obj.optString("bottomTab", null).takeIf { it.isNotBlank() },
                showBottomPanel = obj.optBoolean("showBottomPanel", true),
                activeFilePath = obj.optString("activeFilePath", null).takeIf { it.isNotBlank() },
                openFilePaths = buildList {
                    val array = obj.optJSONArray("openFilePaths") ?: JSONArray()
                    for (i in 0 until array.length()) {
                        val path = array.optString(i, "")
                        if (path.isNotBlank()) add(path)
                    }
                },
                editorFontSize = obj.optInt("editorFontSize", 13),
            )
        } catch (_: Exception) {
            null
        }
    }
}
