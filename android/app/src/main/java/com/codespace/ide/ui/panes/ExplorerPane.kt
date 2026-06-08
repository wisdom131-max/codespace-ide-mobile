package com.codespace.ide.ui.panes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import android.content.ClipboardManager
import android.content.ClipData
import java.io.File

private val BgColor      = Color(0xFFFFFFFF)
private val SelectedBg   = Color(0xFFCCE5FF)
private val TextColor    = Color(0xFF333333)
private val MutedColor   = Color(0xFF717171)
private val DividerColor = Color(0xFFE0E0E0)
private val FolderColor  = Color(0xFFDCB67A)
private val IconColor    = Color(0xFF007ACC)
private val BlueBtn      = Color(0xFF007ACC)

private const val PREFS_WORKSPACE = "workspace_prefs"
private const val KEY_WORKSPACE   = "workspace_path"

private fun saveWorkspacePath(context: Context, path: String) {
    context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
        .edit().putString(KEY_WORKSPACE, path).apply()
}

private fun loadWorkspacePath(context: Context): String? =
    context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
        .getString(KEY_WORKSPACE, null)

data class FsNode(
    val file: File,
    val depth: Int,
    val isExpanded: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerSidePanel(
    onOpenFile: (String) -> Unit,
    onMoreMenu: () -> Unit,
) {
    val context = LocalContext.current

    var workspacePath by remember {
        mutableStateOf(loadWorkspacePath(context))
    }
    val workspaceRoot = remember(workspacePath) {
        workspacePath?.let { File(it) }
    }

    val expanded      = remember { mutableStateMapOf<String, Boolean>() }
    var selected      by remember { mutableStateOf<String?>(null) }
    var contextFile   by remember { mutableStateOf<File?>(null) }
    var showCtxMenu   by remember { mutableStateOf(false) }
    var showNewFile   by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showRename    by remember { mutableStateOf(false) }
    var showDelete    by remember { mutableStateOf(false) }
    var nameInput     by remember { mutableStateOf("") }
    var refresh       by remember { mutableStateOf(0) }

    // Folder picker launcher
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistent permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Convert to real path
            val docFile = DocumentFile.fromTreeUri(context, uri)
            val realPath = uri.path?.let { p ->
                val split = p.split(":")
                if (split.size >= 2) {
                    val type = split[0].substringAfterLast("/")
                    val rel  = split[1]
                    if (type == "primary") "/storage/emulated/0/$rel"
                    else "/storage/$type/$rel"
                } else null
            } ?: docFile?.name?.let { name -> "/storage/emulated/0/$name" }

            realPath?.let {
                workspacePath = it
                saveWorkspacePath(context, it)
                expanded.clear()
                refresh++
            }
        }
    }

    // Create Document launcher — opens Android file picker to create a new file
    var pendingFileName by remember { mutableStateOf("") }
    val createFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Write empty content to create the file
                context.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf()) }
                // Get the real path
                val realPath = uri.path?.let { p ->
                    val split = p.split(":")
                    if (split.size >= 2) {
                        val rel = split[1]
                        "/storage/emulated/0/$rel"
                    } else null
                }
                val pathToOpen = realPath ?: uri.toString()
                refresh++
                onOpenFile(pathToOpen)
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun buildNodes(dir: File, depth: Int): List<FsNode> {
        val nodes = mutableListOf<FsNode>()
        val isExp = expanded[dir.absolutePath] ?: false
        nodes.add(FsNode(dir, depth, isExp))
        if (isExp) {
            val children = dir.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                ?: emptyList()
            children.forEach { child ->
                if (child.isDirectory) nodes.addAll(buildNodes(child, depth + 1))
                else nodes.add(FsNode(child, depth + 1))
            }
        }
        return nodes
    }

    val nodes = remember(workspacePath, expanded.toMap(), refresh) {
        val root = workspaceRoot ?: return@remember emptyList()
        if (!root.exists()) return@remember emptyList()
        val children = root.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
        children.flatMap { f ->
            if (f.isDirectory) buildNodes(f, 0)
            else listOf(FsNode(f, 0))
        }
    }

    Column(Modifier.fillMaxSize().background(BgColor)) {

        // ── Header ───────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(35.dp)
                .background(Color(0xFFF3F3F3))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (workspaceRoot != null) workspaceRoot.name.uppercase()
                else "EXPLORER",
                fontSize = 11.sp, color = MutedColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (workspaceRoot != null) {
                // New File
                Icon(Icons.Default.Add, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        contextFile = workspaceRoot; showNewFile = true; nameInput = ""
                    })
                Spacer(Modifier.width(8.dp))
                // New Folder
                Icon(Icons.Default.CreateNewFolder, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        contextFile = workspaceRoot; showNewFolder = true; nameInput = ""
                    })
                Spacer(Modifier.width(8.dp))
                // Refresh
                Icon(Icons.Default.Refresh, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable { refresh++ })
                Spacer(Modifier.width(8.dp))
                // Change folder
                Icon(Icons.Default.OpenInNew, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        folderPicker.launch(null)
                    })
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.MoreVert, null, tint = MutedColor,
                modifier = Modifier.size(16.dp).clickable { onMoreMenu() })
        }
        HorizontalDivider(color = DividerColor)

        // ── No workspace selected ─────────────────────────────────────────
        if (workspaceRoot == null) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Description, null, tint = Color(0xFFDDDDDD),
                    modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No folder opened", fontSize = 14.sp, color = MutedColor,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("Open a folder to start working", fontSize = 12.sp,
                    color = MutedColor)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { folderPicker.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = BlueBtn),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Folder") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        // Quick pick: use /storage/emulated/0
                        workspacePath = "/storage/emulated/0"
                        saveWorkspacePath(context, "/storage/emulated/0")
                        refresh++
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use Phone Storage") }
            }
        } else {
            // ── File tree ─────────────────────────────────────────────────
            // Workspace root row
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFFF0F0F0))
                    .clickable {
                        expanded[workspaceRoot.absolutePath] =
                            !(expanded[workspaceRoot.absolutePath] ?: true)
                        refresh++
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, null,
                    tint = MutedColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Description, null,
                    tint = FolderColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(workspaceRoot.name, fontSize = 13.sp,
                    color = TextColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(nodes, key = { it.file.absolutePath }) { node ->
                    val isSelected = selected == node.file.absolutePath
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) SelectedBg else BgColor)
                            .combinedClickable(
                                onClick = {
                                    selected = node.file.absolutePath
                                    if (node.file.isDirectory) {
                                        expanded[node.file.absolutePath] =
                                            !(expanded[node.file.absolutePath] ?: false)
                                        refresh++
                                    } else {
                                        onOpenFile(node.file.absolutePath)
                                    }
                                },
                                onLongClick = {
                                    contextFile = node.file
                                    showCtxMenu = true
                                }
                            )
                            .padding(
                                start = (8 + node.depth * 14).dp,
                                top = 5.dp, bottom = 5.dp, end = 8.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (node.file.isDirectory) {
                            Icon(
                                if (node.isExpanded) Icons.Default.KeyboardArrowDown
                                else Icons.Default.ChevronRight,
                                null, tint = MutedColor,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Icon(Icons.Default.Description, null,
                                tint = FolderColor, modifier = Modifier.size(16.dp))
                        } else {
                            Spacer(Modifier.width(18.dp))
                            Icon(fileIcon(node.file.name), null,
                                tint = IconColor, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            node.file.name,
                            fontSize = 13.sp, color = TextColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    // ── Context menu (long press) ─────────────────────────────────────────
    if (showCtxMenu && contextFile != null) {
        val f = contextFile!!
        AlertDialog(
            onDismissRequest = { showCtxMenu = false },
            title = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    listOf(
                        "Open"            to Icons.Default.OpenInNew,
                        "Rename"          to Icons.Default.Edit,
                        "Delete"          to Icons.Default.Delete,
                        "Copy Path"       to Icons.Default.ContentCopy,
                        "New File Here"   to Icons.Default.Add,
                        "New Folder Here" to Icons.Default.CreateNewFolder,
                    ).forEach { (label, icon) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    showCtxMenu = false
                                    when (label) {
                                        "Open"   -> if (!f.isDirectory) onOpenFile(f.absolutePath)
                                                   else { expanded[f.absolutePath] = true; refresh++ }
                                        "Rename" -> { nameInput = f.name; showRename = true }
                                        "Delete" -> showDelete = true
                                        "New File Here" -> {
                                            contextFile = if (f.isDirectory) f else f.parentFile
                                            nameInput = ""; showNewFile = true
                                        }
                                        "New Folder Here" -> {
                                            contextFile = if (f.isDirectory) f else f.parentFile
                                            nameInput = ""; showNewFolder = true
                                        }
                                        "Copy Path" -> {
                                            val clipboard = context.getSystemService(
                                                Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(
                                                android.content.ClipData.newPlainText("path", f.absolutePath))
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, tint = MutedColor, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCtxMenu = false }) { Text("Close") }
            },
        )
    }

    // ── New File dialog ───────────────────────────────────────────────────
    if (showNewFile) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("New File") },
            text = {
                OutlinedTextField(
                    value = nameInput, onValueChange = { nameInput = it },
                    label = { Text("File name (e.g. main.py)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (nameInput.isNotBlank()) {
                        pendingFileName = nameInput
                        showNewFile = false
                        createFileLauncher.launch(nameInput)
                        nameInput = ""
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFile = false }) { Text("Cancel") }
            },
        )
    }

    // ── New Folder dialog ─────────────────────────────────────────────────
    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = nameInput, onValueChange = { nameInput = it },
                    label = { Text("Folder name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (nameInput.isNotBlank()) {
                        val dir = contextFile?.let {
                            if (it.isDirectory) it else it.parentFile
                        } ?: workspaceRoot ?: return@Button
                        File(dir, nameInput).mkdirs()
                        refresh++
                    }
                    showNewFolder = false; nameInput = ""
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("Cancel") }
            },
        )
    }

    // ── Rename dialog ─────────────────────────────────────────────────────
    if (showRename && contextFile != null) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = nameInput, onValueChange = { nameInput = it },
                    label = { Text("New name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (nameInput.isNotBlank()) {
                        contextFile!!.renameTo(File(contextFile!!.parent, nameInput))
                        refresh++
                    }
                    showRename = false; nameInput = ""
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }

    // ── Delete confirmation ───────────────────────────────────────────────
    if (showDelete && contextFile != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete ${contextFile!!.name}?") },
            text  = { Text("This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        contextFile!!.deleteRecursively()
                        refresh++
                        showDelete = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private fun fileIcon(name: String) = when {
    name.endsWith(".kt") || name.endsWith(".kts") -> Icons.Default.Code
    name.endsWith(".java")  -> Icons.Default.Code
    name.endsWith(".py")    -> Icons.Default.Code
    name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".jsx") -> Icons.Default.Code
    name.endsWith(".html") || name.endsWith(".xml") -> Icons.Default.Code
    name.endsWith(".json")  -> Icons.Default.Code
    name.endsWith(".md")    -> Icons.Default.Article
    name.endsWith(".gradle") -> Icons.Default.Build
    name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".svg") || name.endsWith(".webp") -> Icons.Default.Image
    name.endsWith(".zip") || name.endsWith(".apk") -> Icons.Default.FolderZip
    name.endsWith(".sh")    -> Icons.Default.Computer
    name.endsWith(".txt")   -> Icons.Default.Article
    name.endsWith(".pdf")   -> Icons.Default.Article
    else                    -> Icons.Default.Article
}

// ── Stub panels ──────────────────────────────────────────────────────────────
@Composable fun SearchPanel() {
    var searchQuery  by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it },
            label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = replaceQuery, onValueChange = { replaceQuery = it },
            label = { Text("Replace") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.padding(top = 8.dp)) {
            listOf("Aa", "\b", ".*").forEach { opt ->
                Box(Modifier.border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Text(opt, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        Text("No results", fontSize = 13.sp, color = Color(0xFF717171),
            modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable fun GitSidePanel() { SourceControlPane() }

@Composable fun RunDebugPanel(onMoreMenu: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("RUN AND DEBUG", fontSize = 11.sp, color = Color(0xFF717171))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text("▶ Run") }
            OutlinedButton(onClick = {}) { Text("⏹ Stop") }
        }
        Spacer(Modifier.height(16.dp))
        Text("VARIABLES", fontSize = 11.sp, color = Color(0xFF717171))
        Text("No active debug session.", fontSize = 13.sp, color = Color(0xFFAAAAAA),
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable fun ExtensionsPanel() {
    var extQuery by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        OutlinedTextField(value = extQuery, onValueChange = { extQuery = it },
            label = { Text("Search Extensions") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("INSTALLED", fontSize = 11.sp, color = Color(0xFF717171))
        listOf("Kotlin", "Git Lens", "Prettier", "ESLint").forEach { ext ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Extension, null, tint = Color(0xFF007ACC),
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(ext, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("✓", fontSize = 12.sp, color = Color(0xFF4CAF50))
            }
            HorizontalDivider(color = Color(0xFFEEEEEE))
        }
    }
}
