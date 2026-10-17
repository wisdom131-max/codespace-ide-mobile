package com.codespace.ide.diagnostics

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * BATCH6-P1 (advisor B2, 2026-10-17): main-thread watchdog.
 *
 * Owner evidence (device, build 2c9e9a9): the app died with REASON_ANR
 * ("Input dispatching timed out") 25s after a terminal link tap, and the
 * only signal was an after-the-fact "[perf] STALL 19608ms". PerfProbe sees a
 * stall only when the NEXT frame runs — during the freeze there was NO live
 * record of where the main thread was stuck.
 *
 * This watchdog closes that gap: a daemon thread posts a heartbeat to the
 * main looper; if the main thread does not answer within 2s, its stack is
 * sampled every second and written to the crumb file with fsync
 * (CrumbLog.crumbSync) plus the Output tab — so the next start (or the log
 * itself, read live from Output on device) shows exactly what blocked it.
 *
 * Pure add-on: no state of the app is touched, nothing is killed, the
 * watchdog only OBSERVES and writes. The heartbeat posts are capped to one
 * in flight, so a blocked main thread never queues an unbounded pile of them.
 */
object MainThreadWatchdog {

    private const val BEAT_PERIOD_MS = 250L
    private const val STALL_THRESHOLD_MS = 2_000L
    private const val SAMPLE_PERIOD_MS = 1_000L
    private const val MAX_FRAMES = 25

    @Volatile private var started = false
    @Volatile private var lastBeatMs = 0L
    @Volatile private var beatPending = false
    @Volatile private var stallSinceMs = 0L
    @Volatile private var lastSampleMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Idempotent — safe to call from any startup path. */
    fun start() {
        if (started) return
        started = true
        lastBeatMs = SystemClock.elapsedRealtime()
        Thread({
            while (true) {
                try {
                    Thread.sleep(BEAT_PERIOD_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                // One beat in flight at most: while the main thread is blocked
                // the runnable cannot run, beatPending stays true, and we stop
                // posting (the queued one fires as soon as it unblocks).
                if (!beatPending) {
                    beatPending = true
                    mainHandler.post {
                        lastBeatMs = SystemClock.elapsedRealtime()
                        beatPending = false
                    }
                }
                val now = SystemClock.elapsedRealtime()
                val silentFor = now - lastBeatMs
                if (silentFor >= STALL_THRESHOLD_MS) {
                    if (stallSinceMs == 0L) {
                        stallSinceMs = now
                        sample("FIRST")
                    } else if (now - lastSampleMs >= SAMPLE_PERIOD_MS) {
                        sample("repeat")
                    }
                } else if (stallSinceMs != 0L) {
                    val duration = now - stallSinceMs
                    stallSinceMs = 0L
                    val line = "[WATCHDOG] main thread RECOVERED after ${duration}ms stall"
                    crumbSafe("watchdog", line)
                    AppOutputLog.log(line, "perf")
                }
            }
        }, "MainWatchdog").apply {
            isDaemon = true
            start()
        }
    }

    /** Never throws — the watchdog must not become a new crash source. */
    private fun crumbSafe(tag: String, message: String) {
        try {
            CrumbLog.crumbSync(tag, message)
        } catch (_: Throwable) {
        }
    }

    private fun sample(kind: String) {
        lastSampleMs = SystemClock.elapsedRealtime()
        try {
            val silentFor = SystemClock.elapsedRealtime() - lastBeatMs
            val main = Looper.getMainLooper().thread
            val frames = main.stackTrace
            val sb = StringBuilder()
            sb.append("[WATCHDOG] MAIN STALL ").append(kind)
                .append(": no heartbeat for ").append(silentFor).append("ms; main stack (top ")
                .append(MAX_FRAMES.coerceAtMost(frames.size)).append(" frames):")
            for (element in frames.take(MAX_FRAMES)) {
                sb.append("\n    at ").append(element.toString())
            }
            crumbSafe("watchdog", sb.toString())
            // Full stack to Output on the FIRST sample; repeats log a short line
            // (the per-second fsynced crumb still carries the full stack).
            if (kind == "FIRST") {
                AppOutputLog.log(sb.toString(), "perf")
            } else {
                AppOutputLog.log(
                    "[WATCHDOG] main still stalled ${silentFor}ms (stack refreshed in crumb log)",
                    "perf")
            }
        } catch (t: Throwable) {
            crumbSafe("watchdog", "watchdog sample failed: " + (t.message ?: t.javaClass.simpleName))
        }
    }
}
