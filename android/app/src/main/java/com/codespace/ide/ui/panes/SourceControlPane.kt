package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.data.SecureTokenStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Palette ──────────────────────────────────────────────────────────────────
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
private val ErrorColor     = Color(0xFFCC0000)
private val ConflictColor  = Color(0xFFE51400)
private val TagColor       = Color(0xFFDA70D6)

private data class GitChange(
    val status: String,
    val file: String,
    val absPath: String,
    val statusCode: Char,
    val isStaged: Boolean,
)

private data class CommitRow(
    val sha: String,
    val shortSha: String,
    val message: String,
    val author: String,
    val date: String,
)

private data class StashRow(val index: Int, val message: String, val sha: String)
private data class TagRow(val name: String, val sha: String, val isAnnotated: Boolean, val message: String)

// ── Helpers ───────────────────────────────────────────────────────────────────
private fun runGit(context: Context, dir: File, vararg args: String): String {
    val guestPath = ProotInstaller.hostToGuestPath(context, dir.absolutePath)
        ?: return "Error: '${dir.absolutePath}' is not reachable from the Ubuntu terminal."
    val quoted = args.joinToString(" ") { a -> "'" + a.replace("'", "'\\''") + "'" }
    val githubToken = SecureTokenStore(context).githubToken
    val authFlag = if (!githubToken.isNullOrBlank()) {
        val basic = android.util.Base64.encodeToString(
            "x-access-token:$githubToken".toByteArray(), android.util.Base64.NO_WRAP)
        "-c 'http.extraheader=Authorization: Basic $basic' "
    } else ""
    return ProotInstaller.execOnce(context, "git $authFlag$quoted", guestPath)
}

private fun loadWorkspacePath(context: Context, projectId: String): String? =
    context.getSharedPreferences("workspace_prefs", Context.MODE_PRIVATE)
        .getString("workspace_path_$projectId", null)

private fun parsePorcelainLine(line: String, repoDir: File): Pair<GitChange?, GitChange?> {
    if (line.length < 4) return null to null
    val x = line[0]; val y = line[1]
    val raw = line.substring(3).trim().replace("\"", "")
    val filePath = if ((x == 'R' || x == 'C' || y == 'R' || y == 'C') && raw.contains(" -> ")) {
        raw.substringBefore(" -> ").trim()
    } else raw
    val absPath = File(repoDir, filePath).absolutePath
    val staged = if (x != ' ' && x != '?') GitChange(line, filePath, absPath, x, true) else null
    val unstaged = when {
        x == '?' && y == '?' -> GitChange(line, filePath, absPath, '?', false)
        y != ' ' && y != '?' -> GitChange(line, filePath, absPath, y, false)
        else -> null
    }
    return staged to unstaged
}

