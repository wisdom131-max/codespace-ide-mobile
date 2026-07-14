package com.codespace.ide.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.project.BuildHistoryStore
import kotlinx.coroutines.launch

/**
 * Phase 12-K — Build History Panel
 *
 * Bottom panel tab: scrollable list of past builds with status, duration,
 * error/warning counts, and expandable log snippet.
 */
@Composable
fun BuildHistoryPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val records by BuildHistoryStore.records.collectAsState()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Load history on first composition
    LaunchedEffect(Unit) {
        BuildHistoryStore.load(context)
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Build History", fontSize = 14.sp) },
            text = { Text("Delete all ${records.size} build records?", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { BuildHistoryStore.clearAll(context) }
                    showClearDialog = false
                }) { Text("Clear", color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Header ─────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E2E))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "BUILD HISTORY",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9EA3B0),
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${records.size} builds",
                fontSize = 10.sp,
                color = Color(0xFF4B5563),
                modifier = Modifier.padding(end = 8.dp),
            )
            if (records.isNotEmpty()) {
                IconButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Default.Delete, "Clear history", tint = Color(0xFF6B7280), modifier = Modifier.size(14.dp))
                }
            }
        }

        HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 1.dp)

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No builds yet", fontSize = 12.sp, color = Color(0xFF4B5563))
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(records, key = { it.id }) { record ->
                val isExpanded = expandedId == record.id

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expandedId = if (isExpanded) null else record.id }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (record.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                            null,
                            tint = if (record.isSuccess) Color(0xFF4CAF50) else Color(0xFFEF5350),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                record.task,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFD4D4D4),
                            )
                            Row {
                                Text(record.projectName, fontSize = 10.sp, color = Color(0xFF6B7280))
                                Text(" \u00b7 ", fontSize = 10.sp, color = Color(0xFF4B5563))
                                Text(record.formattedDate, fontSize = 10.sp, color = Color(0xFF6B7280))
                                Text(" \u00b7 ", fontSize = 10.sp, color = Color(0xFF4B5563))
                                Text(record.durationLabel, fontSize = 10.sp, color = Color(0xFF6B7280))
                            }
                        }
                        // Error/warning badges
                        if (record.errorCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF3A1A1A),
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                Text(
                                    "${record.errorCount}E",
                                    fontSize = 9.sp,
                                    color = Color(0xFFEF5350),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (record.warningCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF2A1F0A),
                            ) {
                                Text(
                                    "${record.warningCount}W",
                                    fontSize = 9.sp,
                                    color = Color(0xFFFF9800),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            tint = Color(0xFF4B5563),
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    // Expanded log snippet
                    if (isExpanded && record.logSnippet.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0D0D1A), RoundedCornerShape(4.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                record.logSnippet.takeLast(800),
                                fontSize = 9.sp,
                                color = Color(0xFF9EA3B0),
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 13.sp,
                            )
                        }
                        // Delete record
                        TextButton(
                            onClick = { scope.launch { BuildHistoryStore.delete(context, record.id) } },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("Delete record", fontSize = 9.sp, color = Color(0xFF6B7280))
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 0.5.dp)
            }
        }
    }
}
