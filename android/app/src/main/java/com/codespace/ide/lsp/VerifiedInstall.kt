package com.codespace.ide.lsp

import android.content.Context
import com.codespace.ide.diagnostics.AppOutputLog
import java.io.File

/**
 * BATCH6-P3 (advisor D, 2026-10-17): one shared "verified install" core for
 * downloaded tool installs (jdtls first; the same gate serves every config
 * that declares a manifestDir).
 *
 * Owner evidence (build 2c9e9a9 device log): one 300s clock covered apt (77s)
 * AND the jdtls download (silently to the limit); a retry mixed a resumed
 * partial from one mirror with another ("tar: corrupted data / short read"),
 * nothing checked or repaired it, and rm -rf /opt/jdtls ran BEFORE success —
 * a failed retry could delete a working install. The startup check passed on
 * file presence alone.
 *
 * This object holds the decision core (pure — JVM-tested):
 *  - MANIFEST: after a VERIFIED install, every file path + size under the
 *    tool's rootfs dir is snapshotted (host-side walk — no guest I/O).
 *  - GATE: the cheap health check compares the tree against the manifest.
 *    A mismatch means corruption/loss -> repair, bounded by a daily cap with
 *    backoff so a hostile disk can never loop 30-minute installs.
 *  - SNAPSHOT POLICY (advisor D8, REPORTED — backup code untouched): the
 *    lsp-opt snapshot must only snapshot an install that passed THIS manifest
 *    gate; the change is proposed, not wired.
 */
object VerifiedInstall {

    const val DAILY_ATTEMPT_CAP = 3
    const val BACKOFF_BASE_MS = 15L * 60_000L
    const val BACKOFF_MAX_MS = 4L * 60 * 60_000L
    const val ATTEMPT_WINDOW_MS = 24L * 60 * 60_000L

    data class FileMeta(val path: String, val size: Long)

    enum class Gate { PASS, MISMATCH, REPAIR_CAPPED, NOT_TRACKED }

    // ── manifest format + compare (pure) ─────────────────────────────────────

    fun buildManifest(files: List<FileMeta>): String =
        files.sortedBy { it.path }
            .joinToString("\n") { it.path + " " + it.size }

    fun manifestMatches(stored: String, files: List<FileMeta>): Boolean {
        if (stored.isBlank()) return false
        return buildManifest(files) == stored.trim()
    }

    // ── attempt budget + backoff (pure) ──────────────────────────────────────

    /** Timestamps (ms) of repair attempts inside the 24h window, oldest first. */
    fun windowAttempts(attempts: List<Long>, nowMs: Long): List<Long> =
        attempts.filter { nowMs - it < ATTEMPT_WINDOW_MS }

    fun backoffDelayMs(attemptsInWindow: Int): Long {
        val shift = (attemptsInWindow - 1).coerceAtLeast(0)
        // 1st repair: 15min, 2nd: 30min, 3rd: 60min — capped at 4h.
        val delay = BACKOFF_BASE_MS * (1L shl shift.coerceAtMost(6))
        return delay.coerceAtMost(BACKOFF_MAX_MS)
    }

    /** True when a NEW repair attempt may start now (cap + backoff). */
    fun repairAllowed(attempts: List<Long>, nowMs: Long): Boolean {
        val window = windowAttempts(attempts, nowMs)
        if (window.size >= DAILY_ATTEMPT_CAP) return false
        val last = window.lastOrNull() ?: return true
        return nowMs - last >= backoffDelayMs(window.size)
    }

    // ── host-side tree walk (real FS; tested with temp dirs) ─────────────────

