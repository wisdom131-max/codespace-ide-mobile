package com.codespace.ide.chat.panel

import com.codespace.ide.chat.provider.ToolOutcome
import com.codespace.ide.chat.ui.ChatRowModel

/**
 * PROJECT SEVEN 7.3 / 7.4 / 7.5 — the bridge from RUNTIME rows to the presenters.
 *
 * The presenters in [CopilotPresentation] decide wording; this object decides what they are allowed to
 * be told. Everything comes from a real [ChatRowModel] the session produced, so no surface can show a
 * state the runtime never had:
 *
 *  - a step whose runtime said `ok = false` is FAILED;
 *  - a step whose own text says it was refused is BLOCKED (it never ran);
 *  - a step that says its outcome is uncertain is UNKNOWN, and a retry is refused for it;
 *  - a step with no verdict yet is ACTIVE, never DONE. Success is not inferred from absence of failure.
 */
object CopilotBindings {

    private val REFUSED = Regex("\\brefused\\b|\\bnot run\\b|\\bdeclined\\b", RegexOption.IGNORE_CASE)
    private val UNCERTAIN = Regex("uncertain|unknown outcome|may have run|no response", RegexOption.IGNORE_CASE)
    private val FAILED_WORD = Regex("\\bfailed\\b|\\berror\\b|\\bnot found\\b", RegexOption.IGNORE_CASE)

    /** The honest state of one tool step, from the row the session actually emitted. */
    fun taskState(activity: ChatRowModel.ToolActivity): CopilotPresentation.TaskState {
        val text = activity.label + " " + activity.detail
        if (REFUSED.containsMatchIn(text)) return CopilotPresentation.TaskState.BLOCKED
        if (UNCERTAIN.containsMatchIn(text)) return CopilotPresentation.TaskState.UNKNOWN
        if (!activity.ok) return CopilotPresentation.TaskState.FAILED
        if (FAILED_WORD.containsMatchIn(activity.detail)) return CopilotPresentation.TaskState.FAILED
        // No verdict in the row: the step is queued or running, and this code does not guess which.
        return CopilotPresentation.TaskState.ACTIVE
    }

    /** The step as a tree node, keeping the runtime's own id so identity survives a redraw. */
    fun taskNode(activity: ChatRowModel.ToolActivity, index: Int): CopilotPresentation.TaskNode =
        CopilotPresentation.TaskNode(
            id = activity.id.ifBlank { "step-" + index },
            parentId = null,
            title = activity.label,
            state = taskState(activity),
        )

    fun taskNodes(items: List<ChatRowModel>): List<CopilotPresentation.TaskNode> =
        items.filterIsInstance<ChatRowModel.ToolActivity>()
            .mapIndexed { i, a -> taskNode(a, i) }

    /**
     * The group header line: the existing summary, plus the counted progress. The count comes from the
     * rows in the group, so a group of three steps never reads "3 of 3 done" unless the runtime said so.
     */
    fun groupHeader(summary: String, items: List<ChatRowModel>): String {
        val nodes = taskNodes(items)
        if (nodes.isEmpty()) return summary
        val progress = CopilotPresentation.progress(nodes)
        return if (progress.isBlank()) summary else summary + " \u00b7 " + progress
    }

    /** One child line inside the tree, with the state word spelled out (never colour alone). */
    fun childLine(activity: ChatRowModel.ToolActivity): String {
        val word = when (taskState(activity)) {
            CopilotPresentation.TaskState.DONE -> "done"
            CopilotPresentation.TaskState.ACTIVE -> "running"
            CopilotPresentation.TaskState.FAILED -> "failed"
            CopilotPresentation.TaskState.BLOCKED -> "refused"
            CopilotPresentation.TaskState.QUEUED -> "queued"
            CopilotPresentation.TaskState.UNKNOWN -> "outcome unknown"
        }
        return "[" + word + "] " + activity.label
    }

    /**
     * The tool result as a bounded row: the presenter's own 600-character rule and the cut statement,
     * applied to the real output before it can reach a chat pane.
     */
    fun resultRow(activity: ChatRowModel.ToolActivity, output: String): CopilotPresentation.ToolRow =
        CopilotPresentation.toolRow(
            toolName = activity.label,
            outcome = resultOutcome(activity, output),
            output = output,
            refusal = isRefusal(activity),
        )

    /** True when the runtime's own words say this step was refused, which is not a failure. */
    fun isRefusal(activity: ChatRowModel.ToolActivity): Boolean =
        taskState(activity) == CopilotPresentation.TaskState.BLOCKED

    /**
     * PROJECT SEVEN 7.11 seam fix: a RESULT row that returned ok with real output is a success. The
     * earlier mapping sent every "ok but no verdict word" row to UNCERTAIN, so a step that plainly
     * worked was described as "may have run" and offered a verification step it did not need.
     */
    fun resultOutcome(activity: ChatRowModel.ToolActivity, output: String): ToolOutcome = when {
        isRefusal(activity) -> ToolOutcome.FAILED
        !activity.ok -> ToolOutcome.FAILED
        activity.detail.contains("uncertain", ignoreCase = true) -> ToolOutcome.UNCERTAIN
        activity.detail.contains("may have run", ignoreCase = true) -> ToolOutcome.UNCERTAIN
        output.isNotBlank() -> ToolOutcome.SUCCESS
        else -> ToolOutcome.UNCERTAIN
    }

    /** The execution truth of a finished step, for the retry decision the user is shown. */
    fun outcomeOf(activity: ChatRowModel.ToolActivity): ToolOutcome = when (taskState(activity)) {
        CopilotPresentation.TaskState.DONE -> ToolOutcome.SUCCESS
        CopilotPresentation.TaskState.BLOCKED -> ToolOutcome.FAILED
        CopilotPresentation.TaskState.FAILED -> ToolOutcome.FAILED
        CopilotPresentation.TaskState.UNKNOWN -> ToolOutcome.UNCERTAIN
        CopilotPresentation.TaskState.ACTIVE, CopilotPresentation.TaskState.QUEUED -> ToolOutcome.UNCERTAIN
    }

    /**
     * What the user may do next with this step. A refused step can be adjusted and retried; an uncertain
     * one must be verified first. This is the same rule the presenter states, reached from real data.
     */
    /**
     * The retry decision for a step whose RESULT is known. Separate from [nextAction] on purpose: a
     * step with no output has not answered yet, so nothing may be claimed about it.
     */
    fun nextAction(activity: ChatRowModel.ToolActivity, output: String): CopilotPresentation.OutcomeView =
        CopilotPresentation.outcome(
            toolName = activity.label,
            outcome = resultOutcome(activity, output),
            refusal = isRefusal(activity),
        )

    fun nextAction(activity: ChatRowModel.ToolActivity): CopilotPresentation.OutcomeView =
        CopilotPresentation.outcome(
            toolName = activity.label,
            outcome = outcomeOf(activity),
            refusal = isRefusal(activity),
        )
}
