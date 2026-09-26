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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
    // DG09 (P4b): the jump now carries the frame's FILE and LINE — the old
    // no-arg callback ignored the line entirely and the caller joined the
    // GUEST frame path onto the HOST project root, opening a wrong/nonexistent
    // file. Callers translate guest->host and jump with the exact line.
    onJumpToSource: (file: String, line: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val udm = com.codespace.ide.debug.UniversalDebugManager
    // DG10 (P4b): watches come from UDM's single DebugWatch store — the panel's
    // private remember list previously diverged from the Explorer panel's list
    // and neither survived switching.
    var watchExprs by remember { mutableStateOf(udm.getWatches()) }
    // DG10: a watch added/removed in the Explorer debug panel re-syncs this
    // cache immediately — both surfaces render the ONE UDM watch list.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val sync: () -> Unit = { watchExprs = udm.getWatches() }
        udm.addOnWatchesChangedListener(sync)
        onDispose { udm.removeOnWatchesChangedListener(sync) }
    }
    var newExpr by remember { mutableStateOf("") }
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
                                    // DG10: the shared store is the one watch list.
                                    udm.addWatch(newExpr)
                                    val sid = udm.getActiveSession()?.id
                                    if (sid != null) udm.refreshWatches(sid)
                                    watchExprs = udm.getWatches()
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
                            "= ${we.value}",
                            color = if (we.value == "—" || we.value == "---") Color(0xFF666666) else Color(0xFF89D185),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = {
                                udm.removeWatch(we.id)  // DG10: shared store
                                watchExprs = udm.getWatches()
                            },
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
                // P26-1: Multi-listener — no callback conflict with RunDebugPanel
                var pausedVars by remember { mutableStateOf<List<com.codespace.ide.debug.DebugVariable>>(emptyList()) }
                val varsListener: (List<com.codespace.ide.debug.DebugStackFrame>, List<com.codespace.ide.debug.DebugVariable>) -> Unit = { _, vars ->
                    pausedVars = vars
                    // P26-1c/DG10: live watch refresh goes through the shared
                    // UDM store so the Explorer watch list stays in sync too.
                    if (watchExprs.isNotEmpty()) {
                        udm.refreshWatches(udm.getActiveSession()?.id)
                        watchExprs = udm.getWatches()
                    }
                }
                LaunchedEffect(Unit) {
                    com.codespace.ide.debug.UniversalDebugManager.addOnPausedListener(varsListener)
                }
                DisposableEffect(Unit) {
                    onDispose { com.codespace.ide.debug.UniversalDebugManager.removeOnPausedListener(varsListener) }
                }
                val session = com.codespace.ide.debug.UniversalDebugManager.getActiveSession()
                if (session == null) {
                    Text(
                        "  No active debug session — set breakpoints and press Run",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color(0xFF666666),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                } else if (pausedVars.isEmpty()) {
                    Text(
                        "  Running... (variables appear when paused at breakpoint)",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color(0xFF666666),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    // P26-1b: Object expansion — expandable variables can be clicked to show children
                    var expandedVars by remember { mutableStateOf(setOf<String>()) }
                    pausedVars.forEach { v ->
                        val isExpanded = v.name in expandedVars
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (12 + v.depth * 16).dp, end = 8.dp, top = 1.dp, bottom = 1.dp)
                                .clickable {
                                    if (v.expandable) {
                                        expandedVars = if (isExpanded) expandedVars - v.name else expandedVars + v.name
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (v.expandable) {
                                Icon(
                                    if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null, tint = Color(0xFF808080),
                                    modifier = Modifier.size(12.dp)
                                )
                            } else {
                                Spacer(Modifier.width(12.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(v.name, color = Color(0xFF9CDCFE), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(": ", color = Color(0xFF808080), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(v.type, color = Color(0xFF569CD6), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(" = ", color = Color(0xFF808080), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(v.value, color = Color(0xFFD4D4D4), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                maxLines = if (isExpanded) 5 else 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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
            // P26-1: Multi-listener for call stack
            var pausedStack by remember { mutableStateOf<List<com.codespace.ide.debug.DebugStackFrame>>(emptyList()) }
            val stackListener: (List<com.codespace.ide.debug.DebugStackFrame>, List<com.codespace.ide.debug.DebugVariable>) -> Unit = { stack, _ ->
                pausedStack = stack
            }
            LaunchedEffect(Unit) {
                com.codespace.ide.debug.UniversalDebugManager.addOnPausedListener(stackListener)
            }
            DisposableEffect(Unit) {
                onDispose { com.codespace.ide.debug.UniversalDebugManager.removeOnPausedListener(stackListener) }
            }
            val session = com.codespace.ide.debug.UniversalDebugManager.getActiveSession()
            if (session == null) {
                Text(
                    "  No active debug session — set breakpoints and press Run",
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { activeFilePath?.let { onJumpToSource(it, 0) } },
                    color = Color(0xFF666666),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else if (pausedStack.isEmpty()) {
                Text(
                    "  Running...",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Color(0xFF666666),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                // DG09: tapping a frame jumps to its REAL location — the guest
                // frame path is translated by the caller (host form) and the
                // 0-based frame line carries through to the editor jump.
                pausedStack.forEach { frame ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToSource(frame.file, frame.line) }
                            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            frame.function,
                            color = if (frame.active) Color(0xFF569CD6) else Color(0xFF9CDCFE),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            frame.file.substringAfterLast("/") + ":" + (frame.line + 1),
                            color = Color(0xFFCE9178),
                            fontSize = 11.sp,
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
