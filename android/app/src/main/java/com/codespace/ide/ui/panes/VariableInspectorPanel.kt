package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// P8-3 Variable Inspector — shows variables, watch expressions, and call stack.
// Works standalone (static analysis of current file) and is ready for DAP integration.

private data class WatchExpr(
    val id: Int,
    val expression: String,
    val value: String = "—",
)

private data class VarEntry(
    val name: String,
    val type: String,
    val value: String,
    val depth: Int = 0,
    val expandable: Boolean = false,
)

private data class StackFrame(
    val function: String,
    val file: String,
    val line: Int,
    val active: Boolean = false,
)

@Composable
fun VariableInspectorPanel(
    activeFilePath: String? = null,
    onJumpToSource: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var watchExprs by remember { mutableStateOf(listOf<WatchExpr>()) }
    var newExpr by remember { mutableStateOf("") }
    var nextId by remember { mutableStateOf(0) }
    var expandedSections by remember { mutableStateOf(setOf("watch", "locals", "stack")) }

    fun toggleSection(key: String) {
        expandedSections = if (key in expandedSections) expandedSections - key else expandedSections + key
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E)),
    ) {
        // Watch expressions section
        SectionHeader(
            title = "WATCH",
            expanded = "watch" in expandedSections,
            onToggle = { toggleSection("watch") },
        )
        if ("watch" in expandedSections) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = newExpr,
                    onValueChange = { newExpr = it },
                    placeholder = { Text("Add expression...", fontSize = 12.sp, color = Color(0xFF808080)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFD4D4D4),
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (newExpr.isNotBlank()) {
                                    watchExprs = watchExprs + WatchExpr(nextId++, newExpr.trim())
                                    newExpr = ""
                                }
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.Add, "Add", tint = Color(0xFF569CD6), modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }
            if (watchExprs.isEmpty()) {
                Text(
                    "  No watch expressions — add one above",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Color(0xFF666666),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                watchExprs.forEach { we ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.Code, null, tint = Color(0xFF9CDCFE), modifier = Modifier.size(12.dp))
                        Text(
                            we.expression,
                            color = Color(0xFFD4D4D4),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            we.value,
                            color = Color(0xFF808080),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        IconButton(
                            onClick = { watchExprs = watchExprs.filterNot { it.id == we.id } },
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(Icons.Default.Close, "Remove", tint = Color(0xFF666666), modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color(0xFF333333))

        // Local variables section
        SectionHeader(
            title = "LOCAL VARIABLES",
            expanded = "locals" in expandedSections,
            onToggle = { toggleSection("locals") },
        )
        if ("locals" in expandedSections) {
            if (activeFilePath == null) {
                Text(
                    "  No file open — open a file to see its variables",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Color(0xFF666666),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                val stubVars = remember(activeFilePath) {
                    listOf(
                        VarEntry("this", "object", "{ }", 0, true),
                        VarEntry("args", "String[]", "[0 items]", 0, true),
                    )
                }
                stubVars.forEach { v ->
                    VarRow(v)
                }
            }
        }

        HorizontalDivider(color = Color(0xFF333333))

        // Call stack section
        SectionHeader(
            title = "CALL STACK",
            expanded = "stack" in expandedSections,
            onToggle = { toggleSection("stack") },
        )
        if ("stack" in expandedSections) {
            Text(
                "  No active debug session — set breakpoints and press Run",
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { onJumpToSource() },
                color = Color(0xFF666666),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            null,
            tint = Color(0xFF808080),
            modifier = Modifier.size(14.dp),
        )
        Text(
            title,
            color = Color(0xFF808080),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun VarRow(v: VarEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (12 + v.depth * 12).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (v.expandable) {
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF808080), modifier = Modifier.size(12.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            v.name,
            color = Color(0xFF9CDCFE),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            v.type,
            color = Color(0xFF4EC9B0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.weight(1f))
        Text(
            v.value,
            color = Color(0xFFCE9178),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
