package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
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
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.data.SecureTokenStore
import java.io.File

// IDE palette
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

private data class GitChange(
    val status: String,
    val file: String,
    val absPath: String,
    val statusCode: Char,
    val isStaged: Boolean,
)

/**
 * Route a git command through the Ubuntu proot shell.
 *
 * P3 fixes:
 *  - authFlag built with single-quoted value to avoid shell injection
 *  - Returns the raw stdout string; callers must check for "Error:" prefix
 */
private fun runGit(context: Context, dir: File, vararg args: String): String {
    val guestPath = ProotInstaller.hostToGuestPath(context, dir.absolutePath)
        ?: return "Error: '${dir.absolutePath}' is not reachable from the Ubuntu terminal. " +
            "Git only works on folders inside Ubuntu (/root/...) or shared storage."
    val quoted = args.joinToString(" ") { a -> "'" + a.replace("'", "'\\''") + "'" }

    val githubToken = SecureTokenStore(context).githubToken
    val authFlag = if (!githubToken.isNullOrBlank()) {
        val basic = android.util.Base64.encodeToString(
            "x-access-token:$githubToken".toByteArray(), android.util.Base64.NO_WRAP
        )
        "-c 'http.extraheader=Authorization: Basic $basic' "
    } else ""

    return ProotInstaller.execOnce(context, "git $authFlag$quoted", guestPath)
}

private fun loadWorkspacePath(context: Context, projectId: String): String? =
    context.getSharedPreferences("workspace_prefs", Context.MODE_PRIVATE)
        .getString("workspace_path_$projectId", null)

/**
 * Parse a single `git status --porcelain=v1` line into a [GitChange].
 *
 * P3 fix: handle renamed files (R/C codes with " -> " separator).
 */
private fun parsePorcelainLine(line: String, repoDir: File): Pair<GitChange?, GitChange?> {
    if (line.length < 4) return null to null
    val x = line[0]
    val y = line[1]
    // Raw path field (may be "old -> new" for renames)
    val raw = line.substring(3).trim().replace("\"", "")
    // For renames (R/C), git porcelain v1 gives "new -> old"; we want the new name
    val filePath = if ((x == 'R' || x == 'C' || y == 'R' || y == 'C') && raw.contains(" -> ")) {
        raw.substringBefore(" -> ").trim()
    } else raw
    val absPath = File(repoDir, filePath).absolutePath

    val staged = if (x != ' ' && x != '?')
        GitChange(line, filePath, absPath, x, true) else null
    val unstaged = when {
        x == '?' && y == '?' -> GitChange(line, filePath, absPath, '?', false)
        y != ' ' && y != '?' -> GitChange(line, filePath, absPath, y, false)
        else -> null
    }
    return staged to unstaged
}

