package com.codespace.ide.ui.panes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

// Colors
private val BgColor        = Color(0xFFFFFFFF)
private val HeaderBg       = Color(0xFFF3F3F3)
private val TextColor      = Color(0xFF333333)
private val MutedColor     = Color(0xFF717171)
private val DividerColor   = Color(0xFFE0E0E0)
private val IconColor      = Color(0xFF007ACC)
private val ModifiedColor  = Color(0xFFE2C08D)
private val UntrackedColor = Color(0xFF73C991)
private val DeletedColor   = Color(0xFFF48771)
private val AddedColor     = Color(0xFF73C991)

private data class GitChange(
    val status: String,
    val file: String,
    val absPath: String,
    val statusCode: Char,
    val isStaged: Boolean,
)

private fun runGit(dir: File, vararg args: String): String {
    return try {
        val gitBin = if (File("/data/data/com.termux/files/usr/bin/git").exists())
            "/data/data/com.termux/files/usr/bin/git" else "git"
        val pb = ProcessBuilder(gitBin, *args).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            val prefix = "/data/data/com.termux/files/usr"
            put("PREFIX", prefix)
            put("HOME", "/data/data/com.termux/files/home")
            put("PATH", "$prefix/bin:/system/bin:/system/xbin")
            put("LD_LIBRARY_PATH", "$prefix/lib")
        }
        val process = pb.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        process.waitFor()
        output.trim()
    } catch (e: Exception) { "Error: ${e.message}" }
}

private fun loadWorkspacePath(context: Context): String? =
    context.getSharedPreferences("workspace_prefs", Context.MODE_PRIVATE)
        .getString("workspace_path", null)

