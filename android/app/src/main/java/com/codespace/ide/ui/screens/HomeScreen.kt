package com.codespace.ide.ui.screens

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codespace.ide.domain.Project
import com.codespace.ide.domain.ProjectKind
import com.codespace.ide.project.ProjectWizardDialog
import com.codespace.ide.ui.sheets.RepoBrowserSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// ── Local cache (fallback when offline) ────────────────────────────────────────

private fun saveProjectsLocal(context: Context, projects: List<Project>) {
    val arr = JSONArray()
    projects.forEach {
        arr.put(
            JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("kind", it.kind.name)
                .put("pathOrUrl", it.pathOrUrl)
                .put("defaultBranch", it.defaultBranch ?: "")
        )
    }
    context.getSharedPreferences("projects", Context.MODE_PRIVATE)
        .edit().putString("list", arr.toString()).apply()
}

private fun loadProjectsLocal(context: Context): List<Project> {
    val str = context.getSharedPreferences("projects", Context.MODE_PRIVATE)
        .getString("list", null) ?: return emptyList()
    return try {
        val arr = JSONArray(str)
        (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            Project(
                id            = obj.getString("id"),
                name          = obj.getString("name"),
                kind          = ProjectKind.valueOf(obj.getString("kind")),
                pathOrUrl     = obj.getString("pathOrUrl"),
                defaultBranch = obj.getString("defaultBranch").ifBlank { null },
            )
        }
    } catch (e: Exception) { emptyList() }
}

// ── Cloud sync helpers ─────────────────────────────────────────────────────────

private const val API_BASE = "https://codespace-ide-mobile-production.up.railway.app/api/v1"

private suspend fun fetchProjectsFromCloud(accessToken: String): List<Project>? =
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val resp = client.newCall(
                Request.Builder()
                    .url("$API_BASE/projects")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            ).execute()
            if (!resp.isSuccessful) return@withContext null
            val arr = JSONArray(resp.body!!.string())
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                Project(
                    id            = obj.getString("id"),
                    name          = obj.getString("name"),
                    kind          = try { ProjectKind.valueOf(obj.optString("kind", "LOCAL")) } catch (e: Exception) { ProjectKind.LOCAL },
                    pathOrUrl     = obj.optString("pathOrUrl", "local"),
                    defaultBranch = obj.optString("defaultBranch", "main").ifBlank { null },
                )
            }
        } catch (e: Exception) { null }
    }

private suspend fun pushProjectToCloud(accessToken: String, project: Project): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val body = JSONObject().apply {
                put("id", project.id)
                put("name", project.name)
                put("kind", project.kind.name)
                put("pathOrUrl", project.pathOrUrl)
                put("defaultBranch", project.defaultBranch ?: "main")
            }.toString().toRequestBody("application/json".toMediaType())

            val resp = client.newCall(
                Request.Builder()
                    .url("$API_BASE/projects")
                    .header("Authorization", "Bearer $accessToken")
                    .post(body)
                    .build()
            ).execute()
            resp.isSuccessful
        } catch (e: Exception) { false }
    }

