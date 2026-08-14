package com.codespace.ide.editor

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * EDITOR-FIX: Extracted ErrorLens overlay with same-line diagnostic stacking.
 *
 * Replaces the inline ErrorLens block in CodeEditor.kt (lines 2289-2321).
 *
 * Key fix: Multiple diagnostics on the same document line are stacked vertically
 * instead of rendering at the same Y position and overlapping.
 *
 * Stacking logic:
 * 1. Group diagnostics by document line number
 * 2. For each line, render diagnostics top-to-bottom with a small gap
 * 3. First diagnostic at lineY, second at lineY + stackedHeight, etc.
 *
 * This is an internal composable called from CodeEditor's BoxScope.
 */

@Composable
internal fun BoxScope.ErrorLensOverlay(
    showErrorLens: Boolean,
    lintErrors: List<LintError>,
    hasCompletions: Boolean,
    value: androidx.compose.ui.text.input.TextFieldValue,
    lineHeightDp: androidx.compose.ui.unit.Dp,
    vScrollDp: Float,
    fontSize: Int,
    GUTTER_WIDTH: Float,
    displayLineCount: Int,
) {
    if (!showErrorLens || lintErrors.isEmpty() || hasCompletions) return

    val lineHeightPxEL = lineHeightDp.value
    val charWidthPxEL = fontSize * 0.6f
    // Height of each stacked diagnostic message (slightly smaller than line height)
    val stackedHeight = lineHeightPxEL * 0.85f
    val stackGap = lineHeightPxEL * 0.15f

    // Group diagnostics by document line and compute stacking offset
    // Each diagnostic on the same line gets an incremental Y offset
    val lineGroupCount = mutableMapOf<Int, Int>() // line → count seen so far

    for (err in lintErrors) {
        // Find which line this error is on
        val textBefore = value.text.substring(0, err.start.coerceIn(0, value.text.length))
        val errorLine = textBefore.count { it == '\n' }
        // Find the end of that line (for x-positioning after the code)
        val lineStart = value.text.lastIndexOf('\n', (err.start - 1).coerceAtLeast(0)) + 1
        val lineEnd = value.text.indexOf('\n', err.start)
        val lineLength = (if (lineEnd < 0) value.text.length else lineEnd) - lineStart

        // STACKING: Get this diagnostic's slot index on its line
        val slotIndex = lineGroupCount.getOrPut(errorLine) { 0 }
        lineGroupCount[errorLine] = slotIndex + 1

        // Base Y = line position. Stacked Y = base + slot offset
        val baseLineTopDp = errorLine * lineHeightPxEL - vScrollDp
        val stackOffsetDp = slotIndex * (stackedHeight + stackGap)
        val lineTopDp = baseLineTopDp + stackOffsetDp

        val lineLeftDp = (lineLength * charWidthPxEL) + GUTTER_WIDTH + 8f

        // Only render if visible in viewport
        if (lineTopDp >= -stackedHeight && lineTopDp < (displayLineCount + 5) * lineHeightPxEL) {
            val msgColor = when (err.severity) {
                1 -> Color(0xFFFF6B6B).copy(alpha = 0.7f)  // Error — red
                2 -> Color(0xFFCCA700).copy(alpha = 0.7f)  // Warning — amber
                else -> Color(0xFF75BEFF).copy(alpha = 0.7f) // Info — blue
            }
            Text(
                text = "  ${if (err.code != null) "[${err.code}] " else ""}${err.message.replace("\n", " ").take(80)}",
                color = msgColor,
                fontSize = (fontSize * 0.8f).sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = lineLeftDp.dp, top = lineTopDp.dp)
                    .zIndex(3f)
            )
        }
    }
}
