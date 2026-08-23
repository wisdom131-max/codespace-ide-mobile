package com.codespace.ide.editor

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.zIndex

/**
 * EDITOR-FIX: Extracted ErrorLens overlay with same-line diagnostic stacking.
 *
 * Replaces the inline ErrorLens block in CodeEditor.kt.
 *
 * Key fix: Multiple diagnostics on the same document line are stacked vertically
 * instead of rendering at the same Y position and overlapping.
 *
 * Stacking logic:
 * 1. Group diagnostics by document line number
 * 2. For each line, render diagnostics top-to-bottom with a small gap
 * 3. First diagnostic at lineY, second at lineY + stackedHeight, etc.
 *
 * FIX (2026-08-21): Use Modifier.offset instead of Modifier.padding for
 * absolute positioning inside the Box — padding inside a Box with
 * align(TopStart) can cause layout issues where multiple children
 * overlap. offset directly moves the element from the aligned position.
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
    textLayoutResult: TextLayoutResult? = null,
    positionMapper: PositionMapper? = null,
) {
    if (!showErrorLens || lintErrors.isEmpty() || hasCompletions) return

    val lineHeightPxEL = lineHeightDp.value
    val charWidthPxEL = fontSize * 0.6f
    // Height of each stacked diagnostic message — use 1.1x line height for clear separation
    val stackedHeight = lineHeightPxEL * 1.1f

    // Group diagnostics by document line and compute stacking offset
    val lineGroupCount = mutableMapOf<Int, Int>()

    lintErrors.forEachIndexed { index, err ->
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
        val stackOffsetDp = slotIndex * stackedHeight
        val lineTopDp = (baseLineTopDp + stackOffsetDp).coerceAtLeast(0f)

        val lineLeftDp = if (textLayoutResult != null && positionMapper != null) {
            val lineEndOffset = positionMapper.lineEnd(errorLine)
            val clampedEnd = lineEndOffset.coerceIn(0, textLayoutResult.layoutInput.text.length)
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            (textLayoutResult.getHorizontalPosition(clampedEnd, true) / density) + GUTTER_WIDTH + 8f
        } else {
            (lineLength * charWidthPxEL) + GUTTER_WIDTH + 8f
        }

        // Only render if visible in viewport
        if (lineTopDp >= -stackedHeight && lineTopDp < (displayLineCount + 5) * lineHeightPxEL) {
            val msgColor = when (err.severity) {
                1 -> Color(0xFFFF6B6B).copy(alpha = 0.7f)  // Error — red
                2 -> Color(0xFFCCA700).copy(alpha = 0.7f)  // Warning — amber
                else -> Color(0xFF75BEFF).copy(alpha = 0.7f) // Info — blue
            }
            androidx.compose.runtime.key(err.start, err.message) {
                Text(
                    text = "  ${if (err.code != null) "[${err.code}] " else ""}${err.message.replace("\n", " ").take(80)}",
                    color = msgColor,
                    fontSize = (fontSize * 0.8f).sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = lineLeftDp.dp, y = lineTopDp.dp)
                        .zIndex(3f)
                )
            }
        }
    }
}
