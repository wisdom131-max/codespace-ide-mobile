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

    // Project tasks: .vscode/tasks.json, the file VS Code actually reads. Reloaded when the
    // project changes and on demand, because a repository can gain tasks while the app is open.
    var refreshTick by remember { mutableStateOf(0) }
    val projectRead = remember(projectPath, refreshTick) {
        com.codespace.ide.agent.ProjectTaskRunner.load(projectPath)
    }
    val projectTasks = remember(projectRead) {
        (projectRead as? com.codespace.ide.agent.ProjectTasksSpec.ReadOk)
            ?.let { com.codespace.ide.agent.ProjectTasksSpec.ordered(it.tasks) }
            ?: emptyList()
    }
    val projectRunning by com.codespace.ide.agent.ProjectTaskRunner.state.collectAsState()

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
            // ── Project tasks (.vscode/tasks.json) ─────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PROJECT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF9EA3B0),
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "reload",
                    fontSize = 10.sp,
                    color = Color(0xFF569CD6),
                    modifier = Modifier
                        .clickable { refreshTick++ }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            when (val read = projectRead) {
                is com.codespace.ide.agent.ProjectTasksSpec.ReadFailed -> Text(
                    read.reason,
                    fontSize = 10.sp,
                    color = Color(0xFFEF5350),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                is com.codespace.ide.agent.ProjectTasksSpec.ReadOk -> if (read.tasks.isEmpty()) {
                    // VS Code shows an empty task list for a repo with no tasks.json. Same here,
                    // with one line saying where the file would go.
                    Text(
                        "No tasks: add .vscode/tasks.json to this project.",
                        fontSize = 10.sp,
                        color = Color(0xFF6A6A6A),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    projectTasks.forEach { task ->
                        val supported = com.codespace.ide.agent.ProjectTasksSpec.isSupported(task)
                        val busy = projectRunning.runningLabel != null
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = if (projectRunning.lastLabel == task.label && projectRunning.ok == true)
                                Color(0xFF1A3A1A)
                            else if (projectRunning.lastLabel == task.label && projectRunning.ok == false)
                                Color(0xFF3A1A1A)
                            else Color(0xFF252535),
                        ) {
                            Column(
                                Modifier
                                    .clickable(enabled = supported && !busy) {
                                        activeTask = null
                                        logOutput = ""
                                        scope.launch {
                                            logOutput = com.codespace.ide.agent.ProjectTaskRunner
                                                .run(context, projectPath, task.label)
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    task.label,
                                    fontSize = 11.sp,
                                    color = if (supported) Color(0xFFD4D4D4) else Color(0xFF6A6A6A),
                                )
                                val sub = if (supported) task.command + (if (task.args.isEmpty()) "" else " " + task.args.joinToString(" "))
                                          else com.codespace.ide.agent.ProjectTasksSpec.unsupportedReason(task)
                                Text(
                                    sub,
                                    fontSize = 9.sp,
                                    color = Color(0xFF7A7A8C),
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "BUILT IN",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9EA3B0),
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(2.dp))

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
