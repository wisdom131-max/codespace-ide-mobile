package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * P41-H: Generic Peek Widget — inline code preview overlay.
 * Used for Peek Definition, Peek References, and Peek Declaration.
 * Extracted from CodeEditor.kt to avoid 64KB method limit.
 */

/** Result for single-location peek (Definition, Declaration). */
data class PeekResult(
    val title: String,
    val filePath: String,
    val line: Int,           // target line (0-based)
    val lines: List<String>, // code lines to display
    val defLine: Int,        // highlighted line index
    val usedLsp: Boolean,
)

/** Result for multi-location peek (References). */
data class PeekRefsResult(
    val word: String,
    val refs: List<Triple<String, Int, String>>, // (filePath, line0Based, snippet)
    val usedLsp: Boolean,
)

/**
 * Single-location Peek overlay (Definition, Declaration).
 * Renders code preview with highlighted target line.
 */
@Composable
fun PeekCodeWidget(
    result: PeekResult,
    currentFilePath: String?,
    onNavigate: (filePath: String, line: Int) -> Unit,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth(0.92f)
            .fillMaxHeight(0.5f)
            .zIndex(30f),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF252526))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    result.title,
                    color = Color(0xFF4EC9B0),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Box(
                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        .background(if (result.usedLsp) Color(0xFF4EC9B0) else Color(0xFFCC7832))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        if (result.usedLsp) "LSP" else "Fallback",
                        color = Color(0xFF1E1E1E),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.weight(1f))
                val fileName = result.filePath.substringAfterLast('/')
                Text(
                    "$fileName:${result.line + 1}",
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClose, contentPadding = PaddingValues(4.dp)) {
                    Text("X", color = Color(0xFF888888), fontSize = 16.sp)
                }
            }
            HorizontalDivider(color = Color(0xFF333333))
            // Code preview
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                result.lines.forEachIndexed { idx, line ->
                    val isDefLine = idx == result.defLine
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isDefLine) Color(0xFF007ACC).copy(alpha = 0.15f) else Color.Transparent)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "" + (result.line - result.defLine + idx + 1),
                            color = if (isDefLine) Color(0xFF007ACC) else Color(0xFF858585),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(
                            line.take(120),
                            color = if (isDefLine) Color(0xFFD4D4D4) else Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            // Footer
            HorizontalDivider(color = Color(0xFF333333))
            Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) {
                    Text("Close", color = Color(0xFF888888), fontSize = 12.sp)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = {
                        if (result.filePath == currentFilePath) {
                            // Same file — scroll handled by caller
                            onNavigate(result.filePath, result.line)
                        } else {
                            onNavigate(result.filePath, result.line + 1)
                        }
                        onClose()
                    }
                ) {
                    Text("Go to Definition →", color = Color(0xFF007ACC), fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Multi-location Peek overlay (References).
 * Renders a scrollable list of reference locations.
 */
@Composable
fun PeekReferencesWidget(
    result: PeekRefsResult,
    currentFilePath: String?,
    onNavigate: (filePath: String, line: Int) -> Unit,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth(0.92f)
            .fillMaxHeight(0.5f)
            .zIndex(30f),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF252526))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Peek References",
                    color = Color(0xFF4EC9B0),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Box(
                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        .background(if (result.usedLsp) Color(0xFF4EC9B0) else Color(0xFFCC7832))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        if (result.usedLsp) "LSP" else "Fallback",
                        color = Color(0xFF1E1E1E),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${result.refs.size} ref${if (result.refs.size != 1) "s" else ""} — \"${result.word}\"",
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClose, contentPadding = PaddingValues(4.dp)) {
                    Text("X", color = Color(0xFF888888), fontSize = 16.sp)
                }
            }
            HorizontalDivider(color = Color(0xFF333333))
            // Reference list
            if (result.refs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                    Text("No references found for '${result.word}'.", color = Color(0xFF888888), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                    items(result.refs) { (refPath, refLine, snippet) ->
                        val fileName = refPath.substringAfterLast('/')
                        TextButton(
                            onClick = {
                                onNavigate(refPath, refLine)
                                onClose()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(fileName, color = Color(0xFF569CD6), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Spacer(Modifier.width(6.dp))
                                    Text(":${refLine + 1}", color = Color(0xFF858585), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                Text(snippet.take(100), color = Color(0xFFAAAAAA), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }
            // Footer
            HorizontalDivider(color = Color(0xFF333333))
            Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) {
                    Text("Close", color = Color(0xFF888888), fontSize = 12.sp)
                }
            }
        }
    }
}
