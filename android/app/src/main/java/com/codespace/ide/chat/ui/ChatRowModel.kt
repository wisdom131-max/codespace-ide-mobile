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
    /**
     * BATCH 6: attempt/maxAttempts are 0 for a rate limit that is NOT being retried
     * automatically (retries used up, or the provider asked for a wait too long to hold).
     */
    data class RateLimited(val waitSec: Int, val attempt: Int = 0, val maxAttempts: Int = 0) : ChatRowModel
    object NoCredits : ChatRowModel
    object Offline : ChatRowModel
    data class OverBudget(val estimate: String) : ChatRowModel
    /** WP6: visible, honest system note (e.g. key switched on opt-in failover). */
    data class InfoNote(val text: String) : ChatRowModel

    data class TrimmedHistory(val droppedCount: Int) : ChatRowModel
    data class Attachments(val chips: List<AttachmentChipModel>) : ChatRowModel
}