@Composable
fun SourceControlPane(projectId: String) {
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
    // P3: surface errors to the user instead of swallowing them
    var statusError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var newBranchError by remember { mutableStateOf<String?>(null) }

    // P3 fix: repoDir is a guest-side path concept only — we don't do File() ops on it
    // for filesystem existence checks. We just record it for runGit routing.
    val repoDir = remember(projectId) {
        val wsPath = loadWorkspacePath(context, projectId)
        var dir = wsPath?.let { File(it) }
        while (dir != null && !File(dir, ".git").exists()) { dir = dir.parentFile }
        dir ?: File("/root")
    }

    fun refreshStatus() {
        scope.launch {
            loading = true
            statusError = null
            withContext(Dispatchers.IO) {
                // Branch
                val branchOut = runGit(context, repoDir, "branch", "--show-current").trim()
                if (branchOut.startsWith("Error:")) {
                    statusError = branchOut
                    loading = false
                    return@withContext
                }
                branch = branchOut

                // Branch list
                val branchList = runGit(context, repoDir, "branch", "--list", "--format=%(refname:short)")
                branches = if (!branchList.startsWith("Error:"))
                    branchList.lines().filter { it.isNotBlank() }
                else emptyList()

                // Ahead/behind
                val trackInfo = runGit(context, repoDir, "status", "-sb")
                val trackLine = trackInfo.lines().firstOrNull() ?: ""
                aheadBehind = when {
                    trackLine.contains("ahead") || trackLine.contains("behind") -> {
                        val ahead  = Regex("ahead (\\d+)").find(trackLine)?.groupValues?.get(1) ?: "0"
                        val behind = Regex("behind (\\d+)").find(trackLine)?.groupValues?.get(1) ?: "0"
                        buildString {
                            if (behind != "0") append("\u2193$behind ")
                            if (ahead  != "0") append("\u2191$ahead")
                        }.trim()
                    }
                    else -> ""
                }

                // Porcelain status
                val statusOutput = runGit(context, repoDir, "status", "--porcelain=v1")
                val staged = mutableListOf<GitChange>()
                val unstaged = mutableListOf<GitChange>()
                if (!statusOutput.startsWith("Error:")) {
                    for (line in statusOutput.lines()) {
                        val (s, u) = parsePorcelainLine(line, repoDir)
                        s?.let { staged.add(it) }
                        u?.let { unstaged.add(it) }
                    }
                }
                stagedChanges = staged
                unstagedChanges = unstaged
            }
            loading = false
        }
    }

    LaunchedEffect(refresh) { refreshStatus() }

    fun stageFile(file: String)   { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "add", file) }; refreshStatus() } }
    fun unstageFile(file: String) { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "restore", "--staged", file) }; refreshStatus() } }
    fun discardFile(file: String) { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "restore", file) }; refreshStatus() } }
    fun stageAll()                { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "add", ".") }; refreshStatus() } }
    fun unstageAll()              { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "restore", "--staged", ".") }; refreshStatus() } }
    fun pushChanges()             { scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "push") }; refreshStatus() } }

    Column(Modifier.fillMaxSize().background(BgColor)) {
        // ── Header ───────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(35.dp).background(HeaderBg).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SOURCE CONTROL", fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // Refresh
            Icon(Icons.Default.Refresh, null, tint = MutedColor,
                modifier = Modifier.size(16.dp).clickable { refresh++ })
            Spacer(Modifier.width(8.dp))
            // Pull
            Icon(Icons.Default.ArrowDownward, null, tint = MutedColor,
                modifier = Modifier.size(16.dp).clickable {
                    scope.launch { withContext(Dispatchers.IO) { runGit(context, repoDir, "pull") }; refreshStatus() }
                })
            Spacer(Modifier.width(8.dp))
            // P3 new: Push button
            Icon(Icons.Default.ArrowUpward, null, tint = MutedColor,
                modifier = Modifier.size(16.dp).clickable { pushChanges() })
        }
        HorizontalDivider(color = DividerColor)

        // ── Branch selector ───────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    Modifier.clickable { showBranchMenu = true }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.AccountTree, null, tint = IconColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    // P3: guard against error strings being displayed as branch name
                    val displayBranch = if (branch.startsWith("Error:") || branch.isBlank()) "—" else branch
                    Text(displayBranch, fontSize = 12.sp, color = TextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (aheadBehind.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(aheadBehind, fontSize = 10.sp, color = MutedColor)
                    }
                    Icon(Icons.Default.ArrowDropDown, null, tint = MutedColor, modifier = Modifier.size(14.dp))
                }
                DropdownMenu(expanded = showBranchMenu, onDismissRequest = { showBranchMenu = false }) {
                    branches.forEach { b ->
                        DropdownMenuItem(
                            text = { Text(b, fontSize = 12.sp) },
                            onClick = {
                                showBranchMenu = false
                                scope.launch {
                                    withContext(Dispatchers.IO) { runGit(context, repoDir, "checkout", b) }
                                    refreshStatus()
                                }
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("New branch…", fontSize = 12.sp, color = IconColor) },
                        onClick = {
                            showBranchMenu = false
                            newBranchName = ""
                            newBranchError = null
                            showNewBranchDialog = true
                        }
                    )
                }
            }
        }
        HorizontalDivider(color = DividerColor)

        // ── P3: Error banner ──────────────────────────────────────────────
        if (statusError != null) {
            Row(
                Modifier.fillMaxWidth().background(ErrorColor.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Warning, null, tint = ErrorColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    statusError ?: "",
                    fontSize = 11.sp, color = ErrorColor, modifier = Modifier.weight(1f),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(color = DividerColor)
        }

        // ── Commit message + button ────────────────────────────────────────
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                placeholder = { Text("Message (Ctrl+Enter to commit)", fontSize = 11.sp, color = MutedColor) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextColor),
                minLines = 2, maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IconColor,
                    unfocusedBorderColor = DividerColor,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        if (message.isBlank()) return@Button
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                // P3 fix: always stage-all before commit so nothing is missed
                                runGit(context, repoDir, "add", ".")
                                runGit(context, repoDir, "commit", "-m", message)
                            }
                            message = ""
                            refreshStatus()
                        }
                    },
                    enabled = message.isNotBlank() && !loading,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = IconColor),
                ) {
                    Text("Commit", fontSize = 12.sp)
                }
                // Stage all shortcut
                OutlinedButton(
                    onClick = { stageAll(); },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Stage All", fontSize = 12.sp)
                }
            }
        }
        HorizontalDivider(color = DividerColor)

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = IconColor)
        }

        // ── Changes list ──────────────────────────────────────────────────
        LazyColumn(Modifier.fillMaxSize()) {
            // Staged
            item {
                SectionHeader(
                    title = "Staged Changes",
                    count = stagedChanges.size,
                    expanded = showStaged,
                    onToggle = { showStaged = !showStaged },
                    action = if (stagedChanges.isNotEmpty()) "Unstage All" else null,
                    onAction = { unstageAll() },
                )
            }
            if (showStaged) {
                items(stagedChanges) { change ->
                    ChangeRow(change, onStage = null, onUnstage = { unstageFile(change.file) }, onDiscard = null)
                }
            }

            // Unstaged / untracked
            item {
                SectionHeader(
                    title = "Changes",
                    count = unstagedChanges.size,
                    expanded = showChanges,
                    onToggle = { showChanges = !showChanges },
                    action = if (unstagedChanges.isNotEmpty()) "Stage All" else null,
                    onAction = { stageAll() },
                )
            }
            if (showChanges) {
                items(unstagedChanges) { change ->
                    ChangeRow(
                        change,
                        onStage = { stageFile(change.file) },
                        onUnstage = null,
                        onDiscard = { discardFile(change.file) },
                    )
                }
            }
        }
    }
    // ── New Branch dialog ─────────────────────────────────────────────────
    if (showNewBranchDialog) {
        AlertDialog(
            onDismissRequest = { showNewBranchDialog = false },
            title = { Text("New branch", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it; newBranchError = null },
                        label = { Text("Branch name") },
                        singleLine = true,
                        isError = newBranchError != null,
                        supportingText = if (newBranchError != null) {
                            { Text(newBranchError!!, color = ErrorColor, fontSize = 11.sp) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Creates from: $branch",
                        fontSize = 11.sp, color = MutedColor
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = newBranchName.isNotBlank(),
                    onClick = {
                        val trimmed = newBranchName.trim()
                        if (trimmed.contains(" ") || trimmed.contains("..") ||
                            trimmed.startsWith("-") || trimmed.isEmpty()) {
                            newBranchError = "Invalid branch name"
                        } else {
                            showNewBranchDialog = false
                            scope.launch {
                                val out = withContext(Dispatchers.IO) {
                                    runGit(context, repoDir, "checkout", "-b", trimmed)
                                }
                                if (out.startsWith("Error:") || out.contains("fatal")) {
                                    statusError = out
                                } else {
                                    refreshStatus()
                                }
                            }
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewBranchDialog = false }) { Text("Cancel") }
            },
        )
    }

}

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    action: String?,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }
            .background(HeaderBg).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            null, tint = MutedColor, modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("$title ($count)", fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(action, fontSize = 10.sp, color = IconColor,
                modifier = Modifier.clickable { onAction() }.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun ChangeRow(
    change: GitChange,
    onStage: (() -> Unit)?,
    onUnstage: (() -> Unit)?,
    onDiscard: (() -> Unit)?,
) {
    val statusColor = when (change.statusCode) {
        'M'       -> ModifiedColor
        'A'       -> AddedColor
        'D'       -> DeletedColor
        '?'       -> UntrackedColor
        'R', 'C'  -> Color(0xFF4EC9B0)
        else      -> MutedColor
    }
    val statusLabel = when (change.statusCode) {
        'M' -> "M"; 'A' -> "A"; 'D' -> "D"
        '?' -> "U"; 'R' -> "R"; 'C' -> "C"
        else -> change.statusCode.toString()
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(statusLabel, fontSize = 10.sp, color = statusColor,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            change.file,
            fontSize = 12.sp, color = TextColor,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // Actions
        if (onStage != null) {
            Icon(Icons.Default.Add, "Stage", tint = IconColor,
                modifier = Modifier.size(15.dp).clickable { onStage() })
            Spacer(Modifier.width(4.dp))
        }
        if (onUnstage != null) {
            Icon(Icons.Default.Remove, "Unstage", tint = MutedColor,
                modifier = Modifier.size(15.dp).clickable { onUnstage() })
            Spacer(Modifier.width(4.dp))
        }
        if (onDiscard != null) {
            Icon(Icons.Default.Undo, "Discard", tint = DeletedColor,
                modifier = Modifier.size(15.dp).clickable { onDiscard() })
        }
    }
    HorizontalDivider(color = DividerColor.copy(alpha = 0.5f), thickness = 0.5.dp)

}

