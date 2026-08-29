package com.codespace.ide.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codespace.ide.domain.Project
import com.codespace.ide.domain.ProjectKind
import kotlinx.coroutines.launch
import java.io.File

/**
 * Project Wizard - 3-step dialog:
 *   Step 1 - Pick a project type (ProjectType tile grid)
 *   Step 2 - Enter project name
 *   Step 3 - Pick location (directory browser) + Create
 *
 * On confirm: scaffolds files via ProjectTemplates at the chosen location,
 * returns a Project to the caller.
 * Extracted as a separate composable - does NOT add to HomeScreen method size.
 */
@Composable
fun ProjectWizardDialog(
    onDismiss: () -> Unit,
    onProjectCreated: (Project, File) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(1) }
    var selectedType by remember { mutableStateOf<ProjectTemplates.ProjectType?>(null) }
    var projectName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf("") }

    // Location picker state
    val storageRoot = android.os.Environment.getExternalStorageDirectory()
    var currentDir by remember { mutableStateOf(storageRoot) }

    Dialog(
        onDismissRequest = { if (!creating) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(20.dp)) {

                // ── Header ────────────────────────────────────────────────────
                val title = when (step) {
                    1 -> "New Project - Choose Type"
                    2 -> "New Project - Name"
                    else -> "New Project - Location"
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                // Step indicator (EMPTY type skips Location step)
                val showLocationStep = selectedType != ProjectTemplates.ProjectType.EMPTY
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepDot(active = step == 1, done = step > 1, label = "Type")
                    HorizontalDivider(Modifier.width(16.dp).padding(horizontal = 2.dp))
                    StepDot(active = step == 2, done = step > 2, label = "Name")
                    if (showLocationStep) {
                        HorizontalDivider(Modifier.width(16.dp).padding(horizontal = 2.dp))
                        StepDot(active = step == 3, done = false, label = "Location")
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ── Step 1: type picker ───────────────────────────────────────
                if (step == 1) {
                    Column(
                        Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProjectTemplates.ProjectType.entries.chunked(2).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { type ->
                                    ProjectTypeCard(
                                        type = type,
                                        selected = selectedType == type,
                                        modifier = Modifier.weight(1f),
                                        onClick = { selectedType = type },
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { step = 2 },
                            enabled = selectedType != null,
                        ) { Text("Next") }
                    }
                }

                // ── Step 2: name ──────────────────────────────────────────────
                if (step == 2) {
                    val type = selectedType!!

                    Text(
                        text = "Type: ${type.displayName}  \u00b7  ${type.description}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it; nameError = "" },
                        label = { Text("Project name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = nameError.isNotEmpty(),
                        supportingText = if (nameError.isNotEmpty()) {
                            { Text(nameError, color = MaterialTheme.colorScheme.error) }
                        } else null,
                    )

                    if (createError.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(createError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { step = 1; createError = "" }) { Text("Back") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val name = projectName.trim()
                                if (name.isBlank()) {
                                    nameError = "Name cannot be empty"
                                    return@Button
                                }
                                if (!name.matches(Regex("[a-zA-Z0-9_\\-. ]+"))) {
                                    nameError = "Use letters, numbers, spaces, - _ . only"
                                    return@Button
                                }
                                createError = ""
                                // EMPTY type: skip location picker, register project only.
                                // Do NOT create any folder — the user will use the Explorer's
                                // "Open Folder" button to pick any real folder on their phone.
                                if (type == ProjectTemplates.ProjectType.EMPTY) {
                                    val project = Project(
                                        id = System.currentTimeMillis().toString(),
                                        name = name,
                                        kind = ProjectKind.LOCAL,
                                        pathOrUrl = "",  // empty = no folder, Explorer shows "Open Folder"
                                    )
                                    onProjectCreated(project, java.io.File(""))
                                } else {
                                    step = 3
                                }
                            },
                            enabled = !creating,
                        ) {
                            if (creating) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                            if (creating) "Creating\u2026"
                            else if (selectedType == ProjectTemplates.ProjectType.EMPTY) "Create"
                            else "Next"
                        )
                        }
                    }
                }

                // ── Step 3: location picker ──────────────────────────────────
                if (step == 3) {
                    val type = selectedType!!
                    val name = projectName.trim()

                    // Breadcrumb path
                    Text(
                        text = "Select parent folder for \"$name\"",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))

                    // Current path breadcrumb
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = currentDir.absolutePath,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (currentDir.parentFile != null) {
                                IconButton(
                                    onClick = { currentDir = currentDir.parentFile!! },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.ArrowUpward,
                                        contentDescription = "Up",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Folder list
                    val folders = remember(currentDir) {
                        currentDir.listFiles()
                            ?.filter { it.isDirectory && !it.isHidden && !it.name.startsWith(".") }
                            ?.sortedBy { it.name.lowercase() }
                            ?: emptyList()
                    }

                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (folders.isEmpty()) {
                            Text(
                                "No subfolders here. This location will be used as-is.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        folders.forEach { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentDir = folder }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = folder.name,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    // Preview line
                    Text(
                        text = "\u2192 $name will be created in:\n${currentDir.absolutePath}/$name",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )

                    if (createError.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(createError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { step = 2; createError = "" }) { Text("Back") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val parentDir = currentDir
                                val targetDir = File(parentDir, name)
                                if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
                                    createError = "Folder already exists and is not empty"
                                    return@Button
                                }
                                creating = true
                                createError = ""
                                scope.launch {
                                    val result = ProjectTemplates.scaffold(
                                        context = ctx,
                                        projectName = name,
                                        type = type,
                                        rootParent = parentDir,
                                    )
                                    creating = false
                                    if (result.success) {
                                        val project = Project(
                                            id = System.currentTimeMillis().toString(),
                                            name = name,
                                            kind = ProjectKind.LOCAL,
                                            pathOrUrl = result.rootDir.absolutePath,
                                        )
                                        onProjectCreated(project, result.rootDir)
                                    } else {
                                        createError = result.message
                                    }
                                }
                            },
                            enabled = !creating,
                        ) {
                            if (creating) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (creating) "Creating\u2026" else "Create Project Here")
                        }
                    }
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ProjectTypeCard(
    type: ProjectTemplates.ProjectType,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val containerColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val _borderColor = if (selected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = type.displayName,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = type.description,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StepDot(active: Boolean, done: Boolean, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(50),
            color = when {
                done   -> MaterialTheme.colorScheme.primary
                active -> MaterialTheme.colorScheme.primary
                else   -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (done) "\u2713" else if (active) "\u25cf" else "\u25cb",
                    fontSize = 10.sp,
                    color = if (active || done) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
