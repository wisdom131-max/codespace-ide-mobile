package com.codespace.ide.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.codespace.ide.project.TaskRunner
import kotlinx.coroutines.launch

/**
 * Phase 12-J — Task Runner Panel
 *
 * Bottom panel tab: one-tap build tasks (Build Debug, Clean, Lint, Test, etc.)
 * Shows live output log below the task buttons.
 */
@Composable
fun TaskRunnerPanel(
    projectPath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val runs by TaskRunner.runs.collectAsState()
    var logOutput by remember { mutableStateOf("") }
    var activeTask by remember { mutableStateOf<TaskRunner.TaskId?>(null) }
    val logScroll = rememberLazyListState()

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
                "TASKS",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9EA3B0),
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
            if (activeTask != null) {
                TextButton(
                    onClick = { logOutput = ""; activeTask = null },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Clear", fontSize = 10.sp, color = Color(0xFF9EA3B0))
                }
            }
        }

        HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 1.dp)

        // ── Task grid ──────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            val tasks = TaskRunner.CATALOGUE
            val rows = tasks.chunked(2)
            rows.forEach { rowTasks ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowTasks.forEach { task ->
                        val run = runs[task.id]
                        val isRunning = run?.state == TaskRunner.RunState.RUNNING
                        val isSuccess = run?.state == TaskRunner.RunState.SUCCESS
                        val isFailed  = run?.state == TaskRunner.RunState.FAILED

                        val bgColor = when {
                            isRunning -> Color(0xFF1A2A3A)
                            isSuccess -> Color(0xFF1A3A1A)
                            isFailed  -> Color(0xFF3A1A1A)
                            else      -> Color(0xFF252535)
                        }
                        val textColor = when {
                            isRunning -> Color(0xFF569CD6)
                            isSuccess -> Color(0xFF4CAF50)
                            isFailed  -> Color(0xFFEF5350)
                            else      -> Color(0xFFD4D4D4)
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 3.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = bgColor,
                        ) {
                            Row(
                                Modifier
                                    .clickable(enabled = !TaskRunner.isAnyRunning) {
                                        activeTask = task.id
                                        logOutput = ""
                                        scope.launch {
                                            val result = TaskRunner.run(
                                                context = context,
                                                taskId = task.id,
                                                projectPath = projectPath,
                                            )
                                            logOutput = result.output
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = Color(0xFF569CD6),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    task.displayName,
                                    fontSize = 11.sp,
                                    color = textColor,
                                    fontWeight = if (activeTask == task.id) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    // pad odd row
                    if (rowTasks.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 1.dp)

        // ── Output log ─────────────────────────────────────────────────────
        if (logOutput.isNotBlank()) {
            val logLines = remember(logOutput) { logOutput.lines() }
            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty()) logScroll.scrollToItem(logLines.size - 1)
            }
            LazyColumn(
                state = logScroll,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0D1A))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                items(logLines) { line ->
                    val color = when {
                        line.startsWith("e: ") || line.contains("ERROR") || line.contains("FAILED") -> Color(0xFFEF5350)
                        line.contains("WARNING") || line.startsWith("w: ") -> Color(0xFFFF9800)
                        line.contains("BUILD SUCCESSFUL") -> Color(0xFF4CAF50)
                        else -> Color(0xFF9EA3B0)
                    }
                    Text(
                        line,
                        fontSize = 10.sp,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                    )
                }
            }
        } else if (activeTask == null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Tap a task to run it", fontSize = 12.sp, color = Color(0xFF4B5563))
            }
        }
    }
}
