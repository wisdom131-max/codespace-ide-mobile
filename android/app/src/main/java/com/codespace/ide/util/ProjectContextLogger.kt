package com.codespace.ide.util

import android.content.Context
import android.os.Environment
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
 * this writes the moment's context to files that survive app restarts.
 * Wisdom can check these files after the fact instead of needing to catch
 * the Output tab live.
 *
 * Three write targets, in priority order:
 *
 * 1. PUBLIC (visible in any file manager):
 *    /sdcard/CodespaceIDE/logs/crash-context.log
 *    -> /storage/emulated/0/CodespaceIDE/logs/crash-context.log
 *    This is the one Wisdom opens from his phone's file browser.
 *
 * 2. APP-PRIVATE EXTERNAL:
 *    context.getExternalFilesDir(null) / "crash-context.log"
 *    -> /storage/emulated/0/Android/data/com.codespace.ide/files/crash-context.log
 *    Reachable via the app's Terminal only.
 *
 * 3. APP-PRIVATE INTERNAL (fallback):
 *    context.filesDir/diagnostics/crash-context.log
 *    -> /data/data/com.codespace.ide/files/diagnostics/crash-context.log
 *    Last resort if external storage is unavailable.
 *
 * CRITICAL: Every write attempt is also logged to AppOutputLog (the Output
 * tab) BEFORE and AFTER the attempt, including the target path and any
 * exception. This ensures that even if all file writes fail, we can see
 * exactly why from the Output tab -- which always works.
 *
 * Entries are capped at 200 to prevent unbounded growth.
 */
object ProjectContextLogger {

    private const val TAG = "ProjectContextLogger"
    private const val FILE_NAME = "crash-context.log"
    private const val MAX_ENTRIES = 200
    private const val MAX_FILE_BYTES = 256 * 1024 // 256KB safety cap
    private const val PUBLIC_FOLDER = "CodespaceIDE"
    private const val PUBLIC_LOG_SUBDIR = "logs"

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

        // Write to all three targets. Each is independent -- a failure in one
        // does not skip the others. All results go to AppOutputLog.
        writeToPublicStorage(entry)
        writeToAppPrivateExternal(context, entry)
        writeToAppPrivateInternal(context, entry)
    }

    // -- Public storage (visible in file managers) ------------------------

    /**
     * Writes to /sdcard/CodespaceIDE/logs/crash-context.log
     * This path is browsable from any file manager (e.g. Files, Solid Explorer).
     */
    private fun writeToPublicStorage(entry: String) {
        val targetFile = resolvePublicFile()
        AppOutputLog.log("[CONTEXT-LOG] [PUBLIC] Attempting write to: " + targetFile.absolutePath, "lsp")

        try {
            val dir = targetFile.parentFile
            if (dir != null && !dir.exists()) {
                val created = dir.mkdirs()
                AppOutputLog.log("[CONTEXT-LOG] [PUBLIC] Parent dir mkdirs()=" + created + " for " + dir.absolutePath, "lsp")
            }

            if (targetFile.exists() && targetFile.length() > MAX_FILE_BYTES) {
                trimOldEntries(targetFile)
            }

            targetFile.appendText(entry)
            AppOutputLog.log("[CONTEXT-LOG] [PUBLIC] Write SUCCEEDED -- " + entry.length + " bytes, total=" + targetFile.length() + " bytes at " + targetFile.absolutePath, "lsp")
        } catch (e: Exception) {
            Log.e(TAG, "[PUBLIC] Failed to write crash-context.log to " + targetFile.absolutePath, e)
            AppOutputLog.log("[CONTEXT-LOG] [PUBLIC] Write FAILED: " + e.javaClass.simpleName + ": " + e.message + " -- target=" + targetFile.absolutePath, "lsp")
        }
    }

    private fun resolvePublicFile(): File {
        val storageRoot = Environment.getExternalStorageDirectory()
        val dir = File(File(storageRoot, PUBLIC_FOLDER), PUBLIC_LOG_SUBDIR)
        return File(dir, FILE_NAME)
    }

    // -- App-private external storage -------------------------------------

    private fun writeToAppPrivateExternal(context: Context, entry: String) {
        val targetFile = resolveExternalFile(context)
        AppOutputLog.log("[CONTEXT-LOG] [APP-EXT] Attempting write to: " + targetFile.absolutePath, "lsp")

        try {
            val dir = targetFile.parentFile
            if (dir != null && !dir.exists()) {
                val created = dir.mkdirs()
                AppOutputLog.log("[CONTEXT-LOG] [APP-EXT] Parent dir mkdirs()=" + created, "lsp")
            }

            if (targetFile.exists() && targetFile.length() > MAX_FILE_BYTES) {
                trimOldEntries(targetFile)
            }

            targetFile.appendText(entry)
            AppOutputLog.log("[CONTEXT-LOG] [APP-EXT] Write SUCCEEDED -- " + targetFile.length() + " bytes at " + targetFile.absolutePath, "lsp")
        } catch (e: Exception) {
            Log.e(TAG, "[APP-EXT] Failed: " + e.message, e)
            AppOutputLog.log("[CONTEXT-LOG] [APP-EXT] Write FAILED: " + e.javaClass.simpleName + ": " + e.message, "lsp")
        }
    }

    private fun resolveExternalFile(context: Context): File {
        val externalDir = try {
            context.getExternalFilesDir(null)
        } catch (_: Exception) {
            null
        }
        if (externalDir != null) {
            return File(externalDir, FILE_NAME)
        }
        // If getExternalFilesDir returned null, fall through to internal
        return resolveInternalFile(context)
    }

    // -- App-private internal storage (fallback) -------------------------

    private fun writeToAppPrivateInternal(context: Context, entry: String) {
        val targetFile = resolveInternalFile(context)
        AppOutputLog.log("[CONTEXT-LOG] [APP-INT] Attempting write to: " + targetFile.absolutePath, "lsp")

        try {
            val dir = targetFile.parentFile
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
            }

            if (targetFile.exists() && targetFile.length() > MAX_FILE_BYTES) {
                trimOldEntries(targetFile)
            }

            targetFile.appendText(entry)
            AppOutputLog.log("[CONTEXT-LOG] [APP-INT] Write SUCCEEDED -- " + targetFile.length() + " bytes at " + targetFile.absolutePath, "lsp")
        } catch (e: Exception) {
            Log.e(TAG, "[APP-INT] Failed: " + e.message, e)
            AppOutputLog.log("[CONTEXT-LOG] [APP-INT] Write FAILED: " + e.javaClass.simpleName + ": " + e.message + " -- ALL THREE paths failed, no file copy was written", "lsp")
        }
    }

    private fun resolveInternalFile(context: Context): File {
        val dir = File(context.filesDir, "diagnostics")
        return File(dir, FILE_NAME)
    }

    // -- Public API -------------------------------------------------------

    /**
     * Returns the PUBLIC log file path (the one Wisdom should look for
     * in his file manager). Never null.
     */
    fun getLogFilePath(context: Context): String {
        return resolvePublicFile().absolutePath
    }

    /**
     * Returns all log file paths (public, app-ext, app-int) for diagnostic display.
     */
    fun getAllLogPaths(context: Context): List<String> {
        return listOf(
            resolvePublicFile().absolutePath,
            resolveExternalFile(context).absolutePath,
            resolveInternalFile(context).absolutePath,
        )
    }

    // -- Internal helpers -------------------------------------------------

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
