package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * P41-T: Refactor submenu — groups all refactoring actions under a single "Refactor..." menu item,
 * matching VS Code's behavior where refactoring options appear in a nested submenu.
 *
 * Contains:
 * - Extract Method (LSP: refactor.extract)
 * - Extract Variable (LSP: refactor.extract.constant)
 * - Inline Variable (LSP: refactor.inline)
 * - Move Symbol (LSP: refactor.move)
 * - Refactor with AI (sends selection to CopilotChat for AI-powered refactoring)
 */
@Composable
fun RefactorSubmenu(
    onSourceAction: ((String) -> Unit)?,
    onAiFixRequest: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var showSubmenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.background(Color(0xFF252526))
    ) {
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("R", color = Color(0xFF4EC9B0), fontSize = 14.sp)
                    Text("Refactor...", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                }
            },
            onClick = { showSubmenu = true }
        )
        DropdownMenu(
            expanded = showSubmenu,
            onDismissRequest = { showSubmenu = false },
            modifier = Modifier
                .background(Color(0xFF252526))
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (onSourceAction != null) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("R", color = Color(0xFF4EC9B0), fontSize = 14.sp)
                            Text("Extract Method", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                        }
                    },
                    onClick = { onSourceAction.invoke("refactor.extract"); showSubmenu = false; onDismiss() }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("R", color = Color(0xFF4EC9B0), fontSize = 14.sp)
                            Text("Extract Variable", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                        }
                    },
                    onClick = { onSourceAction.invoke("refactor.extract.constant"); showSubmenu = false; onDismiss() }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("I", color = Color(0xFF4EC9B0), fontSize = 14.sp)
                            Text("Inline Variable", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                        }
                    },
                    onClick = { onSourceAction.invoke("refactor.inline"); showSubmenu = false; onDismiss() }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("M", color = Color(0xFF4EC9B0), fontSize = 14.sp)
                            Text("Move Symbol", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                        }
                    },
                    onClick = { onSourceAction.invoke("refactor.move"); showSubmenu = false; onDismiss() }
                )
            }
            // AI-powered refactor
            if (onAiFixRequest != null) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("✨", color = Color(0xFFC586C0), fontSize = 14.sp)
                            Text("Refactor with AI", color = Color(0xFFC586C0), fontSize = 13.sp)
                        }
                    },
                    onClick = {
                        onAiFixRequest.invoke("__refactor_ai__")
                        showSubmenu = false
                        onDismiss()
                    }
                )
            }
        }
    }
}
