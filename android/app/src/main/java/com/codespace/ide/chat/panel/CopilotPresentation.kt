package com.codespace.ide.chat.panel

import com.codespace.ide.chat.provider.ToolOutcome
import com.codespace.ide.engine.SummaryKind

/**
 * PROJECT SEVEN — presentation that cannot outrun the runtime.
 *
 * Every function here is pure: it takes state the runtime already established and returns the exact
 * words and flags a surface may show. Nothing in this file invents progress, a success, or an outcome.
 * Where the runtime did not say, the answer is UNKNOWN and the UI is required to say so.
 *
 * The rule the brief keeps repeating - "the UI must reflect actual runtime state", "never claim an
 * operation succeeded when its outcome is unknown" - is easier to hold if the wording is decided here,
 * once, and tested, instead of being re-invented per row.
 */
object CopilotPresentation {

    // ---------------------------------------------------------------- 7.5 outcome truth

    /** Whether the operation left a trace outside the app. The whole retry question hangs on this. */
    enum class Ran { NO, YES, UNKNOWN }

    data class OutcomeView(
        /** One line: what happened, in the owner's terms. */
        val headline: String,
        val ran: Ran,
        /** True only when re-running cannot duplicate a side effect. */
        val retrySafe: Boolean,
        /** Label for the offered action, or null when nothing safe can be offered. */
        val actionLabel: String?,
        /** Why retrying is not safe, shown next to the disabled action. */
        val whyNot: String?,
    )

    /**
     * A tool result as the owner should read it.
     *
     * [UNCERTAIN] is the case this exists for: the call may have reached the provider and the response
     * was lost. It reads "may have run", refuses a plain Retry, and points at verification instead of
     * pretending the call either failed or succeeded.
     */
    fun outcome(
        toolName: String,
        outcome: ToolOutcome,
        detail: String? = null,
        refusal: Boolean = false,
    ): OutcomeView {
        val name = toolName.ifBlank { "the tool" }
        if (refusal) {
            return OutcomeView(
                headline = name + " was refused before it ran.",
                ran = Ran.NO,
                retrySafe = true,
                actionLabel = "Adjust and retry",
                whyNot = null,
            )
        }
        return when (outcome) {
            ToolOutcome.SUCCESS -> OutcomeView(
                headline = name + " ran and returned a result.",
                ran = Ran.YES,
                retrySafe = false,
                actionLabel = null,
                whyNot = null,
            )
            ToolOutcome.FAILED -> OutcomeView(
                headline = name + " failed before completing." + (detail?.let { " " + it } ?: ""),
                ran = Ran.NO,
                retrySafe = true,
                actionLabel = "Retry",
                whyNot = null,
            )
            ToolOutcome.CANCELLED -> OutcomeView(
                headline = name + " was cancelled. It is not known how far it got.",
                ran = Ran.UNKNOWN,
                // A cancelled write may still have landed: calling that "safe to retry" is the exact
                // duplicate side effect this codebase spends its effort preventing.
                retrySafe = false,
                actionLabel = "Check the outcome first",
                whyNot = "Cancellation does not prove nothing happened.",
            )
            ToolOutcome.UNCERTAIN -> OutcomeView(
                headline = name + " may have run: the response was lost, so its result is unknown.",
                ran = Ran.UNKNOWN,
                retrySafe = false,
                actionLabel = "Verify before retrying",
                whyNot = "Retrying could apply the same change twice.",
            )
        }
    }

    // ---------------------------------------------------------------- 7.4 tool rows

    /** How much of a tool's output a chat row shows before it is cut, with the cut stated. */
    const val TOOL_ROW_CHARS = 600

    data class ToolRow(
        val title: String,
        val state: String,
        val body: String,
        val cut: Boolean,
        val needsApproval: Boolean,
    )

    /**
     * A tool result as one readable row. Large outputs are bounded HERE rather than in the renderer, so
     * a 40k-character result cannot reach a low-memory device's chat pane and freeze a scroll frame.
     */
    fun toolRow(
        toolName: String,
        outcome: ToolOutcome,
        output: String,
        needsApproval: Boolean = false,
        maxChars: Int = TOOL_ROW_CHARS,
    ): ToolRow {
        val view = outcome(toolName, outcome)
        val flat = output.replace("\r", "").trim()
        val cut = flat.length > maxChars
        val body = if (!cut) flat
        else flat.take(maxChars) + "\n[cut " + (flat.length - maxChars) + " of " + flat.length + " characters]"
        return ToolRow(
            title = toolName.ifBlank { "tool" },
            state = view.headline,
            body = body,
            cut = cut,
            needsApproval = needsApproval,
        )
    }

    // ---------------------------------------------------------------- 7.3 task states

    /** The only states a row may claim. Anything else the runtime might report maps to UNKNOWN. */
    enum class TaskState { QUEUED, ACTIVE, DONE, FAILED, BLOCKED, UNKNOWN }

