package com.codespace.ide.agent

import android.content.Context

/**
 * Wave 1 Group C (owner approval 2026-11-11): terminal and job inspection.
 *
 * Every answer here comes from state the app itself recorded:
 *  - sessions from [com.codespace.ide.terminal.TerminalSessionRegistry], which the terminal pane
 *    publishes (so an unmanaged process is not addressable, let alone killable);
 *  - command jobs from [AgentTerminalFeed], the app's real run history, which now also records
 *    scheduled-task runs;
 *  - scheduled tasks from their own persisted records, including the last run this session's
 *    scheduler wrote.
 *
 * Nothing is invented: an unknown id says so, an expired row says so, and a session that is not in
 * the registry cannot be stopped by name.
 */
object JobTools {

    const val MAX_SESSIONS = 40
    const val MAX_JOBS = 40
    const val MAX_OUTPUT_TAIL = 600

    // ── terminal_list ──────────────────────────────────────────────────────

    fun terminalList(): String {
        val sessions = com.codespace.ide.terminal.TerminalSessionRegistry.list()
        if (sessions.isEmpty()) {
            return "No terminal sessions are open. The terminal pane publishes its sessions when it " +
                "is shown; open a terminal and ask again."
        }
        val shown = sessions.take(MAX_SESSIONS)
        val sb = StringBuilder("Terminal sessions (${sessions.size}):\n")
        for (s in shown) {
            sb.append("  id=").append(s.id)
            sb.append("  name=").append(s.name)
            sb.append("  ").append(if (s.running) "running" else "stopped")
            if (s.isActive) sb.append(" (active tab)")
            sb.append('\n')
            if (!s.cwd.isNullOrBlank()) sb.append("    cwd: ").append(s.cwd).append('\n')
            if (s.pid > 0) sb.append("    pid: ").append(s.pid).append('\n')
            if (!s.lockedRoot.isNullOrBlank()) sb.append("    locked to: ").append(s.lockedRoot).append('\n')
        }
        if (sessions.size > shown.size) {
            sb.append("  ...and ").append(sessions.size - shown.size).append(" more (not listed)\n")
        }
        sb.append("Use terminal_kill with one of these ids to stop a session.")
        return sb.toString().trimEnd()
    }

    // ── terminal_kill ──────────────────────────────────────────────────────

    sealed class KillOutcome {
        data class Stopped(val id: String, val name: String) : KillOutcome()
        data class AlreadyStopped(val id: String, val name: String) : KillOutcome()
        data class Unknown(val id: String, val known: List<String>) : KillOutcome()
        data class Failed(val id: String, val reason: String) : KillOutcome()

        fun render(): String = when (this) {
            is Stopped -> "Stopped terminal session \"$name\" (id=$id)."
            is AlreadyStopped -> "Terminal session \"$name\" (id=$id) was already stopped."
            is Unknown -> "No managed terminal session with id \"$id\". Known ids: " +
                (if (known.isEmpty()) "(none)" else known.joinToString(", "))
            is Failed -> "Could not stop terminal session id=$id: $reason"
        }
    }

    /**
     * Only an id the pane published can be stopped. `stop()` returns false for an unknown id, so
     * this is the one place a kill is decided - never a raw pid, never a name match.
     */
    fun terminalKill(id: String): KillOutcome {
        val clean = id.trim()
        if (clean.isEmpty()) {
            return KillOutcome.Unknown("", com.codespace.ide.terminal.TerminalSessionRegistry.list().map { it.id })
        }
        val known = com.codespace.ide.terminal.TerminalSessionRegistry.find(clean)
            ?: return KillOutcome.Unknown(clean, com.codespace.ide.terminal.TerminalSessionRegistry.list().map { it.id })
        if (!known.running) return KillOutcome.AlreadyStopped(known.id, known.name)
        val ok = com.codespace.ide.terminal.TerminalSessionRegistry.stop(clean)
        return if (ok) KillOutcome.Stopped(known.id, known.name)
        else KillOutcome.Failed(clean, "the terminal pane did not accept the stop (no pane attached, or the shell refused to exit)")
    }

    // ── job_status ─────────────────────────────────────────────────────────

    /** A job row, unified from the two real sources, so the model reads one shape. */
    data class Job(
        val id: String,
        val kind: String,          // "command" | "scheduled"
        val label: String,
        val status: String,        // running | completed | failed | cancelled
        val startedAtMs: Long,
        val endedAtMs: Long?,
        val exitCode: Int?,
        val outputTail: String?,
    )

