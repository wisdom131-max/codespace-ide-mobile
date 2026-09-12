package com.codespace.ide.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AUTO-INSTRUCTIONS CHIP (Round 2, Copilot-chat parity):
 *
 * Small chip above the chat input, visible ONLY when the active project has
 * instruction files (AGENTS.md / copilot-instructions.md / CLAUDE.md). Shows
 * which files auto-attach to the system prompt; tapping toggles them on/off
 * for THIS project (persisted via AutoInstructionsProvider). Extracted to its
 * own file per the 64KB rule — the panel calls it with a single line.
 */
@Composable
internal fun AutoInstructionsChip(
    projectRoot: String?,
    enabled: Boolean,
    onToggle: () -> Unit,
    colors: ChatPanelColors,
) {
    val files = remember(projectRoot) {
        if (projectRoot.isNullOrBlank()) emptyList()
        else com.codespace.ide.agent.AutoInstructionsProvider.detect(projectRoot)
    }
    if (files.isEmpty()) return

    val label = if (files.size == 1) files[0].name else files[0].name + " +" + (files.size - 1)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.surface,
        border = BorderStroke(
            1.dp,
            if (enabled) colors.accent else colors.divider,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Project instructions: $label",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (enabled) colors.text else colors.textSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (enabled) "ON" else "OFF",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) colors.accent else colors.textSecondary,
            )
        }
    }
}
