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
import com.codespace.ide.agent.ChatFlowLevel
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens

/**
 * Item 9 (2026-10-31): the approvals level gets a real entry in the chat header's overflow menu.
 *
 * The visible "Local Approvals: Auto-all" row under the composer was removed at the owner's
 * request back in M3, which left ChatPermissionStore reachable only by editing prefs. This sheet
 * is the missing door: three levels, each described by what it actually lets through, with the
 * current one checked.
 *
 * The level is not a preference like a theme. It decides whether a command runs or waits for a
 * human, so every row states its consequence rather than its setting name.
 */
internal data class ApprovalRow(
    val level: ChatFlowLevel,
    val label: String,
    val detail: String,
)

internal object ApprovalLevelSpec {

    const val TITLE = "Approvals"
    const val SUBTITLE = "How much the agent may do without asking."

    /** Order is deliberate: safest first, and the current row is always one of them. */
    val rows: List<ApprovalRow> = listOf(
        ApprovalRow(
            ChatFlowLevel.MANUAL,
            "Ask every time",
            "Every tool call waits for your tap, including reads.",
        ),
        ApprovalRow(
            ChatFlowLevel.AUTO_SAFE,
            "Auto for safe tools",
            "Reads, git inspection and listings run silently. Anything that writes, executes, " +
                "deletes or reaches the network still asks.",
        ),
        ApprovalRow(
            ChatFlowLevel.AUTO_ALL,
            "Auto for everything",
            "No approval cards at all. Commands run as soon as the model asks for them.",
        ),
    )

    fun row(level: ChatFlowLevel): ApprovalRow = rows.first { it.level == level }

    /** Menu label: the level's own words, so the header states the current policy. */
    fun menuLabel(level: ChatFlowLevel): String = "Approvals: " + row(level).label
}

@Composable
internal fun ApprovalLevelSheet(
    current: ChatFlowLevel,
    colors: ChatColors,
    onPick: (ChatFlowLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                .padding(
                    horizontal = ChatTokens.PAD_HORIZONTAL.dp,
                    vertical = ChatTokens.PAD_VERTICAL.dp,
                ),
        ) {
            Text(ApprovalLevelSpec.TITLE, color = colors.text, fontSize = ChatTokens.FONT_BODY.sp)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            Text(
                ApprovalLevelSpec.SUBTITLE,
                color = colors.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
            )
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                ApprovalLevelSpec.rows.forEach { row ->
                    ApprovalLevelRow(row, row.level == current, colors, onPick)
                }
            }
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            CloseRow(colors, onDismiss)
        }
    }
}

@Composable
private fun ApprovalLevelRow(
    row: ApprovalRow,
    selected: Boolean,
    colors: ChatColors,
    onPick: (ChatFlowLevel) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = SessionsPanelTouch.TOUCH_TARGET_DP.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .clickable { onPick(row.level) }
            .padding(
                horizontal = ChatTokens.PAD_HORIZONTAL.dp,
                vertical = ChatTokens.PAD_VERTICAL.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.label,
                color = colors.text,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text("✓", color = colors.accent, fontSize = ChatTokens.FONT_SMALL.sp)
            }
        }
        Text(row.detail, color = colors.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
    }
}

@Composable
private fun CloseRow(colors: ChatColors, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = SessionsPanelTouch.TOUCH_TARGET_DP.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .clickable(onClick = onDismiss)
            .padding(
                horizontal = ChatTokens.PAD_HORIZONTAL.dp,
                vertical = ChatTokens.PAD_VERTICAL.dp,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("Close", color = colors.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp)
    }
}
