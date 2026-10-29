package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codespace.ide.agent.AgentFlowGate
import com.codespace.ide.chat.ui.ChatTokens

/**
 * Section 6/F: the approval card for an agent action.
 *
 * It renders the EXISTING gate's pending state (AgentFlowGate.pending) and resolves it with
 * the existing verdicts, so the permission level, "always allow" and the 10-minute timeout
 * all keep working exactly as before. Nothing here can approve on the app's behalf: with no
 * card, no tap, no execution.
 */
@Composable
internal fun ChatApprovalCard() {
    val c = ChatTokens.LocalChatColors.current
    val pending = AgentFlowGate.pending.value ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.GAP_TIGHT.dp)
            .background(c.surface, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
            .border(1.dp, c.warning, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
            .padding(ChatTokens.PAD_HORIZONTAL.dp, ChatTokens.PAD_VERTICAL.dp),
    ) {
        Text(
            "Agent wants to run: " + pending.toolName,
            color = c.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            pending.argsSummary,
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = ChatTokens.GAP_TIGHT.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = ChatTokens.GAP.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ApprovalAction("Deny", c.textSecondary) { AgentFlowGate.reject() }
            pending.onAlwaysAllow?.let { always ->
                ApprovalAction("Always allow", c.textSecondary) { always() }
            }
            ApprovalAction("Allow", c.accent) { AgentFlowGate.approve() }
        }
    }
}

@Composable
private fun ApprovalAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = ChatTokens.FONT_SMALL.sp,
        maxLines = 1,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = ChatTokens.GAP.dp, vertical = ChatTokens.GAP_TIGHT.dp),
    )
}
