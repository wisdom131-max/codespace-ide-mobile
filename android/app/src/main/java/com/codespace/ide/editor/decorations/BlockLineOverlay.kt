package com.codespace.ide.editor.decorations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.codespace.ide.editor.VisualLineMapper
import com.codespace.ide.ui.EditorColors

/**
 * R3-2: Block line / indent guide overlay.
 * Draws vertical guide lines at each indentation level, like VS Code's
 * "Editor: Render Indent Guides" feature.
 */
@Composable
fun BlockLineOverlay(
    text: String,
    scrollOffsetY: Float,
    lineHeightDp: Float,
    fontSize: Int,
    gutterWidthDp: Float,
    tabSize: Int,
    visibleStartLine: Int,
    visibleEndLine: Int,
    colors: EditorColors,
    textLayoutResult: TextLayoutResult? = null,
    visualLineMapper: VisualLineMapper? = null,
    vScrollPx: Int = 0,
    modifier: Modifier = Modifier,
) {
    if (text.isEmpty()) return
    val density = LocalDensity.current
    val lineHeightPx = with(density) { lineHeightDp.dp.toPx() }
    val charWidthPx = with(density) { (fontSize * 0.6f).sp.toPx() }
    val gutterPx = with(density) { gutterWidthDp.dp.toPx() }
    val guideColor = colors.comment.copy(alpha = 0.25f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val lines = text.split("\n")
        val maxIndent = 20
        var lineStartOffset = 0

        for (lineIdx in visibleStartLine..visibleEndLine) {
            if (lineIdx >= lines.size) break
            val line = lines[lineIdx]
            if (line.isBlank()) {
                lineStartOffset += line.length + 1
                continue
            }

            var indent = 0
            for (ch in line) {
                when (ch) {
                    ' ' -> indent++
                    '\t' -> indent += tabSize
                    else -> break
                }
            }
            if (indent < 2) {
                lineStartOffset += line.length + 1
                continue
            }

            val indentLevels = indent / tabSize.coerceAtLeast(1)
            val visualLine = visualLineMapper?.docToVisualLine(lineIdx) ?: lineIdx
            for (level in 1..minOf(indentLevels, maxIndent)) {
                val colOffset = (level * tabSize).coerceAtMost(line.length)
                val textOffset = lineStartOffset + colOffset
                val x = if (textLayoutResult != null && textOffset <= text.length) {
                    val safeTextOffset = textOffset.coerceIn(0, textLayoutResult.layoutInput.text.length)
                    textLayoutResult.getHorizontalPosition(safeTextOffset, true)
                } else {
                    gutterPx + level * tabSize * charWidthPx
                }
                val y = if (textLayoutResult != null && visualLine < textLayoutResult.lineCount) {
                    textLayoutResult.getLineTop(visualLine) - vScrollPx
                } else {
                    lineIdx * lineHeightPx - scrollOffsetY
                }
                drawLine(
                    color = guideColor,
                    start = Offset(x, y),
                    end = Offset(x, y + lineHeightPx),
                    strokeWidth = 1f,
                )
            }
            lineStartOffset += line.length + 1
        }
    }
}
