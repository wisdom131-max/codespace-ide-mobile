package com.codespace.ide.chat.ui

/**
 * UI-model rows the chat renderer receives. The panel maps engine events and
 * session state into these; the renderer never sees transport details.
 */
sealed interface ChatRowModel {
    data class User(val text: String) : ChatRowModel
    data class AssistantStream(val text: String, val cursorOn: Boolean) : ChatRowModel
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
    data class ToolActivity(val label: String, val detail: String) : ChatRowModel

    data class Attachments(val chips: List<AttachmentChipModel>) : ChatRowModel
}
