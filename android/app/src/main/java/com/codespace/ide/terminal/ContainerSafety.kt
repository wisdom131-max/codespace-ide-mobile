package com.codespace.ide.terminal

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * ContainerSafety — pure-JVM building blocks for the E17 restore round (advisor
 * items A-D, 2026-10-11). NO ANDROID IMPORTS: every function here is covered by
 * the CI JVM suite (BackupTarTest / ContainerSafetyTest), so the failure that
 * destroyed the 2026-10-11 restore attempt can never ship again untested.
 *
 * Root causes this file closes:
 *  A. Kotlin's File.deleteRecursively() walks with File.isDirectory/File.listFiles,
 *     which FOLLOW symlinks — a directory link (bin -> usr/bin, or an absolute
 *     link like /sdcard) is entered and everything on the TARGET side is deleted
 *     through it. Its walkBottomUp DirectoryState assertion ("rootDir must be
 *     verified to be directory beforehand") is also what CRASHED the 14:15:01
 *     restore retry: the stale temp dir contained both a symlink (bin) and its
 *     target subtree (usr), the bottom-up walk deleted the target first, and the
 *     later DirectoryState(link) require fired on the now-dangling link — an
 *     AssertionError (an Error, NOT an Exception) that sailed past the thread's
 *     catch(Exception) and killed UbuntuSetupThread mid-setup. Debug builds assert;
 *     release builds delete THROUGH the link instead. Both are gone now.
 *  B. A crash between the two swap renames leaves no rootfs but a staged .old
 *     and a full temp extraction — recoverHalfSwap repairs that deterministically.
 *  C. A restored container is only healthy if its KEY FILES exist, not merely
 *     usr/bin/bash — the 2026-10-11 launch of proot against a gutted rootfs
 *     (execve /usr/bin/env ENOSYS) came from a backup taken over a damaged
 *     container. healthOf() is the single gate for launching, backing up and
 *     relaunching.
 *  D. Backups must never be verified-less overwrites: verifyBackupArchive()
 *     re-reads the finished tarball (readable, entry count, critical files)
 *     before it is allowed to replace the previous one (kept as .prev).
 */
object ContainerSafety {

    // ────────────────────────────────────────────────────────────────────
    // A. Symlink-safe recursive delete
    // ────────────────────────────────────────────────────────────────────

    /**
     * Recursive delete that NEVER follows a symlink. walkFileTree with
     * NOFOLLOW_LINKS reports every link to visitFile (never preVisitDirectory),
     * so a directory link is deleted AS A LINK — nothing on its target side is
     * touched, whether the target is inside the tree (bin -> usr/bin) or
     * absolute (/sdcard, /storage, /proc). Per-entry failures are counted and
     * reported, never thrown: a stuck entry must not abort the cleanup.
     * A root that is a plain file or a link is deleted as-is — this is exactly
     * the case that crashed FileTreeWalk's assertion on 2026-10-11.
     */
    fun deleteTreeSafe(root: File): DeleteSummary {
        var deleted = 0
        var failed = 0
        val failures = ArrayList<String>()
        if (!root.exists() && !Files.isSymbolicLink(root.toPath())) return DeleteSummary(0, 0, emptyList())
        try {
            Files.walkFileTree(
                root.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // NOFOLLOW: a symlink arrives HERE — delete the link itself,
                        // never the target or anything under it.
                        return try {
                            Files.delete(file)
                            deleted++
                            FileVisitResult.CONTINUE
                        } catch (e: Exception) {
                            failed++
                            failures.add("${file.fileName}: ${e.message}")
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) {
                            failed++
                            failures.add("${dir.fileName}: $exc")
                        }
                        return try {
                            Files.delete(dir)
                            deleted++
                            FileVisitResult.CONTINUE
                        } catch (e: Exception) {
                            failed++
                            failures.add("${dir.fileName}: ${e.message}")
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        failed++
                        failures.add("${file.fileName}: ${exc.message}")
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (e: NoSuchFileException) {
            // vanished mid-walk — nothing left to delete
        } catch (e: Exception) {
            failed++
            failures.add("root: ${e.message}")
        }
        return DeleteSummary(deleted, failed, failures)
    }

    /** Result of a safe delete: counts + human-readable failure lines. */
    data class DeleteSummary(val deleted: Int, val failed: Int, val failures: List<String>) {
        val ok: Boolean get() = failed == 0
    }

    // ────────────────────────────────────────────────────────────────────
    // C. Container health — the single launch/backup gate
    // ────────────────────────────────────────────────────────────────────

    /**
     * A container is HEALTHY only when every CRITICAL path exists (bash, env —
     * the two binaries the proot launch chain execs on this device), and its
     * directory links are present in SOME form (symlink OR real directory — a
     * restored usrmerge tree may legitimately carry either). Optional tools are
     * reported, never fatal. The 2026-10-11 incident: restoreBackup's only gate
     * was usr/bin/bash; the container that shipped was missing usr/bin/env, and
     * proot died with execve ENOSYS — a health gate would have refused to
     * launch and offered Restore/Download instead.
     */
    fun healthOf(rootfs: File): HealthReport {
        val missing = ArrayList<String>()
        val lines = ArrayList<String>()
        fun check(relative: String, critical: Boolean) {
            val f = File(rootfs, relative)
            val present = f.exists()
            lines.add("$relative ${if (present) "present" else "MISSING"}")
            if (!present && critical) missing.add(relative)
        }
        check("usr/bin/bash", critical = true)
        check("usr/bin/env", critical = true)
        // Directory links: bin/lib/sbin/lib64 — present as LINK (preferred) or as a
        // real directory (accepted; the restore maps links that failed to create to
        // nothing, but a usrmerge container also works with real dirs).
        for (link in listOf("bin", "lib", "sbin", "lib64")) {
            val f = File(rootfs, link)
            val isLink = Files.isSymbolicLink(f.toPath())
            val isDir = f.isDirectory
            lines.add("$link ${if (isLink) "symlink ok" else if (isDir) "real directory" else "MISSING"}")
            if (!isLink && !isDir) missing.add(link)
        }
        check("usr/bin/node", critical = false)
        check("usr/bin/python3", critical = false)
        return HealthReport(ok = missing.isEmpty(), missingCritical = missing, lines = lines)
    }

    data class HealthReport(val ok: Boolean, val missingCritical: List<String>, val lines: List<String>) {
        val verdict: String
            get() = if (ok) "healthy" else "damaged (missing: ${missingCritical.joinToString(", ")})"
    }

    // ────────────────────────────────────────────────────────────────────
    // B. Half-finished swap recovery
    // ────────────────────────────────────────────────────────────────────

    /**
     * Deterministic repair of an interrupted atomic swap (crash between
     * rootfs -> .old and tmp -> rootfs). Rules, in order:
     *  - rootfs missing, tmp healthy      -> swap tmp in, delete .old (the
     *    extraction had just finished; its result is the newest good state).
     *  - rootfs missing, tmp UNHEALTHY   -> roll .old back, delete tmp (the
     *    extraction never completed; the previous container wins).
     *  - rootfs missing, no tmp           -> roll .old back.
     *  - rootfs present, tmp leftover     -> stale temp from an aborted run:
     *    safe-delete tmp.
     *  - rootfs present, .old leftover    -> swap completed; the old tree is
     *    garbage: safe-delete .old.
     * Returns a human-readable report of what was found and done.
     */
    fun recoverHalfSwap(rootfs: File, oldDir: File, tmp: File): String {
        val sb = StringBuilder()
        val rootExists = rootfs.exists()
        val oldExists = oldDir.exists()
        val tmpExists = tmp.exists()
        if (!rootExists) {
            when {
                tmpExists -> {
                    val tmpHealth = healthOf(tmp)
                    if (tmpHealth.ok) {
                        if (!oldExists || oldDir.renameTo(File(oldDir.parentFile, oldDir.name + ".trash"))) {
                            // stage .old aside so a tmp->rootfs failure can roll back
                        }
                        val staged = File(oldDir.parentFile, oldDir.name + ".trash")
                        if (tmp.renameTo(rootfs)) {
                            deleteTreeSafe(staged)
                            sb.append("half-swap repaired: completed temp extraction was swapped in (${tmpHealth.verdict}); staged old container discarded")
                        } else {
                            staged.renameTo(oldDir)
                            sb.append("half-swap repair FAILED: temp extraction could not be swapped in; old container kept, temp kept for retry")
                        }
                    } else {
                        if (oldExists) {
                            if (oldDir.renameTo(rootfs)) {
                                deleteTreeSafe(tmp)
                                sb.append("half-swap repaired: incomplete temp extraction discarded (${tmpHealth.verdict}); previous container rolled back")
                            } else {
                                sb.append("half-swap repair FAILED: could not roll the previous container back; nothing deleted — free space and reopen the terminal")
                            }
                        } else {
                            sb.append("half-swap found neither a container nor a usable extraction; temp kept at ${tmp.path} for manual recovery")
                        }
                    }
                }
                oldExists -> {
                    if (oldDir.renameTo(rootfs)) {
                        sb.append("half-swap repaired: previous container rolled back into place")
                    } else {
                        sb.append("half-swap repair FAILED: could not roll the previous container back; it is kept at ${oldDir.path}")
                    }
                }
                else -> sb.append("no container and no recovery state found (fresh install state)")
            }
        } else {
            if (tmpExists) {
                deleteTreeSafe(tmp)
                sb.append("stale restore temp dir discarded (container intact, untouched)")
            }
            if (oldExists) {
                deleteTreeSafe(oldDir)
                sb.append("staged old container discarded (swap had completed)")
            }
            if (!tmpExists && !oldExists) sb.append("container intact, no recovery state")
        }
        return sb.toString()
    }

    // ────────────────────────────────────────────────────────────────────
    // D. Backup verification before it is allowed to replace the previous one
    // ────────────────────────────────────────────────────────────────────

    /**
     * Verifies a finished container-backup tarball BEFORE it replaces the
     * previous backup: readable as gzip+tar, a sane minimum entry count, and
     * the critical launch files (usr/bin/bash, usr/bin/env) present among its
     * entries. This is what would have refused to overwrite the 435 MB good
     * backup with the 147 MB copy of a damaged container on 2026-10-11.
     */
    fun verifyBackupArchive(archive: File): BackupVerifyReport {
        return try {
            var entries = 0
            var hasBash = false
            var hasEnv = false
            GzipOpen.open(archive).use { raw ->
                TarArchiveInputStream(raw).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        entries++
                        val n = e.name.removePrefix("./")
                        if (n == "usr/bin/bash") hasBash = true
                        if (n == "usr/bin/env") hasEnv = true
                        e = tar.nextEntry
                    }
                }
            }
            if (entries < 50) {
                BackupVerifyReport(false, entries, hasBash, hasEnv, "only $entries entries — not a full container backup")
            } else if (!hasBash || !hasEnv) {
                BackupVerifyReport(
                    false, entries, hasBash, hasEnv,
                    "archive is missing " + listOfNotNull(
                        if (hasBash) null else "usr/bin/bash",
                        if (hasEnv) null else "usr/bin/env",
                    ).joinToString(" and ") + " — refusing to trust it",
                )
            } else {
                BackupVerifyReport(true, entries, hasBash, hasEnv, "verified: $entries entries, bash+env present")
            }
        } catch (e: Exception) {
            BackupVerifyReport(false, 0, false, false, "unreadable archive: ${e.message}")
        }
    }

    data class BackupVerifyReport(val ok: Boolean, val entries: Int, val hasBash: Boolean, val hasEnv: Boolean, val verdict: String)

    /** Shared gzip-open indirection (same open path as BackupTar). */
    private object GzipOpen {
        fun open(file: File): java.io.InputStream =
            java.io.BufferedInputStream(
                org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(file.inputStream()),
            )
    }
}

