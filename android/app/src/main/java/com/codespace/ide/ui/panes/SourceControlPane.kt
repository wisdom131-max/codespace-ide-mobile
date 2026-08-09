package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import com.codespace.ide.diagnostics.AppOutputLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.data.SecureTokenStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.automirrored.filled.*

// ── Palette ──────────────────────────────────────────────────────────────────
@Composable private fun BgColor()        = if (isSystemInDarkTheme()) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
@Composable private fun HeaderBg()       = if (isSystemInDarkTheme()) Color(0xFF252526) else Color(0xFFF3F3F3)
@Composable private fun TextColor()      = if (isSystemInDarkTheme()) Color(0xFFCCCCCC) else Color(0xFF333333)
@Composable private fun MutedColor()     = if (isSystemInDarkTheme()) Color(0xFF858585) else Color(0xFF717171)
@Composable private fun DividerColor()   = if (isSystemInDarkTheme()) Color(0xFF333333) else Color(0xFFE0E0E0)
@Composable private fun IconColor()      = if (isSystemInDarkTheme()) Color(0xFF569CD6) else Color(0xFF007ACC)
@Composable private fun ModifiedColor()  = if (isSystemInDarkTheme()) Color(0xFFE2C08D) else Color(0xFFE2C08D)
@Composable private fun UntrackedColor() = if (isSystemInDarkTheme()) Color(0xFF73C991) else Color(0xFF73C991)
@Composable private fun DeletedColor()   = if (isSystemInDarkTheme()) Color(0xFFF48771) else Color(0xFFF48771)
@Composable private fun AddedColor()     = if (isSystemInDarkTheme()) Color(0xFF73C991) else Color(0xFF73C991)
@Composable private fun ErrorColor()     = if (isSystemInDarkTheme()) Color(0xFFF48771) else Color(0xFFCC0000)
@Composable private fun ConflictColor()  = if (isSystemInDarkTheme()) Color(0xFFE51400) else Color(0xFFE51400)
@Composable private fun TagColor()       = if (isSystemInDarkTheme()) Color(0xFFDA70D6) else Color(0xFFDA70D6)

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

