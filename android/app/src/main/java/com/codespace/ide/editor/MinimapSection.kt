package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.codespace.ide.ui.EditorColors
import kotlinx.coroutines.launch
import kotlin.math.maxOf
import kotlin.math.minOf

/**
 * Extracted from CodeEditor.kt to stay under the 64KB JVM bytecode limit.
 * Contains: minimap toggle button, realistic minimap panel with syntax colors
 * and viewport indicator, overview ruler, and indentation guides.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.MinimapSection(
    value: TextFieldValue,
    lineHeightDp: androidx.compose.ui.unit.Dp,
    vScroll: androidx.compose.foundation.ScrollState,
    colors: EditorColors,
    showMinimapState: Boolean,
    onToggleMinimap: () -> Unit,
    positionMapper: com.codespace.ide.editor.PositionMapper,
    lintErrors: List<LintError>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val textLines = remember(value.text) { value.text.split("\n") }
    val lineCountTotal = textLines.size

    val minimapLineHeightDp = when {
        lineCountTotal <= 300 -> 2.dp
        lineCountTotal <= 600 -> (600 / lineCountTotal).coerceAtLeast(1).dp
        lineCountTotal <= 2000 -> 1.dp
        else -> 0.5.dp
    }
    val density = LocalDensity.current
    val miniLineHeightPx = with(density) { minimapLineHeightDp.toPx() }
    val miniWidthPx = with(density) { 60.dp.toPx() }
    val lineHeightPx = lineHeightDp.value
    val viewportTopLine = (vScroll.value / lineHeightPx).toInt()
    val visibleLineCount = with(density) {
        ((maxOf(1, vScroll.viewportSize) / lineHeightPx).toInt())
    }

    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .zIndex(6f)
            .padding(top = 2.dp, end = if (showMinimapState) 64.dp else 2.dp)
            .background(colors.background.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
            .border(1.dp, colors.gutter.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .clickable { onToggleMinimap() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (showMinimapState) "\u25A3" else "\u25A2",
            color = colors.gutter,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
        )
    }

    if (showMinimapState) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(60.dp)
                .fillMaxHeight()
                .background(colors.background.copy(alpha = 0.5f))
                .zIndex(5f)
                .pointerInput(lineCountTotal) {
                    detectTapGestures(
                        onTap = { offset ->
                            val clickedLine = (offset.y / miniLineHeightPx).toInt()
                                .coerceIn(0, (lineCountTotal - 1).coerceAtLeast(0))
                            coroutineScope.launch {
                                vScroll.animateScrollTo((clickedLine * lineHeightPx).toInt())
                            }
                        }
                    )
                },
        ) {
            val miniScroll = rememberScrollState()
            LaunchedEffect(vScroll.value) {
                val target = (viewportTopLine * miniLineHeightPx - miniWidthPx / 2)
                    .coerceAtLeast(0f).toInt()
                miniScroll.scrollTo(target)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(miniScroll)
            ) {
                val errorLinesMap = remember(value.text, lintErrors) {
                    val map = mutableMapOf<Int, Int>()
                    for (err in lintErrors) {
                        val errLine = value.text.substring(0, err.start.coerceIn(0, value.text.length)).count { it == '\n' }
                        if (errLine !in map || err.severity < map[errLine]!!) {
                            map[errLine] = err.severity
                        }
                    }
                    map
                }
                val miniVisibleStart = viewportTopLine.coerceAtLeast(0)
                val miniVisibleEnd = (miniVisibleStart + visibleLineCount + 10).coerceAtMost(textLines.size)
                if (miniVisibleStart > 0) {
                    Spacer(Modifier.height((miniVisibleStart * minimapLineHeightDp.value).dp))
                }
                textLines.subList(miniVisibleStart.coerceAtMost(textLines.size), miniVisibleEnd).forEachIndexed { vi, line ->
                    val idx = vi + miniVisibleStart
                    val trimmed = line.trimStart()
                    val lineColor = when {
                        trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") -> colors.comment
                        trimmed.startsWith("\"") || trimmed.startsWith("'") -> colors.string
                        trimmed.startsWith("import ") || trimmed.startsWith("from ") || trimmed.startsWith("package ") -> colors.keyword
                        trimmed.startsWith("fun ") || trimmed.startsWith("def ") || trimmed.startsWith("func ") || trimmed.startsWith("void ") -> colors.function
                        trimmed.startsWith("class ") || trimmed.startsWith("interface ") || trimmed.startsWith("struct ") || trimmed.startsWith("enum ") -> colors.type
                        trimmed.isNotEmpty() && trimmed[0].isUpperCase() && !trimmed.contains("(") -> colors.type
                        trimmed.isEmpty() -> Color.Transparent
                        else -> colors.text
                    }
                    val localDensity = (line.trimEnd().length.coerceAtMost(80)).toFloat() / 80f
                    val indent = line.length - line.trimStart().length
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(minimapLineHeightDp)
                            .padding(horizontal = 1.dp),
                    ) {
                        if (indent > 0) {
                            Spacer(Modifier.width((indent * 0.25f).coerceAtMost(20f).dp))
                        }
                        if (localDensity > 0f && lineColor != Color.Transparent) {
                            Box(
                                Modifier
                                    .weight(localDensity.coerceIn(0.03f, 1f))
                                    .fillMaxHeight()
                                    .background(lineColor.copy(alpha = 0.5f))
                            )
                        }
                        if (localDensity < 1f) {
                            Spacer(Modifier.weight((1f - localDensity).coerceAtLeast(0f)))
                        }
                        val lineSev = errorLinesMap[idx]
                        if (lineSev != null) {
                            Box(Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(when (lineSev) {
                                    1 -> Color(0xFFFF6B6B)
                                    2 -> Color(0xFFFFD700)
                                    else -> Color(0xFF4EC9B0)
                                })
                            )
                        }
                    }
                }
                if (textLines.size - miniVisibleEnd > 0) {
                    Spacer(Modifier.height(((textLines.size - miniVisibleEnd) * minimapLineHeightDp.value).dp))
                }
            }

            val viewportTopPx = (viewportTopLine * miniLineHeightPx)
            val viewportHeightPx = (visibleLineCount * miniLineHeightPx).coerceAtLeast(20f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, viewportTopPx.toInt()) }
                    .fillMaxWidth()
                    .height(with(density) { viewportHeightPx.toDp() })
                    .background(colors.currentLine.copy(alpha = 0.15f))
                    .border(1.dp, colors.gutter.copy(alpha = 0.3f))
                    .zIndex(4f)
                    .pointerInput(lineCountTotal) {
                        detectDragGestures(
                            onDragStart = { },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                coroutineScope.launch {
                                    val deltaLines = (dragAmount.y / miniLineHeightPx).toInt()
                                    val newScroll = (vScroll.value + (deltaLines * lineHeightPx).toInt())
                                        .coerceIn(0, vScroll.maxValue)
                                    vScroll.scrollTo(newScroll)
                                }
                            }
                        )
                    },
            )
        }
    }

    if (!showMinimapState && lintErrors.isNotEmpty()) {
        val rulerWidth = 4.dp
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(rulerWidth)
                .fillMaxHeight()
                .background(colors.gutter.copy(alpha = 0.2f))
                .zIndex(3f)
        ) {
            lintErrors.forEach { err ->
                val errLine = value.text.substring(0, err.start.coerceIn(0, value.text.length)).count { it == '\n' }
                val totalLines = positionMapper.lineCount()
                val lineFrac = errLine.toFloat() / totalLines.coerceAtLeast(1)
                val markerColor = when (err.severity) {
                    1 -> Color(0xFFFF6B6B)
                    2 -> Color(0xFFFFD700)
                    else -> Color(0xFF4EC9B0)
                }
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(2.dp)
                        .offset { IntOffset(0, (lineFrac * 9999f).toInt().coerceAtMost(9999)) }
                        .background(markerColor)
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 72.dp)
            .zIndex(1f),
    ) {
        val maxIndent = remember(value.text) {
            value.text.split("\n").maxOfOrNull { line ->
                (line.length - line.trimStart().length) / 2
            } ?: 0
        }
        for (indent in 1..minOf(maxIndent, 10)) {
            Box(Modifier.width(2.dp).fillMaxHeight().padding(end = 10.dp))
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.gutter.copy(alpha = 0.15f)))
            Spacer(Modifier.width(11.dp))
        }
    }
}
