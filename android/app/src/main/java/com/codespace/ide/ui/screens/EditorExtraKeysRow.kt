package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * EDITOR-EXTRA-KEYS-ROW (extracted 2026-09-11 from ProjectShellScreen — 64KB rule:
 * never grow the shell's composable bodies; new UI goes to its own file).
 *
 * The coding toolbar above the keyboard: special-character keys + Tab/Esc/MC.
 *
 * MC-VISUAL-STATE: the MC chip now reads MultiCursorModeStore directly and renders
 * an accent highlight while multi-cursor mode is ON — previously the toggle gave
 * ZERO visual feedback, so a working toggle was indistinguishable from a dead one
 * (the on-device "MC button doesn't work" report).
 */
private val SPECIAL_KEYS = listOf(
    "{", "}", "[", "]", "(", ")", "<", ">", "=", "+", "-", "*", "/",
    ":", ";", "'", "\"", "|", "&", "!", "?", "@", "#", "$", "%", "^",
    "~", "\\", ",", ".", "_", "`", "Tab", "Esc", "MC",
    "\u21A9", "\u21AA",
)

@Composable
internal fun EditorExtraKeysRow(
    toolbarBg: Color,
    divider: Color,
    accent: Color,
    onKey: (String) -> Unit,
) {
    val mcActive = com.codespace.ide.editor.MultiCursorModeStore.enabled
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(toolbarBg)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        val isDarkToolbar = toolbarBg.red < 0.5f
        val keyBg = if (isDarkToolbar) Color(0xFF3A3A3A) else Color(0xFFFFFFFF)
        val keyText = if (isDarkToolbar) Color(0xFFEEEEEE) else Color(0xFF333333)
        val keyBorder = if (isDarkToolbar) Color(0xFF555555) else divider
        SPECIAL_KEYS.forEach { key ->
            val active = key == "MC" && mcActive
            Box(
                Modifier.height(32.dp).defaultMinSize(minWidth = 36.dp)
                    .background(if (active) accent.copy(alpha = 0.22f) else keyBg, RoundedCornerShape(4.dp))
                    .border(1.dp, if (active) accent else keyBorder, RoundedCornerShape(4.dp))
                    .clickable { onKey(key) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(key, fontSize = 13.sp, color = if (active) accent else keyText, fontFamily = FontFamily.Monospace) }
            Spacer(Modifier.width(4.dp))
        }
    }
}
