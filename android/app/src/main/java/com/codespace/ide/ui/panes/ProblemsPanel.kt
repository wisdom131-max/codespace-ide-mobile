package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    // P41-O3: Diagnostic filtering — toggleable severity filters
    var showErrors by remember { mutableStateOf(true) }
    var showWarnings by remember { mutableStateOf(true) }
    var showInfos by remember { mutableStateOf(true) }

    val filteredProblems = problems.filter { p ->
        when (p.severity) {
            Problem.Severity.ERROR -> showErrors
            Problem.Severity.WARNING -> showWarnings
            Problem.Severity.INFO -> showInfos
        }
    }

    // Sorted: Errors first, then Warnings, then Infos
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

        // P41-O3: Filter chips row
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
                    text = "No problems detected ✓",
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToLine(problem.line) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Severity Icon
                        val (icon, color) = when (problem.severity) {
                            Problem.Severity.ERROR -> Icons.Filled.Error to Color(0xFFF44747)
                            Problem.Severity.WARNING -> Icons.Filled.Warning to Color(0xFFCCA700)
                            Problem.Severity.INFO -> Icons.Filled.Info to Color(0xFF75BEFF)
                        }
                        
                        Icon(
                            imageVector = icon,
                            contentDescription = problem.severity.name,
                            tint = color,
                            modifier = Modifier.size(16.dp)
                        )

                        // Message text
                        Text(
                            text = problem.message,
                            color = Color(0xFFCCCCCC),
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.weight(1f)
                        )

                        // Line number chip
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
                    }
                }
            }
        }
    }
}
