package com.codespace.ide.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange

/**
 * DONE-CURSOR-3: Bracket match overlay
 *
 * When the cursor is inside or next to a bracket pair, vscode.dev highlights
 * both the opening and closing bracket with glossy grey boxes. The bracket
 * matching logic is already computed in CodeEditor (_bracketMatch); this modifier
 * renders the result as visible highlight boxes.
 *
 * STABILITY-FIX (2026-08-12): same textLayoutResult staleness hazard as
 * [wordHighlightModifier] above — [bracketMatch] positions can be computed
 * against text one frame newer than what [textLayoutResult] describes.
 * Guarded the same way: bail if there's no reliable length to check against,
 * clamp every offset, and catch as a last resort.
 */
internal fun bracketMatchModifier(
    textLayoutResult: TextLayoutResult?,
    bracketMatch: Pair<Int, Int>?,
    highlightColor: Color,
): Modifier {
    if (textLayoutResult == null || bracketMatch == null) return Modifier
    val (openPos, closePos) = bracketMatch
    val layoutLen = textLayoutResult.layoutInput.text.length
    if (openPos < 0 || openPos > layoutLen) return Modifier
    if (closePos < 0 || closePos > layoutLen) return Modifier

    return Modifier.drawWithContent {
        drawContent()
        try {
            for (pos in listOf(openPos, closePos)) {
                val safePos = pos.coerceIn(0, layoutLen)
                val endPos = (pos + 1).coerceIn(0, layoutLen)
                val lineIdx = textLayoutResult.getLineForOffset(safePos)
                val sx = textLayoutResult.getHorizontalPosition(safePos, true)
                val ex = textLayoutResult.getHorizontalPosition(endPos, true)
                val ty = textLayoutResult.getLineTop(lineIdx)
                val by = textLayoutResult.getLineBottom(lineIdx)
                drawRect(
                    color = highlightColor,
                    topLeft = Offset(sx, ty),
                    size = Size(ex - sx, by - ty),
                )
            }
        } catch (_: IllegalArgumentException) {
            // Last-resort safety net — never let a highlight overlay crash the editor.
        }
    }
}

/** Find the word at the cursor position using word-boundary detection. */
private fun wordAtCursor(text: String, cursor: Int): String {
    if (text.isEmpty() || cursor < 0 || cursor > text.length) return ""
    val checkPos = if (cursor > 0 && cursor <= text.length) cursor - 1 else cursor
    if (checkPos < 0 || checkPos >= text.length) return ""
    val c = text[checkPos]
    if (!c.isLetterOrDigit() && c != '_') return ""
    var start = checkPos
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
    var end = checkPos
    while (end < text.length - 1 && (text[end + 1].isLetterOrDigit() || text[end + 1] == '_')) end++
    return text.substring(start, end + 1)
}
