package com.codespace.ide.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange

/**
 * PENDING-CURSOR-1: Word highlight overlay
 *
 * When the cursor is placed at the front of any word, vscode.dev highlights
 * all occurrences of that word with a glossy grey background. This modifier
 * draws translucent grey rectangles behind every occurrence of the current word.
 *
 * Only activates when there is no active selection (just a cursor) to match
 * vscode.dev behavior — selecting text uses the normal selection highlight.
 */
internal fun wordHighlightModifier(
    textLayoutResult: TextLayoutResult?,
    text: String,
    selection: TextRange,
    highlightColor: Color,
): Modifier {
    if (textLayoutResult == null) return Modifier
    // Only highlight when there's no active selection (cursor only)
    if (selection.start != selection.end) return Modifier

    val cursor = selection.end
    if (cursor < 0 || cursor > text.length) return Modifier

    // Find the word at the cursor position
    val word = wordAtCursor(text, cursor)
    if (word.length < 2) return Modifier // skip single chars and empty

    // Find all occurrences of the word (word-boundary aware)
    val pattern = Regex("\\b" + Regex.escape(word) + "\\b")
    val ranges = pattern.findAll(text).map { it.range.first to it.range.last + 1 }.toList()
    if (ranges.isEmpty()) return Modifier

    return Modifier.drawWithContent {
        drawContent()
        ranges.forEach { (start, end) ->
            val startLine = textLayoutResult.getLineForOffset(start)
            val endLine = textLayoutResult.getLineForOffset(end)
            for (line in startLine..endLine) {
                val hlStart = if (line == startLine) start else textLayoutResult.getLineStart(line)
                val hlEnd = if (line == endLine) end else textLayoutResult.getLineEnd(line)
                val sx = textLayoutResult.getHorizontalPosition(hlStart, true)
                val ex = textLayoutResult.getHorizontalPosition(hlEnd, true)
                val ty = textLayoutResult.getLineTop(line)
                val by = textLayoutResult.getLineBottom(line)
                drawRect(
                    color = highlightColor,
                    topLeft = Offset(sx, ty),
                    size = Size(ex - sx, by - ty),
                )
            }
        }
    }
}

/**
 * PENDING-CURSOR-3: Bracket match overlay
 *
 * When the cursor is inside or next to a bracket pair, vscode.dev highlights
 * both the opening and closing bracket with glossy grey boxes. The bracket
 * matching logic is already computed in CodeEditor (_bracketMatch); this modifier
 * renders the result as visible highlight boxes.
 */
internal fun bracketMatchModifier(
    textLayoutResult: TextLayoutResult?,
    bracketMatch: Pair<Int, Int>?,
    highlightColor: Color,
): Modifier {
    if (textLayoutResult == null || bracketMatch == null) return Modifier
    val (openPos, closePos) = bracketMatch
    return Modifier.drawWithContent {
        drawContent()
        for (pos in listOf(openPos, closePos)) {
            val lineIdx = textLayoutResult.getLineForOffset(pos)
            val sx = textLayoutResult.getHorizontalPosition(pos, true)
            val ex = textLayoutResult.getHorizontalPosition(pos + 1, true)
            val ty = textLayoutResult.getLineTop(lineIdx)
            val by = textLayoutResult.getLineBottom(lineIdx)
            drawRect(
                color = highlightColor,
                topLeft = Offset(sx, ty),
                size = Size(ex - sx, by - ty),
            )
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