@Composable
fun SourceControlPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var showBranchMenu by remember { mutableStateOf(false) }
    var stagedChanges by remember { mutableStateOf<List<GitChange>>(emptyList()) }
    var unstagedChanges by remember { mutableStateOf<List<GitChange>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var aheadBehind by remember { mutableStateOf("") }
    var showStaged by remember { mutableStateOf(true) }
    var showChanges by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }

    val repoDir = remember {
        val wsPath = loadWorkspacePath(context)
        var dir = wsPath?.let { File(it) }
        while (dir != null && !File(dir, ".git").exists()) { dir = dir.parentFile }
        dir ?: File("/storage/emulated/0")
    }

    fun refreshStatus() {
        scope.launch {
            loading = true
            withContext(Dispatchers.IO) {
                try {
                    branch = runGit(repoDir, "branch", "--show-current")
                    val branchList = runGit(repoDir, "branch", "--list", "--format=%(refname:short)")
                    branches = if (!branchList.startsWith("Error")) branchList.lines().filter { it.isNotBlank() } else emptyList()

                    val trackInfo = runGit(repoDir, "status", "-sb")
                    val trackLine = trackInfo.lines().firstOrNull()
                    if (trackLine != null && trackLine.contains("ahead")) {
                        val ahead = Regex("ahead (\\d+)").find(trackLine)?.groupValues?.get(1) ?: "0"
                        val behind = Regex("behind (\\d+)").find(trackLine)?.groupValues?.get(1) ?: "0"
                        aheadBehind = if (behind != "0") "down$behind up$ahead" else "up$ahead"
                    } else { aheadBehind = "" }

                    val statusOutput = runGit(repoDir, "status", "--porcelain=v1")
                    val staged = mutableListOf<GitChange>()
                    val unstaged = mutableListOf<GitChange>()

                    for (line in statusOutput.lines()) {
                        if (line.length < 4) continue
                        val x = line[0]
                        val y = line[1]
                        val filePath = line.substring(3).trim().replace("\"", "")
                        val absPath = File(repoDir, filePath).absolutePath

                        if (x != ' ' && x != '?') staged.add(GitChange(line, filePath, absPath, x, true))
                        if (y != ' ' && y != '?') unstaged.add(GitChange(line, filePath, absPath, y, false))
                        if (x == '?' && y == '?') unstaged.add(GitChange(line, filePath, absPath, '?', false))
                    }
                    stagedChanges = staged
                    unstagedChanges = unstaged
                } catch (_: Exception) {}
            }
            loading = false
        }
    }

    LaunchedEffect(refresh) { refreshStatus() }

    fun stageFile(file: String) { scope.launch { withContext(Dispatchers.IO) { runGit(repoDir, "add", file) }; refreshStatus() } }
    fun unstageFile(file: String) { scope.launch { withContext(Dispatchers.IO) { runGit(repoDir, "reset", "HEAD", file) }; refreshStatus() } }
    fun discardFile(file: String) { scope.launch { withContext(Dispatchers.IO) { runGit(repoDir, "checkout", "--", file) }; refreshStatus() } }
    fun stageAll() { scope.launch { withContext(Dispatchers.IO) { runGit(repoDir, "add", ".") }; refreshStatus() } }
    fun unstageAll() { scope.launch { withContext(Dispatchers.IO) { runGit(repoDir, "reset", "HEAD") }; refreshStatus() } }

    Column(Modifier.fillMaxSize().background(BgColor)) {
        // Header
        Row(
            Modifier.fillMaxWidth().height(35.dp).background(HeaderBg).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SOURCE CONTROL", fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Refresh, null, tint = MutedColor, modifier = Modifier.size(16.dp).clickable { refresh++ })
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Sync, null, tint = MutedColor, modifier = Modifier.size(16.dp).clickable {
                scope.launch { withContext(Dispatchers.IO) { runGit(repoDir, "pull") }; refreshStatus() }
            })
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.MoreVert, null, tint = MutedColor, modifier = Modifier.size(16.dp))
        }
        HorizontalDivider(color = DividerColor)

        // Branch selector
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(Modifier.clickable { showBranchMenu = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountTree, null, tint = IconColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(branch.ifBlank { "unknown" }, fontSize = 12.sp, color = TextColor, fontWeight = FontWeight.Medium)
                    if (aheadBehind.isNotBlank()) { Spacer(Modifier.width(4.dp)); Text(aheadBehind, fontSize = 10.sp, color = MutedColor) }
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = MutedColor, modifier = Modifier.size(14.dp))
                }
                DropdownMenu(expanded = showBranchMenu, onDismissRequest = { showBranchMenu = false }) {
                    branches.forEach { b ->
                        DropdownMenuItem(text = { Text(if (b == branch) ">> $b" else b, fontSize = 12.sp) }, onClick = {
                            showBranchMenu = false
                            scope.launch { withContext(Dispatchers.IO) { runGit(repoDir, "checkout", b) }; refreshStatus() }
                        })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = IconColor)
        }

        // Commit message
        OutlinedTextField(
            value = message, onValueChange = { message = it },
            label = { Text("Commit message", fontSize = 11.sp) },
            singleLine = false, minLines = 2, maxLines = 3,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextColor),
        )

        // Commit + Push
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = { if (message.isNotBlank()) { scope.launch { withContext(Dispatchers.IO) { runGit(repoDir, "commit", "-m", message) }; message = ""; refreshStatus() } } },
                modifier = Modifier.weight(1f),
                enabled = message.isNotBlank() && stagedChanges.isNotEmpty(),
            ) { Text("Commit", fontSize = 11.sp) }
            OutlinedButton(
                onClick = { scope.launch { withContext(Dispatchers.IO) { runGit(repoDir, "push") }; refreshStatus() } },
                modifier = Modifier.weight(1f),
            ) { Text("Push", fontSize = 11.sp) }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = DividerColor)

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            // Staged Changes
            item {
                Row(Modifier.fillMaxWidth().height(24.dp).background(HeaderBg).clickable { showStaged = !showStaged }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (showStaged) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, tint = MutedColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("STAGED CHANGES", fontSize = 10.sp, color = MutedColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text(stagedChanges.size.toString(), fontSize = 10.sp, color = MutedColor, modifier = Modifier.background(DividerColor, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                    Spacer(Modifier.weight(1f))
                    if (stagedChanges.isNotEmpty()) Icon(Icons.Default.UnfoldLess, "Unstage all", tint = MutedColor, modifier = Modifier.size(14.dp).clickable { unstageAll() })
                }
            }
            if (showStaged) {
                items(stagedChanges) { change -> ChangeRow(change, { stageFile(change.file) }, { unstageFile(change.file) }, { discardFile(change.file) }, true) }
                if (stagedChanges.isEmpty()) { item { Text("No staged changes", fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) } }
            }

            // Changes
            item {
                Row(Modifier.fillMaxWidth().height(24.dp).background(HeaderBg).clickable { showChanges = !showChanges }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (showChanges) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, tint = MutedColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("CHANGES", fontSize = 10.sp, color = MutedColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text(unstagedChanges.size.toString(), fontSize = 10.sp, color = MutedColor, modifier = Modifier.background(DividerColor, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                    Spacer(Modifier.weight(1f))
                    if (unstagedChanges.isNotEmpty()) Icon(Icons.Default.DoneAll, "Stage all", tint = MutedColor, modifier = Modifier.size(14.dp).clickable { stageAll() })
                }
            }
            if (showChanges) {
                items(unstagedChanges) { change -> ChangeRow(change, { stageFile(change.file) }, { unstageFile(change.file) }, { discardFile(change.file) }, false) }
                if (unstagedChanges.isEmpty()) { item { Text("No changes", fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) } }
            }
        }
    }
}

@Composable
private fun ChangeRow(change: GitChange, onStage: () -> Unit, onUnstage: () -> Unit, onDiscard: () -> Unit, isStaged: Boolean) {
    val statusColor = when (change.statusCode) {
        'M' -> ModifiedColor; 'A' -> AddedColor; 'U' -> UntrackedColor; '?' -> UntrackedColor; 'D' -> DeletedColor; else -> MutedColor
    }
    val statusLetter = when (change.statusCode) {
        'M' -> "M"; 'A' -> "A"; 'U' -> "U"; '?' -> "U"; 'D' -> "D"; else -> " "
    }
    Row(Modifier.fillMaxWidth().clickable { if (isStaged) onUnstage() else onStage() }.padding(start = 16.dp, end = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(statusLetter, fontSize = 11.sp, color = statusColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
        Icon(fileIconFor(change.file), null, tint = IconColor, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(change.file, fontSize = 12.sp, color = TextColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (isStaged) {
            Icon(Icons.Default.Remove, "Unstage", tint = MutedColor, modifier = Modifier.size(16.dp).clickable { onUnstage() })
        } else {
            Icon(Icons.Default.Add, "Stage", tint = MutedColor, modifier = Modifier.size(16.dp).clickable { onStage() })
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Close, "Discard", tint = DeletedColor, modifier = Modifier.size(16.dp).clickable { onDiscard() })
        }
    }
}

private fun fileIconFor(name: String) = when {
    name.endsWith(".kt") || name.endsWith(".kts") -> Icons.Default.Code
    name.endsWith(".java") -> Icons.Default.Code
    name.endsWith(".py") -> Icons.Default.Code
    name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".jsx") -> Icons.Default.Code
    name.endsWith(".html") || name.endsWith(".xml") -> Icons.Default.Code
    name.endsWith(".json") -> Icons.Default.Code
    name.endsWith(".md") -> Icons.Default.Article
    name.endsWith(".gradle") -> Icons.Default.Build
    name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".svg") || name.endsWith(".webp") -> Icons.Default.Image
    name.endsWith(".zip") || name.endsWith(".apk") -> Icons.Default.FolderZip
    name.endsWith(".sh") -> Icons.Default.Computer
    else -> Icons.Default.Article
}
