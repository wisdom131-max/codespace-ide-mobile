package com.codespace.ide.editor

import com.codespace.ide.diagnostics.AppOutputLog

/**
 * PerfProbe — lightweight editor performance instrumentation.
 *
 * APPROVED MEASURE-FIRST PASS (2026-09-06): no engine swap, no optimization yet —
 * this ONLY puts measurement capability in place. Same pattern as the IME
 * diagnostic logging: unconditional counters, log lines routed to the Output tab.
 *
 * What it measures:
 *  1. Keystroke→render latency: time from the text change reaching
 *     onValueChange (PerfProbe.onEdit) to the next text layout pass
 *     (PerfProbe.onTextLaidOut). Reported per-edit when > 8ms.
 *  2. Frame health: frame gaps from the composition frame clock
 *     (PerfProbe.onFrame, driven by a withFrameNanos loop). Gaps > 32ms
 *     count as jank (dropped frames); a 5-second summary line reports
 *     total frames, jank count, worst gap and a rough dropped-frame count.
 *
 * On-device measurement session (for the report): open a large file, type for
 * ~30s, scroll the file top-to-bottom, open the completion popup, then send
 * back every Output-tab line starting with "[perf]".
 */
object PerfProbe {

    private const val JANK_GAP_NS = 32_000_000L       // >32ms between frames = jank
    private const val SUMMARY_PERIOD_NS = 5_000_000_000L // 5s summary cadence

    // keystroke→render
    private var editAtNanos = 0L
    private var editPending = false
    private var keystrokeMaxMs = 0.0
    private var keystrokeCount = 0L

    // frame health
    private var lastFrameNanos = 0L
    private var lastSummaryNanos = 0L
    private var frameCount = 0L
    private var jankCount = 0L
    private var droppedFrames = 0L
    private var worstFrameMs = 0.0
    // PERF-IDLE (2026-09-14): probe goes quiet when nothing is happening.
    private var worstFrameAtMs = 0L
    private var idleNoted = false

    /** Call at the top of onValueChange when the text actually changed. */
    fun onEdit() {
        editAtNanos = System.nanoTime()
        editPending = true
        idleNoted = false
    }

    /** Call from onTextLayout (the render side of the keystroke path). */
    fun onTextLaidOut() {
        if (!editPending) return
        editPending = false
        val ms = (System.nanoTime() - editAtNanos) / 1e6
        if (ms > keystrokeMaxMs) keystrokeMaxMs = ms
        keystrokeCount++
        if (ms > 8.0) {
            AppOutputLog.log("[perf] keystroke→render " + ms.toInt() + "ms", "perf")
        }
    }

    /** Wall-clock time for correlating stalls with device activity. */
    private fun fmtTime(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

    /** Call with the timestamp from withFrameNanos. */
    fun onFrame(nanos: Long) {
        if (lastFrameNanos != 0L) {
            val gap = nanos - lastFrameNanos
            frameCount++
            if (gap > JANK_GAP_NS) {
                jankCount++
                val dropped = ((gap / 16_700_000L) - 1L).coerceAtLeast(0L)
                droppedFrames += dropped
            }
            if (gap / 1e6 > worstFrameMs) {
                worstFrameMs = gap / 1e6
                worstFrameAtMs = System.currentTimeMillis()
            }
            // PERF-STALL: a >5s frame gap means the UI thread was hard-blocked.
            // Log it IMMEDIATELY with a wall-clock timestamp so it can be
            // correlated with what was happening on device at that moment.
            if (gap / 1e6 > 5000.0) {
                AppOutputLog.log(
                    "[perf] STALL " + (gap / 1e6).toInt() + "ms ending " + fmtTime(System.currentTimeMillis()) +
                        " — the UI thread was blocked for this whole gap (correlate with what you did then)",
                    "perf"
                )
            }
        }
        if (lastSummaryNanos == 0L) lastSummaryNanos = nanos
        if (nanos - lastSummaryNanos >= SUMMARY_PERIOD_NS) {
            // PERF-IDLE (2026-09-14): summarize activity windows as before, but
            // when a window had ZERO keystrokes, say "idle" ONCE and go quiet —
            // the probe no longer narrates silence every 5 seconds forever.
            if (keystrokeCount > 0) {
                AppOutputLog.log(
                    "[perf] 5s: frames=" + frameCount + " jank=" + jankCount +
                        " dropped≈" + droppedFrames + " worstFrame=" + worstFrameMs.toInt() +
                        "ms@" + fmtTime(worstFrameAtMs) +
                        " | keystrokes=" + keystrokeCount + " maxLatency=" + keystrokeMaxMs.toInt() + "ms",
                    "perf"
                )
            } else if (!idleNoted && frameCount > 0 && (jankCount > 0 || droppedFrames > 0)) {
                AppOutputLog.log(
                    "[perf] idle — no keystrokes this window (jank=" + jankCount +
                        " worst=" + worstFrameMs.toInt() + "ms@" + fmtTime(worstFrameAtMs) +
                        "); probe quiet until typing resumes",
                    "perf"
                )
                idleNoted = true
            }
            frameCount = 0; jankCount = 0; droppedFrames = 0; worstFrameMs = 0.0
            keystrokeCount = 0; keystrokeMaxMs = 0.0
            lastSummaryNanos = nanos
        }
        lastFrameNanos = nanos
    }
}
