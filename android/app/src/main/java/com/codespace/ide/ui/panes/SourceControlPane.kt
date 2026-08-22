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
import com.codespace.ide.scm.ScmState
import com.codespace.ide.data.GitHubAuth
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.ui.sheets.RepoBrowserSheet
import com.codespace.ide.scm.ScmRepoState
import com.codespace.ide.scm.ScmFileStatus
import com.codespace.ide.scm.FileChange
import com.codespace.ide.scm.ScmOperation
import com.codespace.ide.scm.GitError
import com.codespace.ide.scm.ScmFileDiff
import com.codespace.ide.scm.ScmDiffLine
import com.codespace.ide.scm.DiffLineType
import com.codespace.ide.scm.ScmCommit
import kotlinx.coroutines.launch
import com.codespace.ide.data.NotificationStore

// ── Palette (matches ExplorerPane/ProjectShellScreen dark theme) ─────────────
private val BgColor      = Color(0xFF1E1E1E)
private val HeaderBg     = Color(0xFF252526)
private val TextColor    = Color(0xFFD4D4D4)
private val MutedColor   = Color(0xFF858585)
private val DividerColor = Color(0xFF2D2D30)
private val IconColor    = Color(0xFF007ACC)
private val ModifiedColor = Color(0xFFE2C08D)
private val UntrackedColor = Color(0xFF73C991)
private val DeletedColor   = Color(0xFFF48771)
private val AddedColor    = Color(0xFF73C991)
private val ConflictColor = Color(0xFFE51400)
private val StagedBg      = Color(0xFF2D2D30)

private const val PREFS_WORKSPACE = "workspace_prefs"
private const val KEY_WORKSPACE = "workspace_path"

private fun loadWorkspacePath(context: Context, projectId: String): String? =
    context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
        .getString("${KEY_WORKSPACE}_$projectId", null)

private fun resolveHostPath(context: Context, projectId: String): String {
    return loadWorkspacePath(context, projectId)
        ?: java.io.File(context.filesDir, "projects/$projectId").absolutePath
}

// ── Status letter colors (VS Code style) ─────────────────────────────────────
private fun statusColor(change: FileChange): Color = when (change) {
    FileChange.MODIFIED  -> ModifiedColor
    FileChange.ADDED     -> AddedColor
    FileChange.DELETED   -> DeletedColor
    FileChange.RENAMED   -> ModifiedColor
    FileChange.COPIED    -> AddedColor
    FileChange.UPDATED   -> ConflictColor
    FileChange.UNTRACKED -> UntrackedColor
    FileChange.IGNORED   -> MutedColor
    FileChange.UNMODIFIED -> MutedColor
}

private fun statusLetter(change: FileChange): String = when (change) {
    FileChange.MODIFIED  -> "M"
    FileChange.ADDED     -> "A"
    FileChange.DELETED   -> "D"
    FileChange.RENAMED   -> "R"
    FileChange.COPIED    -> "C"
    FileChange.UPDATED   -> "U"
    FileChange.UNTRACKED -> "U"  // U for untracked (VS Code uses italic U)
    FileChange.IGNORED   -> "!"
    FileChange.UNMODIFIED -> " "
}

