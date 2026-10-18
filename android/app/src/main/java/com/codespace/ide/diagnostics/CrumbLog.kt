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

    /**
     * Advisor Part 4 (2026-10-16): a breadcrumb that is FLUSHED TO DISK (fsync)
     * before returning — the last line in the file marks exactly where the
     * process died, even if the kill lands mid-tap. Used at every staged
     * boundary of the Link tap mode flow.
     */
    fun crumbSync(tag: String, message: String) {
        try {
            val d = dir ?: return
            writeCrumbed(d, tag, message, sync = true)
        } catch (_: Throwable) {
            // A diagnostics log must never crash the app it diagnoses.
        }
    }

    /** The LAST [n] breadcrumb lines (the death trail), newest last. */
    fun crumbTail(n: Int = 8): List<String> {
        val d = dir ?: return emptyList()
        return crumbTailFrom(File(d, FILE_NAME), n)
    }

    /** Pure, unit-tested: the tail of a breadcrumb file. */
    internal fun crumbTailFrom(f: File, n: Int): List<String> {
        return try {
            if (!f.exists()) return emptyList()
            f.readLines().takeLast(n)
        } catch (_: Throwable) { emptyList() }
    }

    /** Pure, unit-tested: the capped, redacted write into a given directory. */
    internal fun writeCrumbed(dir: File, tag: String, message: String, maxBytes: Int = MAX_BYTES, keepOldBytes: Int = KEEP_OLD_BYTES, sync: Boolean = false) {
        dir.mkdirs()
        val f = File(dir, FILE_NAME)
        val line = timeFmt.format(Date()) + " [" + tag + "] " + redact(message) + "\n"
        synchronized(CrumbLog::class.java) {
            if (sync) {
                java.io.FileOutputStream(f, true).use { fos ->
                    fos.write(line.toByteArray())
                    fos.fd.sync()
                }
            } else {
                f.appendText(line)
            }
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

    /**
     * Advisor Part 4 mapping — the exit reason in plain words the owner can act on.
     * REASON_SIGNALED status 31 = SIGSYS = a BLOCKED SYSCALL (the kernel family
     * this device has already killed the app with during proot work).
     */
    internal fun plainExitWords(reason: Int, status: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_SIGNALED ->
                if (status == 31) "killed by signal 31 (SIGSYS — a blocked syscall)"
                else "killed by signal " + status
            ApplicationExitInfo.REASON_ANR -> "the app stopped responding (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "the system killed the app to free memory (LOW MEMORY)"
            ApplicationExitInfo.REASON_CRASH -> "a crash in the app's Java code"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "a native crash inside the app's own code"
            ApplicationExitInfo.REASON_EXIT_SELF -> "the app closed itself"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "you closed the app"
            ApplicationExitInfo.REASON_USER_STOPPED -> "you stopped the app"
            else -> "another system reason (code " + reason + ")"
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
        // Advisor 1a: cold vs resume. A process start is BY DEFINITION a cold
        // start; the interesting fact is whether the PREVIOUS process died
        // moments before (pid differs, its last crumb is seconds old).
        val prevStart = lastStartPidAndAge()
        crumb(
            "start",
            "process start (COLD), pid=" + android.os.Process.myPid() +
                (prevStart?.let { " — previous process pid=" + it.first + " last alive " + it.second + "s ago" } ?: " — no previous process recorded") +
                " " + memorySnapshot(context),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val am = context.getSystemService(ActivityManager::class.java) ?: return
                val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                if (exits.isNullOrEmpty()) {
                    crumb("exit-info", "no previous exit recorded (fresh install or system limit)")
                    return
                }
                // Advisor Part 4 (2026-10-16): the LAST 5 exit records, not only the
                // latest — a recurring kill pattern is visible only across records.
                // NOTE: ApplicationExitInfo.getSubReason()/getTotalPss() are not
                // exposed by this compileSdk (CI-proven, aa74ef2 red) — reason,
                // status, importance and description carry the verdict.
                val recent = exits.take(5)
                recent.forEachIndexed { i, info ->
                    val abnormal = info.reason == ApplicationExitInfo.REASON_CRASH ||
                        info.reason == ApplicationExitInfo.REASON_SIGNALED ||
                        info.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                        info.reason == ApplicationExitInfo.REASON_ANR
                    val line = "exit " + (recent.size - i) + "/" + recent.size + " " + agoText(info.timestamp) +
                        (if (abnormal) " [ABNORMAL]" else "") +
                        ": " + plainExitWords(info.reason, info.status) +
                        " (status=" + info.status + ", importance=" + info.importance + ")" +
                        (info.description?.let { " desc=\"" + it.take(120) + "\"" } ?: "")
                    crumb("exit-info", line)
                    AppOutputLog.log("[CRUMB] " + line, "terminal")
                }
                // The breadcrumb tail — the death trail from the previous process —
                // visible in Output without any file pull.
                AppOutputLog.log("[CRUMB] breadcrumb tail: " + crumbTail(8).joinToString(" | "), "terminal")
                val info = exits[0]
                // BATCH7-P0 (advisor 1a, 2026-10-18): the owner's logs hold FOUR ANRs in
                // three minutes ("Input dispatching timed out") and the BATCH6 trace
                // block was capped at 41 lines — the top of the stack was native regex
                // frames, so the six nested APP frames that NAMED the caller were cut
                // off. Now EVERY ANR record among the recent 5 gets its main-thread
                // JAVA frames ("at ..." lines), native lines skipped, up to 60 frames.
                if (info.reason == ApplicationExitInfo.REASON_ANR) {
                    NotificationStore.add(
                        "The app stopped responding",
                        "Android closed the previous session because the interface froze (" + agoText(info.timestamp) +
                            "). The persistent log (crumb-log) shows what it was doing.",
                        NotificationStore.Type.INFO,
                    )
                }
                recent.forEachIndexed { i, rec ->
                    if (rec.reason == ApplicationExitInfo.REASON_ANR) logAnrJavaFrames(rec, recent.size, i)
                }
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
                }
            } catch (e: Throwable) {
                crumb("exit-info", "ApplicationExitInfo read failed: " + (e.message ?: e.javaClass.simpleName))
            }
        } else {
            crumb("exit-info", "ApplicationExitInfo needs Android 11+ — device is API " + Build.VERSION.SDK_INT)
        }
    }

    /**
     * BATCH7-P0 (advisor 1a, 2026-10-18): recover the main-thread JAVA frames of ONE
     * ANR record and pin them in Output. The old block printed the first 41 lines of
     * the trace verbatim — the top of the stack is native regex/ICU frames, so the
     * APP frames that NAMED the caller were cut off. Now: native lines ("#NN pc ...",
     * "native: ...") are SKIPPED, only "at ..." JAVA frames are printed, up to 60,
     * and frames from our own package are marked so the caller is readable at a
     * glance. The OS may still hold the four traces from 11:11-11:13, so the next
     * start could name the regex caller for real.
     */
    private fun logAnrJavaFrames(info: android.app.ApplicationExitInfo, total: Int, index: Int) {
        try {
            val traceText = info.traceInputStream?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    val sb = StringBuilder()
                    var lines = 0
                    for (line in seq) {
                        if (lines >= 400) break
                        sb.append(line).append('\n')
                        lines++
                    }
                    sb.toString()
                }
            }
            if (traceText.isNullOrBlank()) {
                crumb("exit-info", "ANR trace not held by the OS (already reclaimed)")
                return
            }
            val frames = ArrayList<String>(64)
            var inMain = false
            var sawMainThread = false
            for (raw in traceText.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("\"")) {
                    if (inMain) break // main thread section ends at the next thread header
                    if (line.startsWith("\"main\"")) {
                        inMain = true
                        sawMainThread = true
                        frames.add(line)
                    }
                    continue
                }
                if (inMain && line.startsWith("at ")) {
                    if (frames.size >= ANR_MAX_JAVA_FRAMES + 1) break
                    frames.add((if (line.contains("com.codespace")) "APP " else "    ") + line)
                }
                // native lines ("#NN pc ...", "native: ...") and headers are SKIPPED.
            }
            if (frames.isEmpty()) {
                crumb("exit-info", "ANR trace held but no main-thread Java frames found" +
                    (if (sawMainThread) "" else " (main thread header absent)"))
                return
            }
            val label = "exit " + (total - index) + "/" + total + " " + agoText(info.timestamp)
            AppOutputLog.log("[EXIT] ANR trace (" + label + ") — main thread JAVA frames (APP = our code; native lines skipped, max " + ANR_MAX_JAVA_FRAMES + "):", "terminal")
            frames.forEach { AppOutputLog.log("[EXIT]   " + it, "terminal") }
            crumb("exit-info", "ANR Java frames recovered (" + label + "): " +
                frames.filter { it.startsWith("APP ") }.take(6).joinToString(" | ").take(400))
        } catch (t: Throwable) {
            crumb("exit-info", "ANR trace read failed: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private const val ANR_MAX_JAVA_FRAMES = 60

    /** Advisor 1a: the previous [start] crumb's pid and how long ago the previous
     *  process was last heard from (age of the last crumb in either log file). */
    internal fun lastStartPidAndAge(): Pair<Int, Long>? {
        return try {
            val d = dir ?: return null
            val f = File(d, FILE_NAME)
            val old = File(d, FILE_NAME + OLD_SUFFIX)
            val lines = (if (f.exists()) f.readLines() else emptyList()) +
                (if (old.exists()) old.readLines() else emptyList())
            val startLine = lines.lastOrNull { it.contains("[start] process start") } ?: return null
            val pid = startLine.substringAfter("pid=").substringBefore(' ').toIntOrNull() ?: return null
            val lastTs = lines.lastOrNull()?.let { parseCrumbTime(it) } ?: return null
            Pair(pid, (System.currentTimeMillis() - lastTs) / 1000)
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseCrumbTime(line: String): Long? {
        return try {
            // Lines start "MM-dd HH:mm:ss.SSS" — add the current year so the
            // SimpleDateFormat parses; year rollover within the log window is
            // irrelevant (the log holds minutes, not months).
            SimpleDateFormat("yyyy MM-dd HH:mm:ss.SSS", Locale.US).let { fmt ->
                val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
                fmt.parse(year + " " + line.substring(0, 18))?.time
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Advisor 1a: activity lifecycle — a cold create vs a restore into a live or
     *  recreated process (restored=true = savedInstanceState present: config change
     *  or process-death restore; a plain resume never calls onCreate at all). */
    fun logActivityCreate(restored: Boolean) {
        crumb("activity", "activity onCreate, restored=" + restored + ", pid=" + android.os.Process.myPid())
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