private data class GraphRow(val graph: String, val sha: String, val message: String)

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
    val raw = ProotInstaller.execOnce(context, "git $authFlag$quoted", guestPath)
    // P25-1: Normalize "Exit code NNN" (proot/git error) into "Error:" prefix that callers check.
    // "Exit code 128" = not a git repo or auth failure. "Exit code 129" = bad args.
    // stripProotNoise already removed proot/locale lines; this catches remaining git exit errors.
    return if (raw.startsWith("Exit code"))
        "Error: git ${args.firstOrNull().orEmpty()} failed (${raw.substringBefore("\n").trim()}) — ${raw.substringAfter("\n").take(200).trim().ifBlank { raw.substringBefore("\n").trim() }}"
    else raw
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
private enum class ScmTab { CHANGES, LOG, GRAPH, STASH, TAGS }

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
    var graphData by remember { mutableStateOf<List<GraphRow>>(emptyList()) }
    var loading         by remember { mutableStateOf(false) }
    var aheadBehind     by remember { mutableStateOf("") }
    var statusError     by remember { mutableStateOf<String?>(null) }
    var refresh         by remember { mutableStateOf(0) }
    var showStaged      by remember { mutableStateOf(true) }
    var showChanges     by remember { mutableStateOf(true) }
    var actionToast    by remember { mutableStateOf<String?>(null) } // P16-A: git op feedback

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
    var isGitRepo     by remember { mutableStateOf(false) }
    var initializing  by remember { mutableStateOf(false) }
    var initError     by remember { mutableStateOf<String?>(null) }

    // P43: Clone + Sign-in + Browse Repos
    var cloneUrl        by remember { mutableStateOf("") }
    var cloning         by remember { mutableStateOf(false) }
    var cloneError      by remember { mutableStateOf<String?>(null) }
    var showSignInDialog  by remember { mutableStateOf(false) }
    var showRepoBrowser   by remember { mutableStateOf(false) }
    var repos           by remember { mutableStateOf<List<com.codespace.ide.data.GitHubAuth.RepoInfo>>(emptyList()) }
    var loadingRepos    by remember { mutableStateOf(false) }
    var repoSearchQuery by remember { mutableStateOf("") }

    // P43-Publish: Publish to GitHub state
    var showPublishDialog by remember { mutableStateOf(false) }
    var publishRepoName   by remember { mutableStateOf("") }
    var publishDesc       by remember { mutableStateOf("") }
    var publishPrivate    by remember { mutableStateOf(false) }
    var publishing        by remember { mutableStateOf(false) }
    var publishError      by remember { mutableStateOf<String?>(null) }
    var publishSuccess    by remember { mutableStateOf<String?>(null) }
    var hasRemote       by remember { mutableStateOf(false) }

    val repoDir = remember(projectId) {
        val wsPath = loadWorkspacePath(context, projectId)
        wsPath?.let { File(it) } ?: File(com.codespace.ide.terminal.ProotInstaller.rootfsDir(context), "root")
    }

    // Check if the project dir has a .git folder — if not, show init UI instead of error
    LaunchedEffect(repoDir, refresh) {
        isGitRepo = withContext(Dispatchers.IO) { File(repoDir, ".git").exists() }
    }

    // ── data loaders ─────────────────────────────────────────────────────────
    fun refreshStatus() {
        scope.launch {
            loading = true; statusError = null
            if (!File(repoDir, ".git").exists()) { isGitRepo = false; loading = false; return@launch }
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
                // P43-Publish: check if repo has a remote configured
                val remoteOut = runGit(context, repoDir, "remote")
                hasRemote = remoteOut.isNotBlank() && !remoteOut.startsWith("Error:")

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
            // P19-B: Fetch branch graph
            val graphRaw = withContext(Dispatchers.IO) {
                runGit(context, repoDir, "log", "--graph", "--oneline", "--all", "-n", "100")
            }
            graphData = if (graphRaw.startsWith("Error:")) emptyList() else graphRaw.lines().filter { it.isNotBlank() }.map { line ->
                val graph = line.takeWhile { it == ' ' || it == '|' || it == '*' || it == '/' || it == '\\' || it == '_' || it == '-' }
                val rest = line.drop(graph.length).trim()
                val restParts = rest.split(Regex("\\s+"), 2)
                GraphRow(graph = graph, sha = restParts.getOrElse(0) { "" }.take(7), message = restParts.getOrElse(1) { "" })
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

    // ── P43-Publish: Publish to GitHub dialog ─────────────────────────────────
    if (showPublishDialog) {
        AlertDialog(
            onDismissRequest = { if (!publishing) showPublishDialog = false },
            title = { Text("Publish to GitHub", fontSize = 14.sp) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = publishRepoName,
                        onValueChange = { publishRepoName = it.trim() },
                        label = { Text("Repository name", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = publishDesc,
                        onValueChange = { publishDesc = it },
                        label = { Text("Description (optional)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = publishPrivate, onCheckedChange = { publishPrivate = it })
                        Text("Private repository", fontSize = 12.sp)
                    }
                    publishError?.let { err ->
                        Spacer(Modifier.height(6.dp))
                        Text(err, fontSize = 10.sp, color = ErrorColor())
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            publishing = true; publishError = null; publishSuccess = null
                            try {
                                val token = SecureTokenStore(context).githubToken
                                    ?: throw Exception("Not signed in to GitHub. Connect in Settings first.")
                                val name = publishRepoName.ifBlank {
                                    repoDir.name  // default to folder name
                                }
                                // 1. Create repo on GitHub
                                val cloneUrl = com.codespace.ide.data.GitHubAuth.createRepo(
                                    accessToken = token,
                                    repoName = name,
                                    description = publishDesc,
                                    isPrivate = publishPrivate,
                                )
                                // 2. Add remote origin
                                val addResult = withContext(Dispatchers.IO) {
                                    runGit(context, repoDir, "remote", "add", "origin", cloneUrl)
                                }
                                if (addResult.startsWith("Error:")) {
                                    // remote might already exist, try set-url
                                    withContext(Dispatchers.IO) {
                                        runGit(context, repoDir, "remote", "set-url", "origin", cloneUrl)
                                    }
                                }
                                // 3. Stage all + commit if nothing committed yet
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "add", ".") }
                                val hasCommit = withContext(Dispatchers.IO) {
                                    runGit(context, repoDir, "log", "--oneline", "-n", "1")
                                }
                                if (hasCommit.startsWith("Error:")) {
                                    withContext(Dispatchers.IO) {
                                        runGit(context, repoDir, "commit", "-m", "Initial commit")
                                    }
                                }
                                // 4. Push to GitHub
                                val branchName = withContext(Dispatchers.IO) {
                                    runGit(context, repoDir, "branch", "--show-current").trim().ifBlank { "main" }
                                }
                                val pushResult = withContext(Dispatchers.IO) {
                                    runGit(context, repoDir, "push", "-u", "origin", branchName)
                                }
                                publishing = false
                                if (pushResult.startsWith("Error:")) {
                                    publishError = "Repo created but push failed: ${pushResult.take(100)}"
                                } else {
                                    publishSuccess = cloneUrl
                                    showPublishDialog = false
                                    refresh++
                                }
                            } catch (e: Exception) {
                                publishing = false
                                publishError = e.message ?: "Unknown error"
                            }
                        }
                    },
                    enabled = !publishing,
                ) {
                    if (publishing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text("Publish", fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPublishDialog = false }, enabled = !publishing) {
                    Text("Cancel", fontSize = 12.sp)
                }
            },
        )
    }

    // ── root layout ──────────────────────────────────────────────────────────
    Column(Modifier.fillMaxSize().background(BgColor())) {

        // ── Header ───────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(35.dp).background(HeaderBg()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SOURCE CONTROL", fontSize = 11.sp, color = MutedColor(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (loading) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = IconColor())
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Refresh, null, tint = MutedColor(), modifier = Modifier.size(16.dp).clickable { refresh++ })
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowDownward, null, tint = MutedColor(), modifier = Modifier.size(16.dp).clickable {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runGit(context, repoDir, "pull") }
            AppOutputLog.log("git pull: ${if (result.startsWith("Error:")) "failed" else "ok"}", "git")
                    refreshStatus()
                    actionToast = if (result.startsWith("Error:")) "Pull failed: ${result.take(60)}" else "Pull complete"
                }
            })
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Sync, null, tint = MutedColor(), modifier = Modifier.size(16.dp).clickable {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runGit(context, repoDir, "fetch", "--all") }
                    refreshStatus()
                    actionToast = if (result.startsWith("Error:")) "Fetch failed: ${result.take(60)}" else "Fetch complete"
                }
            })
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowUpward, null, tint = MutedColor(), modifier = Modifier.size(16.dp).clickable {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runGit(context, repoDir, "push") }
            AppOutputLog.log("git push: commit-and-push completed", "git")
            AppOutputLog.log("git push: ${if (result.startsWith("Error:")) "failed" else "ok"}", "git")
                    refreshStatus()
                    actionToast = if (result.startsWith("Error:")) "Push failed: ${result.take(60)}" else "Push complete"
                }
            })
            // P43-Publish: Show Publish button for repos without a remote
            if (isGitRepo && !hasRemote) {
                Spacer(Modifier.width(6.dp))
                val ghToken = remember { SecureTokenStore(context).githubToken }
                if (ghToken != null && !ghToken.isBlank()) {
                    Icon(Icons.Default.CloudUpload, null, tint = Color(0xFF24292F), modifier = Modifier.size(16.dp).clickable {
                        showPublishDialog = true
                    })
                }
            }
        }
        HorizontalDivider(color = DividerColor())

        // P16-A: action feedback toast
        actionToast?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                actionToast = null
            }
            Text(
                text = msg,
                fontSize = 11.sp,
                color = if (msg.contains("failed")) ErrorColor() else IconColor(),
                modifier = Modifier.fillMaxWidth().background(HeaderBg()).padding(horizontal = 8.dp, vertical = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ── Branch row ───────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.clickable { showBranchMenu = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountTree, null, tint = IconColor(), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    val displayBranch = if (branch.startsWith("Error:") || branch.isBlank()) "—" else branch
                    Text(displayBranch, fontSize = 12.sp, color = TextColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (aheadBehind.isNotBlank()) { Spacer(Modifier.width(6.dp)); Text(aheadBehind, fontSize = 10.sp, color = MutedColor()) }
                    Icon(Icons.Default.ArrowDropDown, null, tint = MutedColor(), modifier = Modifier.size(14.dp))
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
                                Icon(Icons.Default.MoreVert, null, tint = MutedColor(), modifier = Modifier.size(14.dp).clickable {
                                    showBranchMenu = false; branchContextMenu = b
                                })
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("New branch…", fontSize = 12.sp, color = IconColor()) },
                        onClick = { showBranchMenu = false; newBranchName = ""; newBranchError = null; showNewBranchDialog = true }
                    )
                }
            }
            // .gitignore quick button
            Icon(Icons.Default.Block, null, tint = MutedColor(), modifier = Modifier.size(15.dp).clickable {
                val gitignoreFile = File(repoDir, ".gitignore")
                gitignoreContent = if (gitignoreFile.exists()) gitignoreFile.readText() else "# .gitignore\n"
                showGitignoreDialog = true
            })
        }
        HorizontalDivider(color = DividerColor())

        // ── Not-a-repo empty state ────────────────────────────────────────────
        if (!isGitRepo && !loading) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FolderOff, null, tint = MutedColor(), modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text("This folder isn\'t a Git repository yet.", fontSize = 12.sp, color = MutedColor(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(16.dp))

                // ── Open Remote Repository (VS Code-style flow) ──
                Button(
                    onClick = {
                        val token = SecureTokenStore(context).githubToken
                        if (token != null && token.isNotBlank()) {
                            scope.launch {
                                loadingRepos = true
                                try {
                                    repos = com.codespace.ide.data.GitHubAuth.listUserRepos(token)
                                    showRepoBrowser = true
                                } catch (e: Exception) {
                                    cloneError = "Failed to load repos: " + (e.message ?: "")
                                }
                                loadingRepos = false
                            }
                        } else {
                            showSignInDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24292F)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Cloud, null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Open Remote Repository", fontSize = 12.sp, color = Color.White)
                }
                Spacer(Modifier.height(4.dp))
                Text("Open a repository from GitHub without cloning.",
                    fontSize = 10.sp, color = MutedColor(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DividerColor())
                Spacer(Modifier.height(12.dp))

                // ── Initialize Repository ──
                if (initializing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = IconColor())
                    Spacer(Modifier.height(4.dp))
                    Text("Initializing...", fontSize = 11.sp, color = MutedColor())
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                initializing = true; initError = null
                                val result = withContext(Dispatchers.IO) {
                                    ProotInstaller.execOnce(context, "git init", ProotInstaller.hostToGuestPath(context, repoDir.absolutePath) ?: "")
                                }
                                initializing = false
                                if (result.startsWith("Exit code") || result.startsWith("Error")) {
                                    initError = "git init failed: " + result.take(100)
                                } else {
                                    isGitRepo = true
                                    refresh++
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IconColor()),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Code, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Initialize Repository", fontSize = 12.sp)
                    }
                }
                initError?.let { err ->
                    Spacer(Modifier.height(6.dp))
                    Text(err, fontSize = 10.sp, color = ErrorColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }

                // ── Clone from URL ──
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DividerColor())
                Spacer(Modifier.height(12.dp))
                Text("Clone from URL", fontSize = 11.sp, color = MutedColor(), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = cloneUrl,
                    onValueChange = { cloneUrl = it; cloneError = null },
                    placeholder = { Text("https://github.com/user/repo.git", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    enabled = !cloning,
                )
                Spacer(Modifier.height(6.dp))
                if (cloning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = IconColor())
                        Spacer(Modifier.width(8.dp))
                        Text("Cloning...", fontSize = 11.sp, color = MutedColor())
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                cloning = true; cloneError = null
                                val url = cloneUrl.trim()
                                if (url.isBlank()) {
                                    cloneError = "Enter a clone URL"
                                    cloning = false
                                    return@launch
                                }
                                val repoName = url.substringAfterLast("/").removeSuffix(".git").ifBlank { "cloned-repo" }
                                val guestWorkspace = ProotInstaller.hostToGuestPath(context, repoDir.absolutePath) ?: "/root"
                                val result = withContext(Dispatchers.IO) {
                                    ProotInstaller.execOnce(context, "git clone '" + url + "' '" + repoName + "'", guestWorkspace)
                                }
                                cloning = false
                                if (result.startsWith("Exit code") || result.startsWith("Error")) {
                                    cloneError = "Clone failed: " + result.take(200)
                AppOutputLog.log("git clone failed: ${result.take(100)}", "git")
                                } else {
                                    cloneUrl = ""
                AppOutputLog.log("git clone: $repoName cloned successfully", "git")
                                    isGitRepo = true
                                    refresh++
                                }
                            }
                        },
                        enabled = cloneUrl.isNotBlank() && !cloning,
                        colors = ButtonDefaults.buttonColors(containerColor = IconColor()),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clone Repository", fontSize = 12.sp)
                    }
                }
                cloneError?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(err, fontSize = 10.sp, color = ErrorColor(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }

                // ── Sign in with GitHub / Browse My Repos ──
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DividerColor())
                Spacer(Modifier.height(12.dp))
                val ghToken = remember { SecureTokenStore(context).githubToken }
                if (ghToken != null && ghToken.isNotBlank()) {
                    val ghUser = remember { SecureTokenStore(context).githubUsername }
                    val userLabel = ghUser ?: "GitHub user"
                    Text("Connected as " + userLabel, fontSize = 10.sp, color = MutedColor(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                loadingRepos = true
                                try {
                                    repos = com.codespace.ide.data.GitHubAuth.listUserRepos(ghToken)
                                    showRepoBrowser = true
                                } catch (e: Exception) {
                                    cloneError = "Failed to load repos: " + (e.message ?: "")
                                }
                                loadingRepos = false
                            }
                        },
                        enabled = !loadingRepos,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24292F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.List, null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Browse My Repos", fontSize = 12.sp, color = Color.White)
                    }
                } else {
                    Button(
                        onClick = { showSignInDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24292F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Login, null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Sign in with GitHub", fontSize = 12.sp, color = Color.White)
                    }
                }

                // ── Publish to GitHub (existing — appears for repos with no remote) ──
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = DividerColor())
                Spacer(Modifier.height(8.dp))
                val publishToken = remember { SecureTokenStore(context).githubToken }
                if (publishToken != null && !publishToken.isBlank()) {
                    Button(
                        onClick = { showPublishDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24292F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Publish to GitHub", fontSize = 12.sp, color = Color.White)
                    }
                } else {
                    Text(
                        "Connect GitHub to publish",
                        fontSize = 10.sp, color = MutedColor(), textAlign = TextAlign.Center
                    )
                }
                publishSuccess?.let { url ->
                    Spacer(Modifier.height(6.dp))
                    Text("Published: " + url, fontSize = 10.sp, color = UntrackedColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                publishError?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(err, fontSize = 10.sp, color = ErrorColor(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = DividerColor())
        } else if (statusError != null) {
        } else if (statusError != null) {
        } else if (statusError != null) {
            // ── Error banner (only shown if it IS a git repo but something went wrong) ──
            Row(Modifier.fillMaxWidth().background(ErrorColor().copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = ErrorColor(), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(statusError ?: "", fontSize = 11.sp, color = ErrorColor(), modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = DividerColor())
        }

        // ── Conflict banner ──────────────────────────────────────────────────
        if (conflictedFiles.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(ConflictColor().copy(alpha = 0.08f)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = ConflictColor(), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${conflictedFiles.size} merge conflict(s)", fontSize = 12.sp, color = ConflictColor(), fontWeight = FontWeight.Bold)
                }
                conflictedFiles.forEach { f ->
                    ConflictResolverRow(filePath = f, repoDir = repoDir, context = context, onResolved = { refreshStatus() })
                }
            }
            HorizontalDivider(color = DividerColor())
        }

        // ── Commit box (only on CHANGES tab) ─────────────────────────────────
        if (activeTab == ScmTab.CHANGES) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = message, onValueChange = { message = it },
                    placeholder = { Text("Commit message (Ctrl+Enter)", fontSize = 11.sp, color = MutedColor()) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextColor()),
                    minLines = 2, maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IconColor(), unfocusedBorderColor = DividerColor(),
                        focusedContainerColor = BgColor(), unfocusedContainerColor = BgColor(),
                    )
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "add", ".") }
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "commit", "-m", message) }
            AppOutputLog.log("git commit: \"${message.take(50)}\"", "git")
                                message = ""; refreshStatus()
                            }
                        },
                        enabled = message.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = IconColor()),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) { Text("Commit", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "add", ".") }
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "commit", "-m", message) }
            AppOutputLog.log("git commit: \"${message.take(50)}\"", "git")
                                withContext(Dispatchers.IO) { runGit(context, repoDir, "push") }
            AppOutputLog.log("git push: commit-and-push completed", "git")
                                message = ""; refreshStatus()
                            }
                        },
                        enabled = message.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) { Text("Commit & Push", fontSize = 12.sp) }
                }
            }
            HorizontalDivider(color = DividerColor())
        }

        // ── Tab row ──────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().background(HeaderBg())) {
            ScmTab.entries.forEach { tab ->
                val label = when (tab) { ScmTab.CHANGES -> "Changes"; ScmTab.LOG -> "Log"; ScmTab.GRAPH -> "Graph"; ScmTab.STASH -> "Stash"; ScmTab.TAGS -> "Tags" }
                val active = tab == activeTab
                Box(
                    Modifier.weight(1f).clickable {
                        activeTab = tab
                        when (tab) { ScmTab.LOG -> loadLog(); ScmTab.GRAPH -> loadLog(); ScmTab.STASH -> loadStashes(); ScmTab.TAGS -> loadTags(); else -> {} }
                    }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 11.sp, color = if (active) IconColor() else MutedColor(),
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        HorizontalDivider(color = if (activeTab != ScmTab.CHANGES) IconColor() else DividerColor(), thickness = if (activeTab != ScmTab.CHANGES) 1.dp else 0.5.dp)

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
                        CircularProgressIndicator(color = IconColor())
                    }
                } else if (commitLog.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No commits yet", fontSize = 12.sp, color = MutedColor())
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
                                    Text(c.shortSha, fontSize = 10.sp, color = IconColor(), fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
                                    Text(c.message, fontSize = 12.sp, color = TextColor(), modifier = Modifier.weight(1f), maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (expanded) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Author: ${c.author}", fontSize = 11.sp, color = MutedColor())
                                    Text("Date:   ${c.date}",   fontSize = 11.sp, color = MutedColor())
                                    Text("SHA:    ${c.sha}",    fontSize = 11.sp, color = MutedColor(), fontFamily = FontFamily.Monospace)
                                } else {
                                    Text("${c.author}  ${c.date}", fontSize = 10.sp, color = MutedColor())
                                }
                            }
                            HorizontalDivider(color = DividerColor(), thickness = 0.5.dp)
                        }
                    }
                }
            }

            // ─────────────────────────── GRAPH (P19-B) ───────────────────────
            ScmTab.GRAPH -> {
                if (logLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = IconColor())
                    }
                } else if (graphData.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No graph data", fontSize = 12.sp, color = MutedColor())
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(graphData) { row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(row.graph.ifBlank { " " }, color = Color(0xFF569CD6), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(80.dp))
                                Text(row.sha, color = IconColor(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
                                Text(row.message, color = TextColor(), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            HorizontalDivider(color = DividerColor(), thickness = 0.3.dp)
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
                            colors = ButtonDefaults.buttonColors(containerColor = IconColor()),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("Save Stash", fontSize = 12.sp) }
                    }
                    HorizontalDivider(color = DividerColor())
                    if (stashLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = IconColor()) }
                    } else if (stashes.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No stashes", fontSize = 12.sp, color = MutedColor()) }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(stashes) { s ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.message, fontSize = 12.sp, color = TextColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("stash@{${s.index}}", fontSize = 10.sp, color = MutedColor(), fontFamily = FontFamily.Monospace)
                                    }
                                    Text("Pop", fontSize = 11.sp, color = IconColor(), modifier = Modifier.clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { runGit(context, repoDir, "stash", "pop", "stash@{${s.index}}") }
                                            loadStashes(); refreshStatus()
                                        }
                                    }.padding(4.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Drop", fontSize = 11.sp, color = DeletedColor(), modifier = Modifier.clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { runGit(context, repoDir, "stash", "drop", "stash@{${s.index}}") }
                                            loadStashes()
                                        }
                                    }.padding(4.dp))
                                }
                                HorizontalDivider(color = DividerColor(), thickness = 0.5.dp)
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
                            colors = ButtonDefaults.buttonColors(containerColor = IconColor()),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("New Tag", fontSize = 12.sp) }
                    }
                    HorizontalDivider(color = DividerColor())
                    if (tagsLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = IconColor()) }
                    } else if (tags.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No tags", fontSize = 12.sp, color = MutedColor()) }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(tags) { t ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(t.name, fontSize = 13.sp, color = TagColor(), fontWeight = FontWeight.Medium)
                                            if (t.isAnnotated) { Spacer(Modifier.width(6.dp)); Text("annotated", fontSize = 9.sp, color = MutedColor()) }
                                        }
                                        Text(t.sha, fontSize = 10.sp, color = MutedColor(), fontFamily = FontFamily.Monospace)
                                        if (t.message.isNotBlank()) Text(t.message, fontSize = 11.sp, color = MutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("Delete", fontSize = 11.sp, color = DeletedColor(), modifier = Modifier.clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { runGit(context, repoDir, "tag", "-d", t.name) }
                                            loadTags()
                                        }
                                    }.padding(4.dp))
                                }
                                HorizontalDivider(color = DividerColor(), thickness = 0.5.dp)
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
                    if (newBranchError != null) Text(newBranchError!!, fontSize = 11.sp, color = ErrorColor())
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
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DeletedColor())) { Text("Delete") }
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
                        Text("Annotated tag", fontSize = 12.sp, color = TextColor())
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

    // ── P43: GitHub Sign-in Dialog (Device Flow) ────────────────────────────
    if (showSignInDialog) {
        GitHubSignInDialog(
            onDismiss = { showSignInDialog = false },
            onSuccess = {
                // After successful sign-in, auto-load repos
                scope.launch {
                    loadingRepos = true
                    try {
                        val token = SecureTokenStore(context).githubToken
                        if (token != null) {
                            repos = com.codespace.ide.data.GitHubAuth.listUserRepos(token)
                            showRepoBrowser = true
                        }
                    } catch (_: Exception) {}
                    loadingRepos = false
                }
            }
        )
    }

    // ── P43: Repo Browser Dialog ─────────────────────────────────────────────
    if (showRepoBrowser) {
        GitHubRepoBrowserDialog(
            repos = repos,
            searchQuery = repoSearchQuery,
            onSearchChange = { repoSearchQuery = it },
            onDismiss = { showRepoBrowser = false; repoSearchQuery = "" },
            onClone = { repoUrl ->
                showRepoBrowser = false; repoSearchQuery = ""
                scope.launch {
                    cloning = true; cloneError = null
                    val repoName = repoUrl.substringAfterLast("/").removeSuffix(".git").ifBlank { "cloned-repo" }
                    val guestWorkspace = ProotInstaller.hostToGuestPath(context, repoDir.absolutePath) ?: "/root"
                    val result = withContext(Dispatchers.IO) {
                        ProotInstaller.execOnce(context, "git clone '${'$'}repoUrl' '${'$'}repoName'", guestWorkspace)
                    }
                    cloning = false
                    if (result.startsWith("Exit code") || result.startsWith("Error")) {
                        cloneError = "Clone failed: ${'$'}{result.take(200)}"
                    } else {
                        isGitRepo = true
                        refresh++
                    }
                }
            }
        )
    }


    // ── P43: GitHub Sign-in Dialog (Device Flow) ────────────────────────────
    if (showSignInDialog) {
        GitHubSignInDialog(
            onDismiss = { showSignInDialog = false },
            onSuccess = {
                scope.launch {
                    loadingRepos = true
                    try {
                        val token = SecureTokenStore(context).githubToken
                        if (token != null) {
                            repos = com.codespace.ide.data.GitHubAuth.listUserRepos(token)
                            showRepoBrowser = true
                        }
                    } catch (_: Exception) {}
                    loadingRepos = false
                }
            }
        )
    }

    // ── P43: Repo Browser Dialog ─────────────────────────────────────────────
    if (showRepoBrowser) {
        GitHubRepoBrowserDialog(
            repos = repos,
            searchQuery = repoSearchQuery,
            onSearchChange = { repoSearchQuery = it },
            onDismiss = { showRepoBrowser = false; repoSearchQuery = "" },
            onClone = { repoUrl ->
                showRepoBrowser = false; repoSearchQuery = ""
                scope.launch {
                    cloning = true; cloneError = null
                    val repoName = repoUrl.substringAfterLast("/").removeSuffix(".git").ifBlank { "cloned-repo" }
                    val guestWorkspace = ProotInstaller.hostToGuestPath(context, repoDir.absolutePath) ?: "/root"
                    val result = withContext(Dispatchers.IO) {
                        ProotInstaller.execOnce(context, "git clone '" + repoUrl + "' '" + repoName + "'", guestWorkspace)
                    }
                    cloning = false
                    if (result.startsWith("Exit code") || result.startsWith("Error")) {
                        cloneError = "Clone failed: " + result.take(200)
                AppOutputLog.log("git clone failed: ${result.take(100)}", "git")
                    } else {
                        isGitRepo = true
                        refresh++
                    }
                }
            }
        )
    }

}

