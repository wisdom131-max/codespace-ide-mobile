package com.codespace.ide.editor

import com.codespace.ide.ui.EditorColors
import androidx.compose.material3.Text

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.codespace.ide.lsp.SnippetSession
import com.codespace.ide.lsp.activeStop
import com.codespace.ide.lsp.shiftAfterEdit
import kotlin.math.roundToInt

@Composable
internal fun SnippetChoicesPopup(
    snippetSessionState: MutableState<SnippetSession?>,
    showSnippetChoicesState: MutableState<Boolean>,
    positionMapper: PositionMapper,
    value: TextFieldValue,
    editorMetrics: EditorMetrics,
    visualLineMapper: VisualLineMapper,
    textLayoutResult: androidx.compose.ui.text.TextLayoutResult?,
    vScroll: ScrollState,
    colors: EditorColors,
    extraCursorsState: MutableState<List<androidx.compose.ui.text.TextRange>>,
    programmaticTextChange: (String, TextRange, String) -> Unit,
) {
    var snippetSession by snippetSessionState
    var showSnippetChoices by showSnippetChoicesState
    val session = snippetSession ?: return
    val activeStop = session.activeStop()
    if (activeStop == null || activeStop.choices.isEmpty()) return
    val cursorLine = positionMapper.offsetToLine(activeStop.startOffset)
    val lineHeightPxPopup = editorMetrics.lineHeightPx
    val visualLineSP = visualLineMapper.docToVisualLine(cursorLine)
    val layoutSP = textLayoutResult
    val popupOffsetY = if (layoutSP != null && visualLineSP < layoutSP.lineCount) {
        (layoutSP.getLineBottom(visualLineSP) - vScroll.value).roundToInt().coerceAtLeast(0)
    } else {
        ((cursorLine + 1) * lineHeightPxPopup - vScroll.value).roundToInt().coerceAtLeast(0)
    }
    val popupOffsetX = with(LocalDensity.current) { GUTTER_WIDTH.dp.toPx() }.roundToInt()
    Popup(
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(popupOffsetX, popupOffsetY + editorMetrics.lineHeightPx.roundToInt()),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.width(180.dp),
            shape = RoundedCornerShape(6.dp),
            color = colors.background,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, colors.function.copy(alpha = 0.6f)),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 2.dp),
            ) {
                activeStop.choices.forEachIndexed { idx, choice ->
                    val isSelected = idx == 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val text = value.text
                                val stopStart = activeStop.startOffset
                                val stopEnd = activeStop.endOffset
                                val newText = text.substring(0, stopStart) + choice + text.substring(stopEnd)
                                val newLen = choice.length
                                val oldLen = stopEnd - stopStart
                                snippetSession = session.shiftAfterEdit(activeStop, oldLen, newLen)
                                extraCursorsState.value = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursorsState.value)
                                programmaticTextChange(newText, TextRange(stopStart, stopStart + newLen), "snippet_tab_stop_update")
                                showSnippetChoices = false
                            }
                            .background(if (isSelected) colors.function.copy(alpha = 0.15f) else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = choice,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isSelected) colors.function else colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
