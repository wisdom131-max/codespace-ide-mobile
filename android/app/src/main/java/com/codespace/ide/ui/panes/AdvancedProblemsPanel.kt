package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.diagnostics.DiagnosticManager

/**
 * Phase P — Advanced Problems Panel.
 *
 * Reads from the central DiagnosticManager (single source of truth).
 * Supports: severity filtering, source filtering, search, grouping by file,
 * stale indicators, source health display, and click-to-navigate.
 */
@Composable
fun AdvancedProblemsPanel(
    onJumpToSource: (filePath: String, line: Int, column: Int) -> Unit,
    panelBg: Color = Color(0xFF1E1E1E),
    dividerColor: Color = Color(0xFF2D2D30),
    tabTextInactive: Color = Color(0xFF858585),
    tabTextActive: Color = Color(0xFFCCCCCC),
) {
    val allDiagnostics = DiagnosticManager.diagnostics
    val counts = remember(allDiagnostics) { DiagnosticManager.countBySeverity() }

    var showErrors by remember { mutableStateOf(true) }
    var showWarnings by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(true) }
    var showHints by remember { mutableStateOf(false) }
    var showStale by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf<DiagnosticManager.DiagnosticSource?>(null) }
    var expandedFile by remember { mutableStateOf<String?>(null) }

    // Apply filters
    val filteredDiagnostics = remember(
        allDiagnostics, showErrors, showWarnings, showInfo, showHints, showStale, searchQuery, sourceFilter
    ) {
        allDiagnostics.filter { d ->
            // Stale filter
            if (!showStale && d.isStale) return@filter false

            // Severity filter
            val severityOk = when (d.severity) {
                DiagnosticManager.Severity.ERROR -> showErrors
                DiagnosticManager.Severity.WARNING -> showWarnings
                DiagnosticManager.Severity.INFO -> showInfo
                DiagnosticManager.Severity.HINT -> showHints
            }
            if (!severityOk) return@filter false

            // Source filter
            if (sourceFilter != null && d.source != sourceFilter) return@filter false

            // Search filter
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                val matches = d.message.lowercase().contains(q) ||
                        d.filePath.lowercase().contains(q) ||
                        d.code?.lowercase()?.contains(q) == true ||
                        d.sourceName?.lowercase()?.contains(q) == true ||
                        d.sourceId.lowercase().contains(q)
                if (!matches) return@filter false
            }

            true
        }
    }

    // Group by file
    val groupedByFile = remember(filteredDiagnostics) {
        filteredDiagnostics.groupBy { it.filePath }
            .toSortedMap()
    }

    // Sort within each file: line -> column -> severity
    val sortedGroups = remember(groupedByFile) {
        groupedByFile.mapValues { (_, diags) ->
            diags.sortedWith(compareBy({ it.range.startLine }, { it.range.startColumn }))
        }
    }

    Column(Modifier.fillMaxSize().background(panelBg)) {
        // ── Header ──────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PROBLEMS" + if (counts.total > 0) " (${counts.total})" else "",
                fontSize = 11.sp, color = tabTextInactive,
                modifier = Modifier.weight(1f),
            )
            // Severity count chips
            if (counts.errors > 0) {
                SeverityChip("${counts.errors}", Color(0xFFF44747), "errors")
            }
            if (counts.warnings > 0) {
                Spacer(Modifier.width(6.dp))
                SeverityChip("${counts.warnings}", Color(0xFFCCA700), "warnings")
            }
            if (counts.info > 0) {
                Spacer(Modifier.width(6.dp))
                SeverityChip("${counts.info}", Color(0xFF75BEFF), "info")
            }
        }
        HorizontalDivider(color = dividerColor)

        // ── Filter bar ────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Error toggle
            FilterToggle("E", showErrors, Color(0xFFF44747)) { showErrors = !showErrors }
            FilterToggle("W", showWarnings, Color(0xFFCCA700)) { showWarnings = !showWarnings }
            FilterToggle("I", showInfo, Color(0xFF75BEFF)) { showInfo = !showInfo }

            // Source filter dropdown (compact)
            if (sourceFilter != null) {
                IconButton(onClick = { sourceFilter = null }, modifier = Modifier.size(24.dp)) {
                    Text("×", color = tabTextInactive, fontSize = 14.sp)
                }
                Text(sourceFilter!!.name, color = tabTextInactive, fontSize = 9.sp, maxLines = 1)
            }
        }

        // ── Search bar ────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search problems...", fontSize = 11.sp, color = tabTextInactive) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = tabTextInactive, modifier = Modifier.size(14.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 11.sp, color = tabTextActive),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = dividerColor,
                unfocusedBorderColor = dividerColor,
            ),
        )
        HorizontalDivider(color = dividerColor)

        // ── Problem list ──────────────────────────────────────────────────
        if (sortedGroups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text(
                    if (counts.total == 0) "✓  No problems detected." else "No problems match current filters.",
                    fontSize = 13.sp, color = Color(0xFF717171),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                sortedGroups.forEach { (filePath, diags) ->
                    val fileName = filePath.substringAfterLast("/")
                    val fileErrors = diags.count { it.severity == DiagnosticManager.Severity.ERROR && !it.isStale }
                    val fileWarnings = diags.count { it.severity == DiagnosticManager.Severity.WARNING && !it.isStale }

                    item(key = "header_$filePath") {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { expandedFile = if (expandedFile == filePath) null else filePath }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.ExpandMore,
                                null,
                                tint = tabTextInactive,
                                modifier = Modifier.size(14.dp).let {
                                    if (expandedFile == filePath) it else it
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                fileName,
                                fontSize = 11.sp, color = tabTextActive, fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (fileErrors > 0) {
                                Text(" $fileErrors", fontSize = 10.sp, color = Color(0xFFF44747))
                            }
                            if (fileWarnings > 0) {
                                Text(" $fileWarnings", fontSize = 10.sp, color = Color(0xFFCCA700))
                            }
                            // Stale indicator
                            val staleCount = diags.count { it.isStale }
                            if (staleCount > 0) {
                                Text(" ⟳$staleCount", fontSize = 10.sp, color = Color(0xFF666666))
                            }
                        }
                    }

                    if (expandedFile == filePath || sortedGroups.size == 1) {
                        items(diags, key = { it.id }) { diag ->
                            DiagnosticRow(
                                diagnostic = diag,
                                onClick = { onJumpToSource(diag.filePath, diag.range.startLine, diag.range.startColumn) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    diagnostic: DiagnosticManager.Diagnostic,
    onClick: () -> Unit,
) {
    val (icon, color) = when (diagnostic.severity) {
        DiagnosticManager.Severity.ERROR -> Icons.Default.Cancel to Color(0xFFE51400)
        DiagnosticManager.Severity.WARNING -> Icons.Default.Warning to Color(0xFFCCA700)
        DiagnosticManager.Severity.INFO -> Icons.Default.Info to Color(0xFF007ACC)
        DiagnosticManager.Severity.HINT -> Icons.Default.Lightbulb to Color(0xFF6A9955)
    }

    Row(
        Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 28.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (diagnostic.isStale) color.copy(alpha = 0.4f) else color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            diagnostic.message,
            fontSize = 11.sp,
            color = if (diagnostic.isStale) Color(0xFF666666) else Color(0xFFCCCCCC),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Source badge
        val sourceLabel = diagnostic.sourceName ?: diagnostic.sourceId
        if (sourceLabel.isNotEmpty()) {
            Text(
                " $sourceLabel",
                fontSize = 9.sp,
                color = Color(0xFF6A6A6A),
                maxLines = 1,
            )
        }
        // Line:col
        Text(
            " ${diagnostic.range.startLine}:${diagnostic.range.startColumn}",
            fontSize = 9.sp,
            color = Color(0xFF6A6A6A),
        )
        // Code
        diagnostic.code?.let {
            Text(" [$it]", fontSize = 9.sp, color = Color(0xFF6A6A6A), maxLines = 1)
        }
    }
}

@Composable
private fun SeverityChip(text: String, color: Color, label: String) {
    Box(
        Modifier.background(color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FilterToggle(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .background(
                if (selected) color.copy(alpha = 0.2f) else Color(0xFF2D2D2D),
                shape = RoundedCornerShape(4.dp),
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = if (selected) color else Color(0xFF555555),
            fontWeight = FontWeight.Bold,
        )
    }
}
