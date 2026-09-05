package com.codespace.ide.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * P39: Lightbulb (quick-fix) indicator drawn in the gutter area.
 *
 * GUTTER-ALIGN / LIGHTBULB FIX: this composable now uses the shared coordinate
 * chain (EditorLinePositioning doc) — the same source of truth the gutter rows
 * use. Previously it committed both identified drift bugs:
 *   1. Used the raw DOCUMENT line as a VISUAL line index into the text layout,
 *      skipping the VisualLineMapper doc-to-visual mapping the gutter and inlay
 *      hints use (wrong position under word wrap; hidden when the line is
 *      inside a folded range instead of drawing on a stale coordinate).
 *   2. Ignored the sticky-pad the scrolled Row applies when the sticky header
 *      is visible, landing the bulb ~1.1 line-heights above the cursor line.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.LightbulbIndicator(
    lightbulbLine: Int,
    lspCodeActionProvider: ((line: Int) -> List<com.codespace.ide.lsp.LspCodeAction>)?,
    showCompletions: Boolean,
    fontSize: Int,
    vScrollValue: Int,
    displayLinesSize: Int,
    showLightbulbMenu: Boolean,
    onShowLightbulbMenu: (Boolean) -> Unit,
    textLayoutResult: TextLayoutResult? = null,
    // FIX 1 source: same mapper the gutter uses (folds + wrap).
    visualLineMapper: VisualLineMapper? = null,
    // FIX 2 source: the sticky pad the scrolled Row applies (0 when inactive).
    stickyPadPx: Float = 0f,
) {
    if (lightbulbLine >= 0 && lspCodeActionProvider != null && !showCompletions) {
        // P46-D5 FIX v2: Stay in pixel space throughout to avoid px->dp->px rounding
        // that accumulates over hundreds of lines and causes drift after extended use.
        val density = LocalDensity.current
        val lineHeightPx = with(density) { (fontSize * 1.25f).sp.toPx() }
        // FIX 1: document line -> visual line via the shared mapper. -1 (e.g. the line
        // is inside a folded range) hides the bulb instead of guessing a position.
        val visualLine = visualLineMapper?.docToVisualLine(lightbulbLine) ?: lightbulbLine
        // FIX 2: content-space top from the same text layout that renders the code,
        // then viewport space = content - scroll + sticky pad.
        val bulbTopPx = if (visualLine >= 0) {
            val contentTopPx = EditorLinePositioning.visualLineTopPx(textLayoutResult, visualLine, lineHeightPx)
            (contentTopPx - vScrollValue + stickyPadPx).coerceAtLeast(0f)
        } else {
            -1f
        }
        val bulbHeightPx = lineHeightPx
        val viewportEndPx = (displayLinesSize + 5) * lineHeightPx
        if (bulbTopPx >= 0f && bulbTopPx < viewportEndPx) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 6.dp, top = with(density) { bulbTopPx.toDp() })
                    .width(20.dp)
                    .height(with(density) { bulbHeightPx.toDp() })
                    .clickable { onShowLightbulbMenu(true) }
                    .zIndex(9f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "💡",
                    fontSize = 10.sp,
                )
            }
        }
    }
}