private fun fmtEpoch(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

// ── Tabs ──────────────────────────────────────────────────────────────────────
private enum class ScmTab { CHANGES, LOG, STASH, TAGS }

// ══════════════════════════════════════════════════════════════════════════════
@Composable
fun SourceControlPane(projectId: String) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── shared state ─────────────────────────────────────────────────────────
    var activeTab by remember { mutableStateOf(ScmTab.CHANGES) }
    var message   by remember { mutableStateOf("") }
    var branch    by remember { mutableStateOf("") }
    var branches  by remember { mutableStateOf<List<String>>(emptyList()) }
    var stagedChanges   by remember { mutableStateOf<List<GitChange>>(emptyList()) }
    var unstagedChanges by remember { mutableStateOf<List<GitChange>>(emptyList()) }
    var conflictedFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading         by remember { mutableStateOf(false) }
    var aheadBehind     by remember { mutableStateOf("") }
    var statusError     by remember { mutableStateOf<String?>(null) }
    var refresh         by remember { mutableStateOf(0) }
    var showStaged      by remember { mutableStateOf(true) }
    var showChanges     by remember { mutableStateOf(true) }

    // branch dialogs
    var showBranchMenu      by remember { mutableStateOf(false) }
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var newBranchName       by remember { mutableStateOf("") }
    var newBranchError      by remember { mutableStateOf<String?>(null) }
    var branchContextMenu   by remember { mutableStateOf<String?>(null) }  // branch name for long-press menu
    var showRenameBranchDialog by remember { mutableStateOf(false) }
    var renameBranchTarget  by remember { mutableStateOf("") }
    var renameBranchNew     by remember { mutableStateOf("") }

    // gitignore dialog
    var showGitignoreDialog by remember { mutableStateOf(false) }
    var gitignoreContent    by remember { mutableStateOf("") }

    // log tab
    var commitLog    by remember { mutableStateOf<List<CommitRow>>(emptyList()) }
    var expandedSha  by remember { mutableStateOf<String?>(null) }
    var logLoading   by remember { mutableStateOf(false) }

    // stash tab
    var stashes         by remember { mutableStateOf<List<StashRow>>(emptyList()) }
    var stashLoading    by remember { mutableStateOf(false) }
    var stashMsg        by remember { mutableStateOf("") }
    var showStashDialog by remember { mutableStateOf(false) }

    // tags tab
    var tags           by remember { mutableStateOf<List<TagRow>>(emptyList()) }
    var tagsLoading    by remember { mutableStateOf(false) }
    var showTagDialog  by remember { mutableStateOf(false) }
    var newTagName     by remember { mutableStateOf("") }
    var newTagMsg      by remember { mutableStateOf("") }
    var newTagAnnotated by remember { mutableStateOf(true) }

    val repoDir = remember(projectId) {
        val wsPath = loadWorkspacePath(context, projectId)
        var dir = wsPath?.let { File(it) }
        while (dir != null && !File(dir, ".git").exists()) { dir = dir.parentFile }
        dir ?: File(com.codespace.ide.terminal.ProotInstaller.rootfsDir(context), "root")
    }

    // ── data loaders ─────────────────────────────────────────────────────────
    fun refreshStatus() {
        scope.launch {
            loading = true; statusError = null
            withContext(Dispatchers.IO) {
                val branchOut = runGit(context, repoDir, "branch", "--show-current").trim()
                if (branchOut.startsWith("Error:")) { statusError = branchOut; loading = false; return@withContext }
                branch = branchOut
                val branchList = runGit(context, repoDir, "branch", "--list", "--format=%(refname:short)")
                branches = if (!branchList.startsWith("Error:")) branchList.lines().filter { it.isNotBlank() } else emptyList()
                val trackInfo = runGit(context, repoDir, "status", "-sb")
                val trackLine = trackInfo.lines().firstOrNull() ?: ""
                aheadBehind = when {
                    trackLine.contains("ahead") || trackLine.contains("behind") -> buildString {
                        val a = Regex("ahead (\\d+)").find(trackLine)?.groupValues?.get(1) ?: "0"
                        val b = Regex("behind (\\d+)").find(trackLine)?.groupValues?.get(1) ?: "0"
                        if (b != "0") append("\u2193$b ")
                        if (a != "0") append("\u2191$a")
                    }.trim()
                    else -> ""
                }
                val statusOutput = runGit(context, repoDir, "status", "--porcelain=v1")
                val staged = mutableListOf<GitChange>(); val unstaged = mutableListOf<GitChange>()
                if (!statusOutput.startsWith("Error:")) {
                    for (line in statusOutput.lines()) {
                        val (s, u) = parsePorcelainLine(line, repoDir)
                        s?.let { staged.add(it) }; u?.let { unstaged.add(it) }
                    }
                }
                stagedChanges = staged; unstagedChanges = unstaged
                // conflicts
                val conflictOut = runGit(context, repoDir, "diff", "--name-only", "--diff-filter=U")
                conflictedFiles = if (!conflictOut.startsWith("Error:")) conflictOut.lines().filter { it.isNotBlank() } else emptyList()
            }
            loading = false
        }
    }

    fun loadLog() {
        scope.launch {
            logLoading = true
            val raw = withContext(Dispatchers.IO) {
                runGit(context, repoDir, "log", "--pretty=format:%H|%s|%an|%ae|%at", "-n", "100")
            }
            commitLog = if (raw.startsWith("Error:")) emptyList() else raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val p = line.split("|")
                if (p.size < 5) return@mapNotNull null
                val epochSec = p[4].toLongOrNull() ?: 0L
                CommitRow(sha = p[0], shortSha = p[0].take(7), message = p[1], author = p[2], date = fmtEpoch(epochSec * 1000))
            }
            logLoading = false
        }
    }

    fun loadStashes() {
        scope.launch {
            stashLoading = true
            val raw = withContext(Dispatchers.IO) { runGit(context, repoDir, "stash", "list") }
            stashes = if (raw.startsWith("Error:") || raw.isBlank()) emptyList() else raw.lines().filter { it.isNotBlank() }.mapIndexed { i, line ->
                val sha = line.substringBefore(":").trim()
                val msg = line.substringAfter(": ").trim()
                StashRow(i, msg, sha)
            }
            stashLoading = false
        }
    }

    fun loadTags() {
        scope.launch {
            tagsLoading = true
            val raw = withContext(Dispatchers.IO) { runGit(context, repoDir, "tag", "-l", "--sort=-creatordate", "--format=%(refname:short)|%(objectname:short)|%(objecttype)|%(subject)") }
            tags = if (raw.startsWith("Error:") || raw.isBlank()) emptyList() else raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val p = line.split("|")
                if (p.isEmpty()) return@mapNotNull null
                val name = p.getOrElse(0) { "" }
                val sha  = p.getOrElse(1) { "" }
                val isAnnotated = p.getOrElse(2) { "" } == "tag"
                val msg  = p.getOrElse(3) { "" }
                TagRow(name, sha, isAnnotated, msg)
            }
            tagsLoading = false
        }
    }

    LaunchedEffect(refresh) { refreshStatus() }

    // ── git actions ──────────────────────────────────────────────────────────
    fun stageFile(file: String)   { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "add", file) }; refreshStatus() } }
    fun unstageFile(file: String) { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "restore", "--staged", file) }; refreshStatus() } }
    fun discardFile(file: String) { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "restore", file) }; refreshStatus() } }
    fun stageAll()                { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "add", ".") }; refreshStatus() } }
    fun unstageAll()              { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "restore", "--staged", ".") }; refreshStatus() } }

    // ── root layout ──────────────────────────────────────────────────────────
    Column(Modifier.fillMaxSize().background(BgColor)) {

        // ── Header ───────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(35.dp).background(HeaderBg).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SOURCE CONTROL", fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (loading) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = IconColor)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Refresh, null, tint = MutedColor, modifier = Modifier.size(16.dp).clickable { refresh++ })
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowDownward, null, tint = MutedColor, modifier = Modifier.size(16.dp).clickable {
                scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "pull") }; refreshStatus() }
            })
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowUpward, null, tint = MutedColor, modifier = Modifier.size(16.dp).clickable {
                scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "push") }; refreshStatus() }
            })
        }
        HorizontalDivider(color = DividerColor)

        // ── Branch row ───────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.clickable { showBranchMenu = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountTree, null, tint = IconColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    val displayBranch = if (branch.startsWith("Error:") || branch.isBlank()) "—" else branch
                    Text(displayBranch, fontSize = 12.sp, color = TextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (aheadBehind.isNotBlank()) { Spacer(Modifier.width(6.dp)); Text(aheadBehind, fontSize = 10.sp, color = MutedColor) }
                    Icon(Icons.Default.ArrowDropDown, null, tint = MutedColor, modifier = Modifier.size(14.dp))
                }
                DropdownMenu(expanded = showBranchMenu, onDismissRequest = { showBranchMenu = false }) {
                    branches.forEach { b ->
                        DropdownMenuItem(
                            text = { Text(b, fontSize = 12.sp) },
                            onClick = {
                                showBranchMenu = false
                                scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "checkout", b) }; refreshStatus() }
                            },
                            trailingIcon = {
                                // long-press context via dedicated button
                                Icon(Icons.Default.MoreVert, null, tint = MutedColor, modifier = Modifier.size(14.dp).clickable {
                                    showBranchMenu = false; branchContextMenu = b
                                })
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("New branch…", fontSize = 12.sp, color = IconColor) },
                        onClick = { showBranchMenu = false; newBranchName = ""; newBranchError = null; showNewBranchDialog = true }
                    )
                }
            }
            // .gitignore quick button
            Icon(Icons.Default.Block, null, tint = MutedColor, modifier = Modifier.size(15.dp).clickable {
                val gitignoreFile = File(repoDir, ".gitignore")
                gitignoreContent = if (gitignoreFile.exists()) gitignoreFile.readText() else "# .gitignore\n"
                showGitignoreDialog = true
            })
        }
        HorizontalDivider(color = DividerColor)

        // ── Error banner ─────────────────────────────────────────────────────
        if (statusError != null) {
            Row(Modifier.fillMaxWidth().background(ErrorColor.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = ErrorColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(statusError ?: "", fontSize = 11.sp, color = ErrorColor, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = DividerColor)
        }

        // ── Conflict banner ──────────────────────────────────────────────────
        if (conflictedFiles.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(ConflictColor.copy(alpha = 0.08f)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = ConflictColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${conflictedFiles.size} merge conflict(s)", fontSize = 12.sp, color = ConflictColor, fontWeight = FontWeight.Bold)
                }
                conflictedFiles.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f, fontSize = 11.sp, color = ConflictColor, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Open", fontSize = 10.sp, color = IconColor, modifier = Modifier.clickable {
                            // fire intent to open in editor — CodeSpaceApp handles "openFile" broadcast
                            val intent = android.content.Intent("com.codespace.ide.OPEN_FILE").apply {
                                putExtra("path", File(repoDir, f).absolutePath)
                            }
                            context.sendBroadcast(intent)
                        }.padding(4.dp))
                    }
                }
            }
            HorizontalDivider(color = DividerColor)
        }

        // ── Commit box (only on CHANGES tab) ─────────────────────────────────
        if (activeTab == ScmTab.CHANGES) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = message, onValueChange = { message = it },
                    placeholder = { Text("Commit message (Ctrl+Enter)", fontSize = 11.sp, color = MutedColor) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextColor),
                    minLines = 2, maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IconColor, unfocusedBorderColor = DividerColor,
                        focusedContainerColor = BgColor, unfocusedContainerColor = BgColor,
                    )
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "add", ".") }
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "commit", "-m", message) }
                                message = ""; refreshStatus()
                            }
                        },
                        enabled = message.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = IconColor),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) { Text("Commit", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "add", ".") }
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "commit", "-m", message) }
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "push") }
                                message = ""; refreshStatus()
                            }
                        },
                        enabled = message.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) { Text("Commit & Push", fontSize = 12.sp) }
                }
            }
            HorizontalDivider(color = DividerColor)
        }

        // ── Tab row ──────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().background(HeaderBg)) {
            ScmTab.entries.forEach { tab ->
                val label = when (tab) { ScmTab.CHANGES -> "Changes"; ScmTab.LOG -> "Log"; ScmTab.STASH -> "Stash"; ScmTab.TAGS -> "Tags" }
                val active = tab == activeTab
                Box(
                    Modifier.weight(1f).clickable {
                        activeTab = tab
                        when (tab) { ScmTab.LOG -> loadLog(); ScmTab.STASH -> loadStashes(); ScmTab.TAGS -> loadTags(); else -> {} }
                    }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 11.sp, color = if (active) IconColor else MutedColor,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        HorizontalDivider(color = if (activeTab != ScmTab.CHANGES) IconColor else DividerColor, thickness = if (activeTab != ScmTab.CHANGES) 1.dp else 0.5.dp)

        // ── Tab content ──────────────────────────────────────────────────────
        when (activeTab) {

            // ────────────────────────── CHANGES ──────────────────────────────
            ScmTab.CHANGES -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    // Staged
                    item {
                        SectionHeader(
                            title = "Staged", count = stagedChanges.size, expanded = showStaged,
                            onToggle = { showStaged = !showStaged },
                            action = if (stagedChanges.isNotEmpty()) "Unstage All" else null,
                            onAction = { unstageAll() }
                        )
                    }
                    if (showStaged) {
                        items(stagedChanges) { c ->
                            ChangeRow(c, onStage = null, onUnstage = { unstageFile(c.file) }, onDiscard = { discardFile(c.file) }, repoDir = repoDir)
                        }
                    }
                    // Unstaged / Untracked
                    item {
                        SectionHeader(
                            title = "Changes", count = unstagedChanges.size, expanded = showChanges,
                            onToggle = { showChanges = !showChanges },
                            action = if (unstagedChanges.isNotEmpty()) "Stage All" else null,
                            onAction = { stageAll() }
                        )
                    }
                    if (showChanges) {
                        items(unstagedChanges) { c ->
                            ChangeRow(c, onStage = { stageFile(c.file) }, onUnstage = null, onDiscard = if (c.statusCode != '?') { { discardFile(c.file) } } else null, repoDir = repoDir)
                        }
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }

            // ─────────────────────────── LOG ─────────────────────────────────
            ScmTab.LOG -> {
                if (logLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = IconColor)
                    }
                } else if (commitLog.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No commits yet", fontSize = 12.sp, color = MutedColor)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(commitLog) { c ->
                            val expanded = expandedSha == c.sha
                            Column(
                                Modifier.fillMaxWidth().clickable { expandedSha = if (expanded) null else c.sha }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.shortSha, fontSize = 10.sp, color = IconColor, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
                                    Text(c.message, fontSize = 12.sp, color = TextColor, modifier = Modifier.weight(1f), maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (expanded) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Author: ${c.author}", fontSize = 11.sp, color = MutedColor)
                                    Text("Date:   ${c.date}",   fontSize = 11.sp, color = MutedColor)
                                    Text("SHA:    ${c.sha}",    fontSize = 11.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                                } else {
                                    Text("${c.author}  ${c.date}", fontSize = 10.sp, color = MutedColor)
                                }
                            }
                            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                        }
                    }
                }
            }

            // ─────────────────────────── STASH ───────────────────────────────
            ScmTab.STASH -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { stashMsg = ""; showStashDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = IconColor),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("Save Stash", fontSize = 12.sp) }
                    }
                    HorizontalDivider(color = DividerColor)
                    if (stashLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = IconColor) }
                    } else if (stashes.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No stashes", fontSize = 12.sp, color = MutedColor) }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(stashes) { s ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.message, fontSize = 12.sp, color = TextColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("stash@{${s.index}}", fontSize = 10.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                                    }
                                    Text("Pop", fontSize = 11.sp, color = IconColor, modifier = Modifier.clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { runGit(context, repoDir, "stash", "pop", "stash@{${s.index}}") }
                                            loadStashes(); refreshStatus()
                                        }
                                    }.padding(4.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Drop", fontSize = 11.sp, color = DeletedColor, modifier = Modifier.clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { runGit(context, repoDir, "stash", "drop", "stash@{${s.index}}") }
                                            loadStashes()
                                        }
                                    }.padding(4.dp))
                                }
                                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }

            // ─────────────────────────── TAGS ────────────────────────────────
            ScmTab.TAGS -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { newTagName = ""; newTagMsg = ""; newTagAnnotated = true; showTagDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = IconColor),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("New Tag", fontSize = 12.sp) }
                    }
                    HorizontalDivider(color = DividerColor)
                    if (tagsLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = IconColor) }
                    } else if (tags.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No tags", fontSize = 12.sp, color = MutedColor) }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(tags) { t ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(t.name, fontSize = 13.sp, color = TagColor, fontWeight = FontWeight.Medium)
                                            if (t.isAnnotated) { Spacer(Modifier.width(6.dp)); Text("annotated", fontSize = 9.sp, color = MutedColor) }
                                        }
                                        Text(t.sha, fontSize = 10.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                                        if (t.message.isNotBlank()) Text(t.message, fontSize = 11.sp, color = MutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("Delete", fontSize = 11.sp, color = DeletedColor, modifier = Modifier.clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { runGit(context, repoDir, "tag", "-d", t.name) }
                                            loadTags()
                                        }
                                    }.padding(4.dp))
                                }
                                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ══ Dialogs ══════════════════════════════════════════════════════════════

    // New Branch
    if (showNewBranchDialog) {
        AlertDialog(
            onDismissRequest = { showNewBranchDialog = false },
            title = { Text("New Branch", fontSize = 14.sp) },
            text = {
                Column {
                    if (newBranchError != null) Text(newBranchError!!, fontSize = 11.sp, color = ErrorColor)
                    OutlinedTextField(value = newBranchName, onValueChange = { newBranchName = it; newBranchError = null },
                        label = { Text("Branch name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val out = withContext(Dispatchers.IO) { runGit(context, repoDir, "checkout", "-b", newBranchName) }
                        if (out.startsWith("Error:") || out.contains("error:", ignoreCase = true)) { newBranchError = out }
                        else { showNewBranchDialog = false; refreshStatus() }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewBranchDialog = false }) { Text("Cancel") } }
        )
    }

    // Branch context menu (delete / rename)
    branchContextMenu?.let { targetBranch ->
        AlertDialog(
            onDismissRequest = { branchContextMenu = null },
            title = { Text(targetBranch, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        branchContextMenu = null
                        renameBranchTarget = targetBranch; renameBranchNew = targetBranch; showRenameBranchDialog = true
                    }, modifier = Modifier.fillMaxWidth()) { Text("Rename…") }
                    OutlinedButton(onClick = {
                        branchContextMenu = null
                        scope.launch {
                            withContext(Dispatchers.IO) { runGit(context, repoDir, "branch", "-d", targetBranch) }
                            refreshStatus()
                        }
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DeletedColor)) { Text("Delete") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { branchContextMenu = null }) { Text("Close") } }
        )
    }

    // Rename Branch
    if (showRenameBranchDialog) {
        AlertDialog(
            onDismissRequest = { showRenameBranchDialog = false },
            title = { Text("Rename Branch", fontSize = 14.sp) },
            text = {
                OutlinedTextField(value = renameBranchNew, onValueChange = { renameBranchNew = it },
                    label = { Text("New name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runGit(context, repoDir, "branch", "-m", renameBranchTarget, renameBranchNew) }
                        showRenameBranchDialog = false; refreshStatus()
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRenameBranchDialog = false }) { Text("Cancel") } }
        )
    }

    // .gitignore editor
    if (showGitignoreDialog) {
        AlertDialog(
            onDismissRequest = { showGitignoreDialog = false },
            title = { Text(".gitignore", fontSize = 14.sp) },
            text = {
                OutlinedTextField(
                    value = gitignoreContent, onValueChange = { gitignoreContent = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    maxLines = 60,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { File(repoDir, ".gitignore").writeText(gitignoreContent) }
                        showGitignoreDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showGitignoreDialog = false }) { Text("Cancel") } }
        )
    }

    // Save Stash
    if (showStashDialog) {
        AlertDialog(
            onDismissRequest = { showStashDialog = false },
            title = { Text("Save Stash", fontSize = 14.sp) },
            text = {
                OutlinedTextField(value = stashMsg, onValueChange = { stashMsg = it },
                    label = { Text("Message (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val args = if (stashMsg.isBlank()) arrayOf("stash", "push") else arrayOf("stash", "push", "-m", stashMsg)
                        withContext(Dispatchers.IO) { runGit(context, repoDir, *args) }
                        showStashDialog = false; loadStashes(); refreshStatus()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showStashDialog = false }) { Text("Cancel") } }
        )
    }

    // New Tag
    if (showTagDialog) {
        AlertDialog(
            onDismissRequest = { showTagDialog = false },
            title = { Text("New Tag", fontSize = 14.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newTagName, onValueChange = { newTagName = it },
                        label = { Text("Tag name (e.g. v1.0.0)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newTagMsg, onValueChange = { newTagMsg = it },
                        label = { Text("Message (blank = lightweight)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = newTagAnnotated, onCheckedChange = { newTagAnnotated = it })
                        Text("Annotated tag", fontSize = 12.sp, color = TextColor)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val args = if (newTagAnnotated && newTagMsg.isNotBlank())
                            arrayOf("tag", "-a", newTagName, "-m", newTagMsg)
                        else arrayOf("tag", newTagName)
                        withContext(Dispatchers.IO) { runGit(context, repoDir, *args) }
                        showTagDialog = false; loadTags()
                    }
                }, enabled = newTagName.isNotBlank()) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showTagDialog = false }) { Text("Cancel") } }
        )
    }
}

// ══ Shared sub-composables ════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.background(HeaderBg).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, tint = MutedColor, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("$title ($count)", fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) Text(action, fontSize = 10.sp, color = IconColor, modifier = Modifier.clickable { onAction() }.padding(horizontal = 4.dp))
    }
}

