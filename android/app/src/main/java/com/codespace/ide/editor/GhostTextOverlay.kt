package com.codespace.ide.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.zIndex

@Composable
fun androidx.compose.foundation.layout.BoxScope.GhostTextOverlay(
    ghostText: String,
    ghostTextLines: List<String>,
    ghostTextIsAi: Boolean,
    cursorPos: Int,
    text: String,
    fontSize: Float,
    vScrollValue: Int,
    languageName: String,
    context: android.content.Context,
    onAcceptFull: (String) -> Unit,
    onAcceptWord: (String, List<String>) -> Unit,
    onDismiss: () -> Unit,
    textLayoutResult: TextLayoutResult? = null,
) {
    // Snooze lives here, keyed by the suggestion: hiding this one does not silence the next.
    var snoozeKey by remember { mutableStateOf<Int?>(null) }
    var snoozeUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(snoozeUntil) {
        if (snoozeUntil > 0L) {
            kotlinx.coroutines.delay(GhostActionSpec.SNOOZE_MS)
            snoozeUntil = 0L
        }
    }
    val key = GhostActionSpec.snoozeKey(ghostText)
    if (snoozeKey == key && GhostActionSpec.snoozeActive(snoozeUntil, System.currentTimeMillis())) return

    val ghostLines = ghostTextLines.ifEmpty { listOf(ghostText) }
    val ghostPos = PositionMapper(text).offsetToPosition(cursorPos)
    val cursorLine = ghostPos.line
    val cursorCol = ghostPos.column
    val lineHeightDp = fontSize * EditorMetrics.LINE_HEIGHT_MULTIPLIER  // Phase E

    ghostLines.forEachIndexed { lineIdx, _ ->
        val line = if (lineIdx == 0) ghostText else ghostLines[lineIdx]
        if (line.isBlank() && lineIdx > 0) return@forEachIndexed
        val topDp = if (textLayoutResult != null && (cursorLine + lineIdx) < textLayoutResult.lineCount) {
            (textLayoutResult.getLineTop(cursorLine + lineIdx) - vScrollValue).coerceAtLeast(0f)
        } else {
            ((cursorLine + lineIdx) * lineHeightDp - vScrollValue).coerceAtLeast(0f)
        }
        val startDp = if (lineIdx == 0) {
            if (textLayoutResult != null) {
                val density = androidx.compose.ui.platform.LocalDensity.current.density
                (textLayoutResult.getHorizontalPosition(cursorPos.coerceIn(0, textLayoutResult.layoutInput.text.length), true) / density) + 72f
            } else {
                64f + cursorCol * (fontSize * EditorMetrics.CHAR_WIDTH_MULTIPLIER)  // Phase E
            }
        } else {
            72f
        }
        if (topDp < -lineHeightDp || topDp > 2000f) return@forEachIndexed

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = startDp.dp, top = topDp.dp)
                .zIndex(8f)
                .clickable {
                    if (lineIdx == 0) {
                        // Partial accept: accept next word only
                        val firstLine = ghostLines.firstOrNull() ?: ""
                        val wordEnd = firstLine.indexOfFirst { it == ' ' || it == '\t' || it == '.' }.let {
                            if (it == -1) firstLine.length else it + 1
                        }
                        val word = firstLine.substring(0, wordEnd)
                        if (word.isNotEmpty()) {
                            val remainingFirst = firstLine.substring(wordEnd)
                            val remainingLines = if (remainingFirst.isBlank() && ghostLines.size > 1) {
                                ghostLines.drop(1)
                            } else {
                                listOf(remainingFirst) + ghostLines.drop(1)
                            }
                            onAcceptWord(word, remainingLines)
                        }
                    } else {
                        // Full accept: accept all lines
                        onAcceptFull(ghostLines.joinToString("\n"))
                    }
                },
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line,
                    color = Color(0xFF6A6A6A),
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                // The controls sit on the first line only, so they read as belonging to the
                // suggestion rather than to the code underneath it.
                if (lineIdx == 0) {
                    GhostActionStrip(
                        lines = ghostLines,
                        onAcceptWord = onAcceptWord,
                        onAcceptLine = onAcceptWord,
                        onAcceptFull = { onAcceptFull(ghostLines.joinToString("\n")) },
                        onSnooze = {
                            snoozeKey = key
                            snoozeUntil = System.currentTimeMillis() + GhostActionSpec.SNOOZE_MS
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

