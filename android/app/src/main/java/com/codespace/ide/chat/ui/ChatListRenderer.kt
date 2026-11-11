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
            // 2026-11-11: ONE grouping implementation. This loop used to re-derive the runs
            // itself while ActivityGrouping.group() existed beside it, so a change in the rules
            // could disagree with what was drawn. The renderer now consumes the pure model.
            val rows = state.rows
            var consumed = 0
            ActivityGrouping.group(rows).forEach { display ->
                when (display) {
                    is ChatDisplayRow.Single -> {
                        val row = display.row
                        if (row is ChatRowModel.Sources) {
                            if (row.items.isNotEmpty()) SourcesRow(row.items)
                        } else {
                            RenderRow(row, state, stepsAfter(rows, consumed))
                        }
                        consumed++
                    }
                    is ChatDisplayRow.ActivityGroup -> {
                        ActivityGroupRow(display.id, display.items)
                        consumed += display.items.size
                    }
                }
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
                    // Only once the reply has settled: a glyph under a growing message would
                    // move on every chunk and copy a half-finished answer.
                    if (!row.cursorOn) CopyIconRow(row.text)
                }
            }
            is ChatRowModel.Thinking -> ThinkingCard(row.seconds, steps)
            is ChatRowModel.Failed -> ErrorCard(row.verdict, row.detail, row.canRetry, state.onRetry)
            ChatRowModel.NoCredits -> NoCreditsCard()
            ChatRowModel.Offline -> OfflineCard(state.onRetry)
            is ChatRowModel.OverBudget -> OverBudgetCard(row.estimate, state.onSendAnyway, state.onCancel)
            is ChatRowModel.InfoNote -> InfoNoteRow(row.text)
            is ChatRowModel.ToolActivity ->
                ToolActivityRow(row.label, row.detail)
            is ChatRowModel.Attachments -> AttachmentChipRow(row.chips, state.onRemoveChip)
            // Drawn by the Single branch before it reaches here; this arm keeps the when total.
            is ChatRowModel.Sources -> SourcesRow(row.items)
        }
    }
}
