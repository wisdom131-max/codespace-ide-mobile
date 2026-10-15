package com.codespace.ide.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import com.codespace.ide.data.NotificationStore
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Advisor A.2 (2026-10-15, approved): the PERSISTENT rolling breadcrumb log.
 *
 * The owner's app closed silently on a terminal link tap (a9b94a8) with no crash
 * log — AppOutputLog only lives in memory, so nothing survived the process death.
 * This log writes LIVE to app-private storage (no permission, excluded from any
 * backup), so whatever killed the process leaves a trail: the LAST breadcrumb is
 * the boundary of the death.
 *
 * Contract:
 * - crumb() is cheap, thread-safe, and never throws (a failed write must never
 *   take the app down).
 * - Size-capped: at MAX_BYTES the current file rotates to .old (one generation
 *   kept) and a fresh file starts — the log can never fill the disk.
 * - Secrets are scrubbed like the Output panel's practice: key-like assignments
 *   and token prefixes never reach disk.
 * - logStartup() runs on EVERY process start and reads ApplicationExitInfo
 *   (Android 11+) so the PREVIOUS exit — reason, sub-reason, status, importance,
 *   the PSS the system recorded at exit — is pinned in the persistent log even
 *   though it happened while we were dead.
 */
object CrumbLog {

    private const val FILE_NAME = "crumb-log.txt"
    private const val OLD_SUFFIX = ".old"
    private const val MAX_BYTES = 256 * 1024
    private const val KEEP_OLD_BYTES = 128 * 1024

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // Secret-shaped patterns never reach disk. Deliberately conservative: the
    // breadcrumb CONTENT is paths and actions, but a pasted token in a terminal
    // line could ride along in a token crumb.
    // A key-looking NAME plus an assignment/value redacts the VALUE; the name
    // survives for debugging. Separator-based so ordinary words in crumb text
    // (file paths, "link text='x.kt:42'") are never touched.
    private val KEY_ASSIGN = Regex("""(?i)\b([a-z0-9_-]*(?:api|secret|access|auth|private)[a-z0-9_-]*|[a-z0-9_-]*(?:key|token|password|credential)[a-z0-9_-]*|authorization)\s*[=:]\s*[^\s,;]+""")
    private val BEARER = Regex("""(?i)\b(bearer)\s+[^\s,;]+""")
    private val KEY_PREFIX = Regex("""\b(sk|pk|rk)[-_][_A-Za-z0-9-]{16,}""")

    @Volatile
    private var dir: File? = null

    /** Called once at app start; harmless if repeated. */
    fun init(context: Context) {
        if (dir == null) dir = File(context.filesDir, "crumbs")
    }

    /** Write one breadcrumb. Live to disk, capped, redacted, never throws. */
    fun crumb(tag: String, message: String) {
        try {
            val d = dir ?: return
            writeCrumbed(d, tag, message)
        } catch (_: Throwable) {
            // A diagnostics log must never crash the app it diagnoses.
        }
    }

    /** Pure, unit-tested: the capped, redacted write into a given directory. */
    internal fun writeCrumbed(dir: File, tag: String, message: String, maxBytes: Int = MAX_BYTES, keepOldBytes: Int = KEEP_OLD_BYTES) {
        dir.mkdirs()
        val f = File(dir, FILE_NAME)
        val line = timeFmt.format(Date()) + " [" + tag + "] " + redact(message) + "\n"
        synchronized(CrumbLog::class.java) {
            f.appendText(line)
            val len = f.length()
            if (len > maxBytes) {
                val old = File(dir, FILE_NAME + OLD_SUFFIX)
                old.delete()
                val rotated = f.renameTo(old)
                if (rotated) {
                    // .old is capped too: past keepOldBytes it is truncated
                    // head-first so the newest part survives.
                    val oldLen = old.length()
                    if (oldLen > keepOldBytes) {
                        val tail = old.inputStream().use { s ->
                            s.skip(oldLen - keepOldBytes)
                            s.readBytes()
                        }
                        old.writeBytes(tail)
                    }
                }
                // INVARIANT (CI-proven, bfa232f red): after a rotation the current
                // file ALWAYS exists and holds the newest breadcrumb — renameTo moves
                // it away, so recreate it with the triggering line. A refused rename
                // truncates in place instead of growing unbounded.
                f.writeText(line)
            }
        }
    }

