package com.codespace.ide.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange

/**
 * DONE-CURSOR-1: Word highlight overlay
 *
 * When the cursor is placed at the front of any word, vscode.dev highlights
 * all occurrences of that word with a glossy grey background. This modifier
 * draws translucent grey rectangles behind every occurrence of the current word.
 *
 * Only activates when there is no active selection (just a cursor) to match
 * vscode.dev behavior — selecting text uses the normal selection highlight.
 *
 * STABILITY-FIX (2026-08-12): [textLayoutResult] is produced by BasicTextField's
 * onTextLayout callback, which fires ASYNCHRONOUSLY — one frame behind the live
 * [text]/[selection] state. During that window (every keystroke, every paste,
 * every snippet expansion) this modifier can be invoked with a [text]/[selection]
 * that describes content textLayoutResult was NOT built for. Calling
 * getHorizontalPosition()/getLineForOffset() with an offset beyond what
 * textLayoutResult actually covers throws
 * "IllegalArgumentException: offset(X) is out of bounds [0, Y]" — confirmed via
 * three independent on-device crash logs (2c/2d/2e), all IllegalArgumentException
 * in MultiParagraph.requireIndexInRangeInclusiveEnd via this exact call chain,
 * all triggered the instant the user finished typing a word or pasted text.
 *
 * Fix: treat textLayoutResult's OWN described text length
 * (layoutInput.text.length) as the only source of truth for bounds-checking.
 * If it disagrees with the live [text] length, the layout is stale — skip
 * drawing this frame entirely (Compose will call this modifier again next
 * frame once onTextLayout catches up; the highlight just appears ~16ms later,
 * imperceptible). A try/catch around the actual draw is kept as a last-resort
 * safety net in case of any other unforeseen offset drift.
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

    // STABILITY-FIX: bail if the layout is stale relative to the live text.
    val layoutLen = textLayoutResult.layoutInput.text.length
    if (text.length != layoutLen) return Modifier

    val cursor = selection.end
    if (cursor < 0 || cursor > layoutLen) return Modifier

    // Find the word at the cursor position
    val word = wordAtCursor(text, cursor)
    if (word.length < 2) return Modifier // skip single chars and empty

    // Find all occurrences of the word (word-boundary aware)
    val pattern = Regex("\\b" + Regex.escape(word) + "\\b")
    val ranges = pattern.findAll(text)
        .map { it.range.first to it.range.last + 1 }
        .filter { (start, end) -> start in 0..layoutLen && end in 0..layoutLen }
        .toList()
    if (ranges.isEmpty()) return Modifier

    return Modifier.drawWithContent {
        drawContent()
        try {
            ranges.forEach { (start, end) ->
                val safeStart = start.coerceIn(0, layoutLen)
                val safeEnd = end.coerceIn(0, layoutLen)
                val startLine = textLayoutResult.getLineForOffset(safeStart)
                val endLine = textLayoutResult.getLineForOffset(safeEnd)
                for (line in startLine..endLine) {
                    val hlStart = if (line == startLine) safeStart else textLayoutResult.getLineStart(line)
                    val hlEnd = if (line == endLine) safeEnd else textLayoutResult.getLineEnd(line)
                    val sx = textLayoutResult.getHorizontalPosition(hlStart.coerceIn(0, layoutLen), true)
                    val ex = textLayoutResult.getHorizontalPosition(hlEnd.coerceIn(0, layoutLen), true)
                    val ty = textLayoutResult.getLineTop(line)
                    val by = textLayoutResult.getLineBottom(line)
                    drawRect(
                        color = highlightColor,
                        topLeft = Offset(sx, ty),
                        size = Size(ex - sx, by - ty),
                    )
                }
            }
        } catch (_: IllegalArgumentException) {
            // Last-resort safety net — never let a highlight overlay crash the editor.
        }
    }
}

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