    data class TaskNode(
        val id: String,
        val parentId: String?,
        val title: String,
        val state: TaskState,
        val attempts: Int = 0,
    ) {
        /** Stable identity: the runtime's own id, so a redraw cannot renumber a task. */
        val key: String get() = id
    }

    /**
     * Parses the runtime's own state word. An unrecognised word is UNKNOWN, never DONE: a task whose
     * state we cannot read has not been shown to be finished.
     */
    fun taskState(raw: String?): TaskState = when (raw?.trim()?.lowercase()) {
        "queued", "pending", "waiting" -> TaskState.QUEUED
        "active", "running", "in_progress", "in progress" -> TaskState.ACTIVE
        "done", "completed", "complete", "succeeded", "pass" -> TaskState.DONE
        "failed", "error" -> TaskState.FAILED
        "blocked", "refused", "cancelled", "abandoned" -> TaskState.BLOCKED
        else -> TaskState.UNKNOWN
    }

    /** Children grouped under their parent, in the order the runtime listed them. */
    fun taskTree(nodes: List<TaskNode>): List<Pair<TaskNode, List<TaskNode>>> =
        nodes.filter { it.parentId == null }.map { parent ->
            parent to nodes.filter { it.parentId == parent.id }
        }

    /** "3 of 5 done" - counted from states, and it refuses to round an unknown up to done. */
    fun progress(nodes: List<TaskNode>): String {
        if (nodes.isEmpty()) return ""
        val done = nodes.count { it.state == TaskState.DONE }
        val unknown = nodes.count { it.state == TaskState.UNKNOWN }
        val tail = if (unknown == 0) "" else ", " + unknown + " with no state reported"
        return "" + done + " of " + nodes.size + " done" + tail
    }

    // ---------------------------------------------------------------- 7.7 carried context

    /**
     * What a successor session is about to receive, described from the ACTUAL summary lines and exchange
     * records, so "carried context" is a report rather than a promise.
     */
    data class CarriedContext(
        val headline: String,
        val detail: List<String>,
        val unresolved: Int,
        val inspectedIds: Int,
        val empty: Boolean,
    )

    fun carriedContext(
        label: String,
        kinds: List<Pair<SummaryKind, String>>,
        exchangeCount: Int,
        retainedOnDisk: Boolean,
    ): CarriedContext {
        if (kinds.isEmpty() && exchangeCount == 0) {
            return CarriedContext(
                headline = label + " starts with no earlier context: nothing was carried.",
                detail = emptyList(),
                unresolved = 0,
                inspectedIds = 0,
                empty = true,
            )
        }
        val unresolved = kinds.count {
            it.first == SummaryKind.OPEN_TASK || it.first == SummaryKind.UNCERTAINTY || it.first == SummaryKind.FAILURE
        }
        val detail = ArrayList<String>()
        for ((kind, name) in linkedMapOf(
            SummaryKind.DECISION to "decision",
            SummaryKind.INSTRUCTION to "instruction",
            SummaryKind.OPEN_TASK to "open task",
            SummaryKind.UNCERTAINTY to "uncertainty",
            SummaryKind.FAILURE to "failure",
            SummaryKind.TOOL_RESULT to "tool result",
            SummaryKind.FACT to "fact",
            SummaryKind.OTHER to "other",
        )) {
            val n = kinds.count { it.first == kind }
            if (n > 0) detail.add(n.toString() + " " + name + (if (n == 1) "" else "s"))
        }
        if (exchangeCount > 0) detail.add(exchangeCount.toString() + " historical tool exchange" +
            (if (exchangeCount == 1) "" else "s") + " (inert data, never re-run)")
        if (!retainedOnDisk) detail.add("this summary is memory-only: it will not survive a restart")
        return CarriedContext(
            headline = label + " carries " + kinds.size + " labelled line(s) of earlier context" +
                (if (unresolved > 0) ", including " + unresolved + " unresolved item(s)" else "") + ".",
            detail = detail,
            unresolved = unresolved,
            inspectedIds = kinds.size,
            empty = false,
        )
    }

    /**
     * The summary a handoff actually carries is the RENDERED text the engine produced. Reading the
     * labels back out of it is honest: those lines are the runtime's own output, not a second guess at
     * what the conversation contained.
     */
    fun kindsFromRenderedSummary(rendered: String): List<Pair<SummaryKind, String>> {
        if (rendered.isBlank()) return emptyList()
        val out = ArrayList<Pair<SummaryKind, String>>()
        for (line in rendered.split("\n")) {
            val t = line.trim()
            if (!t.startsWith("- [")) continue
            val close = t.indexOf(']')
            if (close <= 3) continue
            val kind = SummaryKind.values().firstOrNull { it.name == t.substring(3, close) }
                ?: SummaryKind.OTHER
            out.add(kind to t.substring(close + 1).trim())
        }
        return out
    }

    /** One line for a chat notice, built from the same facts. */
    fun carriedContextLine(c: CarriedContext): String =
        if (c.empty) c.headline else c.headline + " " + c.detail.joinToString(" · ") + "."
}