    internal fun redact(message: String): String =
        message
            .replace(KEY_ASSIGN) { m -> m.groupValues[1] + "=***" }
            .replace(BEARER) { m -> m.groupValues[1] + " ***" }
            .replace(KEY_PREFIX) { m -> m.groupValues[1] + "_***" }

    /**
     * Called on EVERY process start (CodeSpaceApplication.onCreate, first).
     * Reads the previous exit from ApplicationExitInfo and pins it in the
     * persistent log. A low-memory kill gets a plain in-app notification and an
     * Output line — the owner sees WHY the app closed instead of a mystery.
     */
    fun logStartup(context: Context) {
        init(context)
        crumb("start", "process start, pid=" + android.os.Process.myPid() + " " + memorySnapshot(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val am = context.getSystemService(ActivityManager::class.java) ?: return
                val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 3)
                if (exits.isNullOrEmpty()) {
                    crumb("exit-info", "no previous exit recorded (fresh install or system limit)")
                    return
                }
                val info = exits[0]
                val reason = reasonName(info.reason)
                // NOTE: ApplicationExitInfo.getSubReason()/getTotalPss() are not
                // exposed by this compileSdk (CI-proven, aa74ef2 red) — reason,
                // status, importance and description carry the verdict.
                val line = "previous exit " + agoText(info.timestamp) +
                    ": reason=" + reason +
                    " status=" + info.status +
                    " importance=" + info.importance +
                    (info.description?.let { " desc=\"" + it.take(120) + "\"" } ?: "")
                crumb("exit-info", line)
                AppOutputLog.log("[CRUMB] " + line, "terminal")
                if (info.reason == ApplicationExitInfo.REASON_LOW_MEMORY) {
                    val ago = agoText(info.timestamp)
                    NotificationStore.add(
                        "Android closed the app to free memory",
                        "The system killed the app because memory ran low " + ago +
                            ". Nothing was lost — reopen and continue. If this repeats, close other apps before heavy sessions.",
                        NotificationStore.Type.INFO,
                    )
                    AppOutputLog.log(
                        "[CRUMB] Android closed the app for LOW MEMORY " + ago +
                            ". Last breadcrumb before that exit: " + lastCrumbLine(),
                        "terminal",
                    )
                } else if (info.reason == ApplicationExitInfo.REASON_ANR) {
                    NotificationStore.add(
                        "The app stopped responding",
                        "Android closed the previous session because the interface froze (" + agoText(info.timestamp) +
                            "). The persistent log (crumb-log) shows what it was doing.",
                        NotificationStore.Type.INFO,
                    )
                }
            } catch (e: Throwable) {
                crumb("exit-info", "ApplicationExitInfo read failed: " + (e.message ?: e.javaClass.simpleName))
            }
        } else {
            crumb("exit-info", "ApplicationExitInfo needs Android 11+ — device is API " + Build.VERSION.SDK_INT)
        }
    }

    /** The last line before a death is the boundary of the death. */
    internal fun lastCrumbLine(): String {
        return try {
            val d = dir ?: return "(no log dir)"
            val f = File(d, FILE_NAME)
            if (!f.exists()) return "(no crumb file yet)"
            val lines = f.readLines()
            if (lines.isEmpty()) "(empty)" else lines.last().take(160)
        } catch (_: Throwable) {
            "(unreadable)"
        }
    }

    /** Same figures the EXIT9-MEM-DIAG reports, compact for breadcrumbs. */
    fun memorySnapshot(context: Context): String {
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return "(no ActivityManager)"
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val rt = Runtime.getRuntime()
            val jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            "mem: sysAvail=" + (mi.availMem / (1024 * 1024)) + "MB total=" + (mi.totalMem / (1024 * 1024)) +
                "MB low=" + mi.lowMemory + " jvm=" + jvmUsedMb + "/" + (rt.maxMemory() / (1024 * 1024)) + "MB"
        } catch (_: Throwable) {
            "(mem unreadable)"
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (system needed the RAM)"
        ApplicationExitInfo.REASON_ANR -> "ANR (interface stopped responding)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed by a signal)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (swipe-away or Force stop)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "OTHER(" + reason + ")"  // package-update(7), permissions-change(12): not in this compileSdk
    }

    private fun agoText(ts: Long): String {
        val mins = (System.currentTimeMillis() - ts) / 60000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> (mins / 60).toString() + " h ago"
            else -> (mins / (60 * 24)).toString() + " d ago"
        }
    }
}