    fun walk(root: File): List<FileMeta> {
        val out = mutableListOf<FileMeta>()
        fun rec(f: File, rel: String) {
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.forEach { rec(it, rel + it.name + "/") }
            } else if (f.isFile) {
                out.add(FileMeta(rel + f.name, f.length()))
            }
        }
        rec(root, "")
        return out
    }

    // ── device wiring (thin, context-only) ───────────────────────────────────

    private fun manifestFile(context: Context, languageName: String): File =
        File(File(context.filesDir, "install-manifests"), languageName + ".txt")

    private fun attemptsFile(context: Context, languageName: String): File =
        File(File(context.filesDir, "install-manifests"), languageName + ".attempts.txt")

    /** Called ONLY after a verified install (structural check passed). */
    fun writeManifest(context: Context, languageName: String, manifestDir: String) {
        try {
            val dir = File(com.codespace.ide.terminal.ProotInstaller.rootfsDir(context), manifestDir)
            val f = manifestFile(context, languageName)
            f.parentFile?.mkdirs()
            if (!dir.exists()) {
                AppOutputLog.log("[INSTALL-HEALTH] manifest NOT written: $manifestDir does not exist", "lsp")
                return
            }
            val m = buildManifest(walk(dir))
            f.writeText(m)
            AppOutputLog.log(
                "[INSTALL-HEALTH] manifest written for $languageName: ${m.lines().size} files under $manifestDir", "lsp")
        } catch (t: Throwable) {
            AppOutputLog.log(
                "[INSTALL-HEALTH] manifest write failed for $languageName: " + (t.message ?: t.javaClass.simpleName), "lsp")
        }
    }

    /**
     * The CHEAP gate used by isServerInstalled BEFORE the structural probe:
     *  - PASS        -> install tree matches the manifest (no probe needed).
     *  - MISMATCH    -> tree differs and a repair attempt is budgeted now;
     *                   isServerInstalled returns false so startServer repairs.
     *  - REPAIR_CAPPED -> differs, but today's attempts are exhausted or backoff
     *                   is pending: fall through to the structural probe instead
     *                   of looping 30-minute installs.
     *  - NOT_TRACKED -> no manifest stored yet (first install pending): probe.
     */
    fun gate(context: Context, languageName: String, manifestDir: String): Gate {
        try {
            val stored = manifestFile(context, languageName).takeIf { it.exists() }?.readText()
            if (stored.isNullOrBlank()) return Gate.NOT_TRACKED
            val dir = File(com.codespace.ide.terminal.ProotInstaller.rootfsDir(context), manifestDir)
            if (!dir.exists()) return onMismatch(context, languageName, stored, emptyList())
            val ok = manifestMatches(stored, walk(dir))
            if (ok) return Gate.PASS
            return onMismatch(context, languageName, stored, walk(dir))
        } catch (t: Throwable) {
            AppOutputLog.log(
                "[INSTALL-HEALTH] gate failed for $languageName: " + (t.message ?: t.javaClass.simpleName), "lsp")
            return Gate.NOT_TRACKED
        }
    }

    private fun onMismatch(context: Context, languageName: String, stored: String, current: List<FileMeta>): Gate {
        val attempts = readAttempts(context, languageName)
        val now = System.currentTimeMillis()
        if (!repairAllowed(attempts, now)) {
            val window = windowAttempts(attempts, now)
            AppOutputLog.log(
                "[INSTALL-HEALTH] $languageName tree DOES NOT match its manifest (" +
                    stored.lines().size + " recorded vs " + current.size + " present) — repair capped/backoff (" +
                    window.size + "/" + DAILY_ATTEMPT_CAP + " today, last " +
                    ((now - (window.lastOrNull() ?: 0L)) / 60000L) + "min ago). Structural probe decides for now; " +
                    "manual Repair is available in Settings.", "lsp")
            return Gate.REPAIR_CAPPED
        }
        writeAttempts(context, languageName, attempts + now)
        AppOutputLog.log(
            "[INSTALL-HEALTH] $languageName tree DOES NOT match its manifest (" +
                stored.lines().size + " recorded vs " + current.size + " present) — auto-repair attempt " +
                (windowAttempts(attempts, now).size + 1) + "/" + DAILY_ATTEMPT_CAP + " today", "lsp")
        return Gate.MISMATCH
    }

    private fun readAttempts(context: Context, languageName: String): List<Long> = try {
        val f = attemptsFile(context, languageName)
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { it.trim().toLongOrNull() }
    } catch (_: Throwable) { emptyList() }

    private fun writeAttempts(context: Context, languageName: String, attempts: List<Long>) {
        try {
            val f = attemptsFile(context, languageName)
            f.parentFile?.mkdirs()
            f.writeText(attempts.joinToString("\n"))
        } catch (_: Throwable) { }
    }

    /** Manual Settings action: clears the attempt budget so a repair can always start. */
    fun allowManualRepair(context: Context, languageName: String) {
        writeAttempts(context, languageName, emptyList())
        AppOutputLog.log("[INSTALL-HEALTH] manual repair requested for $languageName — attempt budget cleared", "lsp")
    }
}