// ── Main Composable ──────────────────────────────────────────────────────────
@Composable
fun SourceControlPane(projectId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scmState = remember { ScmState(context) }
    val hostPath = remember(projectId) { resolveHostPath(context, projectId) }

    // ── State ──
    var repoState by remember { mutableStateOf<ScmRepoState?>(null) }
    var operation by remember { mutableStateOf<ScmOperation>(ScmOperation.Idle) }
    var commitMessage by remember { mutableStateOf("") }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showTagsDialog by remember { mutableStateOf(false) }
    var showGitignoreDialog by remember { mutableStateOf(false) }
    var showGraphDialog by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }
    var showPublishDialog by remember { mutableStateOf(false) }
    var showRepoBrowser by remember { mutableStateOf(false) }
    var githubDeviceCode by remember { mutableStateOf<GitHubAuth.DeviceCode?>(null) }
    var githubSigningIn by remember { mutableStateOf(false) }
    val tokenStore = remember { SecureTokenStore(context) }
    val githubToken by remember { mutableStateOf(tokenStore.githubToken) }
    var tokenRefreshKey by remember { mutableStateOf(0) }
    val isGithubSignedIn = remember(tokenRefreshKey) { !tokenStore.githubToken.isNullOrBlank() }
    var diffFile by remember { mutableStateOf<String?>(null) }
    var diffData by remember { mutableStateOf<ScmFileDiff?>(null) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    var isRepo by remember { mutableStateOf<Boolean?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var discardTarget by remember { mutableStateOf<ScmFileStatus?>(null) }

    // ── Load status ──
    fun refresh() {
        scope.launch {
            operation = ScmOperation.Loading("Loading...")
            val state = scmState.loadStatus(hostPath)
            repoState = state
            isRepo = state != null
            operation = ScmOperation.Idle
        }
    }

    // Initial load
    LaunchedEffect(projectId) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
    ) {
        // ── Header: branch + sync buttons ──
        ScmHeader(
            repoState = repoState,
            operation = operation,
            isRepo = isRepo,
            onRefresh = { refresh() },
            onFetch = {
                scope.launch {
                    operation = ScmOperation.Fetching(repoState?.upstream ?: "origin")
                    val (ok, msg) = scmState.fetch(hostPath)
                    snackbarMsg = msg
                    operation = if (ok) ScmOperation.Idle else ScmOperation.Error(GitError.Unknown(msg))
                    if (ok) refresh()
                }
            },
            onPush = {
                scope.launch {
                    operation = ScmOperation.Pushing(repoState?.upstream ?: "origin")
                    val (ok, msg) = scmState.push(hostPath)
                    snackbarMsg = msg
                    operation = if (ok) ScmOperation.Idle else ScmOperation.Error(GitError.Unknown(msg))
                    if (ok) refresh()
                }
            },
            onPull = {
                scope.launch {
                    operation = ScmOperation.Pulling(repoState?.upstream ?: "origin")
                    val (ok, msg) = scmState.pull(hostPath)
                    snackbarMsg = msg
                    operation = if (ok) ScmOperation.Idle else ScmOperation.Error(GitError.Unknown(msg))
                    if (ok) refresh()
                }
            },
            onBranchClick = { showBranchDialog = true },
            onOverflowClick = { showOverflowMenu = true },
        )

        // ── Overflow dropdown menu ──
        DropdownMenu(
            expanded = showOverflowMenu,
            onDismissRequest = { showOverflowMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Stash Changes", fontSize = 12.sp) },
                enabled = operation is ScmOperation.Idle,
                onClick = {
                    showOverflowMenu = false
                    scope.launch {
                        val (ok, msg) = scmState.stash(hostPath)
                        snackbarMsg = msg
                        if (ok) refresh()
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Pop Stash", fontSize = 12.sp) },
                enabled = operation is ScmOperation.Idle,
                onClick = {
                    showOverflowMenu = false
                    scope.launch {
                        val (ok, msg) = scmState.stashPop(hostPath)
                        snackbarMsg = msg
                        if (ok) refresh()
                    }
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Merge...", fontSize = 12.sp) },
                enabled = operation is ScmOperation.Idle,
                onClick = {
                    showOverflowMenu = false
                    showMergeDialog = true
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("History", fontSize = 12.sp) },
                onClick = {
                    showOverflowMenu = false
                    showHistory = true
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Graph", fontSize = 12.sp) },
                enabled = operation is ScmOperation.Idle,
                onClick = {
                    showOverflowMenu = false
                    showGraphDialog = true
                },
            )
            if (isGithubSignedIn) {
                DropdownMenuItem(
                    text = { Text("Publish to GitHub", fontSize = 12.sp) },
                    enabled = operation is ScmOperation.Idle,
                    onClick = {
                        showOverflowMenu = false
                        showPublishDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Browse My Repos", fontSize = 12.sp) },
                    enabled = operation is ScmOperation.Idle,
                    onClick = {
                        showOverflowMenu = false
                        showRepoBrowser = true
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Tags", fontSize = 12.sp) },
                enabled = operation is ScmOperation.Idle,
                onClick = {
                    showOverflowMenu = false
                    showTagsDialog = true
                },
            )
            DropdownMenuItem(
                text = { Text(".gitignore", fontSize = 12.sp) },
                enabled = operation is ScmOperation.Idle,
                onClick = {
                    showOverflowMenu = false
                    showGitignoreDialog = true
                },
            )
            if (repoState?.conflicted?.isNotEmpty() == true) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Abort Merge", fontSize = 12.sp, color = ConflictColor) },
                    enabled = operation is ScmOperation.Idle,
                    onClick = {
                        showOverflowMenu = false
                        scope.launch {
                            val (ok, msg) = scmState.abortMerge(hostPath)
                            snackbarMsg = msg
                            if (ok) refresh()
                        }
                    },
                )
            }
        }

        HorizontalDivider(color = DividerColor, thickness = 1.dp)

        if (isRepo == false) {
            // Not a git repo
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Not a git repository",
                        color = MutedColor,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Button(
                            onClick = {
                                scope.launch {
                                    val (ok, msg) = scmState.initRepo(hostPath)
                                    snackbarMsg = msg
                                    if (ok) refresh()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IconColor)
                        ) {
                            Text("Init Repo", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { showCloneDialog = true },
                        ) {
                            Text("Clone URL", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (isGithubSignedIn) {
                        OutlinedButton(
                            onClick = { showRepoBrowser = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = IconColor),
                        ) {
                            Text("Browse My Repos", fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                githubSigningIn = true
                                scope.launch {
                                    try {
                                        val device = GitHubAuth.requestDeviceCode()
                                        githubDeviceCode = device
                                        val tok = GitHubAuth.pollForToken(device)
                                        val user = GitHubAuth.fetchUsername(tok)
                                        tokenStore.githubToken = tok
                                        tokenStore.githubUsername = user
                                        tokenRefreshKey++
                                        snackbarMsg = "Signed in as $user"
                                    } catch (e: Exception) {
                                        snackbarMsg = "GitHub sign-in failed: ${e.message}"
                                    } finally {
                                        githubSigningIn = false
                                        githubDeviceCode = null
                                    }
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = IconColor),
                        ) {
                            Text("Sign in with GitHub", fontSize = 12.sp)
                        }
                    }
                }
            }
            return@Column
        }

        // ── Commit message input ──
        CommitInputSection(
            commitMessage = commitMessage,
            onMessageChange = { commitMessage = it },
            stagedCount = repoState?.staged?.size ?: 0,
            isBusy = operation !is ScmOperation.Idle,
            onCommit = {
                if (commitMessage.isNotBlank()) {
                    scope.launch {
                        operation = ScmOperation.Committing(commitMessage)
                        val (ok, msg) = scmState.stageAllAndCommit(hostPath, commitMessage)
                        snackbarMsg = msg
                        operation = if (ok) ScmOperation.Idle else ScmOperation.Error(GitError.Unknown(msg))
                        if (ok) {
                            commitMessage = ""
                            refresh()
                        }
                    }
                }
            },
        )

        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)

        // ── Show Hidden toggle ──
        if (isRepo == true && repoState != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showHidden) "Showing all files" else "Showing project files only",
                    color = MutedColor,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (showHidden) "Hide dotfiles" else "Show dotfiles",
                    color = IconColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { showHidden = !showHidden },
                )
            }
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
        }

        // ── File changes list ──
        if (operation is ScmOperation.Loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = IconColor
                )
            }
        } else {
            FileChangesList(
                repoState = repoState,
                showHidden = showHidden,
                onDiscard = { file -> discardTarget = file },
                onResolveConflict = { file ->
                    if (operation !is ScmOperation.Idle) {
                        snackbarMsg = "Wait for current operation to finish"
                    } else {
                        scope.launch {
                            val (ok, msg) = scmState.resolveConflict(hostPath, file)
                            snackbarMsg = msg
                            if (ok) refresh()
                        }
                    }
                },
                onShowDiff = { file ->
                    if (operation !is ScmOperation.Idle && operation !is ScmOperation.Loading) {
                        snackbarMsg = "Wait for current operation to finish"
                    } else {
                        scope.launch {
                            diffData = scmState.diffFile(hostPath, file)
                            diffFile = file
                        }
                    }
                },
                onStage = { file ->
                    scope.launch {
                        operation = ScmOperation.Staging(listOf(file))
                        val (ok, msg) = scmState.stageFiles(hostPath, listOf(file))
                        snackbarMsg = msg
                        operation = ScmOperation.Idle
                        if (ok) refresh()
                    }
                },
                onStageAll = {
                    scope.launch {
                        operation = ScmOperation.Staging(emptyList())
                        val (ok, msg) = scmState.stageAll(hostPath)
                        snackbarMsg = msg
                        operation = ScmOperation.Idle
                        if (ok) refresh()
                    }
                },
                onUnstage = { file ->
                    scope.launch {
                        operation = ScmOperation.Unstaging(listOf(file))
                        val (ok, msg) = scmState.unstageFiles(hostPath, listOf(file))
                        snackbarMsg = msg
                        operation = ScmOperation.Idle
                        if (ok) refresh()
                    }
                },
            )
        }

        // ── Discard confirmation dialog ──
        discardTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { discardTarget = null },
                shape = RoundedCornerShape(12.dp),
                title = { Text(if (target.isUntracked) "Delete file?" else "Discard changes?") },
                text = {
                    Text(
                        if (target.isUntracked)
                            "\"${target.path}\" is untracked (never committed). This will permanently delete it from disk."
                        else
                            "This will discard all uncommitted changes to \"${target.path}\" and revert it to the last commit. This cannot be undone."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val file = target
                            discardTarget = null
                            scope.launch {
                                val (ok, msg) = scmState.discardFile(hostPath, file.path, file.isUntracked)
                                snackbarMsg = msg
                                if (ok) refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(if (target.isUntracked) "Delete" else "Discard") }
                },
                dismissButton = {
                    TextButton(onClick = { discardTarget = null }) { Text("Cancel") }
                },
            )
        }

        // ── Snackbar ──
        snackbarMsg?.let { msg ->
            // Classify error type for better UX
            val isAuthError = msg.contains("Authentication failed") || msg.contains("could not read Username")
            val isNetworkError = msg.contains("Network error") || msg.contains("Could not resolve host") || msg.contains("Network is unreachable")
            val isConflictError = msg.contains("Merge conflicts") || msg.contains("CONFLICT")
            val isNotRepoError = msg.contains("Not a git repository")
            val isLockError = msg.contains("Another git process") || msg.contains(".lock")
            val isError = isAuthError || isNetworkError || isConflictError || isNotRepoError ||
                isLockError || msg.startsWith("Error") || msg.contains("failed")

            val displayMsg = when {
                isAuthError -> "$msg\n\nTip: Check your GitHub token in Settings → AI Keys."
                isNetworkError -> "$msg\n\nTip: Check your network connection."
                isConflictError -> "$msg\n\nResolve conflicts in the Conflicts section, then commit."
                isLockError -> "$msg\n\nAnother git operation is running. Wait a moment and retry."
                else -> msg
            }

            LaunchedEffect(msg) {
                // Phase N: Also push to NotificationStore for history/center
                NotificationStore.add(
                    title = if (isError) "Git Error" else "Git",
                    body = msg,
                    severity = if (isError) NotificationStore.Severity.ERROR else NotificationStore.Severity.SUCCESS,
                    source = NotificationStore.Source.GIT,
                    priority = if (isError) NotificationStore.Priority.HIGH else NotificationStore.Priority.NORMAL,
                    deduplicationKey = "scm:${msg.take(40)}",
                    errorDetails = if (isError) NotificationStore.ErrorDetails(
                        userMessage = msg,
                        technicalDetails = displayMsg,
                    ) else null,
                )
                kotlinx.coroutines.delay(if (isError) 5000 else 3000)
                snackbarMsg = null
            }
            Surface(
                color = when {
                    isError -> Color(0xFFCC3333)
                    isConflictError -> Color(0xFF8B4513)  // Brown for conflicts
                    else -> Color(0xFF2D4A22)
                },
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    displayMsg,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp),
                    maxLines = if (isError) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // ── Branch dialog ──
    if (showBranchDialog) {
        BranchSelectionDialog(
            scmState = scmState,
            hostPath = hostPath,
            onDismiss = { showBranchDialog = false },
            onDeleteBranch = { branch ->
                scope.launch {
                    val (ok, msg) = scmState.deleteBranch(hostPath, branch)
                    snackbarMsg = msg
                    if (ok) refresh()
                }
            },
            onRenameBranch = { oldName, newName ->
                scope.launch {
                    val (ok, msg) = scmState.renameBranch(hostPath, oldName, newName)
                    snackbarMsg = msg
                    if (ok) refresh()
                }
            },
            onCheckout = { branch ->
                if (operation !is ScmOperation.Idle) {
                    snackbarMsg = "Wait for current operation to finish"
                } else {
                    scope.launch {
                        operation = ScmOperation.Loading("Checking out $branch...")
                        val (ok, msg) = scmState.checkout(hostPath, branch)
                        snackbarMsg = msg
                        operation = ScmOperation.Idle
                        if (ok) refresh()
                    }
                }
                showBranchDialog = false
            },
            onCreateBranch = { name ->
                if (operation !is ScmOperation.Idle) {
                    snackbarMsg = "Wait for current operation to finish"
                } else {
                    scope.launch {
                        val (ok, msg) = scmState.createBranch(hostPath, name)
                        snackbarMsg = msg
                        if (ok) refresh()
                    }
                }
                showBranchDialog = false
            },
        )
    }

    // ── Merge dialog ──
    if (showMergeDialog) {
        MergeBranchDialog(
            scmState = scmState,
            hostPath = hostPath,
            onDismiss = { showMergeDialog = false },
            onMerge = { branch ->
                scope.launch {
                    operation = ScmOperation.Loading("Merging $branch...")
                    val (ok, msg) = scmState.merge(hostPath, branch)
                    snackbarMsg = msg
                    operation = ScmOperation.Idle
                    if (ok) refresh()
                }
                showMergeDialog = false
            },
        )
    }

    // ── History dialog ──
    if (showHistory) {
        HistoryDialog(
            scmState = scmState,
            hostPath = hostPath,
            onDismiss = { showHistory = false },
        )
    }

    // ── Diff viewer dialog ──
    if (diffFile != null) {
        DiffViewerDialog(
            filePath = diffFile!!,
            diff = diffData,
            onDismiss = {
                diffFile = null
                diffData = null
            },
        )
    }

    // ── Tags dialog ──
    if (showTagsDialog) {
        TagsDialog(
            scmState = scmState,
            hostPath = hostPath,
            onDismiss = { showTagsDialog = false },
            onResult = { msg -> snackbarMsg = msg },
            onRefresh = { refresh() },
        )
    }

    // ── .gitignore dialog ──
    if (showGitignoreDialog) {
        GitignoreDialog(
            hostPath = hostPath,
            onDismiss = { showGitignoreDialog = false },
            onResult = { msg -> snackbarMsg = msg },
            onRefresh = { refresh() },
        )
    }

    // ── Branch graph dialog ──
    if (showGraphDialog) {
        BranchGraphDialog(
            scmState = scmState,
            hostPath = hostPath,
            onDismiss = { showGraphDialog = false },
        )
    }

    // ── Clone dialog ──
    if (showCloneDialog) {
        CloneDialog(
            scmState = scmState,
            hostPath = hostPath,
            onDismiss = { showCloneDialog = false },
            onResult = { msg -> snackbarMsg = msg },
            onRefresh = { refresh() },
        )
    }

    // ── Publish to GitHub dialog ──
    if (showPublishDialog) {
        PublishDialog(
            scmState = scmState,
            hostPath = hostPath,
            token = githubToken ?: "",
            onDismiss = { showPublishDialog = false },
            onResult = { msg -> snackbarMsg = msg },
            onRefresh = { refresh() },
        )
    }

    // ── Repo browser sheet ──
    if (showRepoBrowser) {
        RepoBrowserSheet(
            onDismiss = { showRepoBrowser = false },
            onProjectCreated = { _ ->
                showRepoBrowser = false
                refresh()
            },
        )
    }

    // ── GitHub device code dialog ──
    if (githubDeviceCode != null && githubSigningIn) {
        AlertDialog(
            onDismissRequest = {
                githubSigningIn = false
                githubDeviceCode = null
            },
            title = { Text("Sign in to GitHub", fontSize = 14.sp, color = TextColor) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Enter this code at:", color = MutedColor, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "github.com/login/device",
                        color = IconColor,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Code:", color = MutedColor, fontSize = 12.sp)
                    Text(
                        githubDeviceCode!!.userCode,
                        color = TextColor,
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = IconColor)
                    Spacer(Modifier.height(4.dp))
                    Text("Waiting for approval...", color = MutedColor, fontSize = 11.sp)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    githubSigningIn = false
                    githubDeviceCode = null
                }) { Text("Cancel", fontSize = 12.sp) }
            },
        )
    }
}

// ── Header ───────────────────────────────────────────────────────────────────
@Composable
private fun ScmHeader(
    repoState: ScmRepoState?,
    operation: ScmOperation,
    isRepo: Boolean?,
    onRefresh: () -> Unit,
    onFetch: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onBranchClick: () -> Unit,
    onOverflowClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeaderBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Branch name (clickable to open branch dialog)
        if (repoState != null) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onBranchClick() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CallSplit,
                    contentDescription = "Branch",
                    tint = IconColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    repoState.branch,
                    color = TextColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Ahead/behind indicators
                if (repoState.ahead > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "↑${repoState.ahead}",
                        color = UntrackedColor,
                        fontSize = 10.sp,
                    )
                }
                if (repoState.behind > 0) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "↓${repoState.behind}",
                        color = ModifiedColor,
                        fontSize = 10.sp,
                    )
                }
            }
        } else {
            Text(
                if (isRepo == false) "No repository" else "Loading...",
                color = MutedColor,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
        }

        // Sync buttons
        val isBusy = operation !is ScmOperation.Idle && operation !is ScmOperation.Loading

        IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MutedColor, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onFetch, enabled = !isBusy && repoState != null, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.CloudDownload, contentDescription = "Fetch", tint = MutedColor, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onPull, enabled = !isBusy && repoState != null, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "Pull", tint = MutedColor, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onPush, enabled = !isBusy && repoState != null, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Push", tint = MutedColor, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onOverflowClick, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = MutedColor, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Commit Input ─────────────────────────────────────────────────────────────
@Composable
private fun CommitInputSection(
    commitMessage: String,
    onMessageChange: (String) -> Unit,
    stagedCount: Int,
    isBusy: Boolean,
    onCommit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        OutlinedTextField(
            value = commitMessage,
            onValueChange = onMessageChange,
            placeholder = { Text("Message (Ctrl+Enter to commit)", color = MutedColor, fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextColor),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = IconColor,
                unfocusedBorderColor = DividerColor,
                cursorColor = IconColor,
            ),
            shape = RoundedCornerShape(4.dp),
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onCommit,
            enabled = !isBusy && commitMessage.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = IconColor),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            Text(
                if (stagedCount > 0) "Commit ($stagedCount staged)" else "Stage All & Commit",
                fontSize = 11.sp,
            )
        }
    }
}

