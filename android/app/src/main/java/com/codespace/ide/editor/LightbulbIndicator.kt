package com.codespace.ide.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
) {
    if (lightbulbLine >= 0 && lspCodeActionProvider != null && !showCompletions) {
        // P46-D5 FIX v2: Stay in pixel space throughout to avoid px→dp→px rounding
        // that accumulates over hundreds of lines and causes drift after extended use.
        val density = LocalDensity.current
        val lineHeightPx = with(density) { (fontSize * 1.25f).sp.toPx() }
        val bulbTopPx = ((lightbulbLine * lineHeightPx) - vScrollValue).coerceAtLeast(0f)
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
