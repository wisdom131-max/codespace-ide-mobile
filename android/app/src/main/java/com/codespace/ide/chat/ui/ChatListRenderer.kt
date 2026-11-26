package com.codespace.ide.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Interaction callbacks handed to the renderer together with the rows. */
data class ChatRenderState(
    val rows: List<ChatRowModel>,
    /**
     * Which conversation these rows belong to. The thinking tree remembers open/closed state per
     * conversation, so the section needs the key it should remember under.
     */
    val conversationKey: String = "",
    val onRetry: () -> Unit = {},
    val onSendAnyway: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRemoveChip: (AttachmentChipModel) -> Unit = {},
    /** The x on an activity row. */
    val onDismissRow: (ChatRowModel) -> Unit = {},
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
            // OWNER DECISION (2026-11-26): the OLD thinking-tree UI is gone from the app, not just
            // bypassed. The grouping model, its collapsible group row and the standalone thinking
            // card were deleted; this is now a plain walk over the rows. A legacy ToolActivity row
            // still sitting in an old transcript is skipped rather than drawn as chat content, so
            // tool work only ever appears inside the turn's own section.
            val rows = state.rows
            var consumed = 0
            for (row in rows) {
                if (row is ChatRowModel.ToolActivity) { consumed++; continue }
                if (row is ChatRowModel.Sources) {
                    if (row.items.isNotEmpty()) SourcesRow(row.items)
                } else {
                    RenderRow(row, state, stepsAfter(rows, consumed))
                }
                consumed++
            }
        }
    }

    /**
     * The steps that belong to a Thinking row: the tool lines the engine already emits as
     * InfoNote ("read_file: 42 lines"), which are user-facing summaries, not reasoning.
     */
    internal fun stepsAfter(rows: List<ChatRowModel>, index: Int): List<String> {
        if (rows.getOrNull(index) !is ChatRowModel.Thinking) return emptyList()
        val out = mutableListOf<String>()
        var i = index + 1
        while (i < rows.size) {
            val r = rows[i]
            if (r is ChatRowModel.InfoNote) out.add(r.text) else break
            i++
        }
        return out
    }

    @Composable
    private fun RenderRow(row: ChatRowModel, state: ChatRenderState, steps: List<String> = emptyList()) {
        when (row) {
            is ChatRowModel.User -> UserBubble(row.text)
            // BATCH 7: assistant replies are Markdown. The streaming cursor still rides the
            // same row, so a growing reply keeps one identity and never re-lays out twice.
            is ChatRowModel.AssistantStream -> {
                Column {
                    MarkdownView(row.text, row.cursorOn, state.onInsertCode)
                    // STAGE 8 (Checkpoint 0.5): a reply that did not end on its own terms says so
                    // under its own text. It is part of the reply, never a separate notice, so it
                    // cannot reach the Thinking Tree or be read as tool work.
                    if (row.interrupted) {
                        androidx.compose.material3.Text(
                            text = "This reply was cut off.",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Only once the reply has settled: a glyph under a growing message would
                    // move on every chunk and copy a half-finished answer.
                    if (!row.cursorOn) CopyIconRow(row.text)
                }
            }
            is ChatRowModel.Thinking ->
                // VS CODE'S CONVERSATION PROGRESS: one collapsed section, lazy body, live header,
                // persisted open state, nested turns and steps. Never a row of loose tool lines.
                ThinkingTreeSection(
                    seconds = row.seconds,
                    tree = row.tree,
                    liveLabel = row.liveLabel,
                    running = row.running,
                    conversationKey = state.conversationKey,
                )
            is ChatRowModel.Failed -> ErrorCard(row.verdict, row.detail, row.canRetry, state.onRetry)
            ChatRowModel.NoCredits -> NoCreditsCard()
            ChatRowModel.Offline -> OfflineCard(state.onRetry)
            is ChatRowModel.OverBudget -> OverBudgetCard(row.estimate, state.onSendAnyway, state.onCancel)
            is ChatRowModel.InfoNote -> InfoNoteRow(row.text)
            is ChatRowModel.Attachments -> AttachmentChipRow(row.chips, state.onRemoveChip)
            // Drawn by the Single branch before it reaches here; this arm keeps the when total.
            is ChatRowModel.Sources -> SourcesRow(row.items)
            // Never reached: the list skips legacy ToolActivity rows before this point. The arm
            // exists only so the `when` stays exhaustive, without inventing a rendering for a row
            // the app no longer draws.
            is ChatRowModel.ToolActivity -> Unit
        }
    }
}
