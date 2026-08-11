package com.codespace.ide.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.zIndex

/**
 * Extracted overlay composables from CodeEditor.kt to keep the main
 * CodeEditor composable under the JVM 64KB bytecode limit.
 */

@Composable
private fun androidx.compose.foundation.layout.BoxScope.GitBlameOverlay(
    blameData: Map<Int, GitBlame>?,
    lineHeightDp: androidx.compose.ui.unit.Dp,
    colors: EditorColors,
    vScroll: androidx.compose.foundation.ScrollState,
) {
    if (blameData != null && blameData.isNotEmpty()) {
        Box(
            Modifier
                .padding(start = 72.dp)
                .width(120.dp)
                .fillMaxHeight()
                .background(colors.gutter.copy(alpha = 0.3f))
        ) {
            Column(Modifier.verticalScroll(vScroll)) {
                blameData.entries.sortedBy { it.key }.forEach { (_, blame) ->
                    Box(
                        Modifier.height(lineHeightDp).fillMaxWidth().padding(start = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            blame.author.take(12),
                            fontSize = 9.sp,
                            color = colors.gutter.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ExtraCursorOverlay(
    extraCursors: Set<Int>,
    lineHeightDp: androidx.compose.ui.unit.Dp,
    fontSize: Int,
    GUTTER_WIDTH: Int,
    vScrollDp: Float,
    value: androidx.compose.ui.text.input.TextFieldValue,
    lineFromOffset: (Int) -> Int,
    colors: EditorColors,
) {
    if (extraCursors.isNotEmpty()) {
        val lineHeightPx = lineHeightDp.value
        val charWidthPx  = fontSize * 0.6f
        val gutterDp = GUTTER_WIDTH
        val scrollOffsetPx = vScrollDp
        extraCursors.forEach { off ->
            val clamped  = off.coerceIn(0, value.text.length)
            val lineIdx  = lineFromOffset(clamped)
            val lineStart = (value.text.lastIndexOf('\n', (clamped - 1).coerceAtLeast(0)) + 1)
                                .coerceAtLeast(0)
            val col      = (clamped - lineStart).coerceAtLeast(0)
            val topDp    = (lineIdx * lineHeightPx - scrollOffsetPx).coerceAtLeast(0f)
            val startDp  = gutterDp + col * charWidthPx

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = gutterDp.dp, top = topDp.dp)
                    .height(lineHeightDp)
                    .background(Color(0xFFE5C07B).copy(alpha = 0.08f))
                    .zIndex(4f),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = startDp.dp, top = topDp.dp)
                    .width(2.dp)
                    .height(lineHeightDp)
                    .background(Color(0xFFE5C07B))
                    .zIndex(5f),
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 40.dp)
                .background(colors.background.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .zIndex(10f),
        ) {
            Text("${extraCursors.size}x cursors", color = colors.text, fontSize = 10.sp)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SearchMatchOverlay(
    findReplaceOpen: Boolean,
    matches: List<IntRange>,
    matchIndex: Int,
    lineHeightDp: androidx.compose.ui.unit.Dp,
    fontSize: Int,
    GUTTER_WIDTH: Int,
    vScrollDp: Float,
    value: androidx.compose.ui.text.input.TextFieldValue,
    lineFromOffset: (Int) -> Int,
) {
    if (findReplaceOpen && matches.isNotEmpty() && matches.size <= 200) {
        val lineHeightPxM = lineHeightDp.value
        val charWidthPxM  = fontSize * 0.6f
        val gutterDpM = GUTTER_WIDTH
        val scrollOffsetPxM = vScrollDp
        matches.forEachIndexed { idx, range ->
            val matchStart = range.first
            val lineIdx = lineFromOffset(matchStart)
            val lineStart = value.text.lastIndexOf('\n', (matchStart - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
            val col = matchStart - lineStart
            val matchLen = range.last - range.first + 1
            val topDpM = (lineIdx * lineHeightPxM - scrollOffsetPxM).coerceAtLeast(0f)
            val startDpM = gutterDpM + col * charWidthPxM
            val widthDpM = (matchLen * charWidthPxM).coerceAtLeast(3f)
            val isCurrent = idx == matchIndex
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = startDpM.dp, top = topDpM.dp)
                    .width(widthDpM.dp)
                    .height(lineHeightDp)
                    .background(
                        if (isCurrent) Color(0xFF007ACC).copy(alpha = 0.35f)
                        else Color(0xFFD4D4D4).copy(alpha = 0.15f)
                    )
                    .zIndex(if (isCurrent) 5.5f else 3.5f),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MergeConflictOverlay(
    toggles: EditorFeatureToggles,
    conflictData: List<ConflictHunk>?,
    lineHeightDp: androidx.compose.ui.unit.Dp,
    onResolveConflict: ((ConflictHunk, ConflictResolution) -> Unit)?,
) {
    if (toggles.showMergeConflicts && conflictData != null && conflictData.isNotEmpty()) {
        val lineHeight = lineHeightDp.value
        conflictData.forEach { hunk ->
            val oursHeight = (lineHeight * (hunk.separatorLine - hunk.startLine)).dp
            val theirsHeight = (lineHeight * (hunk.endLine - hunk.separatorLine)).dp
            val oursY = (lineHeight * (hunk.startLine + 1) - lineHeight).dp
            val theirsY = (lineHeight * (hunk.separatorLine + 1)).dp
            val barY = (lineHeight * hunk.startLine).dp

            // Ours section background (red tint)
            Box(
                Modifier
                    .padding(start = 72.dp)
                    .fillMaxWidth()
                    .height(oursHeight)
                    .offset(y = oursY)
                    .background(Color(0x33FF6B6B))
            )
            // Theirs section background (green tint)
            Box(
                Modifier
                    .padding(start = 72.dp)
                    .fillMaxWidth()
                    .height(theirsHeight)
                    .offset(y = theirsY)
                    .background(Color(0x334EC9B0))
            )
            // Resolve button bar at conflict start
            Box(
                Modifier
                    .padding(start = 72.dp)
                    .offset(y = barY)
                    .zIndex(10f)
            ) {
                Row(
                    Modifier.background(Color(0xFF1A1A2E)).padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("<<<", color = Color(0xFFE06C75), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(4.dp))
                    Text(hunk.oursBranch.take(10), color = Color(0xFFE06C75), fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text("vs", color = Color(0xFF858585), fontSize = 8.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(hunk.theirsBranch.take(10), color = Color(0xFF4EC9B0), fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    // Ours button
                    Box(
                        Modifier.background(Color(0x66FF6B6B), RoundedCornerShape(3.dp)).clickable { onResolveConflict?.invoke(hunk, ConflictResolution.OURS) }.padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text("Ours", color = Color.White, fontSize = 8.sp)
                    }
                    Spacer(Modifier.width(3.dp))
                    // Theirs button
                    Box(
                        Modifier.background(Color(0x664EC9B0), RoundedCornerShape(3.dp)).clickable { onResolveConflict?.invoke(hunk, ConflictResolution.THEIRS) }.padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text("Theirs", color = Color.White, fontSize = 8.sp)
                    }
                    Spacer(Modifier.width(3.dp))
                    // Both button
                    Box(
                        Modifier.background(Color(0x66569CD6), RoundedCornerShape(3.dp)).clickable { onResolveConflict?.invoke(hunk, ConflictResolution.BOTH) }.padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text("Both", color = Color.White, fontSize = 8.sp)
                    }
                }
            }
        }
    }
}