private suspend fun deleteProjectFromCloud(accessToken: String, projectId: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val resp = client.newCall(
                Request.Builder()
                    .url("$API_BASE/projects/$projectId")
                    .header("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
            ).execute()
            resp.isSuccessful
        } catch (e: Exception) { false }
    }

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    accessToken: String,          // JWT from AuthResult — used for cloud sync
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,        // lets user switch account
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var projectToRename by remember { mutableStateOf<Project?>(null) }
    var renameInput     by remember { mutableStateOf("") }
    val projects      = remember { mutableStateListOf<Project>().apply { addAll(loadProjectsLocal(context)) } }
    var syncing       by remember { mutableStateOf(false) }
    var syncStatus    by remember { mutableStateOf("") }
    var showAddDialog    by remember { mutableStateOf(false) }
    var showRepoBrowser  by remember { mutableStateOf(false) }
    var fabMenuExpanded  by remember { mutableStateOf(false) }

    // ── Auto-sync on launch ────────────────────────────────────────
    LaunchedEffect(accessToken) {
        if (accessToken.isNotBlank()) {
            syncing    = true
            syncStatus = "Syncing projects…"
            val cloud = fetchProjectsFromCloud(accessToken)
            if (cloud != null) {
                projects.clear()
                projects.addAll(cloud)
                saveProjectsLocal(context, cloud)
                syncStatus = "Synced (${cloud.size} project${if (cloud.size == 1) "" else "s"})"
            } else {
                syncStatus = "Offline — showing local projects"
            }
            syncing = false
        }
    }

    // ── Project Wizard (Phase 12-A) ───────────────────────────────
    if (showAddDialog) {
        ProjectWizardDialog(
            onDismiss = { showAddDialog = false },
            onProjectCreated = { project, _ ->
                projects.add(project)
                saveProjectsLocal(context, projects.toList())
                scope.launch {
                    val ok = pushProjectToCloud(accessToken, project)
                    syncStatus = if (ok) "Project created \u2713" else "Saved locally (offline)"
                }
                showAddDialog = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspaces") },
                actions = {
                    // Manual refresh
                    IconButton(
                        onClick = {
                            scope.launch {
                                syncing    = true
                                syncStatus = "Syncing…"
                                val cloud = fetchProjectsFromCloud(accessToken)
                                if (cloud != null) {
                                    projects.clear()
                                    projects.addAll(cloud)
                                    saveProjectsLocal(context, cloud)
                                    syncStatus = "Synced ✓"
                                } else {
                                    syncStatus = "Offline"
                                }
                                syncing = false
                            }
                        },
                        enabled = !syncing,
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = "Sync")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { fabMenuExpanded = true },
                    icon    = { Icon(Icons.Default.Add, contentDescription = null) },
                    text    = { Text("New project") },
                )
                DropdownMenu(
                    expanded         = fabMenuExpanded,
                    onDismissRequest = { fabMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text    = { Text("New local project") },
                        onClick = { fabMenuExpanded = false; showAddDialog = true },
                    )
                    DropdownMenuItem(
                        text    = { Text("Clone from GitHub") },
                        onClick = { fabMenuExpanded = false; showRepoBrowser = true },
                    )
                }
            }
        },
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Sync status bar ────────────────────────────────────
            if (syncStatus.isNotEmpty()) {
                Text(
                    text     = syncStatus,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (projects.isEmpty() && !syncing) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No projects yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap 'New project' to create one.\nProjects sync across all your devices.",
                        style     = MaterialTheme.typography.bodySmall,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    // Switch account shortcut
                    TextButton(onClick = onSignOut) {
                        Text("Switch Google account", color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    items(projects) { project ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .combinedClickable(
                                    onClick = { onOpenProject(project.id) },
                                    onLongClick = {
                                        projectToRename = project
                                        renameInput = project.name
                                    },
                                ),
                        ) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${project.kind.name.lowercase()} • ${project.pathOrUrl}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                                IconButton(onClick = {
                                    val toRemove = project
                                    projects.remove(toRemove)
                                    saveProjectsLocal(context, projects.toList())
                                    scope.launch {
                                        // P29: Move to trash instead of permanent delete
                                        val projectDir = java.io.File(context.filesDir, "projects/${toRemove.name}")
                                        if (projectDir.exists()) {
                                            com.codespace.ide.util.WorkspaceManager.moveProjectToTrash(context, projectDir)
                                        }
                                        deleteProjectFromCloud(accessToken, toRemove.id)
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }

                    item {
                        // Switch account at the bottom
                        Spacer(Modifier.height(16.dp))
                        TextButton(
                            onClick  = onSignOut,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Switch Google account",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(80.dp)) // FAB clearance
                    }
                }
            }
        }
    }

    if (showRepoBrowser) {
        // Rename dialog
    if (projectToRename != null) {
        AlertDialog(
            onDismissRequest = { projectToRename = null },
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameInput.trim()
                    if (name.isNotEmpty()) {
                        val idx = projects.indexOfFirst { it.id == projectToRename!!.id }
                        if (idx >= 0) {
                            projects[idx] = projects[idx].copy(name = name)
                            saveProjectsLocal(context, projects.toList())
                        }
                    }
                    projectToRename = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { projectToRename = null }) { Text("Cancel") }
            },
        )
    }

    RepoBrowserSheet(
            onDismiss = { showRepoBrowser = false },
            onProjectCreated = { project ->
                projects.add(project)
                saveProjectsLocal(context, projects.toList())
                scope.launch { pushProjectToCloud(accessToken, project) }
            },
        )
    }
}