// ── File Changes List ────────────────────────────────────────────────────────
@Composable
private fun FileChangesList(
    repoState: ScmRepoState?,
    showHidden: Boolean,
    onDiscard: (ScmFileStatus) -> Unit,
    onResolveConflict: (String) -> Unit,
    onShowDiff: (String) -> Unit,
    onStage: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstage: (String) -> Unit,
) {
    // Filter helper: hide dotfiles and internal metadata by default
    fun isHidden(path: String): Boolean {
        val parts = path.split("/")
        return parts.any { it.startsWith(".") }
    }
    fun isVisible(path: String): Boolean = showHidden || !isHidden(path)
    val scrollState = rememberScrollState()

    if (repoState == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("Loading...", color = MutedColor, fontSize = 12.sp)
        }
        return
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // ── Conflicted files (if any) ──
        val visibleConflicted = repoState.conflicted.filter { isVisible(it.path) }
        if (visibleConflicted.isNotEmpty()) {
            SectionHeader("Conflicts (${visibleConflicted.size})", ConflictColor)
            visibleConflicted.forEach { file ->
                FileRow(
                    file = file,
                    isStaged = false,
                    isConflicted = true,
                    onStage = { onResolveConflict(file.path) },
                    onUnstage = {},
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        // ── Staged changes ──
        val visibleStaged = repoState.staged.filter { isVisible(it.path) }
        if (visibleStaged.isNotEmpty()) {
            SectionHeader("Staged Changes (${visibleStaged.size})", IconColor)
            visibleStaged.forEach { file ->
                FileRow(
                    file = file,
                    isStaged = true,
                    isConflicted = false,
                    onStage = {},
                    onUnstage = { onUnstage(file.path) },
                    onShowDiff = { onShowDiff(file.path) },
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        // ── Unstaged changes ──
        val visibleUnstaged = repoState.unstaged.filter { isVisible(it.path) }
        val visibleUntracked = repoState.untracked.filter { isVisible(it.path) }
        if (visibleUnstaged.isNotEmpty() || visibleUntracked.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Changes (${visibleUnstaged.size + visibleUntracked.size})",
                    color = MutedColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (visibleUnstaged.isNotEmpty()) {
                    Text(
                        "+ Stage All",
                        color = IconColor,
                        fontSize = 10.sp,
                        modifier = Modifier.clickable { onStageAll() },
                    )
                }
            }
            visibleUnstaged.forEach { file ->
                FileRow(
                    file = file,
                    isStaged = false,
                    isConflicted = false,
                    onStage = { onStage(file.path) },
                    onUnstage = {},
                    onShowDiff = { onShowDiff(file.path) },
                    onDiscard = { onDiscard(file) },
                )
            }
            visibleUntracked.forEach { file ->
                FileRow(
                    file = file,
                    isStaged = false,
                    isConflicted = false,
                    onStage = { onStage(file.path) },
                    onUnstage = {},
                    onDiscard = { onDiscard(file) },
                )
            }
        }

        // ── Empty state ──
        if (visibleConflicted.isEmpty() && visibleStaged.isEmpty() && visibleUnstaged.isEmpty() && visibleUntracked.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = UntrackedColor, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No uncommitted changes", color = MutedColor, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("All files are committed. See Explorer for your file tree.", color = MutedColor, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(title: String, color: Color) {
    Text(
        title,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun FileRow(
    file: ScmFileStatus,
    isStaged: Boolean,
    isConflicted: Boolean,
    onStage: () -> Unit,
    onUnstage: () -> Unit,
    onShowDiff: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isStaged) StagedBg else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (isConflicted) onStage()  // resolve conflict
                    else if (isStaged) onUnstage()
                    else onStage()
                },
                onLongClick = if (!isStaged && !isConflicted && onDiscard != null) {
                    { onDiscard() }
                } else null,
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status letter
        val letter = when {
            isConflicted -> "C"
            file.isUntracked -> "U"
            isStaged -> statusLetter(file.stagedChange)
            else -> statusLetter(file.workingChange)
        }
        val letterColor = when {
            isConflicted -> ConflictColor
            file.isUntracked -> UntrackedColor
            isStaged -> statusColor(file.stagedChange)
            else -> statusColor(file.workingChange)
        }
        Text(
            letter,
            color = letterColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(16.dp),
        )
        // File path (clickable for diff if available)
        Text(
            file.path,
            color = TextColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(if (onShowDiff != null) Modifier.clickable { onShowDiff() } else Modifier),
        )
        // Action icon
        if (isStaged) {
            Icon(
                Icons.Filled.Remove,
                contentDescription = "Unstage",
                tint = MutedColor,
                modifier = Modifier.size(14.dp).clickable { onUnstage() },
            )
        } else if (!isConflicted) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Stage",
                tint = MutedColor,
                modifier = Modifier.size(14.dp).clickable { onStage() },
            )
        }
    }
}

// ── Branch Selection Dialog ──────────────────────────────────────────────────
@Composable
private fun BranchSelectionDialog(
    scmState: ScmState,
    hostPath: String,
    onDismiss: () -> Unit,
    onCheckout: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit = {},
    onRenameBranch: (String, String) -> Unit = { _, _ -> },
) {
    var branches by remember { mutableStateOf<List<com.codespace.ide.scm.ScmBranch>>(emptyList()) }
    var newBranchName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            branches = scmState.branches(hostPath)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branches", fontSize = 14.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Create new branch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        placeholder = { Text("New branch name", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        if (newBranchName.isNotBlank()) onCreateBranch(newBranchName.trim())
                    }) {
                        Text("Create", fontSize = 11.sp, color = IconColor)
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DividerColor)
                Spacer(Modifier.height(8.dp))

                // Branch list
                branches.forEach { branch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (branch.isCurrent) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = IconColor, modifier = Modifier.size(14.dp))
                        } else {
                            Spacer(Modifier.width(14.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            branch.name,
                            color = if (branch.isCurrent) IconColor else TextColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onCheckout(branch.name) },
                        )
                        if (!branch.isCurrent) {
                            Text(
                                "✕",
                                color = Color(0xFFF48771),
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { onDeleteBranch(branch.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontSize = 12.sp) }
        },
    )
}

// ── Merge Branch Dialog ──────────────────────────────────────────────────────
@Composable
private fun MergeBranchDialog(
    scmState: ScmState,
    hostPath: String,
    onDismiss: () -> Unit,
    onMerge: (String) -> Unit,
) {
    var branches by remember { mutableStateOf<List<com.codespace.ide.scm.ScmBranch>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            branches = scmState.branches(hostPath).filter { !it.isCurrent }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge Branch", fontSize = 14.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (branches.isEmpty()) {
                    Text("No other branches available", color = MutedColor, fontSize = 12.sp)
                } else {
                    Text("Select a branch to merge into current:", color = MutedColor, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    branches.forEach { branch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onMerge(branch.name) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.CallSplit, contentDescription = null, tint = MutedColor, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                branch.name,
                                color = TextColor,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 12.sp) }
        },
    )
}

