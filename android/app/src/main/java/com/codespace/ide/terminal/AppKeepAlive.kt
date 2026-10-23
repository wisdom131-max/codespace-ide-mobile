package com.codespace.ide.terminal

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * C12-U6-b item 3 (advisor ruling 2026-10-23, owner REJECTED the settings
 * toggle): the foreground service starts for the WHOLE APP automatically —
 * no terminal needed, no setting — with every guard that makes it
 * structurally unable to recreate the historical crash loop (see
 * CodeSpaceApplication.kt:137-146: acquiring 2 WakeLocks + FGS within 1 s of
 * PROCESS START made TECNO HiOS SIGKILL the app on every restart — 16 kills
 * in 90 seconds, ~5.6 s per cycle).
 *
 * The guards, all ANDed:
 *
 *  1. NEVER from process start — [CodeSpaceApplication.onCreate] writes only
 *     a timestamp stamp (plain file write, no locks, no service).
 *  2. DELAYED: >= 5 s after the FIRST [MainActivity.onResume] of this
 *     process, one-shot (later resumes do not re-arm the timer).
 *  3. VISIBLE-ONLY: if the app is paused before the timer fires, the pending
 *     start is CANCELLED — it must never fire from the background (the
 *     Android 14 background-FGS-start rule; this targetSdk-28 build is
 *     system-permissive, but visible-only also keeps OEM power managers from
 *     ever seeing a stealth background starter).
 *  4. ANTI CRASH-LOOP GUARD: at fire time the start is skipped if (a) a
 *     crash file was written in the last [CRASH_FILE_SKIP_MS] (the previous
 *     run crashed — both the JVM uncaught handler and the native handler
 *     write into filesDir/crash_logs/), or (b) >= [MIN_CRASHES_IN_WINDOW]
 *     crash files exist in a [CRASH_WINDOW_MS] window, or (c) >=
 *     [MIN_STARTS_IN_WINDOW] process starts were recorded inside
 *     [START_WINDOW_MS] — the SIGKILL loop family leaves NO crash file, and
 *     rapid restarts are its fingerprint.
 *
 * The WakeLock stays the separate user switch it is today (gear menu +
 * notification toggle) — this object NEVER acquires one. The notification
 * gains a "Stop" action (explicit teardown) instead of a settings toggle.
 *
 * Battery honesty (reported to the owner, 2026-10-23): a foreground service
 * alone does NOT keep the CPU awake — with the screen off the device still
 * deep-sleeps unless the user separately enables the WakeLock switch. What
 * the FGS buys is process priority: Android is far less likely to kill the
 * process while backgrounded, so app state (open files, editor state,
 * terminal tabs) survives minimize + 10-minute waits. The cost is one
 * resident process + one low-importance notification — small, constant.
 */
object AppKeepAlive {

    private const val TAG = "AppKeepAlive"

    /** Advisor floor: >= 5 s after first resume. */
    const val START_DELAY_MS = 5_000L

    // ---- Anti crash-loop guard constants (persistent-window based) ----
    /** A crash file younger than this = previous run crashed -> skip. */
    const val CRASH_FILE_SKIP_MS = 60_000L
    /** >= this many crash files in the window = crash loop -> skip. */
    const val MIN_CRASHES_IN_WINDOW = 2
    const val CRASH_WINDOW_MS = 5 * 60_000L
    /** >= this many process starts in the window = SIGKILL/restart loop -> skip. */
    const val MIN_STARTS_IN_WINDOW = 3
    const val START_WINDOW_MS = 120_000L

    private val handler = Handler(Looper.getMainLooper())

    /** Process-wide state — all touched on the main thread. */
    private var armed = false
    private var resumedNow = false
    private var guardTripped: String? = null
    private var pending: Runnable? = null

