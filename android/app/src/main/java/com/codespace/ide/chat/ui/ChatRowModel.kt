package com.codespace.ide.chat.ui

/**
 * UI-model rows the chat renderer receives. The panel maps engine events and
 * session state into these; the renderer never sees transport details.
 */
sealed interface ChatRowModel {
    data class User(val text: String) : ChatRowModel

    /**
     * What a stream row IS, which the tree needs and the transcript did not say before.
     *
     * PROGRESS is the narration a model writes before it acts ("Reading the manifest"). ANSWER is
     * the reply. They looked identical, so grouping treated mid-task narration as the end of the
     * turn and everything after it escaped the tree. Default ANSWER keeps every existing call site
     * honest: a stream that does not say it is progress IS the answer.
     */
    enum class StreamKind { PROGRESS, ANSWER }

    data class AssistantStream(
        val text: String,
        val cursorOn: Boolean,
        val kind: StreamKind = StreamKind.ANSWER,
    ) : ChatRowModel
    data class Thinking(val seconds: Int) : ChatRowModel
    data class Failed(val verdict: String, val detail: String, val canRetry: Boolean) : ChatRowModel
    object NoCredits : ChatRowModel
    object Offline : ChatRowModel
    data class OverBudget(val estimate: String) : ChatRowModel
    /** WP6: visible, honest system note (e.g. key switched on opt-in failover). */
    data class InfoNote(val text: String) : ChatRowModel

    /**
     * One step the assistant took: the tool and what it did. Rendered as a compact line with a
     * chevron for the detail and an x to dismiss, which is the shape VS Code gives its tool
     * activity in the transcript. Kept apart from InfoNote so a note the user must read can
     * never be dismissed by the same gesture.
     */
    /**
     * One step the assistant took. [id] is assigned once, when the step is created, so the tree can
     * collapse and expand by identity instead of by position — the old behaviour reset the group on
     * every streaming update because the key was an index.
     */
    data class ToolActivity(val label: String, val detail: String, val id: String = "") : ChatRowModel

    data class Attachments(val chips: List<AttachmentChipModel>) : ChatRowModel
}
