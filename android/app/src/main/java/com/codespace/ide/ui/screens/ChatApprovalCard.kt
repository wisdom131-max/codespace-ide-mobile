package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ROUND 5 (64KB extraction from CopilotChatPanelInline): the manual-approval
 * floating card, now with a third action — "Always Allow <tool>" writes the
 * tool to ChatPermissionStore's allowlist (applies at every permission level)
 * and approves this call.
 *
 * P2c (CH03, 2026-09-25): the card shows the FULL arguments (the old 160-char
 * take + 4-line clamp hid the tail of long commands) — collapsed to 4 lines
 * with an expand toggle, scrollable when expanded. A "Trust this project"
 * quick-action (approval.onTrust) appears for the first gated action in an
 * untrusted project: it persists trust and approves this call.
 */
@Composable
internal fun ChatApprovalCard(
    approval: com.codespace.ide.agent.AgentFlowGate.PendingApproval,
) {
    // CH03: expandable full-args display state.
    var expanded by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0x80000000)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier.width(280.dp).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("AI Tool Approval",
                    color = Color(0xFFE0E0E0),
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                // P2c: the mode line now says WHY the card appeared — trust-gated
                // cards are not manual-flow cards and used to be mislabeled.
                Text(
                    if (approval.onTrust != null) "Mode: Project Trust"
                    else "Mode: Manual Flow",
                    fontSize = 11.sp, color = Color(0xFF888888))
                if (approval.onTrust != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This project isn't trusted yet. Approve once, or trust the " +
                            "project to stop these prompts.",
                        fontSize = 11.sp, color = Color(0xFFD7B45A))
                }
                Spacer(Modifier.height(12.dp))
                Text("Tool: ${approval.toolName}",
                    color = Color(0xFFCCCCCC), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                // CH03: full arguments — never truncated at the source. Collapsed
                // shows 4 lines with a toggle; expanded is scrollable, capped.
                if (expanded) {
                    Text(approval.argsSummary,
                        color = Color(0xFF999999), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()))
                } else {
                    Text(approval.argsSummary,
                        color = Color(0xFF999999), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 4,
                        overflow = TextOverflow.Ellipsis)
                }
                // Toggle only when the summary actually overflows the clamp.
                if (approval.argsSummary.length > 160) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.padding(top = 2.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            if (expanded) "Show less" else "Show all (${approval.argsSummary.length} chars)",
                            fontSize = 11.sp, color = Color(0xFF4FA3E3))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { com.codespace.ide.agent.AgentFlowGate.reject() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCC4444)),
                    ) { Text("Reject", fontSize = 11.sp) }
                    Button(
                        onClick = { com.codespace.ide.agent.AgentFlowGate.approve() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    ) { Text("Approve", fontSize = 11.sp) }
                }
                // P2c (CH03): "Trust this project" quick-action — persists trust
                // for the folder (keyed by canonical path) AND approves this call.
                // This is the one-tap prompt-once answer for untrusted projects.
                if (approval.onTrust != null) {
                    Button(
                        onClick = {
                            approval.onTrust?.invoke()
                            com.codespace.ide.agent.AgentFlowGate.approve()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E6FB0)),
                    ) { Text("Trust this project", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                // R5: allowlist this tool — approves this call AND skips future gates
                OutlinedButton(
                    onClick = { approval.onAlwaysAllow?.invoke() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4CAF50)),
                    enabled = approval.onAlwaysAllow != null,
                ) { Text("Always Allow ${approval.toolName}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
