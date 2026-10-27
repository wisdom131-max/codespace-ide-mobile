package com.codespace.ide.spike

/**
 * COPYABLE results block (harness fix). Every missing metric prints
 * NOT MEASURED / NOT CAPTURED and the verdict degrades to
 * "NOT ENOUGH DATA (missing: ...)" instead of a false pass/fail.
 */
data class PaneMetrics(
    val pane: String,
    val p50Ms: Double?,
    val p95Ms: Double?,
    val jankPct: Double?,
    val dropped: Long,
    val capturedPssKb: Long?,
    val uncapturedProcs: Boolean,
    val procNames: String,
    val availMemKbBefore: Long,
    val availMemKbAfter: Long,
    val inputLatencyMs: Double?,
)

object ResultsBlock {

    fun build(
        coldStartMs: Long?,
        a: PaneMetrics,
        b: PaneMetrics,
        refreshHz: Double,
        spikeApkInstalledBytes: Long,
        stamp: String,
        frameCountA: Int,
        frameCountB: Int,
    ): String {
        val missing = ArrayList<String>()
        if (a.p50Ms == null || a.p95Ms == null || a.jankPct == null) missing.add("A frame metrics")
        if (b.p50Ms == null || b.p95Ms == null || b.jankPct == null) missing.add("B frame metrics")
        if (a.capturedPssKb == null || b.capturedPssKb == null) missing.add("memory capture")
        if (a.uncapturedProcs || b.uncapturedProcs) missing.add("related-process PSS (see NOT CAPTURED list)")
        if (a.inputLatencyMs == null || b.inputLatencyMs == null) missing.add("input latency (type in the box during each phase)")

        val verdict = if (missing.isNotEmpty()) {
            "NOT ENOUGH DATA (missing: ${missing.joinToString("; ")})"
        } else {
            val framesTieOrBeat = b.p50Ms!! <= a.p50Ms!! * 1.05
            val memWithin = (b.capturedPssKb!! - a.capturedPssKb!!) <= 25.0 * 1024.0
            if (framesTieOrBeat && memWithin)
                "B PASSES: frame p50 ties or beats A and memory within +25 MB"
            else
                "B FAILS the decision rule (frame ties-or-beats: $framesTieOrBeat; memory within +25 MB: $memWithin)"
        }
        return ("VN Spike results (harness v2, FrameMetrics + total memory) — $stamp\n" +
            "refresh_rate_hz=${"%.0f".format(refreshHz)}\n" +
            "cold_start_ms=${coldStartMs ?: "NOT MEASURED"}\n" +
            row("A_native", a, frameCountA) + "\n" +
            row("B_webview", b, frameCountB) + "\n" +
            "A_memory_pids=${a.procNames} availMem_delta=${(a.availMemKbBefore - a.availMemKbAfter) / 1024}MB\n" +
            "B_memory_pids=${b.procNames} availMem_delta=${(b.availMemKbBefore - b.availMemKbAfter) / 1024}MB\n" +
            "verdict: $verdict\n" +
            "spike_apk_installed_bytes=$spikeApkInstalledBytes (installed APK; artifact zip differs)\n" +
            "measure_note: frame=window FrameMetrics TOTAL_DURATION per frame while one pane streams; " +
            "memory=own PSS + related pids via /proc smaps_rollup + device availMem; input=key event to next Choreographer frame")
    }

    private fun row(label: String, m: PaneMetrics, frames: Int): String {
        val d = { v: Double? -> if (v == null) "NOT MEASURED" else "%.2f".format(v) }
        return (label + ": p50_ms=" + d(m.p50Ms) + " p95_ms=" + d(m.p95Ms) +
            " jank_pct=" + d(m.jankPct) + " dropped=" + m.dropped + " frames=$frames" +
            " captured_pss_mb=" + (if (m.capturedPssKb == null) "NOT CAPTURED" else "%.1f".format(m.capturedPssKb / 1024.0)) +
            " input_latency_ms=" + d(m.inputLatencyMs))
    }
}
