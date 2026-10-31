package com.codespace.ide.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The ghost text's own controls (2026-10-31): a word, a line, the whole thing, or not now.
 *
 * Tap-to-accept already worked but the mapping was invisible (first line = one word, any later
 * line = everything), and there was no way to accept exactly one line or to silence the
 * suggestion for a moment. Four small glyphs, so the whole overlay stays tappable code.
 */
@Composable
internal fun GhostActionStrip(
    lines: List<String>,
    onAcceptWord: (String, List<String>) -> Unit,
    onAcceptLine: (String, List<String>) -> Unit,
    onAcceptFull: () -> Unit,
    onSnooze: () -> Unit,
    accent: Color = Color(0xFF6A9955),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GhostGlyph("\u21E5", "Accept next word", accent) {
            val first = lines.firstOrNull() ?: return@GhostGlyph
            val cut = first.indexOfFirst { it == ' ' || it == '\t' }.let { if (it == -1) first.length else it + 1 }
            val word = first.substring(0, cut)
            if (word.isNotEmpty()) onAcceptWord(word, listOf(first.substring(cut)) + lines.drop(1))
        }
        GhostGlyph("\u21B5", "Accept this line", accent) {
            val line = GhostActionSpec.acceptLine(lines)
            if (line.text.isNotEmpty()) onAcceptLine(line.text, line.remaining)
        }
        GhostGlyph("\u21C4", "Accept all", accent) { onAcceptFull() }
        GhostGlyph("\u00D7", "Not now", Color(0xFF858585)) { onSnooze() }
    }
}

@Composable
private fun GhostGlyph(glyph: String, label: String, color: Color, onClick: () -> Unit) {
    Text(
        glyph,
        color = color,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
