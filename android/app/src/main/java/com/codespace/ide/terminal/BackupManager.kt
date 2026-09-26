package com.codespace.ide.terminal

import com.codespace.ide.data.NotificationStore

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import org.xmlpull.v1.XmlPullParser
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

    // TP12 (2026-09-26): guest secrets are EXCLUDED from the rootfs tar — the backup
    // lands on PUBLIC external storage (world-readable on this device class), and it
    // previously contained /root/.ssh keys, gitconfig/git-credential tokens and MCP
    // env files. Secrets stay in the live rootfs only; they are never shipped through
    // a shared-storage artifact. (The shared location itself is an accepted tradeoff:
    // the user pulls backups manually.)
    private val GUEST_SECRET_PATHS = listOf(
        "root/.ssh",
        "root/.gitconfig",
        "root/.git-credentials",
        "root/.aws",
        "root/.kube",
        "root/.mcp",
        "root/.env",
    )
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
                    // TP12 (2026-09-26): skip guest secrets (directory and children) —
                    // see GUEST_SECRET_PATHS above.
                    if (GUEST_SECRET_PATHS.any { relPath == it || relPath.startsWith("$it/") }) {
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
     * RG05 (2026-09-26): called FIRST in CodeSpaceApplication.onCreate, before any store init.
     *
     * - If shared storage holds a prefs-backup but the LIVE prefs are empty (fresh install
     *   after the forced uninstall of a CI rebuild, or wiped prefs), the backup is RESTORED
     *   through the prefs API (RG06) so the first store load sees it — a backup must never
     *   be overwritten with empty data.
     * - Otherwise the prefs-backup is refreshed on EVERY start (a handful of small file
     *   copies) — no more "one forgotten Settings tap loses everything".
     * - The heavy CONTAINER backup stays manual (auto-tarring the rootfs on start would be
     *   too heavy) but now PROMPTS via SettingsScreen: on version change, fresh-install
     *   restore, or a missing/stale (>7 days) backup while a rootfs exists.
     * Synchronous on purpose: it must complete before the first prefs/JSON store load.
     */
    fun onAppStart(context: Context) {
        val dest = File(backupDir(), "prefs-backup")
        val marker = File(dest, "version.txt")
        val currentVersion = currentVersionCode(context)
        val previousVersion = if (marker.exists()) marker.readText().trim().toLongOrNull() else null
        val backupHasData = prefsXmlFiles(dest).isNotEmpty()
        val liveHasData = context.getSharedPreferences("projects", Context.MODE_PRIVATE).all.isNotEmpty()

        if (backupHasData && !liveHasData) {
            restorePrefs(context)
            setContainerPrompt(context, true)
        } else {
            backupPrefs(context)
            if (previousVersion != currentVersion) setContainerPrompt(context, true)
            val rootfs = ProotInstaller.rootfsDir(context)
            val stale = !hasBackup() ||
                System.currentTimeMillis() - backupFile().lastModified() >= 7L * 24 * 60 * 60 * 1000
            if (rootfs.exists() && stale) setContainerPrompt(context, true)
        }
        marker.parentFile?.mkdirs()
        marker.writeText(currentVersion.toString())
    }

    /** RG05: the Settings prompt flag (survives restarts). */
    fun containerPromptPending(context: Context): Boolean =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean("container_backup_prompt", false)

    fun setContainerPrompt(context: Context, pending: Boolean) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("container_backup_prompt", pending).apply()
    }

    private fun currentVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) it.longVersionCode
            else @Suppress("DEPRECATION") it.versionCode.toLong()
        }

    private fun prefsXmlFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.sortedBy { it.name } ?: emptyList()

    /**
     * Backs up ALL app SharedPreferences files to /sdcard/CodespaceIDE/prefs-backup/
     * so they survive an app uninstall, plus the filesDir JSON stores.
     *
     * RG05 (2026-09-26): the list was SELECTIVE (projects/copy_chat/agent_memory plus a
     * legacy "com.codespace.ide_preferences" name that no longer even exists) while
     * keybindings, ssh-profiles, session_state, settings.json, scheduler tasks, terminal
     * history, trust state, etc. were EXCLUDED — a CI-rebuild uninstall silently lost
     * all of them. Now EVERY *.xml under shared_prefs is copied (glob, so future prefs
     * files are covered automatically) plus:
     *   - filesDir/settings.json      (JsonSettingsStore: settings + feature toggles)
     *   - filesDir/ssh-profiles.json   (SshProfileStore)
     *   - filesDir/agent_scheduler/    (AgentScheduler tasks)
     *   - filesDir/agent_memory/memory.json
     * Also runs on every app start via [onAppStart], not just the Settings button.
     */
    fun backupPrefs(context: Context) {
        val dest = File(backupDir(), "prefs-backup")
        dest.mkdirs()
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsXmlFiles(prefsDir).forEach { it.copyTo(File(dest, it.name), overwrite = true) }
        // JSON stores under filesDir (re-read at store init on next process start)
        listOf("settings.json", "ssh-profiles.json").forEach { name ->
            val src = File(context.filesDir, name)
            if (src.exists()) src.copyTo(File(dest, name), overwrite = true)
        }
        // Scheduler tasks
        val schedSrc = File(context.filesDir, "agent_scheduler")
        if (schedSrc.exists()) {
            val schedDest = File(dest, "agent_scheduler")
            schedDest.mkdirs()
            schedSrc.listFiles()?.forEach { it.copyTo(File(schedDest, it.name), overwrite = true) }
        }
        // Agent memory JSON
        val memFile = File(context.filesDir, "agent_memory/memory.json")
        if (memFile.exists()) memFile.copyTo(File(dest, "agent_memory.json"), overwrite = true)
        Log.d(TAG, "Prefs backup written to ${dest.absolutePath}")
    }

    /**
     * Restores the prefs-backup into the app.
     *
     * RG06 (2026-09-26): the old code copied raw XML files over shared_prefs while the
     * process was LIVE — already-loaded SharedPreferences instances ignored the restored
     * file until restart, and any later commit overwrote it with stale in-memory state
     * (the restore was silently lost). Prefs are now applied THROUGH the prefs API
     * ([applyPrefsXml]): the live instance is updated in memory AND persisted atomically.
     * filesDir JSON stores are file copies — those stores re-read at next init.
     */
    fun restorePrefs(context: Context) {
        val src = File(backupDir(), "prefs-backup")
        if (!src.exists()) return
        val applied = mutableListOf<String>()
        prefsXmlFiles(src).forEach { f -> if (applyPrefsXml(context, f)) applied += f.name }
        listOf("settings.json", "ssh-profiles.json").forEach { name ->
            val f = File(src, name)
            if (f.exists()) f.copyTo(File(context.filesDir, name), overwrite = true)
        }
        val schedSrc = File(src, "agent_scheduler")
        if (schedSrc.exists()) {
            val schedDest = File(context.filesDir, "agent_scheduler")
            schedDest.mkdirs()
            schedSrc.listFiles()?.forEach { it.copyTo(File(schedDest, it.name), overwrite = true) }
        }
        val memSrc = File(src, "agent_memory.json")
        if (memSrc.exists()) {
            val memDir = File(context.filesDir, "agent_memory")
            memDir.mkdirs()
            memSrc.copyTo(File(memDir, "memory.json"), overwrite = true)
        }
        Log.d(TAG, "Prefs restored (via prefs API: ${applied.joinToString()})")
    }

    /**
     * RG06: parses a backed-up SharedPreferences XML (AOSP format: map of
     * int/long/float/boolean/string/set entries) and applies every entry through the
     * LIVE instance's editor, so the restore is visible immediately and survives later
     * commits. Unknown tags are skipped (forward-compatible). Returns true if anything
     * was applied. Never throws — a corrupt/foreign XML is reported as false.
     */
    private fun applyPrefsXml(context: Context, src: File): Boolean = runCatching {
        val prefs = context.getSharedPreferences(src.name.removeSuffix(".xml"), Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var applied = false
        val parser = android.util.Xml.newPullParser()
        parser.setInput(src.inputStream(), null)
        var event = parser.eventType
        var inMap = false
        var setName: String? = null
        var setBuilder: MutableSet<String>? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "map" -> inMap = true
                    "set" -> if (inMap) {
                        setName = parser.getAttributeValue(null, "name")
                        setBuilder = mutableSetOf()
                    }
                    "string" -> if (inMap) {
                        val name = parser.getAttributeValue(null, "name")
                        if (setBuilder != null && name == null) setBuilder?.add(parser.nextText())
                        else if (name != null) { editor.putString(name, parser.nextText()); applied = true }
                    }
                    "int" -> if (inMap) {
                        val name = parser.getAttributeValue(null, "name")
                        val v = parser.getAttributeValue(null, "value")
                        if (name != null && v != null) { editor.putInt(name, v.toInt()); applied = true }
                    }
                    "long" -> if (inMap) {
                        val name = parser.getAttributeValue(null, "name")
                        val v = parser.getAttributeValue(null, "value")
                        if (name != null && v != null) { editor.putLong(name, v.toLong()); applied = true }
                    }
                    "float" -> if (inMap) {
                        val name = parser.getAttributeValue(null, "name")
                        val v = parser.getAttributeValue(null, "value")
                        if (name != null && v != null) { editor.putFloat(name, v.toFloat()); applied = true }
                    }
                    "boolean" -> if (inMap) {
                        val name = parser.getAttributeValue(null, "name")
                        val v = parser.getAttributeValue(null, "value")
                        if (name != null && v != null) { editor.putBoolean(name, v.toBoolean()); applied = true }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "set") {
                    val n = setName
                    val b = setBuilder
                    if (n != null && b != null) { editor.putStringSet(n, b); applied = true }
                    setName = null
                    setBuilder = null
                }
            }
            event = parser.next()
        }
        if (applied) editor.apply()
        applied
    }.getOrDefault(false)
}