    /** MainActivity.onResume — arms the one-shot delayed start. */
    @Synchronized
    fun onAppResumed(context: Context) {
        resumedNow = true
        if (guardTripped != null) return
        if (armed) return  // one-shot: later resumes never re-arm
        armed = true
        val appContext = context.applicationContext
        val r = Runnable {
            synchronized(this@AppKeepAlive) {
                pending = null
                if (!resumedNow) {
                    Log.i(TAG, "visible-only rule: app paused before the delay ended — start cancelled")
                    return@Runnable
                }
                val why = KeepAliveGuard.tripReason(appContext, System.currentTimeMillis())
                if (why != null) {
                    guardTripped = why
                    Log.w(TAG, "crash-loop guard tripped — no auto FGS this process: $why")
                    return@Runnable
                }
            }
            Log.i(TAG, "starting whole-app foreground service (delayed keep-alive)")
            val intent = Intent(appContext, TerminalService::class.java)
                .setAction(TerminalService.ACTION_KEEP_ALIVE)
            try {
                appContext.startService(intent)
            } catch (e: Exception) {
                // Honest degradation: a visible in-process start can only
                // fail on system-level trouble — never crash over keep-alive.
                Log.e(TAG, "keep-alive FGS start failed: ${e.message}")
            }
        }
        pending = r
        handler.postDelayed(r, START_DELAY_MS)
    }

    /** MainActivity.onPause — cancels a pending delayed start (visible-only). */
    @Synchronized
    fun onAppPaused() {
        resumedNow = false
        val r = pending
        if (r != null) {
            handler.removeCallbacks(r)
            pending = null
        }
    }

    /**
     * CodeSpaceApplication.onCreate — records the process start for the
     * rapid-restart window. PURE FILE WRITE: no locks, no service, no
     * broadcast — this is exactly the code path the old crash loop lived in,
     * so it must stay inert.
     */
    fun recordProcessStart(context: Context) {
        try {
            KeepAliveGuard.recordProcessStart(context, System.currentTimeMillis())
        } catch (e: Exception) {
            // Guard bookkeeping must never hurt startup.
            Log.w(TAG, "guard stamp write failed: ${e.message}")
        }
    }

    /** Pure decision core — JVM-testable without any Android state. */
    internal fun tripReasonFor(
        now: Long,
        recentStarts: List<Long>,
        crashTimes: List<Long>,
    ): String? {
        // (a) the previous run crashed seconds ago
        val freshCrash = crashTimes.lastOrNull { now - it in 1..CRASH_FILE_SKIP_MS }
        if (freshCrash != null) {
            return "a crash file was written ${now - freshCrash} ms ago (previous run crashed)"
        }
        // (b) crash loop: several crashes inside the window
        val crashesInWindow = crashTimes.count { now - it in 1..CRASH_WINDOW_MS }
        if (crashesInWindow >= MIN_CRASHES_IN_WINDOW) {
            return "$crashesInWindow crash files inside the ${CRASH_WINDOW_MS / 1000}s window"
        }
        // (c) SIGKILL/restart loop: rapid process starts leave NO crash file
        val startsInWindow = recentStarts.count { now - it in 1..START_WINDOW_MS }
        if (startsInWindow >= MIN_STARTS_IN_WINDOW) {
            return "$startsInWindow process starts inside the ${START_WINDOW_MS / 1000}s window (SIGKILL/restart fingerprint)"
        }
        return null
    }
}

/**
 * The guard's Android side: collects the persistent inputs and delegates to
 * the pure decision (so the decision itself runs in JVM unit tests).
 */
internal object KeepAliveGuard {

    internal const val GUARD_FILE = "keepalive_guard.json"
    private const val MAX_RECORDED_STARTS = 8
    private const val CRASH_DIR = "crash_logs"

    fun recordProcessStart(context: Context, now: Long) {
        val f = File(context.filesDir, GUARD_FILE)
        val stamps = readStamps(f).toMutableList()
        stamps.add(now)
        while (stamps.size > MAX_RECORDED_STARTS) stamps.removeAt(0)
        f.writeText(JSONArray(stamps).toString())
    }

    fun tripReason(context: Context, now: Long): String? {
        val stamps = readStamps(File(context.filesDir, GUARD_FILE))
        val crashTimes = try {
            File(context.filesDir, CRASH_DIR)
                .listFiles { file -> file.isFile }
                ?.mapNotNull { it.lastModified().takeIf { t -> t > 0 } }
                ?.sorted()
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
        return AppKeepAlive.tripReasonFor(now, stamps, crashTimes)
    }

    private fun readStamps(f: File): List<Long> = try {
        if (!f.isFile) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.optLong(it) }.filter { it > 0 }
        }
    } catch (_: Exception) { emptyList() }
}
