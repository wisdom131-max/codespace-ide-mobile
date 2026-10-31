package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.chat.voice.VoiceTranscript

/**
 * M3 — THE SIX-ITEM OVERFLOW MENU, in the reference's order and with the reference's labels.
 *
 * Nothing here is a placeholder: every action is wired to a real implementation in
 * [ChatPanelScreen]'s host. Two of them can be legitimately unavailable at a given moment
 * (nothing to undo, no host that can host an editor tab); those are DISABLED WITH A REASON,
 * never shown as working buttons.
 */
internal enum class ChatOverflowAction(val label: String) {
    UNDO_LAST_EDIT("Undo Last Edit"),
    REDO_LAST_EDIT("Redo Last Edit"),
    MOVE_TO_EDITOR_AREA("Move Chat into Editor Area"),
    MOVE_TO_NEW_WINDOW("Move Chat into New Window"),
    SHOW_VOICE_TRANSCRIPTS("Show Voice Transcripts"),
    SHOW_VIEW_BY_DEFAULT("Show View by Default"),

    /** Item 9 (2026-10-31): the approvals level needs a door; this is it. */
    MANAGE_APPROVALS("Approvals"),
}

internal data class ChatOverflowItem(
    val action: ChatOverflowAction,
    val enabled: Boolean,
    val reason: String? = null,
    /** Non-null = this item is a checkbox (Show View by Default). */
    val checked: Boolean? = null,
    /** Non-null overrides the enum's label (the approvals row states the current level). */
    val label: String? = null,
)

internal object ChatOverflowSpec {

    const val TITLE = "More actions"

    /** The order is the requirement, so it is asserted in the tests. */
    val order: List<ChatOverflowAction> = ChatOverflowAction.entries

    fun items(
        canUndo: Boolean,
        canRedo: Boolean,
        viewByDefault: Boolean,
        canMoveToEditor: Boolean,
        approvalsLabel: String = ChatOverflowAction.MANAGE_APPROVALS.label,
    ): List<ChatOverflowItem> = listOf(
        ChatOverflowItem(
            ChatOverflowAction.UNDO_LAST_EDIT,
            enabled = canUndo,
            reason = if (canUndo) null else "No AI edit to undo in this session",
        ),
        ChatOverflowItem(
            ChatOverflowAction.REDO_LAST_EDIT,
            enabled = canRedo,
            reason = if (canRedo) null else "No undone AI edit in this session",
        ),
        ChatOverflowItem(
            ChatOverflowAction.MOVE_TO_EDITOR_AREA,
            enabled = canMoveToEditor,
            reason = if (canMoveToEditor) null else "This host cannot open an editor area",
        ),
        ChatOverflowItem(ChatOverflowAction.MOVE_TO_NEW_WINDOW, enabled = true),
        ChatOverflowItem(ChatOverflowAction.SHOW_VOICE_TRANSCRIPTS, enabled = true),
        ChatOverflowItem(ChatOverflowAction.SHOW_VIEW_BY_DEFAULT, enabled = true, checked = viewByDefault),
        // Item 9: always enabled, and its label carries the CURRENT level so the menu answers
        // "what happens if I run something" without opening anything.
        ChatOverflowItem(ChatOverflowAction.MANAGE_APPROVALS, enabled = true, label = approvalsLabel),
    )
}

@Composable
internal fun ChatOverflowMenu(
    items: List<ChatOverflowItem>,
    colors: ChatColors,
    onPick: (ChatOverflowAction) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
        ) {
            items.forEach { item -> OverflowRow(item, colors, onPick) }
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            OverflowRow(
                ChatOverflowItem(ChatOverflowAction.UNDO_LAST_EDIT, enabled = false),
                colors,
                onPick,
                plainLabel = "Close",
                onPlain = onDismiss,
            )
        }
    }
}

@Composable
private fun OverflowRow(
    item: ChatOverflowItem,
    colors: ChatColors,
    onPick: (ChatOverflowAction) -> Unit,
    plainLabel: String? = null,
    onPlain: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = SessionsPanelTouch.TOUCH_TARGET_DP.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .then(
                if (item.enabled) Modifier.clickable { onPick(item.action) }
                else Modifier.clickable { onPlain?.invoke() },
            )
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                plainLabel ?: item.label ?: item.action.label,
                color = if (item.enabled) colors.text else colors.textSecondary,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier.weight(1f),
            )
            if (item.checked != null) {
                Text(
                    if (item.checked) "✓" else "",
                    color = colors.accent,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                )
            }
        }
        if (!item.enabled && item.reason != null) {
            Text(item.reason, color = colors.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
        }
    }
}

/** Small indirection so this file does not depend on the sessions package. */
internal object SessionsPanelTouch {
    const val TOUCH_TARGET_DP = 40
}

@Composable
internal fun VoiceTranscriptsDialog(
    transcripts: List<VoiceTranscript>,
    skipped: Int,
    problem: String?,
    colors: ChatColors,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
        ) {
            Text("Voice Transcripts", color = colors.text, fontSize = ChatTokens.FONT_BODY.sp)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            when {
                problem != null -> Text(problem, color = colors.error, fontSize = ChatTokens.FONT_SMALL.sp)
                transcripts.isEmpty() -> Text(
                    "Nothing dictated in this session yet. Use Mic in the chat header.",
                    color = colors.textSecondary,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                )
                else -> Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    transcripts.forEach { t ->
                        Text(
                            VoiceMeta.timeLabel(t.atMs),
                            color = colors.textSecondary,
                            fontSize = ChatTokens.FONT_MICRO.sp,
                        )
                        Text(t.text, color = colors.text, fontSize = ChatTokens.FONT_SMALL.sp)
                        Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
                    }
                }
            }
            if (skipped > 0) {
                Text(
                    skipped.toString() + " unreadable record(s) were skipped",
                    color = colors.warning,
                    fontSize = ChatTokens.FONT_MICRO.sp,
                )
            }
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            if (transcripts.isNotEmpty()) {
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = SessionsPanelTouch.TOUCH_TARGET_DP.dp)
                        .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                        .clickable(onClick = onClear)
                        .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("Clear transcripts", color = colors.error, fontSize = ChatTokens.FONT_SMALL.sp) }
            }
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(min = SessionsPanelTouch.TOUCH_TARGET_DP.dp)
                    .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("Close", color = colors.text, fontSize = ChatTokens.FONT_SMALL.sp) }
        }
    }
}

internal object VoiceMeta {
    fun timeLabel(atMs: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(atMs))
    }
}
