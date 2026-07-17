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
     * Extracts the shared-storage backup back into the rootfs dir. Wipes any existing rootfs
     * first — meant to be called either on a fresh install (rootfs doesn't exist yet) or an
     * explicit user-triggered restore they've already confirmed will overwrite the container.
     */
    fun restoreBackup(context: Context, onProgress: (String) -> Unit): Boolean {
        val f = backupFile()
        if (!f.exists()) {
            onProgress("No backup found at ${f.path}")
            return false
        }
        val rootfs = ProotInstaller.rootfsDir(context)
        onProgress("Restoring container from backup (${f.length() / (1024 * 1024)} MB)...")
        rootfs.deleteRecursively()
        rootfs.mkdirs()

        var filesWritten = 0
        GzipCompressorInputStream(f.inputStream()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val outFile = File(rootfs, entry.name)
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            runCatching {
                                val link = outFile.toPath()
                                val target = java.nio.file.Paths.get(entry.linkName)
                                outFile.parentFile?.mkdirs()
                                if (java.nio.file.Files.exists(link) || java.nio.file.Files.isSymbolicLink(link))
                                    java.nio.file.Files.delete(link)
                                java.nio.file.Files.createSymbolicLink(link, target)
                            }.onFailure { Log.w(TAG, "Symlink restore failed ${entry.name}: ${it.message}") }
                        }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            runCatching {
                                outFile.outputStream().use { out -> tar.copyTo(out) }
                                if ((entry.mode and 0b001_001_001) != 0) outFile.setExecutable(true, false)
                                outFile.setReadable(true, false)
                            }.onFailure { Log.w(TAG, "Restore failed ${entry.name}: ${it.message}") }
                        }
                    }
                    filesWritten++
                    if (filesWritten % 500 == 0) onProgress("Restored $filesWritten files...")
                    entry = tar.nextEntry
                }
            }
        }
        onProgress("\u2713 Restore complete: $filesWritten files.")
        NotificationStore.add("Restore complete", "$filesWritten files restored from backup", NotificationStore.Type.BACKUP)
        return true
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
