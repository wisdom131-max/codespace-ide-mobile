package com.codespace.ide.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Interaction callbacks handed to the renderer together with the rows. */
data class ChatRenderState(
    val rows: List<ChatRowModel>,
    val onRetry: () -> Unit = {},
    val onSendAnyway: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRemoveChip: (AttachmentChipModel) -> Unit = {},
    /**
     * BATCH 7: insert a code block into the ACTIVE editor at the caret. Returns false when
     * no editor is open, which the card reports honestly rather than failing silently.
     */
    val onInsertCode: ((String) -> Boolean)? = null,
)

/**
 * A(a) renderer contract: the message list renders THROUGH THIS INTERFACE so a
 * WebView-based renderer can replace the native one later without touching the
 * panel or the engine. Same rows in, same interactions out, tokens always from
 * ChatTokens (the CSS custom properties file mirrors the same single source).
 */
interface ChatListRenderer {
    @Composable
    fun MessageList(state: ChatRenderState, modifier: Modifier)
}

/** Native Compose renderer over the shared component library. */
object NativeChatListRenderer : ChatListRenderer {

    @Composable
    override fun MessageList(state: ChatRenderState, modifier: Modifier) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(ChatTokens.GAP.dp),
        ) {
            state.rows.forEach { row -> RenderRow(row, state) }
        }
    }

    @Composable
    private fun RenderRow(row: ChatRowModel, state: ChatRenderState) {
        when (row) {
            is ChatRowModel.User -> UserBubble(row.text)
            // BATCH 7: assistant replies are Markdown. The streaming cursor still rides the
            // same row, so a growing reply keeps one identity and never re-lays out twice.
            is ChatRowModel.AssistantStream -> {
                Column {
                    MarkdownView(row.text, row.cursorOn, state.onInsertCode)
                    // Only once the reply has settled: a glyph under a growing message would
                    // move on every chunk and copy a half-finished answer.
                    if (!row.cursorOn) CopyIconRow(row.text)
                }
            }
            is ChatRowModel.Thinking -> ThinkingCard(row.seconds)
            is ChatRowModel.Failed -> ErrorCard(row.verdict, row.detail, row.canRetry, state.onRetry)
            is ChatRowModel.RateLimited ->
                RateLimitCard(row.waitSec, row.attempt, row.maxAttempts, state.onRetry)
            ChatRowModel.NoCredits -> NoCreditsCard()
            ChatRowModel.Offline -> OfflineCard(state.onRetry)
            is ChatRowModel.OverBudget -> OverBudgetCard(row.estimate, state.onSendAnyway, state.onCancel)
            is ChatRowModel.TrimmedHistory -> TrimmedHistoryRow(row.droppedCount)
            is ChatRowModel.InfoNote -> InfoNoteRow(row.text)
            is ChatRowModel.Attachments -> AttachmentChipRow(row.chips, state.onRemoveChip)
        }
    }
}
