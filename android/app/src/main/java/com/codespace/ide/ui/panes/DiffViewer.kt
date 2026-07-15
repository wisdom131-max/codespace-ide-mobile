package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DiffViewer — parses unified diff output into structured hunks and renders
 * them with line numbers, background colors, and hunk navigation.
 */

data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>
)

data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLineNum: Int?,
    val newLineNum: Int?
)

enum class DiffLineType { CONTEXT, ADDITION, DELETION, HEADER }

data class ParsedDiff(
    val hunks: List<DiffHunk>,
    val totalAdditions: Int,
    val totalDeletions: Int
)

fun parseUnifiedDiff(diffText: String): ParsedDiff {
    val lines = diffText.lines()
    val hunks = mutableListOf<DiffHunk>()
    var additions = 0
    var deletions = 0

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("@@")) {
            val regex = Regex("@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
            val match = regex.find(line)
            if (match != null) {
                val oldStart = match.groupValues[1].toInt()
                val oldCount = match.groupValues[2].ifBlank { "1" }.toInt()
                val newStart = match.groupValues[3].toInt()
                val newCount = match.groupValues[4].ifBlank { "1" }.toInt()

                val hunkLines = mutableListOf<DiffLine>()
                hunkLines.add(DiffLine(DiffLineType.HEADER, line, null, null))

                var oldLn = oldStart
                var newLn = newStart
                i++

                while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("diff ") && !lines[i].startsWith("--- ") && !lines[i].startsWith("+++ ")) {
                    val l = lines[i]
                    when {
                        l.startsWith(" ") -> {
                            hunkLines.add(DiffLine(DiffLineType.CONTEXT, l.drop(1), oldLn, newLn))
                            oldLn++; newLn++
                        }
                        l.startsWith("+") && !l.startsWith("+++") -> {
                            hunkLines.add(DiffLine(DiffLineType.ADDITION, l.drop(1), null, newLn))
                            newLn++
                            additions++
                        }
                        l.startsWith("-") && !l.startsWith("---") -> {
                            hunkLines.add(DiffLine(DiffLineType.DELETION, l.drop(1), oldLn, null))
                            oldLn++
                            deletions++
                        }
                        l.startsWith("\\") -> {
                            // No newline marker — skip
                        }
                        else -> {
                            hunkLines.add(DiffLine(DiffLineType.CONTEXT, l, oldLn, newLn))
                            oldLn++; newLn++
                        }
                    }
                    i++
                }

                hunks.add(DiffHunk(oldStart, oldCount, newStart, newCount, hunkLines))
                continue
            }
        }
        i++
    }

    return ParsedDiff(hunks, additions, deletions)
}

@Composable
fun DiffViewer(
    diffText: String,
    modifier: Modifier = Modifier
) {
    val parsed = remember(diffText) { parseUnifiedDiff(diffText) }

    if (parsed.hunks.isEmpty()) {
        Text("No changes", fontSize = 11.sp, color = Color(0xFF858585), fontFamily = FontFamily.Monospace, modifier = modifier.padding(16.dp))
        return
    }

    var currentHunk by remember { mutableStateOf(0) }
    val scrollState = rememberScrollState()

    Column(modifier) {
        // Stats bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("+${parsed.totalAdditions}", fontSize = 10.sp, color = Color(0xFF4EC9B0), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("-${parsed.totalDeletions}", fontSize = 10.sp, color = Color(0xFFFF6B6B), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (parsed.hunks.size > 1) {
                Text("${currentHunk + 1}/${parsed.hunks.size}", fontSize = 10.sp, color = Color(0xFF858585), fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ChevronLeft, null, tint = Color(0xFF858585), modifier = Modifier.size(14.dp).clickable { if (currentHunk > 0) currentHunk-- })
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF858585), modifier = Modifier.size(14.dp).clickable { if (currentHunk < parsed.hunks.size - 1) currentHunk++ })
            }
        }

        // Diff content
        Column(
            Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(scrollState).padding(start = 8.dp, end = 4.dp)
        ) {
            parsed.hunks.forEachIndexed { hunkIdx, hunk ->
                val bg = if (hunkIdx == currentHunk) Color(0xFF1A1A2E) else Color.Transparent
                Column(Modifier.fillMaxWidth().background(bg)) {
                    hunk.lines.forEach { diffLine ->
                        DiffLineRow(diffLine)
                    }
                }
                if (hunkIdx < parsed.hunks.size - 1) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val (bgColor, textColor, prefix) = when (line.type) {
        DiffLineType.ADDITION -> Triple(Color(0xFF1B3A1B), Color(0xFF4EC9B0), "+")
        DiffLineType.DELETION -> Triple(Color(0xFF3A1B1B), Color(0xFFFF6B6B), "-")
        DiffLineType.HEADER -> Triple(Color(0xFF0D2233), Color(0xFF569CD6), "@")
        DiffLineType.CONTEXT -> Triple(Color.Transparent, Color(0xFFCCCCCC), " ")
    }

    Row(Modifier.fillMaxWidth().background(bgColor).horizontalScroll(rememberScrollState())) {
        Text(line.oldLineNum?.toString()?.padStart(4) ?: "    ", fontSize = 10.sp, color = Color(0xFF858585), fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp).padding(start = 2.dp))
        Text(line.newLineNum?.toString()?.padStart(4) ?: "    ", fontSize = 10.sp, color = Color(0xFF858585), fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp).padding(start = 2.dp))
        Text(prefix, fontSize = 11.sp, color = textColor, fontFamily = FontFamily.Monospace, modifier = Modifier.width(12.dp))
        Text(line.content, fontSize = 11.sp, color = textColor, fontFamily = FontFamily.Monospace, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