// ══ Shared sub-composables ════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.background(HeaderBg()).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, tint = MutedColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("$title ($count)", fontSize = 11.sp, color = MutedColor(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) Text(action, fontSize = 10.sp, color = IconColor(), modifier = Modifier.clickable { onAction() }.padding(horizontal = 4.dp))
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
        'M' -> ModifiedColor(); 'A' -> AddedColor(); 'D' -> DeletedColor()
        '?' -> UntrackedColor(); 'R', 'C' -> Color(0xFF4EC9B0); else -> MutedColor()
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
            Text(change.file, fontSize = 12.sp, color = TextColor(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (repoDir != null && change.statusCode != '?') {
                Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, tint = MutedColor(), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            if (onStage   != null) { Icon(Icons.Default.Add,   "Stage",   tint = IconColor(),    modifier = Modifier.size(15.dp).clickable { onStage() });   Spacer(Modifier.width(4.dp)) }
            if (onUnstage != null) { Icon(Icons.Default.Remove, "Unstage", tint = MutedColor(),  modifier = Modifier.size(15.dp).clickable { onUnstage() }); Spacer(Modifier.width(4.dp)) }
            if (onDiscard != null) { Icon(Icons.AutoMirrored.Filled.Undo,   "Discard", tint = DeletedColor(), modifier = Modifier.size(15.dp).clickable { onDiscard() }) }
        }
        if (expanded) {
            if (loadingDiff) {
                Box(Modifier.fillMaxWidth().padding(start = 32.dp, bottom = 4.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = IconColor())
                }
            } else {
                DiffViewer(
                    diffText = diffLines.joinToString("\n"),
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF0D0D0D))
                        .padding(start = 24.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }
        HorizontalDivider(color = DividerColor().copy(alpha = 0.5f), thickness = 0.5.dp)
    }
}


@Composable
private fun ConflictResolverRow(filePath: String, repoDir: File, context: Context, onResolved: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var conflictInfo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
            Text(filePath, fontSize = 11.sp, color = ConflictColor(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (expanded) "v" else ">", color = MutedColor(), fontSize = 10.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            LaunchedEffect(filePath) { scope.launch(Dispatchers.IO) { val f = File(repoDir, filePath); if (f.exists()) { val t = f.readText(); conflictInfo = "${t.split("<<<<<<< ").size - 1} conflict(s)" } } }
            conflictInfo?.let { Text(it, fontSize = 10.sp, color = MutedColor()); Spacer(Modifier.height(6.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { scope.launch(Dispatchers.IO) { val f = File(repoDir, filePath); if (f.exists()) { var t = f.readText(); t = Regex("(?s)<<<<<<< .*?\n(.*?)=======.*?>>>>>>> .*\n").replace(t) { m -> m.groupValues[1] }; f.writeText(t); runGit(context, repoDir, "add", filePath); onResolved() } } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC)), modifier = Modifier.weight(1f)) { Text("Ours", color = Color.White, fontSize = 10.sp) }
                Button(onClick = { scope.launch(Dispatchers.IO) { val f = File(repoDir, filePath); if (f.exists()) { var t = f.readText(); t = Regex("(?s)<<<<<<< .*?=======\n(.*?)>>>>>>> .*\n").replace(t) { m -> m.groupValues[1] }; f.writeText(t); runGit(context, repoDir, "add", filePath); onResolved() } } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD16969)), modifier = Modifier.weight(1f)) { Text("Theirs", color = Color.White, fontSize = 10.sp) }
                Button(onClick = { scope.launch(Dispatchers.IO) { val f = File(repoDir, filePath); if (f.exists()) { var t = f.readText(); t = t.replace(Regex("(?m)^<<<<<<< .*\n"), "").replace(Regex("(?m)^=======$"), "").replace(Regex("(?m)^>>>>>>> .*\n"), ""); f.writeText(t); runGit(context, repoDir, "add", filePath); onResolved() } } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4EC9B0)), modifier = Modifier.weight(1f)) { Text("Both", color = Color.Black, fontSize = 10.sp) }
            }
        }
    }
}


