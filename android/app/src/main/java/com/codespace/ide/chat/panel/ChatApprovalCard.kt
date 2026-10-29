package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.agent.AgentFlowGate
import com.codespace.ide.chat.ui.ChatTokens

/**
 * Section 6 — the VISIBLE approval row.
 *
 * This restores the UI, not the security model. It renders the existing gate's real pending
 * state (AgentFlowGate.pending) and resolves it through the existing verdicts, so the
 * permission level, the persisted "always allow" switch and the 10-minute timeout all keep
 * working exactly as before. Nothing here can approve on the app's behalf: no pending request,
 * no row; no tap, no execution. A click is not proof of completion either - the row disappears
 * only because the GATE cleared its own state when the await resolved.
 *
 * Layout follows the VS Code tool-approval reference: glyph + verb + the highlighted target,
 * one line of plain description, then Allow (with a scope dropdown) and Skip, grouped tightly
 * in one row so the card stays short enough to leave the conversation visible.
 */
@Composable
internal fun ChatApprovalCard() {
    val c = ChatTokens.LocalChatColors.current
    val pending = AgentFlowGate.pending.value ?: return
    val view = remember(pending) {
        ApprovalPresentation.describe(pending.toolName, pending.argsSummary)
    }
    var scopeMenu by remember(pending) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ChatTokens.COMPOSER_WRAP_PAD_H.dp, vertical = ChatTokens.COMPOSER_WRAP_PAD_V.dp)
            .background(c.surface, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .border(1.dp, c.warning, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // Header: glyph, verb, target pill - the target is what the user must actually read.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view.glyph, color = c.warning, fontSize = ChatTokens.FONT_SMALL.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                view.title,
                color = c.text,
                fontSize = ChatTokens.FONT_SMALL.sp,
                maxLines = 1,
            )
            view.target?.let { target ->
                Spacer(Modifier.width(5.dp))
                Text(
                    target,
                    color = c.text,
                    fontSize = ChatTokens.FONT_MICRO.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                        .padding(horizontal = ChatTokens.CHIP_PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp),
                )
            }
        }
        // Description: the real summary from the gate, never an invented sentence.
        Text(
            view.description,
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pending.onTrust?.let { trust ->
                ApprovalAction("Trust project", c.textSecondary, filled = false, divider = false) { trust() }
            }
            ApprovalAction("Skip", c.textSecondary, filled = false, divider = true) {
                AgentFlowGate.reject()
            }
            Box {
                ApprovalAction("Allow", c.accent, filled = true, divider = false, chevron = true) {
                    scopeMenu = true
                }
                DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
                    val scopes = listOf(
                        AgentFlowGate.ApprovalScope.ONCE to ApprovalPresentation.scopeOptions[0],
                        AgentFlowGate.ApprovalScope.SESSION to ApprovalPresentation.scopeOptions[1],
                        AgentFlowGate.ApprovalScope.ALWAYS to ApprovalPresentation.scopeOptions[2],
                    )
                    for ((scope, option) in scopes) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.label, fontSize = ChatTokens.FONT_SMALL.sp)
                                    Text(
                                        option.hint,
                                        color = c.textSecondary,
                                        fontSize = ChatTokens.FONT_MICRO.sp,
                                    )
                                }
                            },
                            // "Always allow" only exists when the gate offered it; the row
                            // must not promise a persistence it cannot deliver.
                            enabled = scope != AgentFlowGate.ApprovalScope.ALWAYS || pending.onAlwaysAllow != null,
                            onClick = {
                                scopeMenu = false
                                AgentFlowGate.approve(scope)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalAction(
    label: String,
    color: Color,
    filled: Boolean,
    divider: Boolean,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    val c = ChatTokens.LocalChatColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (divider) {
            // The reference groups Allow and Skip in one cluster with a hairline between.
            Box(
                Modifier
                    .padding(horizontal = 5.dp)
                    .width(1.dp)
                    .height(12.dp)
                    .background(c.border),
            )
            Spacer(Modifier.width(1.dp))
        }
        Text(
            label + (if (chevron) " \u25BE" else ""),
            color = if (filled) Color(0xFFFFFFFF) else color,
            fontSize = ChatTokens.FONT_SMALL.sp,
            maxLines = 1,
            modifier = Modifier
                .background(
                    if (filled) color else Color.Transparent,
                    RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp),
                )
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
