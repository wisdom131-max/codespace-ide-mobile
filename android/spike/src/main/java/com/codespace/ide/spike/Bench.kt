package com.codespace.ide.spike

import android.app.ActivityManager
import android.content.Context
import android.view.Choreographer
import android.os.Debug
import android.os.SystemClock
import java.util.PriorityQueue

/**
 * Frame-time + memory sampler for both panes. Choreographer deltas give frame
 * durations in ns; jank = frame longer than 1.5x the median frame budget
 * (60Hz => 25ms), matching the decision rule's plain-word framing.
 */
class Bench(private val context: Context) {

    private val framesNs = ArrayList<Long>(2048)
    private var lastFrameNs = 0L
    private var sampling = false
    private val choreographer = Choreographer.getInstance()

    // Anonymous object, not a self-referencing lambda val — the lambda form
    // (val cb = FrameCallback { ... postFrameCallback(cb) }) is the recursive
    // type-check failure seen in runs 3366-3368. `this` resolves cleanly.
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            pendingInput?.let { cb ->
                pendingInput = null
                cb((frameTimeNs - inputAtNs).coerceAtLeast(0).toDouble() / 1_000_000.0)
            }
            if (sampling) {
                if (lastFrameNs != 0L) framesNs.add((frameTimeNs - lastFrameNs).coerceAtLeast(0))
            }
            lastFrameNs = frameTimeNs
            choreographer.postFrameCallback(this)
        }
    }

    init { choreographer.postFrameCallback(callback) }

    // ── Input latency: keystroke -> next rendered frame ──────────────
    private var inputAtNs = 0L
    private var pendingInput: ((Double) -> Unit)? = null

    /** Called by the shared native input on each keystroke. */
    fun markInput(report: (Double) -> Unit) {
        inputAtNs = System.nanoTime()
        pendingInput = report
    }

    fun startSampling() {
        framesNs.clear()
        lastFrameNs = 0L
        sampling = true
    }

    fun stopSampling() { sampling = false }

    /** p50/p95 in ms + jank count (frame > 25ms). */
    fun frameStats(): Triple<Double, Double, Int> {
        if (framesNs.isEmpty()) return Triple(0.0, 0.0, 0)
        val sorted = framesNs.sorted()
        val p = { q: Double -> sorted[((sorted.size - 1) * q).toInt()] / 1_000_000.0 }
        val jank = sorted.count { it / 1_000_000.0 > 25.0 }
        return Triple(p(0.5), p(0.95), jank)
    }

    fun pssKb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong()
    }

    fun apkBytes(): Long {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val ai = pm.getApplicationInfo(pkg, 0)
            java.io.File(ai.sourceDir).length()
        } catch (_: Exception) { -1L }
    }

    fun freeRamMb(): Double {
        val mi = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(mi)
        return mi.availMem / (1024.0 * 1024.0)
    }
}

/** One measured pane result, copyable as a plain row. */
data class PaneResult(
    val pane: String,
    val p50Ms: Double,
    val p95Ms: Double,
    val jank: Int,
    val pssDeltaMb: Double,
    val inputLatencyMs: Double,
)

/** Builds the COPYABLE results block the owner pastes back. */
object ResultsBlock {
    fun build(coldStartMs: Long, a: PaneResult, b: PaneResult, apkBytes: Long, stamp: String): String {
        val verdictB = if (b.p50Ms <= a.p50Ms * 1.05 && b.pssDeltaMb <= a.pssDeltaMb + 25.0)
            "B PASSES (frame ties or beats A; memory within +25 MB)"
        else
            "B FAILS the decision rule"
        return """
VN Spike results — $stamp
cold_start_ms=$coldStartMs
A_native: p50=${two(a.p50Ms)}ms p95=${two(a.p95Ms)}ms jank=${a.jank} pss_delta=${two(a.pssDeltaMb)}MB input_latency=${two(a.inputLatencyMs)}ms
B_webview: p50=${two(b.p50Ms)}ms p95=${two(b.p95Ms)}ms jank=${b.jank} pss_delta=${two(b.pssDeltaMb)}MB input_latency=${two(b.inputLatencyMs)}ms
verdict: $verdictB
spike_apk_bytes=$apkBytes
free_ram_note: measured on-device at run time
""".trim()

    }
    private fun two(d: Double) = String.format("%.2f", d)
}
