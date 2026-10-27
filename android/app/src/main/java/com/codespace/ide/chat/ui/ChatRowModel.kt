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
    data class RateLimited(val waitSec: Int) : ChatRowModel
    object NoCredits : ChatRowModel
    object Offline : ChatRowModel
    data class OverBudget(val estimate: String) : ChatRowModel
    data class TrimmedHistory(val droppedCount: Int) : ChatRowModel
    data class Attachments(val chips: List<AttachmentChipModel>) : ChatRowModel
}
