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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 */
@Composable
internal fun ChatApprovalCard(
    approval: com.codespace.ide.agent.AgentFlowGate.PendingApproval,
) {
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
                Text("Mode: Manual Flow", fontSize = 11.sp, color = Color(0xFF888888))
                Spacer(Modifier.height(12.dp))
                Text("Tool: ${approval.toolName}",
                    color = Color(0xFFCCCCCC), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Text(approval.argsSummary,
                    color = Color(0xFF999999), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 4,
                    overflow = TextOverflow.Ellipsis)
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
