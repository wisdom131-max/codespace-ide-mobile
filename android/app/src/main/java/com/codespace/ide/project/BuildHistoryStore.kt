package com.codespace.ide.project

import android.content.Context
import com.codespace.ide.build.BuildRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 12-E — Build History Store
 *
 * Persists a log of all Gradle builds: date/time, duration, type, status, log snippet, artifacts.
 * History is stored in SharedPreferences as JSON. Max 100 entries (oldest pruned).
 */
object BuildHistoryStore {

    private const val PREFS = "build_history"
    private const val KEY   = "entries"
    private const val MAX   = 100

    data class BuildRecord(
        val id: String,
        val projectName: String,
        val task: String,
        val status: BuildRunner.BuildStatus,
        val startedAt: Long,
        val durationMs: Long,
        val errorCount: Int,
        val warningCount: Int,
        val logSnippet: String,      // last 2000 chars of output
        val apkPath: String?,
    ) {
        val formattedDate: String
            get() = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(startedAt))

        val durationLabel: String
            get() {
                val s = durationMs / 1000
                return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
            }

        val isSuccess: Boolean get() = status == BuildRunner.BuildStatus.SUCCESS
    }

    private val _records = MutableStateFlow<List<BuildRecord>>(emptyList())
    val records: StateFlow<List<BuildRecord>> = _records.asStateFlow()

    /** Load history from disk. Call once on startup (or lazily before first use). */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val str = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return@withContext
        try {
            val arr = JSONArray(str)
            val list = (0 until arr.length()).map { arr.getJSONObject(it).toRecord() }
            _records.value = list
        } catch (_: Exception) {}
    }

    /** Record a completed build result. Call from BuildRunner after runBuild() returns. */
    suspend fun record(
        context: Context,
        projectName: String,
        task: String,
        result: BuildRunner.BuildResult,
        startedAt: Long,
    ) = withContext(Dispatchers.IO) {
        val entry = BuildRecord(
            id          = "${startedAt}_${task}",
            projectName = projectName,
            task        = task,
            status      = result.status,
            startedAt   = startedAt,
            durationMs  = result.durationMs,
            errorCount  = result.errorCount,
            warningCount = result.warningCount,
            logSnippet  = result.output.takeLast(2000),
            apkPath     = result.apkPath,
        )
        val updated = (listOf(entry) + _records.value).take(MAX)
        _records.value = updated
        persist(context, updated)
    }

    /** Delete a specific record by ID. */
    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        val updated = _records.value.filter { it.id != id }
        _records.value = updated
        persist(context, updated)
    }

    /** Clear all history. */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        _records.value = emptyList()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    private fun persist(context: Context, records: List<BuildRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("projectName", r.projectName)
                put("task", r.task)
                put("status", r.status.name)
                put("startedAt", r.startedAt)
                put("durationMs", r.durationMs)
                put("errorCount", r.errorCount)
                put("warningCount", r.warningCount)
                put("logSnippet", r.logSnippet)
                put("apkPath", r.apkPath ?: "")
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun JSONObject.toRecord() = BuildRecord(
        id           = getString("id"),
        projectName  = getString("projectName"),
        task         = getString("task"),
        status       = try { BuildRunner.BuildStatus.valueOf(getString("status")) }
                       catch (_: Exception) { BuildRunner.BuildStatus.FAILED },
        startedAt    = getLong("startedAt"),
        durationMs   = getLong("durationMs"),
        errorCount   = getInt("errorCount"),
        warningCount = getInt("warningCount"),
        logSnippet   = getString("logSnippet"),
        apkPath      = getString("apkPath").ifBlank { null },
    )
}
