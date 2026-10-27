package com.codespace.ide.spike

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import java.io.File

/**
 * Measurement core (harness fix, advisor ruling 2026-10-27):
 *  - Frame time = Window.onFrameMetricsAvailableListener TOTAL_DURATION per
 *    frame (NOT Choreographer intervals — those return the vsync period on a
 *    90 Hz panel, not render cost). Refresh rate is reported alongside.
 *  - Memory = own-pid PSS PLUS every related process we can see (renderer /
 *    GPU: /proc/<pid>/cmdline matched against the package, PSS from
 *    smaps_rollup when readable — unreadable pids are REPORTED, not hidden),
 *    PLUS device-wide availMem before/after. Never an app-process-only delta
 *    for the WebView pane.
 */
class FrameStatsCollector(activity: Activity) {

    private val durationsMs = ArrayList<Double>(4096)
    private var dropped = 0L
    private var collecting = false
    val refreshHz: Double

    init {
        refreshHz = try {
            activity.display?.refreshRate?.toDouble() ?: 60.0
        } catch (_: Exception) { 60.0 }
        activity.window.addOnFrameMetricsAvailableListener(
            { _, metrics, dropCount ->
                if (collecting) {
                    durationsMs.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0)
                    dropped += dropCount
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    fun start() { durationsMs.clear(); dropped = 0; collecting = true }
    fun stop() { collecting = false }

    val frameCount: Int get() = durationsMs.size

    /** p50/p95 frame duration in ms; null when no frames were captured. */
    fun p50Ms(): Double? = percentile(0.50)
    fun p95Ms(): Double? = percentile(0.95)

    /** Frames over 1.5x the panel budget, as a percentage. */
    fun jankPct(): Double? {
        if (durationsMs.isEmpty()) return null
        val budget = 1000.0 / refreshHz
        val over = durationsMs.count { it > budget * 1.5 }
        return over * 100.0 / durationsMs.size
    }

    fun droppedCount(): Long = dropped

    private fun percentile(q: Double): Double? {
        if (durationsMs.isEmpty()) return null
        val sorted = durationsMs.sorted()
        return sorted[((sorted.size - 1) * q).toInt()]
    }
}

/** One process we could see: pid -> captured PSS kB (null = NOT CAPTURED). */
data class ProcPss(val pid: Int, val name: String, val pssKb: Long?, val note: String)

/** Full memory picture: own + related + device-wide. */
data class MemSnapshot(
    val ownPssKb: Long,
    val procs: List<ProcPss>,
    val availMemKb: Long,
) {
    /** Sum of everything CAPTURED (own + related readable pids). */
    fun capturedTotalKb(): Long = ownPssKb + procs.filter { it.pssKb != null }.sumOf { it.pssKb!! }
    /** True when some related process exists but its PSS could not be read. */
    fun hasUncaptured(): Boolean = procs.any { it.pssKb == null }
    fun capturedNames(): String =
        (listOf("main") + procs.map { if (it.pssKb != null) it.name else it.name + "(NOT CAPTURED)" })
            .joinToString(",")
}

class MemBench(private val context: Context) {

    fun snapshot(): MemSnapshot {
        val own = Debug.MemoryInfo()
        Debug.getMemoryInfo(own)
        val related = scanRelatedProcesses()
        val avail = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(avail)
        return MemSnapshot(own.totalPss.toLong(), related, avail.availMem / 1024L)
    }

    /**
     * WebView renderer + GPU processes run under this app's package name
     * (e.g. "<pkg>:sandboxed_process0", "<pkg>:gpu"). Enumerate /proc and
     * read smaps_rollup for every same-package pid we can; unreadable ones
     * are reported as NOT CAPTURED rather than silently dropped.
     */
    private fun scanRelatedProcesses(): List<ProcPss> {
        val pkg = context.packageName
        val out = ArrayList<ProcPss>()
        val proc = File("/proc")
        val dirs = proc.listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null } ?: return out
        for (d in dirs) {
            try {
                val cmdline = File(d, "cmdline").readBytes().toString(Charsets.ISO_8859_1)
                val name = cmdline.split(0.toChar().toString()).firstOrNull { it.isNotEmpty() } ?: continue
                if (!name.contains(pkg)) continue
                val pid = d.name.toInt()
                if (pid == android.os.Process.myPid()) continue
                val rollup = File(d, "smaps_rollup")
                val pss = if (rollup.canRead()) {
                    val m = Regex("Pss:\\s+(\\d+) kB").find(rollup.readText())
                    m?.groupValues?.get(1)?.toLong()
                } else null
                out.add(ProcPss(pid, name.substringAfter(':'), pss,
                    if (pss != null) "captured" else "ps-not-readable"))
            } catch (_: Exception) {
                // Different uid or gone — skip silently; only matched pids matter.
            }
        }
        return out
    }

    fun apkInstalledBytes(): Long {
        return try {
            val ai = context.packageManager.getApplicationInfo(context.packageName, 0)
            File(ai.sourceDir).length()
        } catch (_: Exception) { -1L }
    }
}

/**
 * Keystroke -> next frame-presented timestamp, via Choreographer's frame time
 * (a real measurement of input latency, unlike the old invalid 0.00 default).
 * Null until at least one keystroke landed.
 */
class InputLatencyBench {
    private val samplesMs = ArrayList<Double>()
    private var keyAtNs = 0L
    private val choreographer = android.view.Choreographer.getInstance()
    private var armed = false

    private val callback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            if (armed) {
                armed = false
                samplesMs.add(((frameTimeNs - keyAtNs).coerceAtLeast(0)) / 1_000_000.0)
            }
            choreographer.postFrameCallback(this)
        }
    }

    init { choreographer.postFrameCallback(callback) }

    fun onKeyDown() {
        keyAtNs = System.nanoTime() // Choreographer frameTimeNanos is nanoTime-based
        armed = true
    }

    /** Median latency in ms, or null when NOT MEASURED (no typing). */
    fun medianMs(): Double? {
        if (samplesMs.isEmpty()) return null
        val s = samplesMs.sorted()
        return s[s.size / 2]
    }
}

/** Timestamp helper for phase labels. */
fun nowMs(): Long = SystemClock.elapsedRealtime()
