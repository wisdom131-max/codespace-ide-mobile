package com.codespace.ide.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * P16-C: Session Handoff Manager
 * Export/import the full IDE session state as JSON so the same project
 * can be resumed on another device by pushing/pulling from the backend.
 *
 * Backend endpoints (Bearer auth):
 *   POST /api/session/:projectId  — body: JSON session blob
 *   GET  /api/session/:projectId  — returns JSON session blob
 */
object SessionHandoffManager {

    // ── Export ──────────────────────────────────────────────────────────────────

    fun exportSession(context: Context, projectId: String): String {
        val store = SessionStateStore(context)
        val shell    = store.loadShellState(projectId)
        val cursors  = store.loadCursors(projectId)
        val scrolls  = store.loadScrollPositions(projectId)
        val terminal = store.loadTerminalState(projectId)

        return JSONObject().apply {
            put("projectId", projectId)
            put("exportedAt", System.currentTimeMillis())

            shell?.let { s ->
                put("shell", JSONObject().apply {
                    put("activePanel",    s.activePanel    ?: "")
                    put("bottomTab",      s.bottomTab      ?: "")
                    put("activeFilePath", s.activeFilePath ?: "")
                    put("splitFilePath",  s.splitFilePath  ?: "")
                    put("showBottomPanel", s.showBottomPanel)
                    put("openFilePaths",  JSONArray(s.openFilePaths))
                    put("pinnedFilePaths", JSONArray(s.pinnedFilePaths))
                })
            }

            put("cursors", JSONObject(cursors as Map<*, *>))
            put("scrollPositions", JSONObject(scrolls as Map<*, *>))

            terminal?.let { t ->
                put("terminal", JSONObject().apply {
                    put("workingDirectory", t.workingDirectory ?: "")
                    put("recentCommands",   JSONArray(t.recentCommands))
                })
            }
        }.toString()
    }

    // ── Import ──────────────────────────────────────────────────────────────────

    fun importSession(context: Context, json: String) {
        runCatching {
            val obj       = JSONObject(json)
            val projectId = obj.optString("projectId")
            if (projectId.isBlank()) return

            val store = SessionStateStore(context)

            obj.optJSONObject("shell")?.let { s ->
                fun arr(key: String): List<String> {
                    val a = s.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.getString(it) }
                }
                store.saveShellState(projectId, SessionStateStore.ShellState(
                    projectId       = projectId,
                    activePanel     = s.optString("activePanel").ifBlank { null },
                    bottomTab       = s.optString("bottomTab").ifBlank { null },
                    activeFilePath  = s.optString("activeFilePath").ifBlank { null },
                    splitFilePath   = s.optString("splitFilePath").ifBlank { null },
                    showBottomPanel = s.optBoolean("showBottomPanel", true),
                    openFilePaths   = arr("openFilePaths"),
                    pinnedFilePaths = arr("pinnedFilePaths"),
                ))
            }

            obj.optJSONObject("cursors")?.let { co ->
                val cursors = mutableMapOf<String, Int>()
                co.keys().forEach { k -> cursors[k] = co.optInt(k, 0) }
                store.saveCursors(projectId, cursors)
            }

            obj.optJSONObject("scrollPositions")?.let { so ->
                val scrolls = mutableMapOf<String, Int>()
                so.keys().forEach { k -> scrolls[k] = so.optInt(k, 0) }
                store.saveScrollPositions(projectId, scrolls)
            }

            obj.optJSONObject("terminal")?.let { t ->
                val histArr = t.optJSONArray("recentCommands")
                val history = if (histArr != null) (0 until histArr.length()).map { histArr.getString(it) } else emptyList()
                store.saveTerminalState(projectId, SessionStateStore.TerminalMemory(
                    workingDirectory = t.optString("workingDirectory").ifBlank { null },
                    recentCommands   = history,
                ))
            }
        }
    }

    // ── Cloud push ──────────────────────────────────────────────────────────────

    suspend fun pushSessionToCloud(
        context: Context,
        projectId: String,
        backendUrl: String,
        authToken: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = exportSession(context, projectId).toByteArray(Charsets.UTF_8)
            val conn = (URL("$backendUrl/api/session/$projectId").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 15_000
                readTimeout    = 30_000
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (code !in 200..299) error("Push session failed ($code): $resp")
        }
    }

    // ── Cloud pull ──────────────────────────────────────────────────────────────

    suspend fun pullSessionFromCloud(
        context: Context,
        projectId: String,
        backendUrl: String,
        authToken: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$backendUrl/api/session/$projectId").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $authToken")
                connectTimeout = 15_000
                readTimeout    = 30_000
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (code !in 200..299) error("Pull session failed ($code): $body")
            importSession(context, body)
        }
    }
}
