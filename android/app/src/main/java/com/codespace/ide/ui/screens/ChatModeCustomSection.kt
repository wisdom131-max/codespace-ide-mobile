package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R9-A — project-defined custom agent modes (.agent.md / .chatmode.md).
 * Rendered under the three builtin mode chips. Lists every mode discovered by
 * CustomModeStore; tap selects (call site sets mode = AGENT + the session's
 * customModeId + optional model prefill). Tapping a builtin chip clears the
 * custom selection (handled at the call site).
 * UI rule: rounded 8dp rows, 12dp horizontal / 10dp vertical padding.
 */
@Composable
internal fun ChatModeCustomSection(
    projectRoot: String?,
    activeCustomModeId: String?,
    colors: ChatPanelColors,
    onSelect: (com.codespace.ide.chat.CustomModeStore.CustomAgentMode) -> Unit,
) {
    if (projectRoot.isNullOrBlank()) return
    val modes = remember(projectRoot) {
        com.codespace.ide.chat.CustomModeStore.discover(projectRoot)
    }
    if (modes.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text(
            "Custom modes",
            fontSize = 10.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
        modes.forEach { cm ->
            val isSelected = cm.id == activeCustomModeId
            val modeColor = if (isSelected) colors.accent else colors.textSecondary
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .background(
                        if (isSelected) colors.surface else colors.background,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(cm) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Tune, null, tint = modeColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        cm.name,
                        fontSize = 12.sp,
                        color = if (isSelected) colors.accent else colors.text,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (cm.description.isNotBlank()) {
                        Text(
                            cm.description,
                            fontSize = 10.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
                if (cm.tools != null) {
                    Text(
                        "tools: " + cm.tools.size,
                        fontSize = 9.sp,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}