// ── Diff Viewer Dialog ──────────────────────────────────────────────────────
@Composable
private fun DiffViewerDialog(
    filePath: String,
    diff: ScmFileDiff?,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Diff: $filePath",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            if (diff == null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = IconColor)
                }
            } else if (diff.isBinary) {
                Text("Binary file — no diff available", color = MutedColor, fontSize = 12.sp)
            } else if (diff.hunks.isEmpty()) {
                Text("No changes", color = MutedColor, fontSize = 12.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(scrollState)
                ) {
                    diff.hunks.forEach { hunk ->
                        // Hunk header
                        Text(
                            "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@",
                            color = MutedColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        // Diff lines
                        hunk.lines.forEach { line ->
                            val (lineColor, prefix) = when (line.type) {
                                DiffLineType.ADDED -> Pair(UntrackedColor, "+")
                                DiffLineType.DELETED -> Pair(DeletedColor, "-")
                                DiffLineType.CONTEXT -> Pair(TextColor, " ")
                                DiffLineType.HUNK_HEADER -> Pair(MutedColor, "@")
                            }
                            Text(
                                "$prefix${line.content}",
                                color = lineColor,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontSize = 12.sp) }
        },
    )
}

// ── History Dialog ───────────────────────────────────────────────────────────
@Composable
private fun HistoryDialog(
    scmState: ScmState,
    hostPath: String,
    onDismiss: () -> Unit,
) {
    var commits by remember { mutableStateOf<List<ScmCommit>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            commits = scmState.log(hostPath, maxCount = 50)
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit History", fontSize = 14.sp) },
        text = {
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = IconColor)
                }
            } else if (commits.isEmpty()) {
                Text("No commits yet", color = MutedColor, fontSize = 12.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    commits.forEach { commit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            // Hash (short)
                            Text(
                                commit.hash.take(7),
                                color = IconColor,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(56.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    commit.message.take(80),
                                    color = TextColor,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${commit.author} · ${commit.date}",
                                    color = MutedColor,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                )
                            }
                            if (commit.isHead) {
                                Spacer(Modifier.width(4.dp))
                                Text("HEAD", color = UntrackedColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontSize = 12.sp) }
        },
    )
}

// ── Tags Dialog ──────────────────────────────────────────────────────────────
@Composable
private fun TagsDialog(
    scmState: ScmState,
    hostPath: String,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var newTagName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            tags = scmState.tags(hostPath)
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags", fontSize = 14.sp, color = TextColor) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (loading) {
                    Text("Loading...", color = MutedColor, fontSize = 12.sp)
                } else if (tags.isEmpty()) {
                    Text("No tags yet", color = MutedColor, fontSize = 12.sp)
                } else {
                    tags.forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tag,
                                color = TextColor,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Delete",
                                color = Color(0xFFF48771),
                                fontSize = 10.sp,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        val (ok, msg) = scmState.deleteTag(hostPath, tag)
                                        onResult(msg)
                                        if (ok) {
                                            tags = scmState.tags(hostPath)
                                            onRefresh()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    label = { Text("New tag name", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextColor),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newTagName.isNotBlank()) {
                        scope.launch {
                            val (ok, msg) = scmState.createTag(hostPath, newTagName.trim())
                            onResult(msg)
                            if (ok) {
                                newTagName = ""
                                tags = scmState.tags(hostPath)
                                onRefresh()
                            }
                        }
                    }
                },
            ) { Text("Create", fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontSize = 12.sp) }
        },
    )
}

