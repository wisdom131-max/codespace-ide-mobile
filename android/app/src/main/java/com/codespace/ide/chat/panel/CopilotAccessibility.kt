package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel

/**
 * PROJECT SEVEN 7.9 — what a screen reader says, decided once.
 *
 * TalkBack reads the visual text, which is why the state words are spelled out in the tree. What it
 * cannot infer is the meaning of a row: that a tool line is a step, whether it finished, and whether a
 * source is tappable. These labels supply that, and they are pure so they can be tested without a device.
 */
object CopilotAccessibility {


    fun toolLabel(activity: ChatRowModel.ToolActivity, state: CopilotPresentation.TaskState): String {
        val what = activity.label.ifBlank { "tool step" }
        val verb = when (state) {
            CopilotPresentation.TaskState.DONE -> "finished"
            CopilotPresentation.TaskState.ACTIVE -> "running"
            CopilotPresentation.TaskState.FAILED -> "failed"
            CopilotPresentation.TaskState.BLOCKED -> "refused, did not run"
            CopilotPresentation.TaskState.QUEUED -> "queued"
            CopilotPresentation.TaskState.UNKNOWN -> "outcome unknown, may have run"
        }
        return "Assistant step, " + what + ", " + verb + ". Double tap to expand the details."
    }

    fun groupLabel(summary: String, items: List<ChatRowModel>, expanded: Boolean): String =
        summary + ". " + (if (expanded) "Expanded, double tap to collapse." else "Collapsed, double tap to expand.")

    fun messageLabel(isUser: Boolean, text: String): String {
        val who = if (isUser) "You said" else "Assistant said"
        val one = text.replace('\n', ' ').trim()
        val short = if (one.length <= 200) one else one.take(200) + ", longer message"
        return who + ": " + short
    }

    /**
     * PROJECT SEVEN 7.9E — what to ANNOUNCE while text streams in.
     *
     * Announcing every token is worse than announcing nothing: TalkBack drowns the user in fragments of
     * half-written sentences and the actually important events - done, failed, interrupted - are lost in
     * the noise. So the stream is announced in meaningful steps only: the first words, then every
     * [ANNOUNCE_EVERY_CHARS] characters, plus every terminal transition.
     */
    const val ANNOUNCE_EVERY_CHARS = 400

    fun announcement(
        previous: String?,
        next: String,
        finished: Boolean = false,
        failed: Boolean = false,
        interrupted: Boolean = false,
    ): String? {
        if (failed) return "Response failed. " + boundedTail(next)
        if (interrupted) return "Response interrupted. " + boundedTail(next)
        if (finished) return "Response complete. " + boundedTail(next)
        if (next.isBlank()) return null
        if (previous == null || previous.isBlank()) return "Assistant is replying."
        // Re-announcing on every small update is the failure mode this exists to prevent.
        if (next.length - previous.length < ANNOUNCE_EVERY_CHARS) return null
        return "Assistant is still replying, " + next.length + " characters so far."
    }

    private fun boundedTail(text: String): String {
        val one = text.replace('\n', ' ').trim()
        return if (one.length <= 120) one else "… " + one.takeLast(120)
    }

    /** A status that reads the same with colour removed: the word carries the state. */
    fun statusText(state: CopilotPresentation.TaskState): String = when (state) {
        CopilotPresentation.TaskState.DONE -> "done"
        CopilotPresentation.TaskState.ACTIVE -> "running"
        CopilotPresentation.TaskState.FAILED -> "failed"
        CopilotPresentation.TaskState.BLOCKED -> "refused"
        CopilotPresentation.TaskState.QUEUED -> "queued"
        CopilotPresentation.TaskState.UNKNOWN -> "unknown"
    }
}
