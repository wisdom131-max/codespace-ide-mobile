package com.codespace.ide.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.codespace.ide.editor.EditorMetrics
import com.codespace.ide.editor.PositionMapper
import com.codespace.ide.ui.LocalEditorColors

@Composable
internal fun BoxScope.HoverPopup(
    lspHoverContent: String?,
    showCompletions: Boolean,
    fontSize: Int,
    vScrollValue: Int,
    cursorOffset: Int,
    text: String,
    clipboardManager: ClipboardManager,
) {
    val colors = LocalEditorColors.current
    if (lspHoverContent != null && !showCompletions) {
        val hoverScrollState = rememberScrollState()
        var hoverExpanded by remember(lspHoverContent) { mutableStateOf(false) }
        val hoverPos = PositionMapper(text).offsetToPosition(cursorOffset)
        val cursorLineIdxHover = hoverPos.line
        val densityBulb = LocalDensity.current
        val vScrollDpHover = with(densityBulb) { vScrollValue.toDp() }.value
        val lineHeightDpHover = with(densityBulb) { (fontSize * EditorMetrics.LINE_HEIGHT_MULTIPLIER).sp.toDp() }.value
        val hoverTopDp = (((cursorLineIdxHover + 1) * lineHeightDpHover) - vScrollDpHover).coerceAtLeast(0f)
        if (hoverTopDp > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = EditorMetrics.GUTTER_WIDTH_DP.dp, top = hoverTopDp.dp)
                    .widthIn(max = 300.dp)
                    .zIndex(12f)
                    .background(colors.background, RoundedCornerShape(6.dp))
                    .border(1.dp, colors.function.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
            ) {
                Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { hoverExpanded = !hoverExpanded },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (hoverExpanded) "\u25BE" else "\u25B8",
                                color = Color(0xFF888888),
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(lspHoverContent ?: ""))
                                },
                                contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "\u23C9",
                                color = Color(0xFF888888),
                                fontSize = 11.sp,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .then(if (hoverExpanded) Modifier.heightIn(max = 180.dp).verticalScroll(hoverScrollState) else Modifier)
                    ) {
                        Text(
                            text = lspHoverContent ?: "",
                            color = Color(0xFFCCCCCC),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = if (hoverExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
