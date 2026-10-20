package com.codespace.ide.lsp

/**
 * P1e (advisor, 2026-10-20): the READ-ONLY "Language servers" status rows shown
 * in Settings. PURE presentation logic — everything here is JVM-testable without
 * a process or a device; LspManager.statusRows() feeds it the state it already
 * tracks ([LSP-LIFECYCLE] family: serverStates, lastWorkspacePath,
 * restartBackoffs, serverStartedAtMs, lastStopReasons). No behavior change:
 * this file reads state, it never mutates any.
 */
data class LspStatusRow(
    val languageName: String,
    val stateLabel: String,
    val project: String,
    val uptimeLabel: String,
    val restartCount: Int,
    val lastStopReason: String,
)

object LspStatus {

    /** Plain-words state for all nine LspState values (decision-table tested). */
    fun formatState(state: LspState): String = when (state) {
        LspState.STOPPED -> "Stopped"
        LspState.STARTING -> "Starting…"
        LspState.INITIALIZING -> "Initializing…"
        LspState.READY -> "Running"
        LspState.UNHEALTHY -> "Unhealthy"
        LspState.RESTARTING -> "Restarting…"
        LspState.STOPPING -> "Stopping…"
        LspState.IDLE_CLOSE -> "Stopping (idle)"
        LspState.FAILED_DISABLED -> "Disabled — crash cap (tap Retry to re-enable)"
    }

    /**
     * Uptime since [startedAtMs] at [nowMs]. 0 or a future start time = never
     * started ("—"); a server still mid-start counts from its spawn time the
     * same as a ready one (the row shows state separately).
     */
    fun formatUptime(startedAtMs: Long, nowMs: Long): String {
        if (startedAtMs <= 0L || nowMs < startedAtMs) return "—"
        val s = (nowMs - startedAtMs) / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    /**
     * One row from the manager's raw state. Nulls and absences degrade to
     * honest placeholders ("—"), never to a guess: a language never started
     * shows Stopped / — / — / 0 / —.
     */
    fun buildRow(
        languageName: String,
        state: LspState,
        project: String?,
        startedAtMs: Long,
        nowMs: Long,
        restartCount: Int,
        lastStopReason: String?,
    ): LspStatusRow = LspStatusRow(
        languageName = languageName,
        stateLabel = formatState(state),
        project = project?.takeIf { it.isNotBlank() } ?: "—",
        uptimeLabel = formatUptime(startedAtMs, nowMs),
        restartCount = restartCount.coerceAtLeast(0),
        lastStopReason = lastStopReason?.takeIf { it.isNotBlank() } ?: "—",
    )
}
