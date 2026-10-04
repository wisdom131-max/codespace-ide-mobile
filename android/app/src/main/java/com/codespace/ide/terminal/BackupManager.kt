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

    // ── Owner finding (B) (2026-10-01): downloaded LSP server files live guest-side
    // under /opt and were LOST on full uninstall — the container restore can fail or
    // the container backup can be older than the newest server installs, and nothing
    // app-start-side covered them. Owner ruling: same treatment as the other stores.
    // Event-driven snapshot refreshed after every VERIFIED LSP install (never at every
    // app start — /opt can be hundreds of MB and lives on FUSE), re-applied after any
    // container restore/fresh-install path. GUEST_SECRET_PATHS are all under root/, so
    // an opt/-only walk cannot touch them (TP12 parity holds by construction).
    fun lspOptSnapshotFile(): File = File(backupDir(), "lsp-opt.tar.gz")

    /** Tars rootfs/opt into shared storage (atomic tmp+rename). False = nothing to snapshot. */
    fun snapshotLspOpt(context: Context): Boolean {
        val opt = File(ProotInstaller.rootfsDir(context), "opt")
        if (!opt.exists()) return false
        backupDir().mkdirs()
        val tmp = File(backupDir(), "lsp-opt.tar.gz.tmp")
        tmp.delete()
        return runCatching {
            var entries = 0
            GzipCompressorOutputStream(tmp.outputStream()).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                    opt.walkTopDown().forEach { file ->
                        if (file == opt) return@forEach
                        val relPath = "opt/" + file.relativeTo(opt).path
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
                                }
                            }
                            entries++
                        }.onFailure { Log.w(TAG, "lsp-opt snapshot skipped ${file.path}: ${it.message}") }
                    }
                }
            }
            val renamed = tmp.renameTo(lspOptSnapshotFile())
            if (renamed) {
                Log.d(TAG, "lsp-opt snapshot: $entries entries -> ${lspOptSnapshotFile().length()} bytes")
                com.codespace.ide.diagnostics.AppOutputLog.log("[RESTORE-DIAG] lsp-opt snapshot refreshed: $entries entries, ${lspOptSnapshotFile().length() / (1024 * 1024)}MB (LSP servers survive full uninstall)", "terminal")
            }
            renamed
        }.getOrDefault(false)
    }

    /**
     * Re-applies the /opt snapshot into the (restored or freshly installed) rootfs.
     * No-op when no snapshot exists. Per-entry failures are logged, never fatal — the
     * server trees are additive content, and LSP's isServerInstalled self-heal will
     * re-download anything genuinely broken.
     */
    fun applyLspOptSnapshot(context: Context): Boolean {
        val snap = lspOptSnapshotFile()
        if (!snap.exists() || snap.length() == 0L) return false
        val rootfs = ProotInstaller.rootfsDir(context)
        if (!rootfs.exists()) return false
        return runCatching {
            var applied = 0
            GzipCompressorInputStream(snap.inputStream()).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        // RG07 parity: entries arrive from an on-disk archive and get
                        // contained at the rootfs boundary before extraction.
                        val outFile = com.codespace.ide.util.CanonicalPaths.safeEntryDestination(rootfs, entry.name)
                        if (outFile == null) {
                            Log.w(TAG, "Rejected lsp-opt tar entry (escape attempt): ${entry.name}")
                        } else {
                            runCatching {
                                when {
                                    entry.isDirectory -> if (!outFile.exists()) outFile.mkdirs()
                                    entry.isSymbolicLink -> {
                                        val link = outFile.toPath()
                                        val target = java.nio.file.Paths.get(entry.linkName)
                                        outFile.parentFile?.mkdirs()
                                        if (java.nio.file.Files.exists(link) || java.nio.file.Files.isSymbolicLink(link))
                                            java.nio.file.Files.delete(link)
                                        java.nio.file.Files.createSymbolicLink(link, target)
                                    }
                                    else -> {
                                        outFile.parentFile?.mkdirs()
                                        outFile.outputStream().use { out -> tar.copyTo(out) }
                                        if ((entry.mode and 0b001_001_001) != 0) outFile.setExecutable(true, false)
                                        outFile.setReadable(true, false)
                                    }
                                }
                                applied++
                            }.onFailure { Log.w(TAG, "lsp-opt apply skipped ${entry.name}: ${it.message}") }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            Log.d(TAG, "lsp-opt snapshot applied: $applied entries")
            com.codespace.ide.diagnostics.AppOutputLog.log("[RESTORE-DIAG] lsp-opt snapshot applied: $applied entries into rootfs", "terminal")
            true
        }.getOrDefault(false)
    }

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
        // S1-b RESTORE-DIAG (2026-10-04, owner finding A): the full-uninstall restore
        // failure had no captured signature. Space is the prime suspect — the RG02
        // atomic swap needs room for the tmp extraction AND the staged old rootfs at the
        // same time, and a device that just wiped app data may be near its storage limit.
        // The probe is a WARNING, never a refusal: the typed failure paths below are the
        // actual verdict, and this line explains them when they fire.
        runCatching {
            val usableMb = (rootfs.parentFile?.usableSpace() ?: 0L) / (1024 * 1024)
            val diag = "[RESTORE-DIAG] restore start: backupMb=${f.length() / (1024 * 1024)} usableMb=$usableMb rootfsExists=${rootfs.exists()}"
            Log.d(TAG, diag)
            com.codespace.ide.diagnostics.AppOutputLog.log(diag, "terminal")
            if (usableMb < 300) {
                val warn = "[RESTORE-DIAG] LOW SPACE WARNING: ${usableMb}MB usable — the atomic swap needs room for the temp extraction plus the staged old rootfs; restore may fail mid-way"
                Log.w(TAG, warn)
                com.codespace.ide.diagnostics.AppOutputLog.log(warn, "terminal")
            }
        }
        // RG02: extract to a SIBLING temp dir (same filesystem → atomic renames).
        val tmp = File(rootfs.parentFile, "rootfs.restore.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) {
            return RestoreResult(0, 0, 0, false, "Could not create restore temp dir ${tmp.path}")
        }

        var filesWritten = 0
        var fileFailures = 0
        var symlinkWarnings = 0
        // S1-b RESTORE-DIAG: capture the FIRST hard file failure's real exception —
        // the old code counted failures and logged the entry name but swallowed the
        // throwable, so a disk-full / EACCES verdict was invisible in the report.
        var firstFailureDetail: String? = null
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
                            val copyOutcome = runCatching {
                                outFile.outputStream().use { out -> tar.copyTo(out) }
                                if ((entry.mode and 0b001_001_001) != 0) outFile.setExecutable(true, false)
                                outFile.setReadable(true, false)
                            }
                            // RG02: a failed FILE copy is a HARD failure — the old code
                            // logged and continued, producing a broken container.
                            if (copyOutcome.isFailure) {
                                fileFailures++
                                val cause = copyOutcome.exceptionOrNull()
                                Log.e(TAG, "Restore failed ${entry.name}: ${cause?.javaClass?.simpleName}: ${cause?.message}")
                                if (firstFailureDetail == null) {
                                    firstFailureDetail = "${cause?.javaClass?.simpleName}: ${cause?.message} (entry ${entry.name})"
                                }
                                entryFailed = true
                            }
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
            val detailNote = firstFailureDetail?.let { " First failure: $it." } ?: ""
            val msg = "Restore FAILED: $fileFailures of ${filesWritten + fileFailures} entries could not be written. The current container was NOT modified; the backup file is untouched.$detailNote"
            onProgress("\u2717 $msg")
            com.codespace.ide.diagnostics.AppOutputLog.log("[RESTORE-DIAG] $msg", "terminal")
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
        // Startup crash-safety (2026-10-01, second RG05 hotfix): the shared-storage
        // backup path is only usable when THIS install holds the "All files access"
        // grant — a full uninstall/reinstall or Android's unused-app auto-revoke
        // removes it, and the FUSE layer then reports exists() == true while open()
        // fails EACCES (the first RG05 hotfix only handled ENOENT, i.e. a MISSING
        // file). Application.onCreate must NEVER crash: gate on the grant, and any
        // storage I/O failure is an honest ERROR log, never a launch abort.
        val hasAllFilesAccess =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        if (!hasAllFilesAccess) {
            Log.w(TAG, "RG05 onAppStart: All files access NOT granted — shared-storage prefs backup/restore paused (launch continues; grant is re-requested by MainActivity)")
            NotificationStore.add(
                "Storage permission needed",
                "Prefs backup is paused. Grant \"All files access\" in the Settings screen that just opened so backups and restores resume on next start.",
                NotificationStore.Type.BACKUP
            )
            return
        }
        try {
            onAppStartGuarded(context)
        } catch (e: java.io.IOException) {
            // EACCES, ENOSPC, you name it: a backup subsystem failure must never
            // kill the app before the first frame (S01: logged, not swallowed).
            Log.e(TAG, "RG05 onAppStart storage I/O failure — launch continues: ${e}")
        }
    }

    private fun onAppStartGuarded(context: Context) {
        val dest = File(backupDir(), "prefs-backup")
        val marker = File(dest, "version.txt")
        val currentVersion = currentVersionCode(context)
        // EACCES can hit the READ even when exists() said true — a failed marker
        // read only means "version unknown" (previousVersion == null), which
        // arms the container prompt; it is never fatal.
        val previousVersion = runCatching {
            if (marker.exists()) marker.readText().trim().toLongOrNull() else null
        }.getOrNull()
        val backupHasData = prefsXmlFiles(dest).isNotEmpty()
        val liveHasData = context.getSharedPreferences("projects", Context.MODE_PRIVATE).all.isNotEmpty()

        if (backupHasData && !liveHasData) {
            restorePrefs(context)
            setContainerPrompt(context, true)
        } else {
            val prefsBackup = backupPrefs(context)
            if (!prefsBackup.ok) {
                Log.w(TAG, "RG05 onAppStart: prefs backup reported ${prefsBackup.failedNames.size} failure(s) — launch continues; see the ERROR line above")
            }
            if (previousVersion != currentVersion) setContainerPrompt(context, true)
            val rootfs = ProotInstaller.rootfsDir(context)
            val stale = !hasBackup() ||
                System.currentTimeMillis() - backupFile().lastModified() >= 7L * 24 * 60 * 60 * 1000
            if (rootfs.exists() && stale) setContainerPrompt(context, true)
        }
        marker.parentFile?.mkdirs()
        try {
            marker.writeText(currentVersion.toString())
        } catch (e: java.io.IOException) {
            // The version marker only arms the Settings prompt banner — a failed write
            // is an honest WARNING, never a launch crash (same RG05 hotfix discipline).
            Log.w(TAG, "RG05 version marker not written: ${e.message}")
        }
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
     * Keystore-bound encrypted store (SecureTokenStore "codespace_secure"): its file
     * holds ciphertext under an Android Keystore MASTER KEY that a full uninstall
     * DESTROYS — the key lives in the OS keystore, never in the file or in this
     * backup. Copying it back can only ever poison the fresh install with
     * undecryptable data (owner device 2026-10-01: AEADBadTagException crash loop on
     * the first launch after uninstall + restore). It is excluded from BOTH backup
     * and restore. Side benefit: tokens/AI keys/PIN hash no longer land on the
     * world-readable shared-storage backup either (SK04's brute-force concern).
     * The SecureTokenStore itself quarantines and recreates if the live file is
     * ever unreadable, so credentials are re-entered — never restored.
     */
    private fun isKeystoreBoundFile(name: String): Boolean =
        name == "codespace_secure.xml" || name.startsWith("codespace_secure.xml.corrupt-")

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
    fun backupPrefs(context: Context): PrefsBackupResult {
        val dest = File(backupDir(), "prefs-backup")
        dest.mkdirs()
        var copied = 0
        var skippedMissing = 0
        val failed = mutableListOf<String>()
        // Local tally must sit ABOVE its first call site (local-fn-before-call rule).
        fun tally(v: CopyVerdict, name: String) {
            when (v) {
                CopyVerdict.COPIED -> copied++
                CopyVerdict.SKIPPED_MISSING -> skippedMissing++
                CopyVerdict.FAILED -> failed += name
            }
        }
        // EVERY shared_prefs XML — the Firebase heartbeat files are created/rewritten
        // LAZILY by the Firebase process, so a listed file can be absent at open time.
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsXmlFiles(prefsDir).filterNot { isKeystoreBoundFile(it.name) }
            .forEach { f -> tally(copyStoreFileOrSkip(f, File(dest, f.name)), f.name) }
        // JSON stores under filesDir (re-read at store init on next process start)
        listOf("settings.json", "ssh-profiles.json").forEach { name ->
            tally(copyStoreFileOrSkip(File(context.filesDir, name), File(dest, name)), name)
        }
        // Scheduler tasks
        val schedSrc = File(context.filesDir, "agent_scheduler")
        if (schedSrc.exists()) {
            val schedDest = File(dest, "agent_scheduler")
            schedDest.mkdirs()
            schedSrc.listFiles()?.forEach { f -> tally(copyStoreFileOrSkip(f, File(schedDest, f.name)), "agent_scheduler/" + f.name) }
        }
        // Agent memory JSON
        tally(copyStoreFileOrSkip(File(context.filesDir, "agent_memory/memory.json"), File(dest, "agent_memory.json")), "agent_memory.json")
        val result = PrefsBackupResult(copied, skippedMissing, failed)
        if (failed.isEmpty()) {
            Log.d(TAG, "RG05 prefs backup ok (${result.summary()}) -> ${dest.absolutePath}")
        } else {
            // S01 discipline: real IO failures are NOT swallowed — logged at ERROR and
            // carried in the typed result so callers surface them. But a backup failure
            // NEVER kills the launch (this runs first in Application.onCreate).
            Log.e(TAG, "RG05 prefs backup FAILED for ${failed.size} file(s): ${failed.joinToString()} (other files: ${result.summary()})")
        }
        return result
    }

    /** RG05 hotfix (2026-09-27): honest per-file outcome of one store-file copy. */
    enum class CopyVerdict { COPIED, SKIPPED_MISSING, FAILED }

    /** RG05 hotfix (2026-09-27): typed, surfaced backup result (S01 — never silently lost). */
    data class PrefsBackupResult(val copied: Int, val skippedMissing: Int, val failedNames: List<String>) {
        val ok: Boolean get() = failedNames.isEmpty()
        fun summary(): String = "copied $copied, skipped $skippedMissing (not present), failed ${failedNames.size}"
    }

    /**
     * RG05 hotfix (2026-09-27): copies ONE prefs/JSON store file. A source that is not
     * present — or vanishes between the directory listing and the open (Firebase
     * heartbeat prefs are created and rewritten lazily by the Firebase process) — is
     * NOTHING-TO-BACK-UP, not a failure. Real IO failures (unwritable destination,
     * permissions, disk full) are logged at ERROR and returned as FAILED; they are
     * never thrown from here, because the callers run before the app has a UI.
     *
     * Crash context: File.copyTo THROWS FileNotFoundException when the source cannot
     * be opened; the old unguarded loop in backupPrefs let that exception escape into
     * Application.onCreate and killed the app on EVERY launch (crash loop) whenever the
     * heartbeat file had not been created yet.
     */
    private fun copyStoreFileOrSkip(src: File, dest: File): CopyVerdict {
        if (!src.exists()) return CopyVerdict.SKIPPED_MISSING
        return try {
            src.copyTo(dest, overwrite = true)
            CopyVerdict.COPIED
        } catch (e: java.io.FileNotFoundException) {
            if (!src.exists()) {
                // Vanished between listing and open (heartbeat rewrite) — skip it.
                CopyVerdict.SKIPPED_MISSING
            } else {
                // The FileNotFoundException points at the DESTINATION (or something
                // else genuinely broken) — a real failure, surfaced not swallowed.
                Log.e(TAG, "RG05 backup copy FAILED for ${src.name}: ${e.message}")
                CopyVerdict.FAILED
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "RG05 backup copy FAILED for ${src.name}: ${e.message}")
            CopyVerdict.FAILED
        }
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
        // Never reapply Keystore-bound ciphertext — old installs (and existing
        // SD-card backups made before this fix) contain a codespace_secure.xml that
        // poisons the fresh install. Skipping it here heals that class for good.
        prefsXmlFiles(src).filterNot { isKeystoreBoundFile(it.name) }
            .forEach { f -> if (applyPrefsXml(context, f)) applied += f.name }
        // Startup crash-safety (2026-10-01): EVERY file copy in the restore path is
        // guarded — an EACCES/IO failure on one file skips that file with an honest
        // ERROR log instead of aborting the restore (and, on the onAppStart path,
        // crashing the launch). Applies to Settings-tap restores too.
        var copyFailures = 0
        fun guardedCopy(from: File, to: File) {
            try {
                to.parentFile?.mkdirs()
                from.copyTo(to, overwrite = true)
            } catch (e: java.io.IOException) {
                copyFailures++
                Log.e(TAG, "RG06 restore copy FAILED for ${from.name} -> ${to.name}: ${e.message}")
            }
        }
        fun guardedCopyIntoDir(from: File, toDir: File) {
            try {
                toDir.mkdirs()
                from.copyTo(File(toDir, from.name), overwrite = true)
            } catch (e: java.io.IOException) {
                copyFailures++
                Log.e(TAG, "RG06 restore copy FAILED for ${from.name} -> ${toDir.name}: ${e.message}")
            }
        }
        listOf("settings.json", "ssh-profiles.json").forEach { name ->
            val f = File(src, name)
            if (f.exists()) guardedCopy(f, File(context.filesDir, name))
        }
        val schedSrc = File(src, "agent_scheduler")
        if (schedSrc.exists()) {
            schedSrc.listFiles()?.forEach { guardedCopyIntoDir(it, File(context.filesDir, "agent_scheduler")) }
        }
        val memSrc = File(src, "agent_memory.json")
        if (memSrc.exists()) {
            guardedCopy(memSrc, File(File(context.filesDir, "agent_memory"), "memory.json"))
        }
        if (copyFailures > 0) {
            NotificationStore.add(
                "Prefs restore incomplete",
                "$copyFailures backup file(s) could not be restored — check storage permission and free space, then restore again.",
                NotificationStore.Type.BACKUP
            )
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
