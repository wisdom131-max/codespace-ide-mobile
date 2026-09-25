package com.codespace.ide.ui.panes

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * P2c (2026-09-25): the global workspace-trust prompt, rendered once from
 * ProjectShellScreen. Surfaces: chat tool loop, task launch, debugger launch,
 * MCP session spawn. Unattended surfaces (scheduler, AgentApiServer) never
 * prompt — they fail closed on isTrusted instead.
 */
@Composable
internal fun TrustPromptDialog() {
    val prompt = com.codespace.ide.security.TrustState.prompt.value ?: return
    // Capture at composable level — LocalContext.current is a @Composable call
    // and cannot be read inside the onClick lambda.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { com.codespace.ide.security.TrustState.deny(prompt) },
        title = { Text("Trust this project?", fontSize = 14.sp, color = Color(0xFFE0E0E0)) },
        text = {
            Text(
                "'" + prompt.displayName + "' isn't trusted yet. Gated actions (AI tools and commands, " +
                    "task and debugger launches, MCP servers, connector calls) ask first. " +
                    "Trust it once and this folder stops asking — trust is remembered for the " +
                    "folder even if the project is deleted and re-registered.",
                fontSize = 12.sp, color = Color(0xFF999999)
            )
        },
        confirmButton = {
            Button(
                onClick = { com.codespace.ide.security.TrustState.grant(prompt, context) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
            ) { Text("Trust this project", fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = { com.codespace.ide.security.TrustState.deny(prompt) }) {
                Text("Cancel", fontSize = 12.sp)
            }
        },
    )
}