// ── .gitignore Dialog ───────────────────────────────────────────────────────
@Composable
private fun GitignoreDialog(
    hostPath: String,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            val gitignore = java.io.File(hostPath, ".gitignore")
            content = if (gitignore.exists()) gitignore.readText() else ""
            loaded = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(".gitignore", fontSize = 14.sp, color = TextColor) },
        text = {
            if (!loaded) {
                Text("Loading...", color = MutedColor, fontSize = 12.sp)
            } else {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextColor,
                    ),
                    placeholder = { Text("Add ignore patterns...", fontSize = 11.sp, color = MutedColor) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        try {
                            java.io.File(hostPath, ".gitignore").writeText(content)
                            onResult(".gitignore saved")
                            onRefresh()
                        } catch (e: Exception) {
                            onResult("Error: ${e.message}")
                        }
                    }
                },
            ) { Text("Save", fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 12.sp) }
        },
    )
}

// ── Branch Graph Dialog ──────────────────────────────────────────────────────
@Composable
private fun BranchGraphDialog(
    scmState: ScmState,
    hostPath: String,
    onDismiss: () -> Unit,
) {
    var graphText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            graphText = scmState.graphLog(hostPath, 100)
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branch Graph", fontSize = 14.sp, color = TextColor) },
        text = {
            if (loading) {
                Text("Loading...", color = MutedColor, fontSize = 12.sp)
            } else if (graphText.isBlank()) {
                Text("No commits yet", color = MutedColor, fontSize = 12.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        graphText,
                        color = TextColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontSize = 12.sp) }
        },
    )
}

