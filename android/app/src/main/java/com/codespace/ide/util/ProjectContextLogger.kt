package com.codespace.ide.util

import android.content.Context
import android.util.Log
import com.codespace.ide.diagnostics.AppOutputLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent diagnostic logger for the "project context lost" issue.
 *
 * When the LSP detects that projectId is blank (projectRootPath is null),
 * this writes the moment's context to a file that survives app restarts.
 * Wisdom can check this file after the fact instead of needing to catch
 * the Output tab live.
 *
 * Primary location: context.getExternalFilesDir(null) / "crash-context.log"
 *   → /storage/emulated/0/Android/data/com.codespace.ide/files/crash-context.log
 *
 * Fallback location: context.filesDir/diagnostics/crash-context.log
 *   → /data/data/com.codespace.ide/files/diagnostics/crash-context.log
 *
 * CRITICAL: Every write attempt is also logged to AppOutputLog (the Output
 * tab) BEFORE and AFTER the attempt, including the target path and any
 * exception. This ensures that even if the file write fails, we can see
 * exactly why from the Output tab — which always works.
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

        // Log to logcat (always)
        Log.w(TAG, "Project context lost: " + reason + " | projectId='" + projectId + "' lastProjectId='" + lastProjectId + "'")

        // Resolve the target file BEFORE attempting the write, and log it to the Output tab
        val targetFile = resolveTargetFile(context)
        AppOutputLog.log("[CONTEXT-LOG] Attempting write to: " + targetFile.absolutePath + " (exists=" + targetFile.parentFile?.exists() + " dir=" + targetFile.parentFile?.absolutePath + ")", "lsp")

        try {
            // Ensure the parent directory exists
            val dir = targetFile.parentFile
            if (dir != null && !dir.exists()) {
                val created = dir.mkdirs()
                AppOutputLog.log("[CONTEXT-LOG] Parent dir did not exist — mkdirs() returned " + created + " for " + dir.absolutePath, "lsp")
            }

            // Trim if file is too large
            if (targetFile.exists() && targetFile.length() > MAX_FILE_BYTES) {
                trimOldEntries(targetFile)
            }

            // Write
            targetFile.appendText(entry)

            AppOutputLog.log("[CONTEXT-LOG] Write SUCCEEDED — " + entry.length + " bytes appended to " + targetFile.absolutePath + " (total=" + targetFile.length() + " bytes)", "lsp")
        } catch (e: Exception) {
            // Log the failure to BOTH logcat AND the Output tab
            Log.e(TAG, "Failed to write crash-context.log to " + targetFile.absolutePath, e)
            AppOutputLog.log("[CONTEXT-LOG] Write FAILED: " + e.javaClass.simpleName + ": " + e.message + " — target was " + targetFile.absolutePath, "lsp")

            // Attempt fallback to internal filesDir if the primary was external
            val fallback = resolveFallbackFile(context)
            if (fallback.absolutePath != targetFile.absolutePath) {
                AppOutputLog.log("[CONTEXT-LOG] Attempting fallback write to: " + fallback.absolutePath, "lsp")
                try {
                    fallback.parentFile?.mkdirs()
                    fallback.appendText(entry)
                    AppOutputLog.log("[CONTEXT-LOG] Fallback write SUCCEEDED — " + fallback.absolutePath + " (total=" + fallback.length() + " bytes)", "lsp")
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback write also failed to " + fallback.absolutePath, e2)
                    AppOutputLog.log("[CONTEXT-LOG] Fallback write ALSO FAILED: " + e2.javaClass.simpleName + ": " + e2.message + " — both primary and fallback paths failed", "lsp")
                }
            }
        }
    }

    fun getLogFilePath(context: Context): String? {
        return try {
            resolveTargetFile(context).absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the primary target file. Tries external files dir first,
     * falls back to internal filesDir/diagnostics if external is unavailable.
     * Never returns null — always resolves to a File path.
     */
    private fun resolveTargetFile(context: Context): File {
        // Try external first
        val externalDir = try {
            context.getExternalFilesDir(null)
        } catch (_: Exception) {
            null
        }
        if (externalDir != null) {
            return File(externalDir, FILE_NAME)
        }
        // Fallback to internal
        return resolveFallbackFile(context)
    }

    private fun resolveFallbackFile(context: Context): File {
        val dir = File(context.filesDir, "diagnostics")
        return File(dir, FILE_NAME)
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
