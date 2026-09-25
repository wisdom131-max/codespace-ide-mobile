package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.codespace.ide.editor.PowerUserAnalyzer

/**
 * P41-P: TODO Explorer panel — scans workspace for TODO/FIXME/HACK/XXX/NOTE comments.
 */
@Composable
fun TodoExplorerPanel(
    todos: List<PowerUserAnalyzer.TodoItem>,
    onJumpToSource: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filterTag by remember { mutableStateOf<String?>(null) }
    val tags = todos.map { it.tag }.distinct()
    val filtered = if (filterTag != null) todos.filter { it.tag == filterTag } else todos

    Column(modifier = modifier.fillMaxSize().padding(4.dp)) {
        // Tag filter chips
        if (tags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChipSmall("All", filterTag == null) { filterTag = null }
                tags.forEach { tag ->
                    val color = when (tag) {
                        "FIXME" -> Color(0xFFFF6B6B)
                        "HACK" -> Color(0xFFFFD93D)
                        "XXX" -> Color(0xFFFF9F43)
                        "TODO" -> Color(0xFF4EC9B0)
                        else -> Color(0xFF888888)
                    }
                    FilterChipSmall(tag, filterTag == tag, color) { filterTag = tag }
                }
            }
        }

        Text(
            "${filtered.size} items",
            color = Color(0xFF888888),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJumpToSource(item.file, item.line) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        item.tag,
                        color = when (item.tag) {
                            "FIXME" -> Color(0xFFFF6B6B)
                            "HACK" -> Color(0xFFFFD93D)
                            "XXX" -> Color(0xFFFF9F43)
                            "TODO" -> Color(0xFF4EC9B0)
                            else -> Color(0xFF888888)
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${item.file}:${item.line}",
                        color = Color(0xFF858585),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        item.text,
                        color = Color(0xFFD4D4D4),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * P41-P: Code Analysis panel — shows dead code, duplicate code, and complexity for current file.
 */
@Composable
fun CodeAnalysisPanel(
    deadCode: List<PowerUserAnalyzer.DeadCodeItem>,
    duplicates: List<PowerUserAnalyzer.DuplicateItem>,
    complexity: List<PowerUserAnalyzer.ComplexityItem>,
    onJumpToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(0) }
    val tabs = listOf("Dead Code (${deadCode.size})", "Duplicates (${duplicates.size})", "Complexity (${complexity.size})")

    Column(modifier = modifier.fillMaxSize().padding(4.dp)) {
        // Tab selector
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEachIndexed { idx, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (activeTab == idx) Color(0xFF3C3C3C) else Color(0xFF252526),
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { activeTab = idx }
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (activeTab == idx) Color(0xFFD4D4D4) else Color(0xFF888888), fontSize = 10.sp)
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            when (activeTab) {
                0 -> {
                    items(deadCode) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpToLine(item.line) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.kind,
                                color = Color(0xFF888888),
                                fontSize = 10.sp,
                                modifier = Modifier.width(60.dp),
                            )
                            Text(
                                item.name,
                                color = Color(0xFFFF6B6B),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("L${item.line}", color = Color(0xFF666666), fontSize = 10.sp)
                        }
                    }
                    if (deadCode.isEmpty()) {
                        item { Text("No dead code detected.", color = Color(0xFF666666), fontSize = 11.sp, modifier = Modifier.padding(16.dp)) }
                    }
                }
                1 -> {
                    items(duplicates) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpToLine(item.lineStart) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${item.duplicateCount}x", color = Color(0xFFFFD93D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                item.preview,
                                color = Color(0xFFD4D4D4),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("L${item.lineStart}-${item.lineEnd}", color = Color(0xFF666666), fontSize = 10.sp)
                        }
                    }
                    if (duplicates.isEmpty()) {
                        item { Text("No duplicate blocks detected.", color = Color(0xFF666666), fontSize = 11.sp, modifier = Modifier.padding(16.dp)) }
                    }
                }
                2 -> {
                    items(complexity) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpToLine(item.line) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.risk,
                                color = when (item.risk) {
                                    "Critical" -> Color(0xFFFF6B6B)
                                    "High" -> Color(0xFFFF9F43)
                                    "Medium" -> Color(0xFFFFD93D)
                                    else -> Color(0xFF4EC9B0)
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(60.dp),
                            )
                            Text(
                                item.functionName,
                                color = Color(0xFFD4D4D4),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${item.complexity}", color = Color(0xFF888888), fontSize = 11.sp)
                        }
                    }
                    if (complexity.isEmpty()) {
                        item { Text("No functions detected.", color = Color(0xFF666666), fontSize = 11.sp, modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipSmall(label: String, isActive: Boolean, color: Color = Color(0xFF888888), onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (isActive) color.copy(alpha = 0.3f) else Color(0xFF252526),
                RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            color = if (isActive) color else Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