@Composable
private fun ChangeRow(change: GitChange, onStage: (() -> Unit)?, onUnstage: (() -> Unit)?, onDiscard: (() -> Unit)?, repoDir: File? = null) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var expanded    by remember { mutableStateOf(false) }
    var diffLines   by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingDiff by remember { mutableStateOf(false) }

    val statusColor = when (change.statusCode) {
        'M' -> ModifiedColor; 'A' -> AddedColor; 'D' -> DeletedColor
        '?' -> UntrackedColor; 'R', 'C' -> Color(0xFF4EC9B0); else -> MutedColor
    }
    val statusLabel = when (change.statusCode) {
        'M' -> "M"; 'A' -> "A"; 'D' -> "D"; '?' -> "U"; 'R' -> "R"; 'C' -> "C"
        else -> change.statusCode.toString()
    }

    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable {
                    if (repoDir != null && change.statusCode != '?') {
                        expanded = !expanded
                        if (expanded && diffLines.isEmpty() && !loadingDiff) {
                            loadingDiff = true
                            scope.launch {
                                val out = withContext(Dispatchers.IO) {
                                    runGit(context, repoDir, "diff", "--", change.file).ifBlank {
                                        runGit(context, repoDir, "diff", "--cached", "--", change.file)
                                    }
                                }
                                diffLines = out.lines(); loadingDiff = false
                            }
                        }
                    }
                }
                .padding(start = 24.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(statusLabel, fontSize = 10.sp, color = statusColor, fontFamily = FontFamily.Monospace, modifier = Modifier.width(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(change.file, fontSize = 12.sp, color = TextColor, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (repoDir != null && change.statusCode != '?') {
                Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, tint = MutedColor, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            if (onStage   != null) { Icon(Icons.Default.Add,   "Stage",   tint = IconColor,    modifier = Modifier.size(15.dp).clickable { onStage() });   Spacer(Modifier.width(4.dp)) }
            if (onUnstage != null) { Icon(Icons.Default.Remove, "Unstage", tint = MutedColor,  modifier = Modifier.size(15.dp).clickable { onUnstage() }); Spacer(Modifier.width(4.dp)) }
            if (onDiscard != null) { Icon(Icons.Default.Undo,   "Discard", tint = DeletedColor, modifier = Modifier.size(15.dp).clickable { onDiscard() }) }
        }
        if (expanded) {
            if (loadingDiff) {
                Box(Modifier.fillMaxWidth().padding(start = 32.dp, bottom = 4.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = IconColor)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF0D0D0D)).heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    diffLines.forEach { line ->
                        val color = when {
                            line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF4EC9B0)
                            line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFF6B6B)
                            line.startsWith("@@") -> Color(0xFF569CD6)
                            else -> MutedColor
                        }
                        Text(line, fontSize = 11.sp, color = color, fontFamily = FontFamily.Monospace, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        HorizontalDivider(color = DividerColor.copy(alpha = 0.5f), thickness = 0.5.dp)
    }
}
