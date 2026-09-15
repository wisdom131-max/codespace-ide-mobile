package com.codespace.ide.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.ui.screens.ChatPanelColors

/**
 * CW8 (cheap win 8): retry + session-export bar — small chat-panel render parts
 * (VS Code chat parity: regenerate the last turn, export the session to a file).
 * Rendered as the last transcript item; each half hides itself when unavailable.
 */
@Composable
internal fun ChatRetryExportBar(
    showRetry: Boolean,
    canExport: Boolean,
    onRetry: () -> Unit,
    onExport: () -> Unit,
    colors: ChatPanelColors,
) {
    if (!showRetry && !canExport) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showRetry) {
            RetryExportChip(
                icon = Icons.Default.Refresh,
                label = "Retry",
                onClick = onRetry,
                colors = colors,
            )
        }
        if (canExport) {
            RetryExportChip(
                icon = Icons.Default.Save,
                label = "Export .md",
                onClick = onExport,
                colors = colors,
            )
        }
    }
}

@Composable
private fun RetryExportChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    colors: ChatPanelColors,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.assistantBubble)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, tint = colors.accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 11.sp, color = colors.text)
        }
    }
}

/**
 * CW8 export: writes the conversation to `<projectRoot>/.codespace/exports/chat-<timestamp>.md`.
 * Returns the relative path on success (for a toast), null when there is no
 * project root or the write failed. Pure std-lib — no entity/store churn.
 */
internal fun writeSessionMarkdown(
    projectRoot: String?,
    title: String,
    entries: List<Pair<String, String>>,
): String? {
    if (projectRoot.isNullOrBlank()) return null
    return try {
        val dir = java.io.File(projectRoot, ".codespace/exports")
        if (!dir.exists() && !dir.mkdirs()) return null
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val sb = StringBuilder()
        sb.append("# Chat session export \u2014 ").append(title).append('\n')
        sb.append('\n')
        for ((role, text) in entries) {
            val label = when (role) {
                "user" -> "## You"
                "assistant" -> "## Copilot"
                "tool" -> "## Tools"
                else -> "## System"
            }
            sb.append(label).append('\n').append('\n').append(text).append('\n').append('\n')
        }
        val f = java.io.File(dir, "chat-" + ts + ".md")
        f.writeText(sb.toString())
        ".codespace/exports/" + f.name
    } catch (_: Exception) {
        null
    }
}
