package com.codespace.ide.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.os.Environment
import androidx.core.content.FileProvider
import com.codespace.ide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 7 — Recovery & Reliability utilities.
 *
 * P7-1  Workspace Snapshots  — zip project dir → external storage
 * P7-2  Diagnostics Report   — device info + crash logs + terminal output
 * P7-3  Safe Mode            — crash counter; getters/setters for MainActivity
 * P7-4  Workspace Trash      — move-to-trash / list / restore / purge
 */
object WorkspaceManager {

    // ─────────────────────────────────────────────────────────────────────────
    // P7-3  SAFE MODE / CRASH COUNTER
    // ─────────────────────────────────────────────────────────────────────────

    private const val PREFS_SAFETY   = "ws_safety"
    private const val KEY_CRASH_CNT  = "crash_count"
    private const val KEY_LAST_START = "last_start_ms"
    private const val SAFE_THRESHOLD = 3        // crashes before safe mode
    private const val STABLE_MS      = 60_000L  // 60s uptime = stable launch

    /** Call from MainActivity.onCreate() BEFORE loading any project or terminal. */
    fun recordLaunch(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_SAFETY, Context.MODE_PRIVATE)
        val prev  = prefs.getLong(KEY_LAST_START, 0L)
        val now   = System.currentTimeMillis()
        val count = prefs.getInt(KEY_CRASH_CNT, 0)

        // If last launch ended within 60s, it likely crashed
        val prevCrashed = prev > 0 && (now - prev) < STABLE_MS
        val newCount    = if (prevCrashed) count + 1 else 0

