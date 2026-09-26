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

    /** RG10 (2026-09-26): session blob schema version. Bump when the export format
     *  changes; the importer refuses blobs from NEWER schemas instead of decoding
     *  them to garbage silently. */
    private const val SCHEMA_VERSION = 1

    // ── Export ──────────────────────────────────────────────────────────────────

    fun exportSession(context: Context, projectId: String): String {
        val store = SessionStateStore(context)
        val shell    = store.loadShellState(projectId)
        val cursors  = store.loadCursors(projectId)
        val scrolls  = store.loadScrollPositions(projectId)
        val terminal = store.loadTerminalState(projectId)

        return JSONObject().apply {
            put("schema", SCHEMA_VERSION)
            put("projectId", projectId)
            put("exportedAt", System.currentTimeMillis())

            shell?.let { s ->
                put("shell", JSONObject().apply {
                    put("activePanel",    s.activePanel    ?: "")
                    put("bottomTab",      s.bottomTab      ?: "")
                    put("activeFilePath", s.activeFilePath ?: "")
                    put("showBottomPanel", s.showBottomPanel)
                    put("openFilePaths",  JSONArray(s.openFilePaths))
                    put("pinnedFilePaths", JSONArray(s.pinnedFilePaths))
                })
            }

            put("cursors", JSONObject(cursors as Map<*, *>))
            put("scrollPositions", JSONObject(scrolls as Map<*, *>))

            terminal?.let { t ->
                // RG11 (2026-09-26): terminal persistence is COSMETIC-ONLY (cwd + last
                // 50 commands) — processes do not resume. The blob says so, so a restore
                // surface can never read it as a live-session revival.
                put("terminal", JSONObject().apply {
                    put("workingDirectory", t.workingDirectory ?: "")
                    put("recentCommands",   JSONArray(t.recentCommands))
                    put("note", "history only - terminal processes do not resume")
                })
            }
        }.toString()
    }

    // ── Import ──────────────────────────────────────────────────────────────────

    /**
     * RG10 (2026-09-26): typed verdict — a corrupt blob or one from a NEWER schema now
     * reports an honest failure instead of being swallowed by runCatching. Returns a
     * user-facing verdict string.
     */
    fun importSession(context: Context, json: String): String = runCatching {
        val obj = JSONObject(json)
        val schema = obj.optInt("schema", 1)
        if (schema > SCHEMA_VERSION) {
            return@runCatching "Session blob uses schema v$schema, newer than this build reads (v$SCHEMA_VERSION) - update the app, or export a fresh session on the other device."
        }
        val projectId = obj.optString("projectId")
        if (projectId.isBlank()) return@runCatching "Not a CodeSpace session export (no projectId) - nothing imported."
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

        var terminalNote = ""
        obj.optJSONObject("terminal")?.let { t ->
            // RG11: terminal state is history-only — the verdict says so, so the
            // restore never reads as a live-session revival.
            val histArr = t.optJSONArray("recentCommands")
            val history = if (histArr != null) (0 until histArr.length()).map { histArr.getString(it) } else emptyList()
            store.saveTerminalState(projectId, SessionStateStore.TerminalMemory(
                workingDirectory = t.optString("workingDirectory").ifBlank { null },
                recentCommands   = history,
            ))
            terminalNote = " (terminal block restored as history only - processes do not resume)"
        }
        "Session imported (schema v$schema).$terminalNote"
    }.getOrElse { "Session import failed: ${it.message} - nothing imported." }

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
            // RG10: importSession now returns a typed verdict — log it and keep
            // this cloud-pull's own result shape Result<Unit>.
            android.util.Log.d("SessionHandoff", importSession(context, body))
            // Log.d returns Int — explicit Unit keeps this runCatching's Result shape.
            Unit
        }
    }
}