// ── Clone Dialog ─────────────────────────────────────────────────────────────
@Composable
private fun CloneDialog(
    scmState: ScmState,
    hostPath: String,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var cloneUrl by remember { mutableStateOf("") }
    var destDir by remember { mutableStateOf("") }
    var cloning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!cloning) onDismiss() },
        title = { Text("Clone Repository", fontSize = 14.sp, color = TextColor) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = cloneUrl,
                    onValueChange = { cloneUrl = it },
                    label = { Text("Repository URL", fontSize = 11.sp) },
                    placeholder = { Text("https://github.com/user/repo.git", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !cloning,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextColor),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = destDir,
                    onValueChange = { destDir = it },
                    label = { Text("Destination (relative path)", fontSize = 11.sp) },
                    placeholder = { Text("my-cloned-repo", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !cloning,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextColor),
                )
                if (cloning) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = IconColor)
                        Spacer(Modifier.width(8.dp))
                        Text("Cloning...", color = MutedColor, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (cloneUrl.isNotBlank() && destDir.isNotBlank()) {
                        cloning = true
                        scope.launch {
                            val (ok, msg) = scmState.cloneRepo(hostPath, cloneUrl.trim(), destDir.trim())
                            cloning = false
                            onResult(msg)
                            if (ok) {
                                onDismiss()
                                onRefresh()
                            }
                        }
                    }
                },
                enabled = !cloning && cloneUrl.isNotBlank() && destDir.isNotBlank(),
            ) { Text("Clone", fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !cloning) { Text("Cancel", fontSize = 12.sp) }
        },
    )
}

