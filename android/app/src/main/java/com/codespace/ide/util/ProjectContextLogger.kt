package com.codespace.ide.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent diagnostic logger for the "project context lost" issue.
 *
 * When the LSP detects that projectId is blank (projectRootPath is null),
 * this writes the moment's context to a file in the app's external storage
 * that survives app restarts. Wisdom can check this file after the fact
 * instead of needing to catch the Output tab live.
 *
 * File location: /sdcard/Android/data/com.codespace.ide/files/crash-context.log
 * (a.k.a. context.getExternalFilesDir(null) / "crash-context.log")
 *
 * Entries are capped at 200 to prevent unbounded growth.
 */
object ProjectContextLogger {

    private const val TAG = "ProjectContextLogger"
    private const val FILE_NAME = "crash-context.log"
    private const val MAX_ENTRIES = 200
    private const val MAX_FILE_BYTES = 256 * 1024 // 256KB safety cap

    fun logContextLost(
        context: Context,
        reason: String,
        projectId: String?,
        filePath: String? = null,
        language: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val lastProjectId = readLastProjectId(context)

            val sb = StringBuilder()
            sb.append("=== ").append(timestamp).append(" ===\n")
            sb.append("REASON: ").append(reason).append("\n")
            sb.append("projectId: '").append(projectId ?: "null").append("'\n")
            sb.append("projectId.isNullOrBlank: ").append(projectId.isNullOrBlank()).append("\n")
            sb.append("lastProjectId (from prefs): '").append(lastProjectId ?: "null").append("'\n")
            sb.append("filePath: ").append(filePath ?: "N/A").append("\n")
            sb.append("language: ").append(language ?: "N/A").append("\n")
            for ((k, v) in extra) {
                sb.append(k).append(": ").append(v).append("\n")
            }
            sb.append("\n")

            val entry = sb.toString()
            Log.w(TAG, "Project context lost: " + reason + " | projectId='" + projectId + "' lastProjectId='" + lastProjectId + "'")

            writeAppend(context, entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash-context.log", e)
        }
    }

    fun getLogFilePath(context: Context): String? {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return null
            File(dir, FILE_NAME).absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAppend(context: Context, entry: String) {
        val dir = context.getExternalFilesDir(null) ?: run {
            File(context.filesDir, "diagnostics").also { it.mkdirs() }
        }
        val file = File(dir, FILE_NAME)

        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            trimOldEntries(file)
        }

        file.appendText(entry)

        val lines = file.readLines()
        if (lines.size > MAX_ENTRIES * 10) {
            trimOldEntries(file)
        }
    }

    private fun trimOldEntries(file: File) {
        try {
            val content = file.readText()
            val entries = content.split("=== ").filter { it.isNotBlank() }
            if (entries.size > MAX_ENTRIES) {
                val kept = entries.takeLast(MAX_ENTRIES)
                file.writeText(kept.joinToString(prefix = "=== ", separator = "=== "))
            }
        } catch (_: Exception) {
            file.writeText("")
        }
    }

    private fun readLastProjectId(context: Context): String? {
        return try {
            context.getSharedPreferences("session_state", Context.MODE_PRIVATE)
                .getString("last_project_id", null)
        } catch (_: Exception) {
            null
        }
    }
}
