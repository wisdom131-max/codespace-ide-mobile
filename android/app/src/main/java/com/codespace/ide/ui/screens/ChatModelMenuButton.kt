package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ROUND 5 (64KB extraction from CopilotChatPanelInline): the model-picker
 * chip in the chat panel header. VS Code-parity features:
 *  - "Auto" entry at the top (default) — resolved at send time by
 *    ChatModelSelection.resolveAuto, never dispatched literally.
 *  - Pinned (starred) models sort above the rest; pin/unpin the current
 *    selection from the menu footer.
 */
@Composable
internal fun ChatModelMenuButton(
    selectedModel: String,
    availModels: List<String>,
    pinned: List<String>,
    colors: ChatPanelColors,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    onTogglePin: () -> Unit,
) {
    val isAuto = selectedModel == com.codespace.ide.chat.ChatModelSelection.AUTO_MODEL
    val label = if (isAuto) "Auto" else selectedModel.take(12)
    Box {
        Text(
            label,
            color = colors.accent, fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(colors.surface, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clickable { onExpandedChange(true) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            // Auto first — VS Code's default picker entry
            DropdownMenuItem(
                text = { Text("Auto" + if (isAuto) "  ✓" else "", fontSize = 12.sp) },
                onClick = { onPick(com.codespace.ide.chat.ChatModelSelection.AUTO_MODEL); onExpandedChange(false) },
            )
            if (pinned.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Pinned", fontSize = 10.sp, color = colors.textSecondary) },
                    onClick = { },
                    enabled = false,
                )
                pinned.forEach { m ->
                    DropdownMenuItem(
                        text = { Text("★ " + m, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Star, null, tint = colors.accent) },
                        onClick = { onPick(m); onExpandedChange(false) },
                    )
                }
            }
            val rest = availModels.filter { it !in pinned }
            if (rest.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Models", fontSize = 10.sp, color = colors.textSecondary) },
                    onClick = { },
                    enabled = false,
                )
                rest.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m, fontSize = 12.sp) },
                        onClick = { onPick(m); onExpandedChange(false) },
                    )
                }
            }
            DropdownMenuItem(
                text = {
                    Text(
                        if (selectedModel in pinned) "Unpin current model" else "Pin current model",
                        fontSize = 12.sp,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Star,
                        null,
                        tint = if (selectedModel in pinned) colors.accent else colors.textSecondary,
                    )
                },
                onClick = { onTogglePin(); onExpandedChange(false) },
            )
        }
    }
}
