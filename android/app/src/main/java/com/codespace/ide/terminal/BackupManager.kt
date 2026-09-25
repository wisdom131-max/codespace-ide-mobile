package com.codespace.ide.terminal

import com.codespace.ide.data.NotificationStore

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

/**
 * Backs up and restores the entire Ubuntu proot container (rootfs) to/from shared storage,
 * so it survives an app uninstall — which GitHub Actions rebuilds force on every fresh install
 * (a differently-signed APK each time means Android won't let the new build install over the
 * old one, so the user must fully uninstall first, wiping the app's sandboxed data directory).
 *
 * The backup lives in PUBLIC shared storage (requires MANAGE_EXTERNAL_STORAGE, already granted
 * in the manifest) at /storage/emulated/0/CodespaceIDE/container-backup.tar.gz — outside the
 * app's sandbox, so it survives uninstall. TerminalPane checks hasBackup() before doing a
 * normal first-time rootfs download and restores from this file instead if one exists.
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private const val BACKUP_FOLDER = "CodespaceIDE"
    private const val BACKUP_FILE = "container-backup.tar.gz"

    fun backupDir(): File = File(Environment.getExternalStorageDirectory(), BACKUP_FOLDER)
    fun backupFile(): File = File(backupDir(), BACKUP_FILE)

    fun hasBackup(): Boolean = backupFile().exists() && backupFile().length() > 0

    /** Human-readable "X MB • taken on <date>" for display in Settings, or null if none exists. */
    fun backupInfo(): String? {
        val f = backupFile()
        if (!f.exists()) return null
        val mb = f.length() / (1024.0 * 1024.0)
        val date = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(f.lastModified()))
        return "%.1f MB • %s".format(mb, date)
    }

    fun deleteBackup(): Boolean = backupFile().let { !it.exists() || it.delete() }

    /**
     * Tars + gzips the entire rootfs into the shared-storage backup file. Writes to a .tmp
     * file first and renames atomically on success, so an interrupted backup never leaves a
     * corrupt file behind that a later restore would silently fail (or partially fail) on.
     */
    fun createBackup(context: Context, onProgress: (String) -> Unit) {
        val rootfs = ProotInstaller.rootfsDir(context)
        if (!rootfs.exists()) {
            onProgress("Nothing to back up — Ubuntu isn't installed yet.")
            return
        }
        backupDir().mkdirs()
        val tmp = File(backupDir(), "$BACKUP_FILE.tmp")
        tmp.delete()

        var filesWritten = 0
        var totalBytes = 0L
        GzipCompressorOutputStream(tmp.outputStream()).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                rootfs.walkTopDown().forEach { file ->
                    if (file == rootfs) return@forEach
                    val relPath = file.relativeTo(rootfs).path
                    // Skip proc/sys/dev virtual mounts — never real files worth archiving,
                    // and walking into them can hang or explode in apparent size.
                    if (relPath.startsWith("proc/") || relPath.startsWith("sys/") || relPath.startsWith("dev/")) {
                        return@forEach
                    }
                    runCatching {
                        when {
                            java.nio.file.Files.isSymbolicLink(file.toPath()) -> {
                                val link = java.nio.file.Files.readSymbolicLink(file.toPath())
                                val symEntry = TarArchiveEntry(relPath, TarArchiveEntry.LF_SYMLINK)
                                symEntry.linkName = link.toString()
                                tar.putArchiveEntry(symEntry)
                                tar.closeArchiveEntry()
                            }
                            file.isDirectory -> {
                                tar.putArchiveEntry(TarArchiveEntry(file, relPath))
                                tar.closeArchiveEntry()
                            }
                            else -> {
                                val entry = TarArchiveEntry(file, relPath)
                                entry.size = file.length()
                                if (file.canExecute()) entry.mode = entry.mode or 0b001_001_001
                                tar.putArchiveEntry(entry)
                                file.inputStream().use { it.copyTo(tar) }
                                tar.closeArchiveEntry()
                                totalBytes += file.length()
                            }
                        }
                        filesWritten++
                        if (filesWritten % 500 == 0) {
                            onProgress("Backed up $filesWritten files (${totalBytes / (1024 * 1024)} MB)...")
                        }
                    }.onFailure { Log.w(TAG, "Skipped ${file.path}: ${it.message}") }
                }
            }
        }
        tmp.renameTo(backupFile())
        onProgress("\u2713 Backup complete: $filesWritten files, ${backupFile().length() / (1024 * 1024)} MB \u2192 ${backupFile().path}")
        NotificationStore.add("Backup complete", "$filesWritten files saved to ${backupFile().name}", NotificationStore.Type.BACKUP)
    }

    /**
     * RG02 (P3a): typed rootfs-restore result. ok=false means the LIVE
     * container was NOT touched (extraction failed into the temp dir first
     * and was discarded) or the backup file was missing/unreadable.
     * symlinkWarnings counts best-effort symlink entries that could not be
     * created (device kernels block symlink syscalls — logged, not fatal,
     * same tolerance as the pre-P3a code but now VISIBLE in the report).
     */
    data class RestoreResult(
        val filesWritten: Int,
        val fileFailures: Int,
        val symlinkWarnings: Int,
        val ok: Boolean,
        val message: String,
    )

    /**
     * Extracts the shared-storage backup back into the rootfs dir.
     * RG02 (P3a): the old implementation WIPED the live rootfs FIRST
     * (deleteRecursively before extraction), swallowed per-file failures
     * (Log.w + continue), and returned true unless the file was missing —
     * a mid-restore failure left a DESTROYED container + half-extracted
     * rootfs + a success message. Now the archive is extracted into a temp
     * dir and swapped ATOMICALLY on success; any hard file-write failure
     * aborts with the live rootfs untouched.
     */
    fun restoreBackup(context: Context, onProgress: (String) -> Unit): RestoreResult {
        val f = backupFile()
        if (!f.exists()) {
            onProgress("No backup found at ${f.path}")
            return RestoreResult(0, 0, 0, false, "No backup found at ${f.path}")
        }
        val rootfs = ProotInstaller.rootfsDir(context)
        onProgress("Restoring container from backup (${f.length() / (1024 * 1024)} MB)...")
        // RG02: extract to a SIBLING temp dir (same filesystem → atomic renames).
        val tmp = File(rootfs.parentFile, "rootfs.restore.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) {
            return RestoreResult(0, 0, 0, false, "Could not create restore temp dir ${tmp.path}")
        }

        var filesWritten = 0
        var fileFailures = 0
        var symlinkWarnings = 0
        GzipCompressorInputStream(f.inputStream()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    // RG07 (P2a): entry names arrive from a backup archive file and were
                    // used RAW at the extraction sink — a crafted archive could write
                    // outside rootfs. Contain every destination (ProotInstaller.kt:107
                    // boundary); rejected entries are logged and skipped (nextEntry
                    // skips their data).
                    val outFile = com.codespace.ide.util.CanonicalPaths.safeEntryDestination(rootfs, entry.name)
                    if (outFile == null) {
                        Log.w(TAG, "Rejected rootfs tar entry (escape attempt): ${entry.name}")
                        entry = tar.nextEntry
                        continue
                    }
                    var entryFailed = false
                    when {
                        entry.isDirectory -> if (!outFile.exists() && !outFile.mkdirs()) entryFailed = true
                        entry.isSymbolicLink -> {
                            // RG02: symlink creation is best-effort (kernels block the
                            // syscall on some devices) — counted as a WARNING, not a
                            // hard failure, but no longer invisible.
                            runCatching {
                                val link = outFile.toPath()
                                val target = java.nio.file.Paths.get(entry.linkName)
                                outFile.parentFile?.mkdirs()
                                if (java.nio.file.Files.exists(link) || java.nio.file.Files.isSymbolicLink(link))
                                    java.nio.file.Files.delete(link)
                                java.nio.file.Files.createSymbolicLink(link, target)
                            }.onFailure { symlinkWarnings++; Log.w(TAG, "Symlink restore failed ${entry.name}: ${it.message}") }
                        }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            val fileOk = runCatching {
                                outFile.outputStream().use { out -> tar.copyTo(out) }
                                if ((entry.mode and 0b001_001_001) != 0) outFile.setExecutable(true, false)
                                outFile.setReadable(true, false)
                            }.isSuccess
                            // RG02: a failed FILE copy is a HARD failure — the old code
                            // logged and continued, producing a broken container.
                            if (!fileOk) { fileFailures++; Log.e(TAG, "Restore failed ${entry.name}"); entryFailed = true }
                        }
                    }
                    if (entryFailed) return@use
                    filesWritten++
                    if (filesWritten % 500 == 0) onProgress("Restored $filesWritten files...")
                    entry = tar.nextEntry
                }
            }
        }

        // RG02: any hard file failure ABORTS — the temp extraction is discarded
        // and the LIVE container is untouched (the old code destroyed the live
        // rootfs first and reported success over a half-extracted container).
        if (fileFailures > 0) {
            tmp.deleteRecursively()
            val msg = "Restore FAILED: $fileFailures of ${filesWritten + fileFailures} entries could not be written. The current container was NOT modified; the backup file is untouched."
            onProgress("\u2717 $msg")
            return RestoreResult(filesWritten, fileFailures, symlinkWarnings, false, msg)
        }

        // RG02: atomic swap — live rootfs only disappears once the full
        // replacement exists. If any rename fails, roll back so the live
        // container is not left deleted.
        if (rootfs.exists()) {
            val oldDir = File(rootfs.parentFile, "rootfs.restore.old")
            oldDir.deleteRecursively()
            if (!rootfs.renameTo(oldDir)) {
                tmp.deleteRecursively()
                val msg = "Restore FAILED: could not stage the old container for replacement. Nothing was modified."
                onProgress("\u2717 $msg")
                return RestoreResult(filesWritten, 0, symlinkWarnings, false, msg)
            }
            if (!tmp.renameTo(rootfs)) {
                oldDir.renameTo(rootfs)  // roll back
                tmp.deleteRecursively()
                val msg = "Restore FAILED: could not swap in the restored container. The original container was kept."
                onProgress("\u2717 $msg")
                return RestoreResult(filesWritten, 0, symlinkWarnings, false, msg)
            }
            oldDir.deleteRecursively()
        } else {
            if (!tmp.renameTo(rootfs)) {
                tmp.deleteRecursively()
                val msg = "Restore FAILED: could not place the restored container at ${rootfs.path}"
                onProgress("\u2717 $msg")
                return RestoreResult(filesWritten, 0, symlinkWarnings, false, msg)
            }
        }

        val warnNote = if (symlinkWarnings > 0) " ($symlinkWarnings symlinks skipped — see logcat)" else ""
        onProgress("\u2713 Restore complete: $filesWritten files.$warnNote")
        NotificationStore.add("Restore complete", "$filesWritten files restored from backup$warnNote", NotificationStore.Type.BACKUP)
        return RestoreResult(filesWritten, 0, symlinkWarnings, true, "Restored $filesWritten files$warnNote")
    }

    /**
     * Backs up all relevant SharedPreferences files to /sdcard/CodespaceIDE/prefs-backup/
     * so they survive an app uninstall. Called alongside createBackup().
     * Prefs saved: "projects", "copilot_chat", "agent_memory" (from agent_memory/ dir),
     * and the global app prefs file (com.codespace.ide_preferences.xml).
     */
    fun backupPrefs(context: Context) {
        val dest = File(backupDir(), "prefs-backup")
        dest.mkdirs()
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        listOf("projects.xml", "copilot_chat.xml", "com.codespace.ide_preferences.xml").forEach { name ->
            val src = File(prefsDir, name)
            if (src.exists()) src.copyTo(File(dest, name), overwrite = true)
        }
        // Agent memory JSON
        val memFile = File(context.filesDir, "agent_memory/memory.json")
        if (memFile.exists()) memFile.copyTo(File(dest, "agent_memory.json"), overwrite = true)
        Log.d(TAG, "Prefs backup written to ${dest.absolutePath}")
    }

    /**
     * Restores SharedPreferences from /sdcard/CodespaceIDE/prefs-backup/ into the app's
     * shared_prefs folder. Called alongside restoreBackup(). Safe to call even if the
     * backup folder doesn't exist (returns silently).
     */
    fun restorePrefs(context: Context) {
        val src = File(backupDir(), "prefs-backup")
        if (!src.exists()) return
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.mkdirs()
        listOf("projects.xml", "copilot_chat.xml", "com.codespace.ide_preferences.xml").forEach { name ->
            val f = File(src, name)
            if (f.exists()) f.copyTo(File(prefsDir, name), overwrite = true)
        }
        val memSrc = File(src, "agent_memory.json")
        if (memSrc.exists()) {
            val memDir = File(context.filesDir, "agent_memory")
            memDir.mkdirs()
            memSrc.copyTo(File(memDir, "memory.json"), overwrite = true)
        }
        Log.d(TAG, "Prefs restored from ${src.absolutePath}")
    }
}
