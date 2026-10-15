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
        // Advisor item 2 (2026-10-11): SIZE is the primary identifier; the
        // date is labelled "modified" because the Files app resets it on copy —
        // it is never an integrity check.
        return "%.1f MB (modified %s)".format(mb, date)
    }

    fun deleteBackup(): Boolean = backupFile().let { !it.exists() || it.delete() }

    /** Advisor item 3 (2026-10-11): the previous generation, kept after every
     *  successful backup. Restoring it is an explicit owner choice — never a
     *  silent fallback. */
    fun previousBackupFile(): File = File(backupDir(), "$BACKUP_FILE.prev")

    fun previousBackupInfo(): String? {
        val f = previousBackupFile()
        if (!f.exists()) return null
        val mb = f.length() / (1024.0 * 1024.0)
        val date = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(f.lastModified()))
        return "%.1f MB (modified %s)".format(mb, date)
    }

    /** ADVISOR 2026-10-13 (item 4b): one-shot restore-source requests set by the
     *  restore row (system file picker / previous-generation button) and consumed
     *  by the install thread when it calls restoreBackup — the row itself never
     *  runs a restore outside that thread. */
    @Volatile var pickedSource: java.io.File? = null
    @Volatile var usePreviousOnNextRestore: Boolean = false
    fun consumePickedSource(): java.io.File? { val f = pickedSource; pickedSource = null; return f }
    fun consumeUsePrevious(): Boolean { val v = usePreviousOnNextRestore; usePreviousOnNextRestore = false; return v }

    /** Bug 4 (owner round 2026-10-14): a SAF-picked backup uri — restored from the
     *  STREAM, never a 200+ MB cache copy. One-shot like pickedSource. */
    @Volatile var pickedUri: android.net.Uri? = null
    fun consumePickedUri(): android.net.Uri? { val u = pickedUri; pickedUri = null; return u }

    /** Bug 2 (owner round 2026-10-14): the restore row's per-LAUNCH dismissal —
     *  process lifetime, NOT persisted (next launch shows the row again). */
    @Volatile var restoreRowDismissed: Boolean = false

    // ── Bug 1b (owner round 2026-10-14): SINGLE-FLIGHT restore lock ──────────
    // A restore replaces the rootfs; two at once, or a restore while a second
    // tab waits in the install flow, is how "second tab re-restores" destroyed
    // freshly-made changes. One restore at a time, process-wide.
    private val restoreLock = Any()
    @Volatile private var restoreRunning = false
    fun isRestoreRunning(): Boolean = restoreRunning
    fun awaitRestoreDone(timeoutMs: Long = 15 * 60 * 1000L) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (restoreRunning && android.os.SystemClock.elapsedRealtime() < deadline) {
            try { Thread.sleep(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
    }

    /** Bug 1a/d (owner round 2026-10-14): PURE tab-install decision — the single
     *  source of truth every pane consults. isInstalled = healthy rootfs + the
     *  version marker (written by install AND on restore success); a tab that
     *  opens while a restore runs WAITS and re-decides — it never starts its
     *  own restore. Pure JVM-testable (TabInstallDecisionTest). */
    enum class TabInstallAction { ATTACH_INSTALLED, WAIT_FOR_RESTORE, RESTORE_BACKUP, FRESH_INSTALL, ADOPT_LEGACY }
    fun decideTabInstall(
        hasBackup: Boolean,
        isInstalled: Boolean,
        restoreRunning: Boolean,
        legacyRootfs: Boolean = false,
    ): TabInstallAction {
        if (isInstalled) return TabInstallAction.ATTACH_INSTALLED
        if (restoreRunning) return TabInstallAction.WAIT_FOR_RESTORE
        // Advisor review of 3b34e27: a healthy rootfs with a missing/stale marker
        // (pre-marker-build container, or an app update) is ADOPTED — it takes
        // precedence over a restore: a working container is never silently
        // replaced, and no notice, restore or download ever runs for it.
        if (legacyRootfs) return TabInstallAction.ADOPT_LEGACY
        return if (hasBackup) TabInstallAction.RESTORE_BACKUP else TabInstallAction.FRESH_INSTALL
    }

    /** Bug 3 (owner round 2026-10-14): "Restore automatically" — default ON. */
    fun autoRestoreEnabled(context: Context): Boolean =
        context.getSharedPreferences("container_backup", Context.MODE_PRIVATE).getBoolean("auto_restore", true)
    fun setAutoRestore(context: Context, enabled: Boolean) {
        context.getSharedPreferences("container_backup", Context.MODE_PRIVATE).edit().putBoolean("auto_restore", enabled).apply()
    }

    /** Bug 4 (owner round 2026-10-14): the picked file's display name (null = unknown). */
    fun queryPickedName(context: Context, uri: android.net.Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) { null }

    /** Bug 4: the picked file's size in bytes, -1 = the provider does not report it. */
    fun queryPickedSize(context: Context, uri: android.net.Uri): Long = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }

    /** D follow-up (advisor 2026-10-11 item 3): typed createBackup outcome so the
     *  UI can implement the small-backup confirmation flow instead of guessing
     *  from progress strings. */
    enum class CreateBackupOutcome {
        SAVED,
        /** The new archive is much smaller than the existing backup — NOTHING was
         *  overwritten; the user must tap again to confirm (forceOverwriteSmaller). */
        SHRINK_CONFIRMATION_NEEDED,
        /** The container failed the health gate — backup refused, existing file untouched. */
        REFUSED_DAMAGED,
        REFUSED_EMPTY,
        FAILED_VERIFY,
        FAILED_MOVE,
        FAILED_STAGE_PREVIOUS,
    }

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
    fun createBackup(
        context: Context,
        onProgress: (String) -> Unit,
        forceOverwriteSmaller: Boolean = false,
    ): CreateBackupOutcome {
        val rootfs = ProotInstaller.rootfsDir(context)
        if (!rootfs.exists()) {
            onProgress("Nothing to back up — Ubuntu isn't installed yet.")
            return CreateBackupOutcome.REFUSED_EMPTY
        }
        // D (2026-10-11): NEVER back up a damaged container — the 147 MB backup
        // was written over a partially-broken rootfs and then overwrote the
        // good 435 MB backup in place. The same health gate that guards the
        // proot launch guards the backup writer.
        val health = ContainerSafety.healthOf(rootfs)
        if (!health.ok) {
            val msg = "Refusing to back up: the container is damaged (missing ${health.missingCritical.joinToString(", ")}). Restore it first — the existing backup was NOT touched."
            onProgress("\u2717 $msg")
            com.codespace.ide.diagnostics.AppOutputLog.log("[BACKUP-DIAG] $msg", "terminal")
            return CreateBackupOutcome.REFUSED_DAMAGED
        }
        backupDir().mkdirs()
        val tmp = File(backupDir(), "$BACKUP_FILE.tmp")
        ContainerSafety.deleteTreeSafe(tmp)

        var filesWritten = 0
        GzipCompressorOutputStream(tmp.outputStream()).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                // E17 writer fix (2026-10-11): shares the EXACT writer with the JVM
                // suite (BackupTar.writeRootfs). walkTopDown follows directory
                // symlinks (bin duplicates on restore), and its onEnter hook DROPS
                // the symlinked directories entirely — how a new backup could
                // silently lose bin/lib/sbin/lib64. BackupTar's recursive visit
                // emits every symlink and never descends through one.
                filesWritten = BackupTar.writeRootfs(rootfs, tar, GUEST_SECRET_PATHS) { msg ->
                    onProgress(msg)
                }
            }
        }
        // D (2026-10-11): a backup file is NEVER overwritten in place. The
        // finished tarball is VERIFIED (readable, sane entry count, bash+env
        // present) before the swap; the previous backup is kept aside as
        // container-backup.prev until the new one has proven itself.
        if (filesWritten <= 0) {
            tmp.delete()
            onProgress("\u2717 Backup failed: no files were written. The existing backup was NOT touched.")
            return CreateBackupOutcome.REFUSED_EMPTY
        }
        val verify = ContainerSafety.verifyBackupArchive(tmp)
        if (!verify.ok) {
            tmp.delete()
            val msg = "Backup NOT saved: the written archive failed verification (${verify.verdict}). The existing backup was NOT touched."
            onProgress("\u2717 $msg")
            com.codespace.ide.diagnostics.AppOutputLog.log("[BACKUP-DIAG] $msg", "terminal")
            return CreateBackupOutcome.FAILED_VERIFY
        }
        val target = backupFile()
        val prev = File(backupDir(), "$BACKUP_FILE.prev")
        // Advisor item 3 (2026-10-11): a new archive much smaller than the one it
        // would replace is the signature of backing up a gutted container (the
        // 435 MB -> 147 MB overwrite). Refuse the swap once; the caller re-invokes
        // with forceOverwriteSmaller after an explicit confirmation.
        if (target.exists() && !forceOverwriteSmaller &&
            tmp.length() * 10 < target.length() * 6
        ) {
            val newMb = tmp.length() / (1024 * 1024)
            val oldMb = target.length() / (1024 * 1024)
            tmp.delete()
            val msg = "Backup NOT saved yet: the new archive would be $newMb MB, much smaller than the current backup ($oldMb MB). " +
                "This can mean the container lost files. Tap \"Back up now\" again to confirm replacing it — the current file will be kept as container-backup.prev either way."
            onProgress("\u2717 $msg")
            com.codespace.ide.diagnostics.AppOutputLog.log("[BACKUP-DIAG] shrink confirmation: new=$newMb MB vs current=$oldMb MB — nothing overwritten", "terminal")
            return CreateBackupOutcome.SHRINK_CONFIRMATION_NEEDED
        }
        if (target.exists()) {
            prev.delete()
            if (!target.renameTo(prev)) {
                tmp.delete()
                onProgress("\u2717 Backup NOT saved: the previous backup could not be kept aside. Nothing was overwritten.")
                return CreateBackupOutcome.FAILED_STAGE_PREVIOUS
            }
        }
        if (!tmp.renameTo(target)) {
            prev.renameTo(target)  // roll back
            onProgress("\u2717 Backup NOT saved: the new archive could not be moved into place. The previous backup was kept.")
            return CreateBackupOutcome.FAILED_MOVE
        }
        onProgress("\u2713 Backup complete: $filesWritten files, ${target.length() / (1024 * 1024)} MB \u2192 ${target.path} (previous kept as ${prev.name})")
        NotificationStore.add("Backup complete", "$filesWritten files saved to ${target.name}", NotificationStore.Type.BACKUP)
        return CreateBackupOutcome.SAVED
    }

    /**
     * RG02 (P3a): typed rootfs-restore result. ok=false means the LIVE
     * container was NOT touched (extraction failed into the temp dir first
     * and was discarded) or the backup file was missing/unreadable.
     * symlinkWarnings counts best-effort symlink entries that could not be
     * created (device kernels block symlink syscalls — logged, not fatal,
     * same tolerance as the pre-P3a code but now VISIBLE in the report).
     */
    /** ADVISOR 2026-10-13 (items 2-3): typed restore verdict so the UI can act
     *  without parsing message strings. */
    enum class RestoreVerdict {
        /** Swap happened, smoke/health verified — safe to launch. */
        OK_HEALTHY,
        /** Swap happened; the smoke test could not RUN on this host (e.g. JVM
         *  unit tests, missing proot) — health check alone passed. */
        OK_UNVERIFIED,
        /** Nothing-to-lose restore: installed anyway, smoke test FAILED — the
         *  caller must offer Download fresh and NOT launch. */
        OK_SMOKE_FAILED,
        /** Gate refused while a VALID container is installed (protect the good one). */
        REFUSED_GATE_LIVE_VALID,
        /** Gate failed with NO valid container installed: the caller offers
         *  "Restore anyway" vs "Download fresh" (nothing to lose). */
        NEEDS_RESTORE_ANYWAY,
        /** Bug 1c: a healthy container is installed and this restore was not an
         *  explicit owner choice (Settings > Container Backup confirm) — refused. */
        REFUSED_INSTALLED,
        /** Bug 1b: another restore is already running — this call was refused. */
        ALREADY_RUNNING,
        FAILED,
    }

    data class RestoreResult(
        val filesWritten: Int,
        val fileFailures: Int,
        val symlinkWarnings: Int,
        val ok: Boolean,
        val message: String,
        /** ADVISOR 2026-10-13: verdict; keep [ok]/[healthy] consistent with it. */
        val verdict: RestoreVerdict = RestoreVerdict.FAILED,
        /** C (2026-10-11): ok = the swap happened; healthy = the restored
         *  container passes the full key-file health check (bash AND env AND
         *  the directory links). A restore can be ok=true healthy=false — that
         *  is the "backup of a damaged container" case, and the caller must
         *  NOT launch proot into it. */
        val healthy: Boolean = true,
        /** E17 grant gate: true when the ONLY reason for failure is the missing
         *  All-files-access grant. Callers use it to wait/retry instead of falling
         *  straight through to a fresh install. */
        val needsGrant: Boolean = false,
    )

    /** E17 (2026-10-07, advisor-approved): does THIS install hold the All-files-access
     *  grant? A full uninstall/reinstall (or Android's unused-app auto-revoke)
     *  removes the grant; without it the FUSE layer reports exists()==true while
     *  open() fails EACCES — so a backup "restore" attempt silently degrades into
     *  a fresh ~250MB download. Pre-R devices have legacy storage and always pass. */
    fun hasAllFilesAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

    /** E17: bounded wait for the user to grant All files access (the Settings screen
     *  MainActivity opened at launch — this never re-fires the intent, so no screen
     *  bounce). Polls every [pollMs] up to [timeoutMs]; returns as soon as granted.
     *  False = user-denied/timeout — callers proceed down their defined fallback. */
    fun awaitAllFilesGrant(timeoutMs: Long = 90_000L, pollMs: Long = 3_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasAllFilesAccess()) return true
            try { Thread.sleep(pollMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        return hasAllFilesAccess()
    }

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
    fun restoreBackup(
        context: Context,
        onProgress: (String) -> Unit,
        // Advisor item 3 (2026-10-11): restore reads container-backup.tar.gz ONLY;
        // the previous generation (.prev) is a separate, clearly-labeled owner
        // choice — never a silent fallback.
        usePrevious: Boolean = false,
        // ADVISOR 2026-10-13 (item 4b): a file picked via the system picker (a
        // copy in cacheDir) — restores from it WITHOUT touching the shared-storage
        // backup. Null = the standard files.
        sourceFile: File? = null,
        // ADVISOR 2026-10-13 (item 3): the "Restore anyway" confirmation for the
        // nothing-to-lose path (gate failed, no valid container installed).
        restoreAnyway: Boolean = false,
        // Bug 4 (owner round 2026-10-14): restore straight from a SAF-picked uri —
        // NO 200+ MB cache copy; validated by CONTENT (gzip magic), never by
        // name or MIME; free space checked from the reported size.
        sourceUri: android.net.Uri? = null,
        // Bug 1c (owner round 2026-10-14): TRUE only for the explicit Settings >
        // Container Backup confirm — the ONE path allowed to replace a healthy
        // installed container. Every other call is refused while one is installed.
        explicit: Boolean = false,
    ): RestoreResult {
        // Bug 1b: SINGLE-FLIGHT — one restore at a time, process-wide. A second
        // restore (another tab racing through the install flow) replaces the
        // rootfs under the first one's feet: changes lost, shells broken.
        synchronized(restoreLock) {
            if (restoreRunning) {
                val msg = "A restore is already running in another tab — this one was refused, nothing was changed."
                onProgress("✗ $msg")
                return RestoreResult(0, 0, 0, false, msg, verdict = RestoreVerdict.ALREADY_RUNNING)
            }
            restoreRunning = true
        }
        try {
            return restoreBackupInner(context, onProgress, usePrevious, sourceFile, restoreAnyway, sourceUri, explicit)
        } finally {
            restoreRunning = false
        }
    }

    private fun restoreBackupInner(
        context: Context,
        onProgress: (String) -> Unit,
        usePrevious: Boolean = false,
        sourceFile: File? = null,
        restoreAnyway: Boolean = false,
        sourceUri: android.net.Uri? = null,
        explicit: Boolean = false,
    ): RestoreResult {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        // E17 grant gate (2026-10-07): the backup tarball lives in shared storage;
        // without the All-files-access grant FUSE lets exists() pass and open()
        // fail EACCES mid-restore. Refuse UP FRONT with a typed result so callers
        // can wait for the grant and retry instead of burning the attempt.
        if (sourceUri == null && !hasAllFilesAccess()) {
            val msg = "All files access not granted \u2014 the backup lives in shared storage and cannot be read without it. Grant \"All files access\" (the Settings screen the app opened at launch) and restore again."
            Log.w(TAG, "[RESTORE-DIAG] E17 gate: restore refused, All files access not granted")
            com.codespace.ide.diagnostics.AppOutputLog.log("[RESTORE-DIAG] E17 gate: restore refused, All files access not granted", "terminal")
            onProgress("Restore needs the \"All files access\" permission.")
            return RestoreResult(0, 0, 0, false, msg, needsGrant = true)
        }
        // Bug 1c (owner round 2026-10-14): a restore NEVER runs over a healthy
        // installed container unless the owner chose it explicitly in Settings >
        // Container Backup (a confirm that says what will be lost and to close
        // running terminals first). Nothing was changed on refusal.
        if (!explicit && !restoreAnyway && ProotInstaller.isInstalled(context)) {
            val msg = "A healthy Ubuntu container is already installed — restoring is only offered in Settings > Container Backup, where the confirmation says what will be lost. Nothing was changed."
            onProgress("✗ $msg")
            return RestoreResult(0, 0, 0, false, msg, verdict = RestoreVerdict.REFUSED_INSTALLED)
        }
        val f: File? = if (sourceUri != null) null else (sourceFile ?: if (usePrevious) File(backupDir(), "$BACKUP_FILE.prev") else backupFile())
        if (f != null && !f.exists()) {
            onProgress("No backup found at ${f.path}")
            return RestoreResult(0, 0, 0, false, "No backup found at ${f.path}")
        }
        // Bug 4 (owner round 2026-10-14): the archive opener + identity. A uri
        // source streams from the picker (no copy) and is validated by CONTENT —
        // the first two bytes must be the gzip magic (1f 8b); the detected MIME
        // and size are REPORTED, never used as the decision.
        val fLabel: String
        val fBytes: Long
        val opener: () -> java.io.InputStream
        if (sourceUri != null) {
            val nm = queryPickedName(context, sourceUri) ?: "picked file"
            val bytes = queryPickedSize(context, sourceUri)
            val head = try {
                context.contentResolver.openInputStream(sourceUri)?.use { s ->
                    val b = ByteArray(2)
                    var off = 0
                    while (off < 2) { val r = s.read(b, off, 2 - off); if (r < 0) break; off += r }
                    if (off < 2) throw java.io.IOException("the file is too short to be a backup archive")
                    b
                } ?: throw java.io.IOException("cannot open the picked file")
            } catch (e: Exception) {
                val msg = "Could not read the picked file: ${e.message}"
                onProgress("✗ $msg")
                return RestoreResult(0, 0, 0, false, msg)
            }
            if (head[0] != 0x1F.toByte() || head[1] != 0x8B.toByte()) {
                val mime = try { context.contentResolver.getType(sourceUri) ?: "unknown" } catch (_: Exception) { "unknown" }
                val sizeText = if (bytes >= 0) "$bytes bytes" else "unknown size"
                val msg = "This is not a container backup — it is not a gzip archive. Detected: MIME type \"$mime\", $sizeText, name \"$nm\". Expected container-backup.tar.gz (a .tar.gz archive)."
                onProgress("✗ $msg")
                return RestoreResult(0, 0, 0, false, msg)
            }
            fLabel = nm
            fBytes = bytes
            opener = { context.contentResolver.openInputStream(sourceUri) ?: throw java.io.IOException("cannot open the picked file") }
        } else {
            val ff = f!!
            fLabel = ff.name
            fBytes = ff.length()
            opener = { java.io.FileInputStream(ff) }
        }
        val rootfs = ProotInstaller.rootfsDir(context)
        // Advisor item 3 (2026-10-11): EXACT file identity at restore start —
        // path, size in BYTES (the primary identifier), modified time (labelled
        // as modified; Android's Files app can reset it on copy, so it is NEVER
        // treated as an integrity check).
        val fPath = f?.path ?: sourceUri.toString()
        val fModified = if (f != null) java.text.SimpleDateFormat("MMM d, yyyy HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(f.lastModified())) else "unknown (picked file)"
        onProgress("Restoring from: $fLabel")
        onProgress("  Size: ${if (fBytes >= 0) "${fBytes} bytes (${fBytes / (1024 * 1024)} MB)" else "unknown"} — modified $fModified")
        com.codespace.ide.diagnostics.AppOutputLog.log(
            "[RESTORE-DIAG] file identity: path=$fPath bytes=$fBytes modified=$fModified usePrevious=$usePrevious",
            "terminal",
        )
        if (fBytes >= 0) onProgress("Restoring container from backup (${fBytes / (1024 * 1024)} MB)...")
        else onProgress("Restoring container from the picked file (size not reported)...")
        com.codespace.ide.diagnostics.AppOutputLog.log(
            "[RESTORE-DIAG] restore start: backupBytes=$fBytes rootfsExists=${rootfs.exists()}",
            "terminal",
        )
        // S1-b RESTORE-DIAG (2026-10-04, owner finding A): the full-uninstall restore
        // failure had no captured signature. Space is the prime suspect — the RG02
        // atomic swap needs room for the tmp extraction AND the staged old rootfs at the
        // same time, and a device that just wiped app data may be near its storage limit.
        // The probe is a WARNING, never a refusal: the typed failure paths below are the
        // actual verdict, and this line explains them when they fire.
        runCatching {
            val usableMb = (rootfs.parentFile?.usableSpace ?: 0L) / (1024 * 1024)
            val diag = "[RESTORE-DIAG] restore start: backupMb=${if (fBytes >= 0) fBytes / (1024 * 1024) else -1} usableMb=$usableMb rootfsExists=${rootfs.exists()}"
            Log.d(TAG, diag)
            com.codespace.ide.diagnostics.AppOutputLog.log(diag, "terminal")
        }.also {
            // Advisor item 7 (2026-10-11): a REAL refusal with plain words, not a
            // logcat warning. The restore needs room for the decompressed
            // extraction (~3x the tarball is the safe envelope on this device's
            // FUSE storage) PLUS the staged old container until the swap lands.
            val usableMb = (ProotInstaller.rootfsDir(context).parentFile?.usableSpace ?: 0L) / (1024 * 1024)
            val neededMb = if (fBytes >= 0) (fBytes / (1024 * 1024)) * 3 else -1
            if (neededMb >= 0 && usableMb < neededMb) {
                val msg = "Restore needs about $neededMb MB of free space, but this device has only $usableMb MB. " +
                    "Free some space (old downloads, unused apps) and try again — nothing was changed and the backup is untouched."
                onProgress("\u2717 $msg")
                com.codespace.ide.diagnostics.AppOutputLog.log("[RESTORE-DIAG] space refusal: needs~${neededMb}MB, usable=${usableMb}MB", "terminal")
                return RestoreResult(0, 0, 0, false, msg)
            }
        }
        // RG02: extract to a SIBLING temp dir (same filesystem → atomic renames).
        val tmp = File(rootfs.parentFile, "rootfs.restore.tmp")
        val oldDir = File(rootfs.parentFile, "rootfs.restore.old")
        // 2026-10-11 advisor A+B: recover any half-finished swap FIRST (crash
        // between the two renames), and clear stale temp state with the
        // SYMLINK-SAFE delete. The old tmp.deleteRecursively() crashed with
        // FileTreeWalk's AssertionError on a stale temp dir that contained both
        // the bin link and its target subtree (bottom-up walk deleted the target
        // first, then required the now-dangling link to be a directory) — an Error
        // that sailed past catch(Exception) and killed the setup thread. The
        // stale-temp delete also DELETED THROUGH directory symlinks.
        val recovery = ContainerSafety.recoverHalfSwap(rootfs, oldDir, tmp)
        com.codespace.ide.diagnostics.AppOutputLog.log("[RESTORE-DIAG] half-swap recovery: $recovery", "terminal")
        Log.d(TAG, "[RESTORE-DIAG] half-swap recovery: $recovery")
        onProgress(recovery)
        val staleDelete = ContainerSafety.deleteTreeSafe(tmp)
        if (!staleDelete.ok) {
            Log.w(TAG, "[RESTORE-DIAG] stale temp cleanup: ${staleDelete.failed} entries could not be deleted")
        }
        if (!tmp.exists() && !tmp.mkdirs()) {
            return RestoreResult(0, 0, 0, false, "Could not create restore temp dir ${tmp.path}")
        }

        // E17 device fix (2026-10-11): two-pass extraction through BackupTar —
        // scan (totals + symlink map) then extract (dirs/files, symlinks
        // DEFERRED last, traversal entries resolved, duplicates skipped and
        // counted). The owner's 435 MB backup (old writer, entries under bin
        // entries) is accepted by design.
        // Advisor review of 3b34e27 (gap 4): a PICKED STREAM (cloud/Drive file,
        // flaky connection) can DIE mid-read. Both passes open a FRESH stream
        // (the opener is re-invoked), so a cloud source is read twice — by design,
        // no local copy is made. An interruption here is TYPED (never an uncaught
        // exception in the install thread): the partial temp dir is cleaned up,
        // the live container was never touched (the swap is still ahead), and
        // the pane's failure dialog waits for the owner's tap — NO auto-retry.
        val scan: BackupTar.ScanResult
        val ext: BackupTar.ExtractResult
        try {
            scan = BackupTar.scanStream(opener())
            ext = BackupTar.extractStream(opener(), tmp, scan) { msg -> onProgress(msg) }
        } catch (e: Exception) {
            ContainerSafety.deleteTreeSafe(tmp)
            val msg = "Restore FAILED: the backup could not be read to the end (${e.message}). " +
                "For a cloud or Drive file this is usually a dropped connection mid-stream; the file may be large or the connection slow. " +
                "The current container was NOT modified; the backup file is untouched. Tap Try restore again to retry from the start."
            onProgress("\u2717 $msg")
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[RESTORE-DIAG] stream read failed: ${e.javaClass.simpleName}: ${e.message} [elapsed=${elapsed / 1000}s]",
                "terminal",
            )
            Log.w(TAG, "[RESTORE-DIAG] stream read failed", e)
            return RestoreResult(0, 0, 0, false, msg, verdict = RestoreVerdict.FAILED)
        }
        val filesWritten = ext.filesWritten
        val fileFailures = ext.fileFailures
        val symlinkWarnings = ext.symlinkWarnings

        // RG02: any hard file failure ABORTS — the temp extraction is discarded
        // and the LIVE container is untouched (the old code destroyed the live
        // rootfs first and reported success over a half-extracted container).
        if (fileFailures > 0 || !ext.bashOk) {
            ContainerSafety.deleteTreeSafe(tmp)
            val reason = if (fileFailures > 0) {
                "$fileFailures of ${ext.totalEntries} entries could not be written " +
                    "(${ext.processed} processed, ${ext.duplicatesSkipped} duplicates skipped)." +
                    (ext.firstFailure?.let { " First failure: $it." } ?: "")
            } else {
                "the archive extracted without file errors but is missing usr/bin/bash — not a working container."
            }
            val msg = "Restore FAILED: $reason The current container was NOT modified; the backup file is untouched."
            onProgress("\u2717 $msg")
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[RESTORE-DIAG] $msg [processed=${ext.processed} of ${ext.totalEntries} entries, duplicates skipped=${ext.duplicatesSkipped}, elapsed=${elapsed / 1000}s]",
                "terminal",
            )
            return RestoreResult(filesWritten, fileFailures, symlinkWarnings, false, msg)
        }

        // ADVISOR 2026-10-13 (item 2a): manifest check from the archive itself —
        // every entry accounted for, symlink/dir counts sane. Runs before the
        // swap; a phantom or dropped entry aborts with the LIVE container and
        // the backup both untouched.
        val manifest = ContainerSafety.manifestCheck(scan, ext)
        com.codespace.ide.diagnostics.AppOutputLog.log("[RESTORE-DIAG] ${manifest.detail}", "terminal")
        if (!manifest.ok) {
            ContainerSafety.deleteTreeSafe(tmp)
            val msg = "Restore FAILED: the backup's manifest does not add up. The current container was NOT modified; the backup file is untouched."
            onProgress("\u2717 ${manifest.detail}")
            onProgress("\u2717 $msg")
            return RestoreResult(filesWritten, 0, symlinkWarnings, false, msg, verdict = RestoreVerdict.FAILED)
        }

        // ADVISOR 2026-10-13 (item 2b/2c): the gate = critical health (bash+env
        // only, arm64-accurate — no lib64 requirement) PLUS a functional smoke
        // test with the SAME proot the app uses, both BEFORE the swap.
        val health = ContainerSafety.healthOf(tmp)
        com.codespace.ide.diagnostics.AppOutputLog.log(
            "[RESTORE-DIAG] pre-swap health: ${health.lines.joinToString("; ")}\n[RESTORE-DIAG] verdict: ${health.verdict}",
            "terminal",
        )
        val smoke = ContainerSafety.smokeTest(context, tmp)
        com.codespace.ide.diagnostics.AppOutputLog.log("[RESTORE-DIAG] smoke: ran=${smoke.ran} ok=${smoke.ok} — ${smoke.detail}", "terminal")
        val gateOk = health.ok && (!smoke.ran || smoke.ok)
        if (!gateOk) {
            val gateDetail = health.missingCritical.joinToString(", ") +
                (if (health.warnings.isNotEmpty()) "; warnings: ${health.warnings.joinToString("; ")}" else "") +
                "; smoke: ${smoke.detail}"
            val liveValid = rootfs.exists() && ContainerSafety.healthOf(rootfs).ok
            if (liveValid && !restoreAnyway) {
                // A working container is installed: NEVER overwrite it with a gate-failing backup.
                ContainerSafety.deleteTreeSafe(tmp)
                val msg = "Restore REFUSED: the backup fails the verification gate ($gateDetail). " +
                    "Your current Ubuntu container works, so it was NOT overwritten. The backup file is untouched."
                onProgress("\u2717 $msg")
                return RestoreResult(filesWritten, 0, symlinkWarnings, false, msg, verdict = RestoreVerdict.REFUSED_GATE_LIVE_VALID)
            }
            if (!liveValid && !restoreAnyway) {
                // ADVISOR 2026-10-13 (item 3): NOTHING TO LOSE — no valid container
                // is installed, so a dead-end refusal helps nobody. Typed result:
                // the UI offers "Restore anyway" vs "Download fresh".
                ContainerSafety.deleteTreeSafe(tmp)
                val msg = "The backup failed the verification gate ($gateDetail), but no working Ubuntu is installed either — " +
                    "there is nothing to lose. You can install this backup anyway, or download a fresh Ubuntu (~58 MB). The backup file is untouched."
                onProgress("\u2717 $msg")
                return RestoreResult(0, 0, symlinkWarnings, false, msg, verdict = RestoreVerdict.NEEDS_RESTORE_ANYWAY)
            }
            // restoreAnyway=true (nothing-to-lose path): install the backup
            // regardless; the final verdict below reflects the smoke outcome.
            onProgress("! You chose Restore anyway (no working container to protect): installing the backup regardless.")
        }
        // RG02: atomic swap — live rootfs only disappears once the full
        // replacement exists. If any rename fails, roll back so the live
        // container is not left deleted.
        if (rootfs.exists()) {
            val oldDir = File(rootfs.parentFile, "rootfs.restore.old")
            ContainerSafety.deleteTreeSafe(oldDir)
            if (!rootfs.renameTo(oldDir)) {
                ContainerSafety.deleteTreeSafe(tmp)
                val msg = "Restore FAILED: could not stage the old container for replacement. Nothing was modified."
                onProgress("\u2717 $msg")
                return RestoreResult(filesWritten, 0, symlinkWarnings, false, msg)
            }
            if (!tmp.renameTo(rootfs)) {
                oldDir.renameTo(rootfs)  // roll back
                ContainerSafety.deleteTreeSafe(tmp)
                val msg = "Restore FAILED: could not swap in the restored container. The original container was kept."
                onProgress("\u2717 $msg")
                return RestoreResult(filesWritten, 0, symlinkWarnings, false, msg)
            }
            ContainerSafety.deleteTreeSafe(oldDir)
        } else {
            if (!tmp.renameTo(rootfs)) {
                ContainerSafety.deleteTreeSafe(tmp)
                val msg = "Restore FAILED: could not place the restored container at ${rootfs.path}"
                onProgress("\u2717 $msg")
                return RestoreResult(filesWritten, 0, symlinkWarnings, false, msg)
            }
        }

        // Bug 1a (owner round 2026-10-14): THE install marker — the same one
        // install() writes, from the ONE shared writer. Without it isInstalled()
        // stays false forever after a restore and every new tab re-restores
        // (rootfs replaced: changes lost, running shells broken).
        ProotInstaller.writeVersionMarker(context)
        com.codespace.ide.diagnostics.AppOutputLog.log(
            "[RESTORE-DIAG] install marker written on restore success — isInstalled() is true from now on",
            "terminal",
        )
        val warnNote = if (symlinkWarnings > 0) " ($symlinkWarnings symlinks skipped — see logcat)" else ""
        val dupNote = if (ext.duplicatesSkipped > 0) ", ${ext.duplicatesSkipped} duplicate entries skipped" else ""
        val verifyNote = " — key files: ${ext.verifyLines.joinToString(", ")}"
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        // C (2026-10-11): the health verdict travels with the typed result (the
        // gate itself ran pre-swap above; tmp was moved verbatim into rootfs, so
        // the pre-swap report IS the final state).
        val healthNote = if (health.ok) {
            " — container health: healthy"
        } else {
            " — container health: DAMAGED (missing: ${health.missingCritical.joinToString(", ")})"
        }
        com.codespace.ide.diagnostics.AppOutputLog.log(
            "[RESTORE-DIAG] restore end: ok=true healthy=${health.ok} files=$filesWritten " +
                "processed=${ext.processed} of ${ext.totalEntries} duplicatesSkipped=${ext.duplicatesSkipped} " +
                "symlinkWarnings=$symlinkWarnings elapsed=${elapsed / 1000}s\n" +
                "[RESTORE-DIAG] container health: ${health.lines.joinToString("; ")}\n" +
                "[RESTORE-DIAG] final state: ${health.verdict}",
            "terminal",
        )
        // Advisor item 4 (2026-10-11): the final verdict in PLAIN WORDS in the
        // terminal itself (every onProgress line lands there), not just Output.
        onProgress("\u2713 Restore complete: $filesWritten files$dupNote$warnNote$verifyNote$healthNote")
        // Full plain summary: entries, duplicates, links, health, next steps.
        onProgress("Summary:")
        onProgress("  Files restored: $filesWritten (of ${ext.totalEntries} entries in the backup; ${ext.duplicatesSkipped} duplicate entries skipped)")
        onProgress("  Symlinks created: ${ext.symlinksCreated} (warnings: $symlinkWarnings)")
        onProgress("  Health check: bash ${if (File(rootfs, "usr/bin/bash").exists()) "PRESENT" else "MISSING"}; " +
            "env ${if (File(rootfs, "usr/bin/env").exists()) "PRESENT" else "MISSING"}; " +
            "node ${if (File(rootfs, "usr/bin/node").exists()) "PRESENT" else "not found"}; " +
            "python3 ${if (File(rootfs, "usr/bin/python3").exists()) "PRESENT" else "not found"}")
        onProgress("  Time taken: ${elapsed / 1000}s")
        if (symlinkWarnings == 0) {
            onProgress("Your Ubuntu container is restored and usable. Projects and tools are back; nothing else to do.")
        } else {
            onProgress("Container restored, but $symlinkWarnings symlink(s) could not be created — some tools may misbehave; report this if you see errors.")
        }
        NotificationStore.add("Restore complete", "$filesWritten files restored from backup$dupNote$warnNote", NotificationStore.Type.BACKUP)
        val verdict = when {
            gateOk && smoke.ran && smoke.ok -> RestoreVerdict.OK_HEALTHY
            !smoke.ran -> RestoreVerdict.OK_UNVERIFIED
            else -> RestoreVerdict.OK_SMOKE_FAILED
        }
        val smokeNote = when (verdict) {
            RestoreVerdict.OK_HEALTHY -> "\u2713 Smoke test: proot ran 'env true' and 'bash -c echo ok' — the container REALLY works."
            RestoreVerdict.OK_UNVERIFIED -> "Smoke test could not run on this host — the health check alone passed."
            RestoreVerdict.OK_SMOKE_FAILED -> "\u2717 Smoke test FAILED after installing anyway: the container is not usable. A fresh Ubuntu download is recommended."
            else -> ""
        }
        onProgress(smokeNote)
        return RestoreResult(
            filesWritten, 0, symlinkWarnings, true,
            "Restored $filesWritten files$dupNote$warnNote$verifyNote$healthNote",
            verdict = verdict,
            healthy = verdict != RestoreVerdict.OK_SMOKE_FAILED,
        )
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
        // NOTE (E17): local renamed so it never shadows the member fun hasAllFilesAccess().
        val grantHeld =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        if (!grantHeld) {
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
            // SAFETY GATE (2026-10-11, advisor E17 item 0): a fresh install that
            // has not restored its container yet must NEVER rewrite prefs-backup —
            // if live prefs somehow gained data before the restore landed (a
            // project picked, a prefs write), the else-branch would back the
            // FRESH-INSTALL prefs over the good backup. No container = backup
            // writes skipped; the next start after a successful restore resumes.
            val containerReady = runCatching { ProotInstaller.isInstalled(context) }.getOrDefault(false)
            if (!containerReady) {
                Log.w(TAG, "RG05 onAppStart: container not installed (fresh install / restore pending) — prefs-backup refresh SKIPPED to protect the backup")
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
