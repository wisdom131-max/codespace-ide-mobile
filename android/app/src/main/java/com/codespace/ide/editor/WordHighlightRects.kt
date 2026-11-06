package com.codespace.ide.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Cursor-word occurrences, derived OFF the draw pass and cached as rectangles.
 *
 * The old path (deleted from CursorBehaviors.kt) did three expensive things per frame: a regex over
 * the whole document, a `getLineForOffset`+2x`getHorizontalPosition` per occurrence, and all of it
 * inside `drawWithContent`. This splits it into three cheap steps that happen once per change
 * rather than once per frame:
 *
 *   1. ranges  - windowed, capped, computed on Dispatchers.Default after a 100 ms debounce
 *                (VS Code likewise delays its occurrence decorations: wordHighlighter.ts
 *                _beginRenderDecorations, and CodeMirror recomputes only on selection/doc/viewport
 *                change, never per frame).
 *   2. rects   - layout lookups for at most OccurrenceScan.CAP ranges, once per (layout, ranges).
 *   3. draw    - `drawRect` over a prepared list. No layout calls, no regex, no searching.
 *
 * Visual output is unchanged for normal files: same colour, same geometry, same rules (cursor only,
 * 2+ word characters, whole-word).
 */

private const val DEBOUNCE_MS = 100L
private const val OVERSCAN_SCREENS = 1

@Composable
internal fun rememberWordHighlightRects(
    textLayoutResult: TextLayoutResult?,
    text: String,
    selection: TextRange,
    vScroll: ScrollState,
    lineHeight: Dp,
    screenHeightDp: Int,
): List<Rect> {
    if (textLayoutResult == null) return emptyList()
    if (selection.start != selection.end) return emptyList()
    val layoutLen = textLayoutResult.layoutInput.text.length
    if (text.length != layoutLen) return emptyList()

    val caret = selection.end.coerceIn(0, layoutLen)
    val word = OccurrenceScan.wordAt(text, caret) ?: return emptyList()

    val density = LocalDensity.current
    val lineHeightPx = with(density) { lineHeight.toPx() }.coerceAtLeast(1f)
    val screenPx = with(density) { screenHeightDp.toFloat().dp.toPx() }
    val lineCount = textLayoutResult.lineCount
    val firstVisible = (vScroll.value / lineHeightPx).toInt()
    val screenLines = (screenPx / lineHeightPx).toInt().coerceAtLeast(1)
    val topLine = (firstVisible - screenLines * OVERSCAN_SCREENS).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    val bottomLine = (firstVisible + screenLines * (1 + OVERSCAN_SCREENS)).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    // Quantised window: the derive key must not change on every pixel of scroll, or the debounce
    // would never settle while a finger is moving.
    val windowStart = textLayoutResult.getLineStart(topLine)
    val windowEnd = textLayoutResult.getLineEnd(bottomLine)

    val ranges by produceState(
        initialValue = emptyList<IntRange>(),
        word,
        text,
        windowStart,
        windowEnd,
    ) {
        value = withContext(Dispatchers.Default) {
            delay(DEBOUNCE_MS)
            val started = System.nanoTime()
            val found = OccurrenceScan.wordRanges(text, word, windowStart, windowEnd, caret)
            val ms = (System.nanoTime() - started) / 1_000_000.0
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[perf] wordhighlight: " + found.size + " ranges, " +
                    String.format(java.util.Locale.US, "%.2f", ms) + "ms derive, window " +
                    topLine + "-" + bottomLine,
                "perf",
            )
            found
        }
    }

    return remember(textLayoutResult, ranges) {
        val started = System.nanoTime()
        val rects = buildRects(textLayoutResult, ranges, layoutLen)
        val ms = (System.nanoTime() - started) / 1_000_000.0
        if (rects.isNotEmpty()) {
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[perf] wordhighlight: " + rects.size + " rects, " +
                    String.format(java.util.Locale.US, "%.2f", ms) + "ms layout",
                "perf",
            )
        }
        rects
    }
}

/** Content-space rectangles for [ranges]. Called once per (layout, ranges), never per frame. */
private fun buildRects(layout: TextLayoutResult, ranges: List<IntRange>, layoutLen: Int): List<Rect> {
    if (ranges.isEmpty()) return emptyList()
    val out = ArrayList<Rect>(ranges.size)
    try {
        ranges.forEach { range ->
            val safeStart = range.first.coerceIn(0, layoutLen)
            val safeEnd = (range.last + 1).coerceIn(0, layoutLen)
            val startLine = layout.getLineForOffset(safeStart)
            val endLine = layout.getLineForOffset(safeEnd.coerceAtMost(layoutLen))
            for (line in startLine..endLine) {
                if (line >= layout.lineCount) break
                val hlStart = if (line == startLine) safeStart else layout.getLineStart(line)
                val hlEnd = if (line == endLine) safeEnd else layout.getLineEnd(line)
                val sx = layout.getHorizontalPosition(hlStart.coerceIn(0, layoutLen), true)
                val ex = layout.getHorizontalPosition(hlEnd.coerceIn(0, layoutLen), true)
                if (ex > sx) {
                    out.add(
                        Rect(
                            left = sx,
                            top = layout.getLineTop(line),
                            right = ex,
                            bottom = layout.getLineBottom(line),
                        ),
                    )
                }
            }
        }
    } catch (_: IllegalArgumentException) {
        // Same last-resort net the old modifier had: a stale layout must never crash the editor.
    }
    return out
}

/** Draws prepared rectangles. The only work in the draw pass. */
internal fun wordHighlightRectsModifier(rects: List<Rect>, highlightColor: Color): Modifier {
    if (rects.isEmpty()) return Modifier
    return Modifier.drawWithContent {
        drawContent()
        rects.forEach {
            drawRect(
                color = highlightColor,
                topLeft = Offset(it.left, it.top),
                size = Size(it.width, it.height),
            )
        }
    }
}
