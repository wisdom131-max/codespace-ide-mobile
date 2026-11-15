package com.codespace.ide.agent.research

/**
 * PROJECT 1.10 (2026-11-15): research progress.
 *
 * A research round is seconds of blocking HTTP with nothing on screen: the transcript only gains a
 * tool row after the call returns, so a thin search that escalates to three asks looks like a hang.
 * The spec asks for research progress, so the round publishes what it is doing and the panel reads
 * it on a ticker.
 *
 * Deliberately tiny and thread-safe: the search thread writes, the UI thread reads, and nothing
 * here allocates per tick.
 */
object ResearchProgress {

    /** One step of a round. [part] is 1-based, [of] is the number of searches this round will run. */
    data class Snapshot(
        val label: String,
        val part: Int,
        val of: Int,
        val startedAtMs: Long,
    ) {
        fun line(nowMs: Long): String {
            val secs = ((nowMs - startedAtMs) / 1000L).coerceAtLeast(0L)
            val where = if (of > 1) " (" + part + " of " + of + ")" else ""
            return label + where + " " + secs + "s"
        }
    }

    @Volatile
    private var current: Snapshot? = null

    fun begin(label: String, of: Int): Snapshot {
        val s = Snapshot(label, 1, of.coerceAtLeast(1), System.currentTimeMillis())
        current = s
        return s
    }

    fun step(part: Int, label: String?) {
        val prev = current ?: return
        current = prev.copy(part = part.coerceAtLeast(1), label = label ?: prev.label)
    }

    fun finish() {
        current = null
    }

    fun snapshot(): Snapshot? = current
}