// ── P43: GitHub Sign-in Dialog (OAuth Device Flow UI) ──────────────────────────
@Composable
private fun GitHubSignInDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("idle") }
    var deviceCode by remember { mutableStateOf<com.codespace.ide.data.GitHubAuth.DeviceCode?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (phase != "polling") onDismiss()
        },
        title = { Text("Sign in to GitHub", fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (phase) {
                    "idle" -> {
                        Text("Sign in with GitHub to clone your repos, push, and pull.", fontSize = 12.sp, color = Color(0xFF717171))
                        Spacer(Modifier.height(8.dp))
                        Text("You'll get a short code to enter at github.com/login/device.", fontSize = 11.sp, color = Color(0xFF999999))
                    }
                    "code", "polling" -> {
                        deviceCode?.let { dc ->
                            Text("Enter this code at:", fontSize = 11.sp, color = Color(0xFF717171))
                            Spacer(Modifier.height(4.dp))
                            Text(dc.verificationUri, fontSize = 13.sp, color = Color(0xFF007ACC), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Your code:", fontSize = 11.sp, color = Color(0xFF717171))
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF0F0F0))
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    dc.userCode,
                                    fontSize = 24.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 3.sp,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            if (phase == "polling") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Waiting for approval...", fontSize = 11.sp, color = Color(0xFF717171))
                                }
                            }
                        }
                    }
                    "done" -> {
                        Text("✓ Signed in successfully!", fontSize = 12.sp, color = Color(0xFF73C991))
                    }
                    "error" -> {
                        Text(errorMsg ?: "Sign-in failed.", fontSize = 12.sp, color = Color(0xFFF48771))
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (phase) {
                "idle" -> {
                    TextButton(onClick = {
                        scope.launch {
                            phase = "code"
                            try {
                                val dc = com.codespace.ide.data.GitHubAuth.requestDeviceCode()
                                deviceCode = dc
                                phase = "polling"
                                val token = com.codespace.ide.data.GitHubAuth.pollForToken(dc)
                                val username = com.codespace.ide.data.GitHubAuth.fetchUsername(token)
                                val store = com.codespace.ide.data.SecureTokenStore(context)
                                store.githubToken = token
                                store.githubUsername = username
                                phase = "done"
                                onSuccess()
                                onDismiss()
                            } catch (e: Exception) {
                                errorMsg = e.message
                                phase = "error"
                            }
                        }
                    }) { Text("Get Code") }
                }
                "error" -> {
                    TextButton(onClick = { phase = "idle"; errorMsg = null }) { Text("Retry") }
                }
                "done" -> {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                else -> {} // polling — no button, just wait
            }
        },
        dismissButton = {
            if (phase != "polling") {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ── P43: GitHub Repo Browser Dialog ─────────────────────────────────────────────
@Composable
private fun GitHubRepoBrowserDialog(
    repos: List<com.codespace.ide.data.GitHubAuth.RepoInfo>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onClone: (String) -> Unit,
) {
    val filtered = remember(repos, searchQuery) {
        if (searchQuery.isBlank()) repos
        else repos.filter { it.name.contains(searchQuery, ignoreCase = true) || it.fullName.contains(searchQuery, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Cloud, null, tint = Color(0xFF24292F), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Your Repositories", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${filtered.size}", fontSize = 11.sp, color = Color(0xFF717171))
                }
                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search repos...", fontSize = 12.sp) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFFE0E0E0))
                // Repo list
                LazyColumn(Modifier.fillMaxSize().weight(1f)) {
                    items(filtered) { repo ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onClone(repo.cloneUrl) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Public,
                                null,
                                tint = if (repo.isPrivate) Color(0xFFD29922) else Color(0xFF73C991),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(repo.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                repo.description?.let { desc ->
                                    Text(desc, fontSize = 10.sp, color = Color(0xFF999999), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            if (repo.stars > 0) {
                                Text("★ ${repo.stars}", fontSize = 10.sp, color = Color(0xFF999999))
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(Icons.Default.Download, null, tint = Color(0xFF007ACC), modifier = Modifier.size(16.dp))
                        }
                        HorizontalDivider(color = Color(0xFFF0F0F0), thickness = 0.5.dp)
                    }
                }
                // Footer
                HorizontalDivider(color = Color(0xFFE0E0E0))
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}