        prefs.edit()
            .putInt(KEY_CRASH_CNT, newCount)
            .putLong(KEY_LAST_START, now)
            .apply()
    }

    /** Call when the app has been stable for 60s+ to reset the counter. */
    fun recordStable(ctx: Context) {
        ctx.getSharedPreferences(PREFS_SAFETY, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CRASH_CNT, 0).apply()
    }

    fun isSafeMode(ctx: Context): Boolean {
        val count = ctx.getSharedPreferences(PREFS_SAFETY, Context.MODE_PRIVATE)
            .getInt(KEY_CRASH_CNT, 0)
        return count >= SAFE_THRESHOLD
    }

    fun resetSafeMode(ctx: Context) {
        ctx.getSharedPreferences(PREFS_SAFETY, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CRASH_CNT, 0).apply()
    }

    fun crashCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_SAFETY, Context.MODE_PRIVATE)
            .getInt(KEY_CRASH_CNT, 0)

    // ─────────────────────────────────────────────────────────────────────────
    // P7-1  WORKSPACE SNAPSHOTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Zips [projectDir] into a timestamped .zip in the user's Downloads folder.
     * Returns the output File on success, throws on error.
     * Must be called from a coroutine (does IO on Dispatchers.IO).
     */
    suspend fun createSnapshot(ctx: Context, projectDir: File): File = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val name  = "${projectDir.name}_snapshot_$stamp.zip"

        val outFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — write to app-owned Downloads sub-dir (no MANAGE_EXTERNAL_STORAGE needed)
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dl, "CodespaceIDE").apply { mkdirs() }.let { File(it, name) }
        } else {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dl.mkdirs()
            File(dl, name)
        }

        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            projectDir.walkTopDown()
                .filter { it.isFile }
                .filter { !it.path.contains("/.ide-trash/") }   // exclude trash
                .filter { !it.path.contains("/.autosave/") }    // exclude autosave
                .forEach { file ->
                    val entryName = file.relativeTo(projectDir.parentFile!!).path
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
        outFile
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P7-2  DIAGNOSTICS REPORT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a plain-text diagnostics report and returns a shareable Intent.
     * [recentTerminalOutput] is the last N lines from AppOutputLog / terminal ring buffer.
     */
    suspend fun generateDiagnosticsReport(
        ctx: Context,
        recentTerminalOutput: String = "",
    ): Pair<File, Intent> = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val sb    = StringBuilder()

        sb.appendLine("=== CodeSpace IDE Diagnostics Report ===")
        sb.appendLine("Generated : $stamp")
        sb.appendLine()
        sb.appendLine("--- Device ---")
        sb.appendLine("Model        : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android      : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI          : ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        sb.appendLine()
        sb.appendLine("--- App ---")
        sb.appendLine("Package      : ${ctx.packageName}")
        sb.appendLine("Version      : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Safe mode    : ${isSafeMode(ctx)} (crash count: ${crashCount(ctx)})")
        sb.appendLine()

        // Crash logs
        val crashDir = File(ctx.filesDir, "crash_logs")
        val crashFiles = crashDir.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        sb.appendLine("--- Crash Logs (${crashFiles.size} file(s)) ---")
        if (crashFiles.isEmpty()) {
            sb.appendLine("No crash logs found.")
        } else {
            crashFiles.take(3).forEach { f ->
                sb.appendLine()
                sb.appendLine(">>> ${f.name}")
                sb.appendLine(f.readText().take(4000))
            }
        }

        // Recent terminal output
        if (recentTerminalOutput.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("--- Recent Terminal Output ---")
            sb.appendLine(recentTerminalOutput.takeLast(6000))
        }

        // Write to cache dir
        val outDir  = File(ctx.cacheDir, "diagnostics").apply { mkdirs() }
        val outFile = File(outDir, "diagnostics_$stamp.txt")
        outFile.writeText(sb.toString())

        // Build share intent
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", outFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type     = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CodeSpace IDE Diagnostics $stamp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Pair(outFile, intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P7-4  WORKSPACE TRASH
    // ─────────────────────────────────────────────────────────────────────────

    private fun trashDir(projectDir: File) = File(projectDir, ".ide-trash").apply { mkdirs() }

    data class TrashEntry(
        val originalPath: String,   // relative to projectDir
        val trashedName: String,    // timestamped name inside .ide-trash/
        val deletedAtMs: Long,
    )

    /**
     * Moves [target] into `.ide-trash/<timestamp>-<name>` inside [projectDir].
     * Returns the trash entry on success.
     */
    fun moveToTrash(projectDir: File, target: File): TrashEntry {
        val stamp      = System.currentTimeMillis()
        val trashedName = "${stamp}-${target.name}"
        val dest       = File(trashDir(projectDir), trashedName)
        target.renameTo(dest)
        return TrashEntry(
            originalPath = target.relativeTo(projectDir).path,
            trashedName  = trashedName,
            deletedAtMs  = stamp,
        )
    }

    /** Lists all items currently in the trash for [projectDir]. */
    fun listTrash(projectDir: File): List<TrashEntry> {
        val td = trashDir(projectDir)
        return td.listFiles()
            ?.filter { it.exists() }
            ?.map { f ->
                val dashIdx = f.name.indexOf('-')
                val stamp   = if (dashIdx > 0) f.name.substring(0, dashIdx).toLongOrNull() ?: 0L else 0L
                val origName = if (dashIdx > 0) f.name.substring(dashIdx + 1) else f.name
                TrashEntry(
                    originalPath = origName,   // best-effort; original dir is lost if not a top-level file
                    trashedName  = f.name,
                    deletedAtMs  = stamp,
                )
            }
            ?.sortedByDescending { it.deletedAtMs }
            ?: emptyList()
    }

    /**
     * Restores a trash entry back to [projectDir]/<original name>.
     * If the destination already exists, the file is restored with a `_restored` suffix.
     */
    fun restoreFromTrash(projectDir: File, entry: TrashEntry): Boolean {
        val src  = File(trashDir(projectDir), entry.trashedName)
        if (!src.exists()) return false
        var dest = File(projectDir, entry.originalPath)
        if (dest.exists()) dest = File(projectDir, "${dest.nameWithoutExtension}_restored.${dest.extension}")
        return src.renameTo(dest)
    }

    /** Permanently deletes a single trash entry. */
    fun purgeTrashEntry(projectDir: File, entry: TrashEntry) {
        File(trashDir(projectDir), entry.trashedName).deleteRecursively()
    }

    /** Empties the entire trash for [projectDir]. */
    fun emptyTrash(projectDir: File) {
        trashDir(projectDir).listFiles()?.forEach { it.deleteRecursively() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P29  PROJECT-LEVEL TRASH — recycle bin for whole deleted projects
    // ─────────────────────────────────────────────────────────────────────────

    private fun projectsTrashDir(context: Context): File =
        File(context.filesDir, "projects/.trash").apply { mkdirs() }

    /** Data class for a trashed project. */
    data class TrashedProject(
        val name: String,
        val trashedDir: File,
        val deletedAtMs: Long,
        val sizeBytes: Long,
    )

    /**
     * Moves an entire project directory to the project-level trash bin.
     * The project is NOT permanently deleted — call [purTrashedProject] for that.
     * Returns true on success.
     */
    fun moveProjectToTrash(context: Context, projectDir: File): Boolean {
        if (!projectDir.exists()) return false
        val trash = projectsTrashDir(context)
        val stamp = System.currentTimeMillis()
        val destName = "${stamp}-${projectDir.name}"
        val dest = File(trash, destName)
        val ok = projectDir.renameTo(dest)
        if (ok) Log.d("WorkspaceManager", "Project moved to trash: ${projectDir.name} -> $destName")
        return ok
    }

    /** Lists all projects currently in the trash bin. */
    fun listTrashedProjects(context: Context): List<TrashedProject> {
        val trash = projectsTrashDir(context)
        return trash.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { 
                val dashIdx = it.name.indexOf('-')
                if (dashIdx > 0) it.name.substring(0, dashIdx).toLongOrNull() ?: 0L else 0L
            }
            ?.map { dir ->
                val dashIdx = dir.name.indexOf('-')
                val stamp = if (dashIdx > 0) dir.name.substring(0, dashIdx).toLongOrNull() ?: 0L else 0L
                val origName = if (dashIdx > 0) dir.name.substring(dashIdx + 1) else dir.name
                TrashedProject(
                    name = origName,
                    trashedDir = dir,
                    deletedAtMs = stamp,
                    sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
            }
            ?: emptyList()
    }

    /**
     * Restores a trashed project back to the projects directory.
     * If a project with the same name already exists, appends _restored suffix.
     * Returns the restored File on success, null on failure.
     */
    fun restoreTrashedProject(context: Context, entry: TrashedProject): File? {
        val projectsRoot = File(context.filesDir, "projects")
        projectsRoot.mkdirs()
        var dest = File(projectsRoot, entry.name)
        if (dest.exists()) dest = File(projectsRoot, "${entry.name}_restored")
        val ok = entry.trashedDir.renameTo(dest)
        return if (ok) dest else null
    }

    /** Permanently deletes a trashed project. Cannot be undone. */
    fun purgeTrashedProject(context: Context, entry: TrashedProject): Boolean {
        return entry.trashedDir.deleteRecursively()
    }

    /** Empties the entire project trash bin. */
    fun emptyProjectTrash(context: Context) {
        projectsTrashDir(context).listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Formats a byte count into a human-readable string. */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        if (bytes < 1024 * 1024 * 1024) return "${bytes / (1024 * 1024)} MB"
        return "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
