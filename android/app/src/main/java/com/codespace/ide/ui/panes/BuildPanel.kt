package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.build.BuildEnvironment
import com.codespace.ide.build.BuildRunner
import com.codespace.ide.build.GradleErrorParser
import kotlinx.coroutines.launch

/**
 * Phase 11-4: Build Panel — extracted composable for the BUILD tab in the bottom panel.
 *
 * Shows: environment health, build button, live build output, error/warning summary.
 * All UI is self-contained — does NOT add to ProjectShellScreen method size.
 */
@Composable
fun BuildPanel(
    projectPath: String?,
    onProblemsUpdate: (List<GradleErrorParser.BuildProblem>) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var envReport by remember { mutableStateOf<BuildEnvironment.EnvironmentReport?>(null) }
    var isValidating by remember { mutableStateOf(false) }
    var buildResult by remember { mutableStateOf<BuildRunner.BuildResult?>(null) }
    var isBuilding by remember { mutableStateOf(false) }
    var buildOutput by remember { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf("assembleDebug") }
    var showEnvDetails by remember { mutableStateOf(false) }

    val buildStatus by BuildRunner.buildStatus.collectAsState()
    val liveOutput by BuildRunner.buildOutput.collectAsState()

    Column(
        Modifier.fillMaxSize().background(Color(0xFF1E1E1E)),
    ) {
        // ── Toolbar ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Build task selector
            var taskExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { taskExpanded = true },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(selectedTask, fontSize = 11.sp)
                    Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = taskExpanded, onDismissRequest = { taskExpanded = false }) {
                    listOf("assembleDebug", "assembleRelease", "build", "clean", "lint", "test").forEach { task ->
                        DropdownMenuItem(
                            text = { Text(task, fontSize = 12.sp) },
                            onClick = { selectedTask = task; taskExpanded = false },
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // Build button
            Button(
                onClick = {
                    if (projectPath != null && !isBuilding) {
                        isBuilding = true
                        buildOutput = ""
                        scope.launch {
                            val result = BuildRunner.runBuild(
                                context = ctx,
                                projectPath = projectPath,
                                task = selectedTask,
                            )
                            buildResult = result
                            buildOutput = result.output
                            isBuilding = false
                            onProblemsUpdate(GradleErrorParser.extractAllProblems(result.output))
                        }
                    }
                },
                enabled = projectPath != null && !isBuilding,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF007ACC),
                ),
            ) {
                if (isBuilding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (isBuilding) "Building..." else "Build",
                    fontSize = 11.sp,
                    color = Color.White,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Check Environment button
            OutlinedButton(
                onClick = {
                    if (!isValidating) {
                        isValidating = true
                        scope.launch {
                            envReport = BuildEnvironment.validateEnvironment(ctx)
                            isValidating = false
                        }
                    }
                },
                enabled = !isValidating,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Build, null, Modifier.size(14.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text("Check Env", fontSize = 11.sp)
            }

            Spacer(Modifier.weight(1f))

            // Build status indicator
            if (buildResult != null) {
                val result = buildResult!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (result.status == BuildRunner.BuildStatus.SUCCESS) Icons.Default.CheckCircle
                        else Icons.Default.Error,
                        null,
                        Modifier.size(14.dp),
                        tint = if (result.status == BuildRunner.BuildStatus.SUCCESS) Color(0xFF4CAF50) else Color(0xFFEF5350),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${result.errorCount}E / ${result.warningCount}W  ${(result.durationMs / 1000)}s",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFF333333))

        // ── Environment status (if checked) ──
        if (envReport != null) {
            val report = envReport!!
            Card(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (report.overallHealthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                            null,
                            Modifier.size(16.dp),
                            tint = if (report.overallHealthy) Color(0xFF4CAF50) else Color(0xFFFFA726),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (report.overallHealthy) "Environment Ready" else "Environment Issues Found",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showEnvDetails = !showEnvDetails }) {
                            Text(
                                if (showEnvDetails) "Hide" else "Details",
                                fontSize = 10.sp,
                            )
                        }
                    }

                    if (showEnvDetails) {
                        Spacer(Modifier.height(4.dp))
                        report.tools.forEach { tool ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(8.dp).background(
                                        when (tool.status) {
                                            BuildEnvironment.ToolStatus.OK -> Color(0xFF4CAF50)
                                            BuildEnvironment.ToolStatus.MISSING -> Color(0xFFEF5350)
                                            BuildEnvironment.ToolStatus.BROKEN -> Color(0xFFFFA726)
                                            BuildEnvironment.ToolStatus.UNKNOWN -> Color(0xFF757575)
                                        },
                                        RoundedCornerShape(50),
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(tool.displayName, fontSize = 10.sp, color = Color.White, modifier = Modifier.width(140.dp))
                                Text(
                                    tool.version.ifEmpty { tool.status.name.lowercase() },
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        if (report.recommendations.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Recommendations:", fontSize = 10.sp, color = Color(0xFFFFA726), fontWeight = FontWeight.Medium)
                            report.recommendations.forEach { rec ->
                                Text("  • $rec", fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // ── Build output ──
        val displayOutput = if (isBuilding) liveOutput else buildOutput
        if (displayOutput.isNotEmpty()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(4.dp),
            ) {
                val outputLines = displayOutput.lines()
                items(outputLines) { line ->
                    val color = when {
                        line.contains("BUILD SUCCESSFUL") -> Color(0xFF4CAF50)
                        line.contains("BUILD FAILED") -> Color(0xFFEF5350)
                        line.startsWith("e:") || line.contains("error:") -> Color(0xFFEF5350)
                        line.startsWith("w:") || line.contains("warning:") -> Color(0xFFFFA726)
                        line.contains("> Task :") -> Color(0xFF64B5F6)
                        else -> Color(0xFFCCCCCC)
                    }
                    Text(
                        line,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else if (!isBuilding) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Select a build task and click Build to start.\nClick 'Check Env' to validate the build environment.",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