    /**
     * Every job the app has actually run this session, newest first: chat/agent commands from the
     * terminal feed, plus the last run of each scheduled task. A scheduled task that has never
     * fired is NOT reported as a finished job - it is reported separately, because "scheduled" is
     * not a status.
     */
    fun jobs(context: Context?): List<Job> {
        val scheduled = if (context != null) {
            try { AgentScheduler.lastRuns(context) } catch (_: Exception) { emptyList() }
        } else emptyList()
        return jobsFrom(AgentTerminalFeed.runs.toList(), scheduled)
    }

    /**
     * The merge, over plain records rather than live stores: chat/agent commands first, then the
     * last run of each scheduled task. Newest first, and a task that has never fired contributes
     * nothing - "scheduled" is not a status.
     */
    fun jobsFrom(
        runs: List<AgentTerminalFeed.Run>,
        scheduled: List<AgentScheduler.LastRun>,
    ): List<Job> {
        val out = ArrayList<Job>(runs.size + scheduled.size)
        for (run in runs) {
            out.add(
                Job(
                    id = "run:" + run.id,
                    kind = "command",
                    label = run.command.take(120),
                    status = when (run.status) {
                        AgentTerminalFeed.Status.RUNNING -> "running"
                        AgentTerminalFeed.Status.DONE -> "completed"
                        AgentTerminalFeed.Status.FAILED -> "failed"
                        AgentTerminalFeed.Status.CANCELLED -> "cancelled"
                    },
                    startedAtMs = run.startedAtMs,
                    endedAtMs = run.endedAtMs,
                    exitCode = run.exitCode,
                    outputTail = run.output?.takeLast(MAX_OUTPUT_TAIL),
                )
            )
        }
        for (last in scheduled) {
            out.add(
                Job(
                    id = "task:" + last.name,
                    kind = "scheduled",
                    label = last.name + " [" + last.cron + "]",
                    status = when {
                        last.timedOut -> "cancelled"
                        last.failed -> "failed"
                        else -> "completed"
                    },
                    startedAtMs = last.startedAt,
                    endedAtMs = last.endedAt,
                    exitCode = last.exitCode,
                    outputTail = if (last.timedOut) "Timed out after 60s." else null,
                )
            )
        }
        return out.sortedByDescending { it.startedAtMs }
    }

    fun jobStatus(context: Context?, id: String?, limit: Int = 10): String {
        val all = jobs(context)
        val wanted = id?.trim().orEmpty()
        if (wanted.isNotEmpty()) {
            val one = all.firstOrNull { it.id == wanted }
                ?: all.firstOrNull { it.id.equals(wanted, ignoreCase = true) }
            if (one == null) {
                val known = all.take(5).joinToString(", ") { it.id }
                return "No job with id \"" + wanted + "\"." +
                    if (known.isBlank()) " No jobs have run in this session."
                    else " Known ids include: $known"
            }
            return render(one)
        }
        if (all.isEmpty()) return "No jobs have run in this session (no commands, and no scheduled task has fired yet)."
        val shown = all.take(limit.coerceIn(1, MAX_JOBS))
        val sb = StringBuilder("Jobs (newest first, showing ${shown.size} of ${all.size}):\n")
        for (j in shown) {
            sb.append("  ").append(j.id).append("  ").append(j.status).append("  ").append(j.kind)
                .append("  ").append(j.label).append('\n')
        }
        sb.append("Ask for one id for its timings, exit code and output.")
        return sb.toString().trimEnd()
    }

    private fun render(j: Job): String {
        val sb = StringBuilder()
        sb.append("Job ").append(j.id).append(" (").append(j.kind).append(")\n")
        sb.append("label: ").append(j.label).append('\n')
        sb.append("status: ").append(j.status).append('\n')
        sb.append("started: ").append(j.startedAtMs)
        j.endedAtMs?.let { sb.append(", ended: ").append(it).append(", duration: ").append(it - j.startedAtMs).append(" ms") }
        sb.append('\n')
        j.exitCode?.let { sb.append("exit code: ").append(it).append('\n') }
        j.outputTail?.takeIf { it.isNotBlank() }?.let { sb.append("output (tail):\n").append(it).append('\n') }
        return sb.toString().trimEnd()
    }
}
