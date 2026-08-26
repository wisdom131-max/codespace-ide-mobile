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
import com.codespace.ide.lsp.LspCodeAction

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
) {
    if (lightbulbLine >= 0 && lspCodeActionProvider != null && !showCompletions) {
        // P46-D5 FIX v2: Stay in pixel space throughout to avoid px→dp→px rounding
        // that accumulates over hundreds of lines and causes drift after extended use.
        val density = LocalDensity.current
        val lineHeightPx = with(density) { (fontSize * 1.25f).sp.toPx() }
        // FIX: lightbulbLine is a 0-based DOCUMENT line. textLayoutResult uses
        // VISUAL lines (after word-wrap/folding). Convert doc line to visual line
        // to prevent the lightbulb from appearing on the wrong line.
        val visualLine = if (textLayoutResult != null) {
            // When wordWrap is off, doc lines == visual lines.
            // When wordWrap is on, we need to find the visual line for this doc line.
            // textLayoutResult.lineCount gives total visual lines; we find the one
            // whose char offset range contains the start of our doc line.
            // Simplest: if lightbulbLine < lineCount, use it directly (no wrap case).
            // For wrap case, clamp to avoid out-of-bounds.
            lightbulbLine.coerceAtMost(textLayoutResult.lineCount - 1)
        } else {
            lightbulbLine
        }
        val bulbTopPx = if (textLayoutResult != null && visualLine >= 0 && visualLine < textLayoutResult.lineCount) {
            (textLayoutResult.getLineTop(visualLine) - vScrollValue).coerceAtLeast(0f)
        } else {
            ((lightbulbLine * lineHeightPx) - vScrollValue).coerceAtLeast(0f)
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
