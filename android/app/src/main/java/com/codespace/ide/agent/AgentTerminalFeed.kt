package com.codespace.ide.agent

import androidx.compose.runtime.mutableStateListOf

/**
 * U6-c — agent command visibility.
 *
 * The chat panel's tool loop executes commands through AgentTools.runCommand, which runs
 * them via ProotInstaller.execOnce (a one-shot exec, NOT the user's interactive shell). So
 * "show the command in the terminal" cannot mean typing it into the live session: recreating
 * it there would execute it a SECOND time. Instead this feed is the terminal's honest record
 * of what the agent ran: the real command, its real status, and its real output, rendered by
 * the terminal pane in an agent strip.
 *
 * Association rules that matter (owner spec U6-c):
 *  - Each execution gets its OWN id, so output can never be attached to the wrong run when
 *    several land in a row.
 *  - The list is capped and keyed by id, so a Compose re-composition (or a pane re-open)
 *    re-renders the same rows instead of appending duplicates.
 *  - Status is explicit and terminal: RUNNING -> DONE / FAILED / CANCELLED. There is no
 *    "success" default: a run that throws is finished as FAILED with the message.
 */
object AgentTerminalFeed {

    enum class Status { RUNNING, DONE, FAILED, CANCELLED }

    data class Run(
        val id: Long,
        val command: String,
        val workdir: String?,
        val status: Status,
        val output: String?,
        /** Exit status when the executor reports one; null when unknown (never guessed). */
        val exitCode: Int?,
        val startedAtMs: Long,
        val endedAtMs: Long?,
    ) {
        val isFinished: Boolean get() = status != Status.RUNNING
    }

    /** How many finished runs the strip keeps; older ones fall off the top. */
    const val MAX_RUNS = 20

    /** How much output a single row carries; the full text already went to the model. */
    const val MAX_OUTPUT_CHARS = 4000

    private var nextId = 1L

    /** Observable, capped, oldest-first. The pane renders the tail. */
    val runs = mutableStateListOf<Run>()

    /** Called BEFORE execution, so a long-running command is visible while it runs. */
    @Synchronized
    fun begin(command: String, workdir: String?, nowMs: Long = System.currentTimeMillis()): Long {
        val id = nextId++
        runs.add(
            Run(
                id = id,
                command = command,
                workdir = workdir,
                status = Status.RUNNING,
                output = null,
                exitCode = null,
                startedAtMs = nowMs,
                endedAtMs = null,
            ),
        )
        trim()
        return id
    }

    /**
     * Called after execution finishes (or throws). Updates THAT id only, so concurrent runs
     * cannot overwrite each other's rows.
     */
    @Synchronized
    fun finish(
        id: Long,
        output: String?,
        failed: Boolean = false,
        cancelled: Boolean = false,
        exitCode: Int? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val i = runs.indexOfFirst { it.id == id }
        if (i < 0) return
        val status = when {
            cancelled -> Status.CANCELLED
            failed -> Status.FAILED
            else -> Status.DONE
        }
        runs[i] = runs[i].copy(
            status = status,
            output = output?.let { clip(it) },
            exitCode = exitCode,
            endedAtMs = nowMs,
        )
    }

    /** A command that never got to run (approval refused, executor missing). */
    @Synchronized
    fun cancel(id: Long, reason: String? = null, nowMs: Long = System.currentTimeMillis()) {
        val i = runs.indexOfFirst { it.id == id }
        if (i < 0) return
        runs[i] = runs[i].copy(
            status = Status.CANCELLED,
            output = reason ?: runs[i].output,
            endedAtMs = nowMs,
        )
    }

    fun clear() {
        synchronized(this) { runs.clear() }
    }

    private fun trim() {
        while (runs.size > MAX_RUNS) runs.removeAt(0)
    }

    private fun clip(raw: String): String =
        if (raw.length <= MAX_OUTPUT_CHARS) raw
        else raw.take(MAX_OUTPUT_CHARS) + "\n[... truncated in the terminal record]"

    // --- pure helpers, unit-tested -------------------------------------------------------

    /** "2.4s" / "41s" / "3m 12s" — compact, no locale surprises. */
    fun durationLabel(startedAtMs: Long, endedAtMs: Long?): String {
        val end = endedAtMs ?: return "running"
        val ms = (end - startedAtMs).coerceAtLeast(0)
        val totalSeconds = ms / 1000
        return when {
            totalSeconds < 60 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
            totalSeconds < 3600 -> {
                val m = totalSeconds / 60
                val s = totalSeconds % 60
                if (s == 0L) m.toString() + "m" else m.toString() + "m " + s + "s"
            }
            else -> {
                val h = totalSeconds / 3600
                val m = (totalSeconds % 3600) / 60
                m == 0L ? h.toString() + "h" : h.toString() + "h " + m + "m"
            }
        }
    }

    /** One line for the strip header: status word + duration, never color-only. */
    fun statusLabel(run: Run): String = when (run.status) {
        Status.RUNNING -> "running"
        Status.DONE -> if (run.exitCode != null && run.exitCode != 0) "exit " + run.exitCode else "done"
        Status.FAILED -> "failed"
        Status.CANCELLED -> "cancelled"
    }

    /** True when the row should show output (a finished run with any output at all). */
    fun showsOutput(run: Run): Boolean = run.isFinished && !run.output.isNullOrBlank()
}