// ── Publish to GitHub Dialog ─────────────────────────────────────────────────
@Composable
private fun PublishDialog(
    scmState: ScmState,
    hostPath: String,
    token: String,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var repoName by remember { mutableStateOf("") }
    var repoDesc by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!publishing) onDismiss() },
        title = { Text("Publish to GitHub", fontSize = 14.sp, color = TextColor) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = repoName,
                    onValueChange = { repoName = it },
                    label = { Text("Repository name", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !publishing,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextColor),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = repoDesc,
                    onValueChange = { repoDesc = it },
                    label = { Text("Description (optional)", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !publishing,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextColor),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = isPrivate,
                        onCheckedChange = { isPrivate = it },
                        enabled = !publishing,
                        modifier = Modifier.size(32.dp),
                    )
                    Text("Private repo", fontSize = 12.sp, color = TextColor)
                }
                if (publishing) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = IconColor)
                        Spacer(Modifier.width(8.dp))
                        Text("Publishing...", color = MutedColor, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (repoName.isNotBlank()) {
                        publishing = true
                        scope.launch {
                            try {
                                // 1. Create repo on GitHub
                                val cloneUrl = GitHubAuth.createRepo(token, repoName.trim(), repoDesc.trim(), isPrivate)
                                // 2. Init local repo if not already
                                val state = scmState.loadStatus(hostPath)
                                if (state == null) {
                                    scmState.initRepo(hostPath)
                                }
                                // 3. Add remote origin
                                scmState.addRemote(hostPath, "origin", cloneUrl)
                                // 4. Stage and commit if there are changes
                                val st = scmState.loadStatus(hostPath)
                                if (st != null && (st.staged.isNotEmpty() || st.unstaged.isNotEmpty())) {
                                    scmState.stageAllAndCommit(hostPath, "Initial commit")
                                }
                                // 5. Push
                                val (pushOk, pushMsg) = scmState.push(hostPath)
                                if (pushOk) {
                                    onResult("Published $repoName to GitHub!")
                                } else {
                                    onResult("Repo created but push failed: $pushMsg\nRemote added — try pushing manually.")
                                }
                                publishing = false
                                onDismiss()
                                onRefresh()
                            } catch (e: Exception) {
                                publishing = false
                                onResult("Publish failed: ${e.message}")
                            }
                        }
                    }
                },
                enabled = !publishing && repoName.isNotBlank(),
            ) { Text("Publish", fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !publishing) { Text("Cancel", fontSize = 12.sp) }
        },
    )
}
