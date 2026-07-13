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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    // Sorted: Errors first, then Warnings, then Infos
    val sortedProblems = errors + warnings + infos

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
                    color = Color(0xFF4EC9B0), // Green tint matching VS Code success / green
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
