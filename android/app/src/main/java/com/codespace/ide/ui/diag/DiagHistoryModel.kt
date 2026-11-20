package com.codespace.ide.ui.diag

import com.codespace.ide.agent.CopilotDiag

/**
 * P4.5: the pure part of the diagnostic history screen. Filtering, labelling, formatting and
 * bounding live here so they are testable without Compose, and so the screen holds no logic of
 * its own that could drift from the tested behaviour.
 *
 * It reads [CopilotDiag] directly. There is deliberately no second event list: the screen shows
 * exactly what the recorder stored, already redacted at the recording boundary.
 */
object DiagHistoryModel {

    /** The most rows the screen will ever hold in memory at once. */
    const val MAX_ROWS = 200

    data class Row(
        val kind: CopilotDiag.Kind,
        val name: String,
        val ok: Boolean,
        val detail: String,
        val at: Long,
        val session: String,
    )

    /** Newest first, bounded, optionally narrowed to one kind and/or failures only. */
    fun rows(
        kind: CopilotDiag.Kind? = null,
        onlyFailures: Boolean = false,
        limit: Int = MAX_ROWS,
    ): List<Row> = CopilotDiag.recent(CopilotDiag.MAX_EVENTS)
        .asReversed()
        .asSequence()
        .filter { kind == null || it.kind == kind }
        .filter { !onlyFailures || !it.ok }
        .take(limit.coerceIn(1, MAX_ROWS))
        .map { Row(it.kind, it.name, it.ok, it.detail, it.at, it.session) }
        .toList()

    /** Which kinds currently have at least one event, in a stable display order. */
    fun availableKinds(): List<CopilotDiag.Kind> {
        val present = CopilotDiag.recent(CopilotDiag.MAX_EVENTS).map { it.kind }.toSet()
        return CopilotDiag.Kind.entries.filter { it in present }
    }

    fun label(kind: CopilotDiag.Kind): String = when (kind) {
        CopilotDiag.Kind.SESSION -> "Session"
        CopilotDiag.Kind.TOOL_START -> "Tool start"
        CopilotDiag.Kind.TOOL_END -> "Tool end"
        CopilotDiag.Kind.APPROVAL -> "Approval"
        CopilotDiag.Kind.APPROVAL_REQUIRED -> "Approval required"
        CopilotDiag.Kind.APPROVAL_GRANTED -> "Approval granted"
        CopilotDiag.Kind.APPROVAL_DENIED -> "Approval denied"
        CopilotDiag.Kind.APPROVAL_CANCELLED -> "Approval cancelled"
        CopilotDiag.Kind.EXECUTION_AUTHORIZED -> "Execution authorized"
        CopilotDiag.Kind.WRITE_POLICY_ALLOW -> "Write ALLOW"
        CopilotDiag.Kind.WRITE_POLICY_STAGE -> "Write STAGE"
        CopilotDiag.Kind.WRITE_POLICY_REFUSE -> "Write REFUSE"
        CopilotDiag.Kind.OFFLOAD -> "Offload"
        CopilotDiag.Kind.RETRIEVAL -> "Retrieval"
        CopilotDiag.Kind.PROVIDER_ERROR -> "Provider error"
        CopilotDiag.Kind.PROVIDER_REQUEST_START -> "Provider start"
        CopilotDiag.Kind.PROVIDER_REQUEST_SUCCESS -> "Provider success"
        CopilotDiag.Kind.PROVIDER_REQUEST_FAILURE -> "Provider failure"
        CopilotDiag.Kind.PROVIDER_REQUEST_CANCELLED -> "Provider cancelled"
        CopilotDiag.Kind.RETRY_SCHEDULED -> "Retry scheduled"
        CopilotDiag.Kind.RETRY_STARTED -> "Retry started"
        CopilotDiag.Kind.RETRY_EXHAUSTED -> "Retry exhausted"
        CopilotDiag.Kind.CONTINUATION -> "Continuation"
        CopilotDiag.Kind.CONTINUATION_STARTED -> "Continuation started"
        CopilotDiag.Kind.CONTINUATION_COMPLETED -> "Continuation done"
        CopilotDiag.Kind.CONTINUATION_FAILED -> "Continuation failed"
        CopilotDiag.Kind.CONTINUATION_SKIPPED -> "Continuation skipped"
        CopilotDiag.Kind.CONTINUATION_CANCELLED -> "Continuation cancelled"
    }

    /** Clock time only: a diagnostic row is read in the context of "just now". */
    fun time(at: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
        fun two(v: Int) = if (v < 10) "0" + v else "" + v
        return two(c.get(java.util.Calendar.HOUR_OF_DAY)) + ":" + two(c.get(java.util.Calendar.MINUTE)) +
            ":" + two(c.get(java.util.Calendar.SECOND))
    }

    fun outcome(row: Row): String = if (row.ok) "ok" else "failed"

    /** One-line summary for the section header. */
    fun summary(): String {
        val all = CopilotDiag.recent(CopilotDiag.MAX_EVENTS)
        if (all.isEmpty()) return "No diagnostic events yet"
        val failed = all.count { !it.ok }
        return all.size.toString() + " events" + (if (failed > 0) ", " + failed + " failed" else "")
    }

    /** Clearing is explicit and total; it never touches project files. */
    fun clear() {
        CopilotDiag.clear()
        CopilotDiag.flush()
    }
}
