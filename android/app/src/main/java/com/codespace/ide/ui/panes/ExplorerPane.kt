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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.documentfile.provider.DocumentFile
import android.content.ClipboardManager
import android.content.ClipData
import java.io.File
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download

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
    onOpenInTerminal: (String) -> Unit = {},
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
    var pendingTargetDir by remember { mutableStateOf<java.io.File?>(null) }
    val createFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Write empty content to the SAF uri
                context.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf()) }
                // Use the captured target dir
                val targetDir = pendingTargetDir ?: workspaceRoot
                if (targetDir != null && pendingFileName.isNotBlank()) {
                    val localFile = java.io.File(targetDir, pendingFileName)
                    try {
                        if (!localFile.exists()) localFile.createNewFile()
                        refresh++
                        onOpenFile(localFile.absolutePath)
                    } catch (e: Exception) {
                        // fallback to SAF uri path
                        val realPath = uri.path?.let { p ->
                            val split = p.split(":")
                            if (split.size >= 2) "/storage/emulated/0/${split[1]}" else null
                        }
                        refresh++
                        onOpenFile(realPath ?: uri.toString())
                    }
                } else {
                    refresh++
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun buildNodes(dir: File, depth: Int): List<FsNode> {
        val nodes = mutableListOf<FsNode>()
        val isExp = expanded[dir.absolutePath] ?: false
        nodes.add(FsNode(dir, depth, isExp))
        if (isExp) {
            val children = dir.listFiles()
                ?.filter { !it.name.trimEnd().startsWith(".") }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.trimEnd() })
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
            ?.filter { !it.name.trimEnd().startsWith(".") }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.trimEnd() })
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
                    modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(nodes) { node ->
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
                        "Open in Terminal" to Icons.Default.Computer,
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
                                        "Open in Terminal" -> onOpenInTerminal(if (f.isDirectory) f.absolutePath else f.parent ?: f.absolutePath)
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
                        val targetDir = contextFile?.let {
                            if (it.isDirectory) it else it.parentFile
                        } ?: workspaceRoot
                        if (targetDir != null) {
                            val newFile = java.io.File(targetDir, nameInput)
                            try {
                                newFile.createNewFile()
                                refresh++
                                onOpenFile(newFile.absolutePath)
                            } catch (_: Exception) {}
                        }
                        showNewFile = false
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
private data class SearchResult(val file: String, val lineNum: Int, val lineText: String, val matchRange: IntRange)

@Composable fun SearchPanel() {
    var searchQuery  by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var useRegex      by remember { mutableStateOf(false) }
    var matchWholeWord by remember { mutableStateOf(false) }
    var results       by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching     by remember { mutableStateOf(false) }
    var expandedFiles by remember { mutableStateOf(setOf<String>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    fun performSearch(query: String) {
        if (query.isBlank()) { results = emptyList(); return }
        searching = true
        scope.launch {
            val wsPath = loadWorkspacePath(context)
            val wsRoot = wsPath?.let { File(it) }
            val allResults = mutableListOf<SearchResult>()
            if (wsRoot != null && wsRoot.exists()) {
                val extensions = setOf("kt", "java", "xml", "gradle", "kts", "py", "js", "ts", "json", "md", "txt", "yml", "yaml", "sh", "html", "css")
                val maxFiles = 500
                var filesScanned = 0
                fun walk(dir: File) {
                    if (filesScanned >= maxFiles) return
                    val files = dir.listFiles() ?: return
                    for (f in files) {
                        if (filesScanned >= maxFiles) break
                        if (f.isDirectory) {
                            if (!f.name.startsWith(".") && f.name != "build" && f.name != "node_modules") {
                                walk(f)
                            }
                        } else if (f.extension.lowercase() in extensions || f.extension.isEmpty()) {
                            filesScanned++
                            try {
                                f.useLines { lines ->
                                    lines.forEachIndexed { idx, line ->
                                        val matched = if (useRegex) {
                                            try {
                                                val regex = if (caseSensitive) Regex(query) else Regex(query, RegexOption.IGNORE_CASE)
                                                regex.containsMatchIn(line)
                                            } catch (_: Exception) { false }
                                        } else if (matchWholeWord) {
                                            val regex = if (caseSensitive) Regex("\\b${Regex.escape(query)}\\b") else Regex("\\b${Regex.escape(query)}\\b", RegexOption.IGNORE_CASE)
                                            regex.containsMatchIn(line)
                                        } else if (caseSensitive) {
                                            line.contains(query)
                                        } else {
                                            line.contains(query, ignoreCase = true)
                                        }
                                        if (matched) {
                                            val matchStart = if (caseSensitive) line.indexOf(query) else line.indexOf(query, ignoreCase = true)
                                            if (matchStart >= 0) {
                                                allResults.add(SearchResult(f.absolutePath, idx + 1, line.trim(), matchStart..(matchStart + query.length - 1)))
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                walk(wsRoot)
            }
            results = allResults
            searching = false
        }
    }

    val grouped = results.groupBy { it.file }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        // Search input
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    Icon(Icons.Default.Close, "Clear", tint = MutedColor,
                        modifier = Modifier.size(16.dp).clickable { searchQuery = ""; results = emptyList() })
                }
            },
        )
        // Replace input
        OutlinedTextField(
            value = replaceQuery, onValueChange = { replaceQuery = it },
            label = { Text("Replace") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        // Toggle buttons — case sensitive, whole word, regex
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val toggles = listOf(
                "Aa" to caseSensitive,
                "\b" to matchWholeWord,
                ".*" to useRegex,
            )
            toggles.forEachIndexed { idx, (label, active) ->
                Box(
                    Modifier
                        .background(if (active) IconColor else Color.Transparent, RoundedCornerShape(3.dp))
                        .border(1.dp, if (active) IconColor else DividerColor, RoundedCornerShape(3.dp))
                        .clickable {
                            when (idx) {
                                0 -> caseSensitive = !caseSensitive
                                1 -> matchWholeWord = !matchWholeWord
                                2 -> useRegex = !useRegex
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = if (active) Color.White else MutedColor)
                }
            }
            Spacer(Modifier.fillMaxWidth())
            // Search button
            if (searching) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = IconColor)
            } else {
                Text("Search", fontSize = 11.sp, color = IconColor, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { performSearch(searchQuery) }.padding(4.dp))
            }
        }

        // Results count
        if (results.isNotEmpty()) {
            Text("${results.size} results in ${grouped.size} files",
                fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(top = 8.dp))
        }

        // Results list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
        ) {
            if (results.isEmpty() && !searching && searchQuery.isNotEmpty()) {
                item {
                    Text("No results found", fontSize = 13.sp, color = MutedColor,
                        modifier = Modifier.padding(top = 16.dp))
                }
            }
            grouped.forEach { (filePath, fileResults) ->
                val isExpanded = filePath in expandedFiles
                item(key = "header_$filePath") {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            expandedFiles = if (isExpanded) expandedFiles - filePath else expandedFiles + filePath
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            null, tint = MutedColor, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Description, null, tint = IconColor, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            filePath.substringAfterLast("/"),
                            fontSize = 12.sp, color = TextColor, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("${fileResults.size}", fontSize = 10.sp, color = MutedColor,
                            modifier = Modifier.background(DividerColor, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
                if (isExpanded) {
                    items(fileResults, key = { r: SearchResult -> "${r.file}_${r.lineNum}" }) { result ->
                        Row(
                            Modifier.fillMaxWidth().padding(start = 36.dp, top = 2.dp, bottom = 2.dp),
                        ) {
                            Text("${result.lineNum}: ", fontSize = 11.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                            Text(result.lineText, fontSize = 11.sp, color = TextColor, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun GitSidePanel() { SourceControlPane() }

@Composable fun RunDebugPanel(onMoreMenu: () -> Unit) {
    var selectedConfig by remember { mutableStateOf("Kotlin Application") }
    var showConfigMenu by remember { mutableStateOf(false) }
    var isRunning      by remember { mutableStateOf(false) }
    var variables      by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var callStack      by remember { mutableStateOf(listOf<String>()) }
    var breakpoints    by remember { mutableStateOf(listOf<Pair<String, Int>>()) }
    var showVariables  by remember { mutableStateOf(true) }
    var showWatch      by remember { mutableStateOf(false) }
    var showCallStack  by remember { mutableStateOf(true) }
    var showBreakpoints by remember { mutableStateOf(true) }

    val configs = listOf(
        "Kotlin Application",
        "Android App (Debug)",
        "Android App (Release)",
        "Gradle Build",
        "JUnit Tests",
        "Terminal Script",
    )

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("RUN AND DEBUG", fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.fillMaxWidth())
            Icon(Icons.Default.MoreVert, "More", tint = MutedColor,
                modifier = Modifier.size(18.dp).clickable { onMoreMenu() })
        }

        // Config selector + run/stop buttons
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(onClick = { showConfigMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedConfig, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(14.dp))
                }
                DropdownMenu(expanded = showConfigMenu, onDismissRequest = { showConfigMenu = false }) {
                    configs.forEach { config ->
                        DropdownMenuItem(
                            text = { Text(config, fontSize = 12.sp) },
                            onClick = { selectedConfig = config; showConfigMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!isRunning) {
                FilledIconButton(
                    onClick = {
                        isRunning = true
                        variables = listOf(
                            "this" to "MainActivity@8a3f",
                            "args" to "String[0]",
                            "workspace" to "/storage/emulated/0/CodeSpace",
                        )
                        callStack = listOf(
                            "MainActivity.onCreate() - line 42",
                            "setContentView() - line 58",
                            "TerminalManager.start() - line 124",
                        )
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, "Run", tint = Color.White)
                }
            } else {
                FilledIconButton(
                    onClick = {
                        isRunning = false
                        variables = emptyList()
                        callStack = emptyList()
                    },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935)),
                ) {
                    Icon(Icons.Default.Stop, "Stop", tint = Color.White)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = DividerColor)
        Spacer(Modifier.height(4.dp))

        // Collapsible sections
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { SectionHeader("VARIABLES", showVariables) { showVariables = !showVariables } }
            if (showVariables) {
                if (isRunning && variables.isNotEmpty()) {
                    items(variables) { item ->
                        val (name, value) = item
                        Row(Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp)) {
                            Icon(Icons.Default.KeyboardArrowRight, null, tint = MutedColor, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(name, fontSize = 11.sp, color = IconColor, fontFamily = FontFamily.Monospace)
                            Text(" = ", fontSize = 11.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                            Text(value, fontSize = 11.sp, color = TextColor, fontFamily = FontFamily.Monospace)
                        }
                    }
                } else {
                    item { Text(if (!isRunning) "Not started" else "No variables", fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) }
                }
            }

            item { SectionHeader("WATCH", showWatch) { showWatch = !showWatch } }
            if (showWatch) {
                item {
                    Row(Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) {
                        Text("Click + to add a watch expression", fontSize = 11.sp, color = MutedColor)
                        Spacer(Modifier.fillMaxWidth())
                        Icon(Icons.Default.Add, "Add", tint = IconColor, modifier = Modifier.size(14.dp).clickable { })
                    }
                }
            }

            item { SectionHeader("CALL STACK", showCallStack) { showCallStack = !showCallStack } }
            if (showCallStack) {
                if (isRunning && callStack.isNotEmpty()) {
                    items(callStack) { frame ->
                        Row(Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp)) {
                            Icon(Icons.Default.Code, null, tint = IconColor, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(frame, fontSize = 11.sp, color = TextColor, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    item { Text("Not paused", fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) }
                }
            }

            item { SectionHeader("BREAKPOINTS", showBreakpoints) { showBreakpoints = !showBreakpoints } }
            if (showBreakpoints) {
                if (breakpoints.isEmpty()) {
                    item { Text("No breakpoints set", fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) }
                } else {
                    items(breakpoints) { item ->
                            val (file, line) = item
                        Row(Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp)) {
                            Icon(Icons.Default.RadioButtonChecked, "Breakpoint", tint = Color(0xFFE53935), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${file.substringAfterLast("/")}:$line", fontSize = 11.sp, color = TextColor, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            null, tint = MutedColor, modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold)
    }
}

@Composable fun ExtensionsPanel() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    // Installed packages: read from Ubuntu dpkg status file
    var installed by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadError by remember { mutableStateOf("") }

    // Suggested packages — curated list of useful proot/Ubuntu tools
    val suggested = listOf(
        "python3"         to "Python 3 interpreter & pip",
        "nodejs"          to "Node.js JavaScript runtime",
        "git"             to "Distributed version control system",
        "curl"            to "HTTP client (already pre-installed)",
        "vim"             to "Terminal text editor",
        "nano"            to "Simple terminal text editor",
        "htop"            to "Interactive process viewer",
        "tmux"            to "Terminal multiplexer / split panes",
        "gcc"             to "GNU C / C++ compiler",
        "make"            to "Build automation tool",
        "jq"              to "Command-line JSON processor",
        "sqlite3"         to "Lightweight SQL database",
        "ffmpeg"          to "Audio/video conversion toolkit",
        "php"             to "PHP scripting language",
        "ruby"            to "Ruby language interpreter",
        "golang-go"       to "Go programming language",
        "rustc"           to "Rust compiler",
        "clang"           to "LLVM C/C++ compiler",
        "neofetch"        to "System info display tool",
        "tree"            to "Directory tree viewer",
        "wget"            to "Non-interactive network downloader",
        "zip"             to "ZIP archive utility",
        "unzip"           to "Extract ZIP archives",
        "ssh"             to "Secure Shell client",
        "nmap"            to "Network exploration / scanner",
        "net-tools"       to "ifconfig, netstat, route",
        "build-essential" to "gcc + make + core build tools bundle",
        "libssl-dev"      to "SSL/TLS development headers",
        "libffi-dev"      to "Foreign function interface library",
        "zlib1g-dev"      to "Compression library headers",
        "openjdk-21-jdk"  to "Java Development Kit 21"
    )

    // Load installed packages from dpkg status file (runs once)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val rootfs = com.codespace.ide.terminal.ProotInstaller.rootfsDir(context)
                val statusFile = java.io.File(rootfs, "var/lib/dpkg/status")
                if (statusFile.exists()) {
                    val pkgs = mutableListOf<String>()
                    var currentPkg = ""
                    statusFile.bufferedReader().forEachLine { line ->
                        when {
                            line.startsWith("Package: ") -> currentPkg = line.removePrefix("Package: ").trim()
                            line.startsWith("Status: install ok installed") && currentPkg.isNotEmpty() -> {
                                pkgs.add(currentPkg); currentPkg = ""
                            }
                        }
                    }
                    installed = pkgs.sorted()
                } else {
                    loadError = "Ubuntu not installed yet — open the Ubuntu tab first"
                }
            } catch (e: Exception) {
                loadError = "Error reading packages: ${e.message}"
            }
        }
    }

    val qLower = query.lowercase()
    val filteredInstalled = if (qLower.isEmpty()) installed else installed.filter { it.contains(qLower) }
    val filteredSuggested = if (qLower.isEmpty()) suggested else suggested.filter {
        it.first.contains(qLower) || it.second.lowercase().contains(qLower)
    }
    val installedSet = installed.toSet()

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        // Search bar
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search packages…", color = Color(0xFF717171), fontSize = 13.sp) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp)) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF569CD6),
                unfocusedBorderColor = Color(0xFF444444),
                focusedTextColor = Color(0xFFCCCCCC),
                unfocusedTextColor = Color(0xFFCCCCCC),
                cursorColor = Color(0xFF569CD6)
            ),
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        )

        LazyColumn(Modifier.fillMaxSize()) {
            // ── INSTALLED section ───────────────────────────────────────
            if (filteredInstalled.isNotEmpty()) {
                item {
                    Text(
                        "INSTALLED (${filteredInstalled.size})",
                        fontSize = 10.sp, color = Color(0xFF717171),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                items(filteredInstalled) { pkg ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFF252526))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4EC9B0), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.fillMaxWidth()) {
                            Text(pkg, color = Color(0xFFCCCCCC), fontSize = 13.sp)
                        }
                        Text("installed", color = Color(0xFF4EC9B0), fontSize = 10.sp)
                    }
                    HorizontalDivider(color = Color(0xFF2D2D2D), thickness = 0.5.dp)
                }
            }

            if (loadError.isNotEmpty()) {
                item {
                    Text(loadError, color = Color(0xFFFF6B6B), fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp))
                }
            }

            // ── SUGGESTED section ───────────────────────────────────────
            if (filteredSuggested.isNotEmpty()) {
                item {
                    Text(
                        "SUGGESTED FOR PROOT / UBUNTU",
                        fontSize = 10.sp, color = Color(0xFF717171),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                items(filteredSuggested) { (pkg, desc) ->
                    val isInstalled = pkg in installedSet
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isInstalled) Color(0xFF252526) else Color(0xFF1E1E1E))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isInstalled) Icons.Default.CheckCircle else Icons.Default.Download,
                            null,
                            tint = if (isInstalled) Color(0xFF4EC9B0) else Color(0xFF569CD6),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.fillMaxWidth()) {
                            Text(pkg, color = Color(0xFFCCCCCC), fontSize = 13.sp)
                            Text(desc, color = Color(0xFF717171), fontSize = 11.sp)
                        }
                        if (!isInstalled) {
                            Text(
                                "apt install",
                                color = Color(0xFF569CD6), fontSize = 10.sp,
                                modifier = Modifier
                                    .background(Color(0xFF2D2D2D), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .clickable {
                                        // Copy the install command to system clipboard
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cmd", "apt install -y $pkg"))
                                        android.widget.Toast.makeText(context, "Copied: apt install -y $pkg", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                            )
                        } else {
                            Text("✓", color = Color(0xFF4EC9B0), fontSize = 10.sp)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF2D2D2D), thickness = 0.5.dp)
                }
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// MCP Panel — Model Context Protocol server manager
// Separate from the dpkg ExtensionsPanel above. Do not merge.
// ─────────────────────────────────────────────────────────────────────────────

private const val MCP_PREFS = "mcp_installed"
private const val MCP_KEY   = "mcp_list"

private data class McpEntry(
    val id: String,
    val name: String,
    val description: String,
    val command: String,       // npx / uvx / node command
    val args: List<String>,
    val envKeys: List<String>, // env vars user must supply
    val docsUrl: String = ""
)

private val MCP_MARKETPLACE: List<McpEntry> = listOf(
    McpEntry(
        id = "github",
        name = "GitHub",
        description = "Read/write repos, issues, PRs, commits and releases via the GitHub API.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-github"),
        envKeys = listOf("GITHUB_PERSONAL_ACCESS_TOKEN"),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/github"
    ),
    McpEntry(
        id = "filesystem",
        name = "Filesystem",
        description = "Read and write files on the device filesystem with path sandboxing.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/storage/emulated/0"),
        envKeys = emptyList(),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem"
    ),
    McpEntry(
        id = "fetch",
        name = "Fetch",
        description = "Fetch any URL and convert web pages to Markdown for LLM consumption.",
        command = "uvx",
        args = listOf("mcp-server-fetch"),
        envKeys = emptyList(),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/fetch"
    ),
    McpEntry(
        id = "brave-search",
        name = "Brave Search",
        description = "Web and local search powered by the Brave Search API.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-brave-search"),
        envKeys = listOf("BRAVE_API_KEY"),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/brave-search"
    ),
    McpEntry(
        id = "sqlite",
        name = "SQLite",
        description = "Query and manipulate a local SQLite database with full SQL support.",
        command = "uvx",
        args = listOf("mcp-server-sqlite", "--db-path", "/storage/emulated/0/codespace.db"),
        envKeys = emptyList(),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/sqlite"
    ),
    McpEntry(
        id = "puppeteer",
        name = "Puppeteer",
        description = "Browser automation — navigate pages, take screenshots, fill forms.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-puppeteer"),
        envKeys = emptyList(),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/puppeteer"
    ),
    McpEntry(
        id = "memory",
        name = "Memory",
        description = "Persistent key-value memory store for long-running agent context.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-memory"),
        envKeys = emptyList(),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/memory"
    ),
    McpEntry(
        id = "sequential-thinking",
        name = "Sequential Thinking",
        description = "Structured step-by-step reasoning for complex problem solving.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-sequential-thinking"),
        envKeys = emptyList(),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/sequentialthinking"
    ),
    McpEntry(
        id = "postgres",
        name = "PostgreSQL",
        description = "Read-only query access to a PostgreSQL database.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-postgres"),
        envKeys = listOf("POSTGRES_URL"),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/postgres"
    ),
    McpEntry(
        id = "slack",
        name = "Slack",
        description = "Post messages, list channels, and read Slack workspace data.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-slack"),
        envKeys = listOf("SLACK_BOT_TOKEN", "SLACK_TEAM_ID"),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/slack"
    ),
    McpEntry(
        id = "google-maps",
        name = "Google Maps",
        description = "Geocoding, directions, and place search via Google Maps API.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-google-maps"),
        envKeys = listOf("GOOGLE_MAPS_API_KEY"),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/google-maps"
    ),
    McpEntry(
        id = "everything",
        name = "Everything (test)",
        description = "Reference MCP server exposing all protocol features — great for testing.",
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-everything"),
        envKeys = emptyList(),
        docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/everything"
    ),
)

private fun loadInstalledMcps(context: android.content.Context): MutableList<String> {
    val prefs = context.getSharedPreferences(MCP_PREFS, android.content.Context.MODE_PRIVATE)
    val raw = prefs.getString(MCP_KEY, "[]") ?: "[]"
    return try {
        val arr = org.json.JSONArray(raw)
        MutableList(arr.length()) { arr.getString(it) }
    } catch (_: Exception) { mutableListOf() }
}

private fun saveInstalledMcps(context: android.content.Context, ids: List<String>) {
    val prefs = context.getSharedPreferences(MCP_PREFS, android.content.Context.MODE_PRIVATE)
    prefs.edit().putString(MCP_KEY, org.json.JSONArray(ids).toString()).apply()
}

private fun buildMcpConfigJson(entries: List<McpEntry>): String {
    val servers = org.json.JSONObject()
    entries.forEach { e ->
        val obj = org.json.JSONObject()
        obj.put("command", e.command)
        obj.put("args", org.json.JSONArray(e.args))
        if (e.envKeys.isNotEmpty()) {
            val env = org.json.JSONObject()
            e.envKeys.forEach { k -> env.put(k, "YOUR_${k}_HERE") }
            obj.put("env", env)
        }
        servers.put(e.id, obj)
    }
    return org.json.JSONObject().put("mcpServers", servers).toString(2)
}

@Composable
fun McpPanel() {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Recommended") }  // Recommended | Search | Installed
    var installedIds by remember { mutableStateOf(loadInstalledMcps(context)) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    val tabs = listOf("Recommended", "Search", "Installed")

    val installedSet = installedIds.toSet()
    val qLow = searchQuery.lowercase()

    val displayList: List<McpEntry> = when (selectedTab) {
        "Installed"   -> MCP_MARKETPLACE.filter { it.id in installedSet }
        "Search"      -> if (qLow.isEmpty()) MCP_MARKETPLACE
                         else MCP_MARKETPLACE.filter {
                             it.name.lowercase().contains(qLow) ||
                             it.description.lowercase().contains(qLow) ||
                             it.id.contains(qLow)
                         }
        else          -> MCP_MARKETPLACE // Recommended = full curated list
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF181818))
    ) {
        // ── Section header ────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                tint = Color(0xFF569CD6),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "MCP SERVERS",
                fontSize = 10.sp,
                color = Color(0xFF717171),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.fillMaxWidth())
            Text(
                "${installedIds.size} installed",
                fontSize = 10.sp,
                color = Color(0xFF4EC9B0)
            )
        }

        // ── Tab dropdown row ──────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF252526))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { tab ->
                val active = tab == selectedTab
                Box(
                    Modifier
                        .clickable { selectedTab = tab; if (tab != "Search") searchQuery = "" }
                        .background(
                            if (active) Color(0xFF37373D) else Color.Transparent,
                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        tab,
                        fontSize = 11.sp,
                        color = if (active) Color(0xFFCCCCCC) else Color(0xFF717171),
                        fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Medium
                                     else androidx.compose.ui.text.font.FontWeight.Normal
                    )
                }
            }
        }

        // ── Search bar (only in Search tab) ──────────────────────────────
        if (selectedTab == "Search") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search MCP marketplace…", color = Color(0xFF717171), fontSize = 12.sp) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF717171), modifier = Modifier.size(15.dp)) },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF569CD6),
                    unfocusedBorderColor = Color(0xFF3C3C3C),
                    focusedTextColor = Color(0xFFCCCCCC),
                    unfocusedTextColor = Color(0xFFCCCCCC),
                    cursorColor = Color(0xFF569CD6)
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // ── Empty state ───────────────────────────────────────────────────
        if (displayList.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    if (selectedTab == "Installed") "No MCP servers added yet.\nTap Recommended to browse."
                    else "No results for \"$searchQuery\"",
                    color = Color(0xFF717171),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            return@Column
        }

        // ── MCP list ──────────────────────────────────────────────────────
        LazyColumn(
            Modifier.heightIn(max = 480.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(displayList, key = { it.id }) { mcp ->
                val isInstalled = mcp.id in installedSet
                val isExpanded = expandedId == mcp.id

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expandedId = if (isExpanded) null else mcp.id }
                        .background(if (isExpanded) Color(0xFF2A2D2E) else Color(0xFF1E1E1E))
                ) {
                    // Row summary
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isInstalled) Icons.Default.CheckCircle else Icons.Default.Extension,
                            null,
                            tint = if (isInstalled) Color(0xFF4EC9B0) else Color(0xFF569CD6),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.fillMaxWidth()) {
                            Text(mcp.name, color = Color(0xFFCCCCCC), fontSize = 13.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text(mcp.description, color = Color(0xFF717171), fontSize = 11.sp, maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                                overflow = if (isExpanded) androidx.compose.ui.text.style.TextOverflow.Visible
                                           else androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(6.dp))
                        if (isInstalled) {
                            Text("✓", color = Color(0xFF4EC9B0), fontSize = 11.sp)
                        } else {
                            Box(
                                Modifier
                                    .background(Color(0xFF0E639C), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                    .clickable {
                                        val newList = (installedIds + mcp.id).distinct().toMutableList()
                                        installedIds = newList
                                        saveInstalledMcps(context, newList)
                                        // Write mcp_servers.json to app files dir
                                        val installedMcps = MCP_MARKETPLACE.filter { it.id in newList.toSet() }
                                        val json = buildMcpConfigJson(installedMcps)
                                        try {
                                            java.io.File(context.filesDir, "mcp_servers.json").writeText(json)
                                        } catch (_: Exception) {}
                                        android.widget.Toast.makeText(context, "Added ${mcp.name}", android.widget.Toast.LENGTH_SHORT).show()
                                        expandedId = mcp.id
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Add", color = Color(0xFFFFFFFF), fontSize = 10.sp)
                            }
                        }
                    }

                    // Expanded detail
                    if (isExpanded) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF252526))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            // Command preview
                            Text("Command", color = Color(0xFF9CDCFE), fontSize = 10.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            val cmdPreview = (listOf(mcp.command) + mcp.args).joinToString(" ")
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1A1A1A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(cmdPreview, color = Color(0xFF4EC9B0), fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }

                            // Env vars required
                            if (mcp.envKeys.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("Required env vars", color = Color(0xFFFF9966), fontSize = 10.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                mcp.envKeys.forEach { key ->
                                    Row(Modifier.padding(top = 3.dp)) {
                                        Text("• ", color = Color(0xFF717171), fontSize = 11.sp)
                                        Text(key, color = Color(0xFFCE9178), fontSize = 11.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Action buttons row
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isInstalled) {
                                    // Copy config button
                                    Box(
                                        Modifier
                                            .background(Color(0xFF37373D), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                            .clickable {
                                                val single = buildMcpConfigJson(listOf(mcp))
                                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("mcp", single))
                                                android.widget.Toast.makeText(context, "Config copied", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text("Copy config", color = Color(0xFFCCCCCC), fontSize = 10.sp)
                                    }
                                    // Remove button
                                    Box(
                                        Modifier
                                            .background(Color(0xFF5A1D1D), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                            .clickable {
                                                val newList = installedIds.filter { it != mcp.id }.toMutableList()
                                                installedIds = newList
                                                saveInstalledMcps(context, newList)
                                                val installedMcps = MCP_MARKETPLACE.filter { it.id in newList.toSet() }
                                                val json = buildMcpConfigJson(installedMcps)
                                                try {
                                                    java.io.File(context.filesDir, "mcp_servers.json").writeText(json)
                                                } catch (_: Exception) {}
                                                android.widget.Toast.makeText(context, "Removed ${mcp.name}", android.widget.Toast.LENGTH_SHORT).show()
                                                expandedId = null
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text("Remove", color = Color(0xFFFF6B6B), fontSize = 10.sp)
                                    }
                                }
                                // Docs button
                                if (mcp.docsUrl.isNotEmpty()) {
                                    Box(
                                        Modifier
                                            .background(Color(0xFF37373D), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                            .clickable {
                                                try {
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(mcp.docsUrl))
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text("Docs ↗", color = Color(0xFF569CD6), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF2D2D2D), thickness = 0.5.dp)
                }
            }
        }
    }
}
