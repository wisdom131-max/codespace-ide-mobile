package com.codespace.ide.ui.panes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.diagnostics.Problem

@Composable
fun ProblemsPanel(
    problems: List<Problem>,
    filePath: String,
    onJumpToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errors = problems.filter { it.severity == Problem.Severity.ERROR }
    val warnings = problems.filter { it.severity == Problem.Severity.WARNING }
    val infos = problems.filter { it.severity == Problem.Severity.INFO }

    var showErrors by remember { mutableStateOf(true) }
    var showWarnings by remember { mutableStateOf(true) }
    var showInfos by remember { mutableStateOf(true) }
    var expandedProblem by remember { mutableStateOf<Problem?>(null) }

    val filteredProblems = problems.filter { p ->
        when (p.severity) {
            Problem.Severity.ERROR -> showErrors
            Problem.Severity.WARNING -> showWarnings
            Problem.Severity.INFO -> showInfos
        }
    }

    val sortedProblems = filteredProblems.filter { it.severity == Problem.Severity.ERROR } +
                         filteredProblems.filter { it.severity == Problem.Severity.WARNING } +
                         filteredProblems.filter { it.severity == Problem.Severity.INFO }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        // Top Badges / Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val fileName = filePath.substringAfterLast('/')
            Text(
                text = if (fileName.isNotEmpty()) fileName else "Problems",
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${errors.size} errors, ${warnings.size} warnings",
                    color = Color(0xFF969696),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Filter chips row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = showErrors,
                onClick = { showErrors = !showErrors },
                label = { Text("${errors.size} Errors", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFF44747)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF332222),
                    containerColor = Color(0xFF2D2D2D),
                )
            )
            if (warnings.isNotEmpty()) {
                FilterChip(
                    selected = showWarnings,
                    onClick = { showWarnings = !showWarnings },
                    label = { Text("${warnings.size} Warnings", fontSize = 10.sp) },
                    leadingIcon = { Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFCCA700)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF332B0F),
                        containerColor = Color(0xFF2D2D2D),
                    )
                )
            }
            if (infos.isNotEmpty()) {
                FilterChip(
                    selected = showInfos,
                    onClick = { showInfos = !showInfos },
                    label = { Text("${infos.size} Info", fontSize = 10.sp) },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF75BEFF)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF112233),
                        containerColor = Color(0xFF2D2D2D),
                    )
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF2D2D2D))
        )

        if (sortedProblems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No problems detected",
                    color = Color(0xFF4EC9B0),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(sortedProblems) { problem ->
                    ProblemRow(
                        problem = problem,
                        isExpanded = expandedProblem == problem,
                        onToggle = {
                            expandedProblem = if (expandedProblem == problem) null else problem
                        },
                        onJumpToLine = onJumpToLine,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProblemRow(
    problem: Problem,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onJumpToLine: (Int) -> Unit,
) {
    val (icon, color) = when (problem.severity) {
        Problem.Severity.ERROR -> Icons.Filled.Error to Color(0xFFF44747)
        Problem.Severity.WARNING -> Icons.Filled.Warning to Color(0xFFCCA700)
        Problem.Severity.INFO -> Icons.Filled.Info to Color(0xFF75BEFF)
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onJumpToLine(problem.line) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = problem.severity.name,
                tint = color,
                modifier = Modifier.size(16.dp)
            )

            Text(
                text = problem.message,
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .background(Color(0xFF2D2D2D), shape = RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Ln ${problem.line}",
                    color = Color(0xFF858585),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = "Show full error",
                    tint = Color(0xFF858585),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (isExpanded) {
            ProblemDetailPopup(problem = problem, onDismiss = onToggle)
        }
    }
}

@Composable
private fun ProblemDetailPopup(
    problem: Problem,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color(0xFF252526),
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Full error message (monospace, multi-line, no truncation)
            Text(
                text = problem.message,
                color = Color(0xFFD4D4D4),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
            )

            // Metadata
            Spacer(Modifier.height(8.dp))
            val metaParts = mutableListOf("Line: ${problem.line}")
            problem.source?.let { metaParts.add("Source: $it") }
            problem.code?.let { metaParts.add("Code: $it") }
            Text(
                text = metaParts.joinToString("  |  "),
                color = Color(0xFF858585),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )

            // Related info
            if (problem.relatedInfo.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                problem.relatedInfo.forEach { (msg, loc) ->
                    Text(
                        text = "- $msg ($loc)",
                        color = Color(0xFF858585),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                    )
                }
            }

            // Action buttons
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Copy button
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val fullText = buildString {
                            append(problem.message)
                            problem.source?.let { append("\nSource: $it") }
                            problem.code?.let { append("\nCode: $it") }
                            append("\nLine: ${problem.line}")
                            if (problem.relatedInfo.isNotEmpty()) {
                                append("\n\nRelated:")
                                problem.relatedInfo.forEach { (msg, loc) ->
                                    append("\n- $msg ($loc)")
                                }
                            }
                        }
                        cm.setPrimaryClip(ClipData.newPlainText("Error", fullText))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy", fontSize = 12.sp)
                }

                // Save button
                OutlinedButton(
                    onClick = {
                        val errorText = buildString {
                            append("Line: ${problem.line}\n")
                            append("Severity: ${problem.severity}\n")
                            append("Message: ${problem.message}\n")
                            problem.source?.let { append("Source: $it\n") }
                            problem.code?.let { append("Code: $it\n") }
                            if (problem.relatedInfo.isNotEmpty()) {
                                append("\nRelated:\n")
                                problem.relatedInfo.forEach { (msg, loc) ->
                                    append("- $msg ($loc)\n")
                                }
                            }
                        }
                        val fileName = "error_${problem.line}_${problem.code ?: problem.severity.name}.txt"
                        try {
                            val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS
                            )
                            downloads.mkdirs()
                            val outFile = java.io.File(downloads, fileName.replace("/", "_"))
                            outFile.writeText(errorText)
                            Toast.makeText(context, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save", fontSize = 12.sp)
                }

                // Close button
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("Close", fontSize = 12.sp)
                }
            }
        }
    }
}
