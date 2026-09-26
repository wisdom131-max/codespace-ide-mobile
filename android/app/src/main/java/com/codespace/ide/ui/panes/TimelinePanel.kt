package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
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
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.util.VersionHistoryV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TlBgColor   = Color(0xFF1E1E1E)
private val TlHeaderBg  = Color(0xFF252526)
private val TlMuted     = Color(0xFF858585)
private val TlText      = Color(0xFFD4D4D4)
private val TlDivider   = Color(0xFF2D2D30)
private val TlIcon      = Color(0xFF569CD6)

private data class TimelineEntry(
    val hash: String,
    val author: String,
    val relativeDate: String,
    val message: String,
) {
    val shortHash: String get() = hash.take(7)
}

/**
 * P42: Timeline panel — shows git log for the currently active file.
 * Rendered as a nested section inside ExplorerPane (not a top-level panel).
 */
@Composable
fun TimelinePanel(
    filePath: String,
    projectDir: File?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snapScope = rememberCoroutineScope()
    // LAYER-1 KEYED STATE (2026-09-19, audit F5 layer 1, Wisdom-approved): these four
    // were plain remember — a file switch left the previous file's commits AND its
    // Restore buttons visible through the refill window (a stale Restore could copy
    // the previous file's snapshot into the NEW file). Keyed on (filePath, projectDir):
    // the keys change in the SAME composition frame as the panel params, so the state
    // resets instantly — no one-frame stale window. Same-file operations (Restore,
    // appliedTick refreshes) keep both keys identical, so a Timeline viewed for the
    // SAME file is never wiped. Layer 2 (tap-time guard) is BLOCKED on the
    // .versionhistory name-only collision — see VERSIONHISTORY_NAMING_PLAN.md.
    var entries by remember(filePath, projectDir) { mutableStateOf<List<TimelineEntry>>(emptyList()) }
    var loading by remember(filePath, projectDir) { mutableStateOf(false) }
    var isGitRepo by remember(filePath, projectDir) { mutableStateOf(false) }
    // I1: local .versionhistory snapshots (checkpoints incl. AI pre-apply backups)
    var snapshots by remember(filePath, projectDir) { mutableStateOf<List<File>>(emptyList()) }
    // V2 (plan v3): legacy name-only snapshots — view-only, NEVER restorable
    var legacySnapshots by remember(filePath, projectDir) { mutableStateOf<List<File>>(emptyList()) }
    // SG08 (P4g): guest workdir for tap-time actions (kept from the load pass) and
    // the commit-diff dialog target (title → parsed diff).
    var guestRoot by remember(filePath, projectDir) { mutableStateOf("") }
    var commitDiff by remember(filePath, projectDir) { mutableStateOf<Pair<String, com.codespace.ide.scm.ScmFileDiff>?>(null) }

    LaunchedEffect(filePath, projectDir) {
        if (filePath.isBlank() || projectDir == null) {
            snapshots = emptyList()
            legacySnapshots = emptyList()
        } else withContext(Dispatchers.IO) {
            // V2 (plan v3): restorable snapshots come ONLY from the canonical
            // rel-path dir; legacy name-only dirs are view-only preview material.
            snapshots = VersionHistoryV2.listSnapshots(VersionHistoryV2.v2DirFor(projectDir, filePath))
            legacySnapshots = VersionHistoryV2.listSnapshots(VersionHistoryV2.legacyDirFor(projectDir, File(filePath).name))
        }
    }

    LaunchedEffect(filePath, projectDir) {
        if (filePath.isBlank() || projectDir == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        withContext(Dispatchers.IO) {
            // SG08 (P4g): repo detection now ASKS GIT (rev-parse, guest-side) —
            // the old File(projectDir, ".git").exists() host check disagreed with
            // GitService (which supports parent-dir repos), so a project checked
            // out inside an outer repo showed "No timeline available" while
            // git log worked in the terminal.
            val guestPath = ProotInstaller.hostToGuestPath(context, projectDir.absolutePath) ?: ""
            val insideRepo = guestPath.isNotBlank() &&
                com.codespace.ide.scm.GitCommandExecutor.run(
                    context, listOf("rev-parse", "--is-inside-work-tree"), guestPath, timeoutSeconds = 10L
                ) is com.codespace.ide.scm.GitResult.Ok
            if (!insideRepo) {
                isGitRepo = false
                guestRoot = ""
                entries = emptyList()
                loading = false
                return@withContext
            }
            isGitRepo = true
            guestRoot = guestPath
            // SG08 (P4g): the basename fallback could query ANOTHER file's
            // history (a same-named file elsewhere in the repo — LS06 family).
            // Honest containment instead: outside the project root → no rows.
            val relPath = try {
                val rel = File(filePath).canonicalFile.relativeTo(projectDir.canonicalFile).path
                if (rel.startsWith("..")) "" else rel
            } catch (_: Exception) { "" }
            // P-SCM-10: Use GitCommandExecutor for git log (centralized safe.directory)
            val result = com.codespace.ide.scm.GitCommandExecutor.run(
                context, listOf("log", "--follow", "--format=%H|%an|%ar|%s", "-50", "--", relPath),
                guestPath, timeoutSeconds = 15L
            )
            entries = if (result is com.codespace.ide.scm.GitResult.Err) {
                emptyList()
            } else {
                (result as com.codespace.ide.scm.GitResult.Ok).lines.mapNotNull { line ->
                    val parts = line.split("|", limit = 4)
                    if (parts.size < 4) return@mapNotNull null
                    TimelineEntry(
                        hash = parts[0].trim(),
                        author = parts[1].trim(),
                        relativeDate = parts[2].trim(),
                        message = parts[3].trim(),
                    )
                }
            }
            loading = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TlIcon)
            }
        } else if (!isGitRepo) {
            if (snapshots.isEmpty() && legacySnapshots.isEmpty()) {
                Text(
                    "No timeline available.",
                    fontSize = 11.sp, color = TlMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                if (snapshots.isNotEmpty()) LocalSnapshotsSection(snapshots, filePath, projectDir, snapScope)
                LegacySnapshotSection(legacySnapshots)
            }
        } else if (filePath.isBlank()) {
            Text(
                "Open a file to see its timeline.",
                fontSize = 11.sp, color = TlMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else if (entries.isEmpty()) {
            Text(
                "No commits for ${filePath.substringAfterLast('/')}.",
                fontSize = 11.sp, color = TlMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(entries) { entry ->
                    // SG08 (P4g): the git rows were completely INERT (no tap
                    // action) — a tap now shows what this commit changed in
                    // THIS file (DiffViewerDialog, same renderer the SCM pane
                    // uses; git show <hash> -- <path>).
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable {
                                val gd = guestRoot
                                val rootDir = projectDir ?: return@clickable
                                if (gd.isBlank()) return@clickable
                                scope.launch(Dispatchers.IO) {
                                    val rel = try {
                                        val r = File(filePath).canonicalFile.relativeTo(rootDir.canonicalFile).path
                                        if (r.startsWith("..")) "" else r
                                    } catch (_: Exception) { "" }
                                    if (rel.isBlank()) return@launch
                                    val d = com.codespace.ide.scm.GitService(context).showFileAtCommit(entry.hash, rel, gd)
                                    commitDiff = ("${'$'}{entry.shortHash} ${'$'}{entry.message}") to d
                                }
                            },
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            entry.shortHash,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TlIcon,
                            modifier = Modifier.width(56.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.message,
                                fontSize = 11.sp,
                                color = TlText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${entry.author} • ${entry.relativeDate}",
                                fontSize = 9.sp,
                                color = TlMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = TlDivider, thickness = 0.5.dp)
                }
                if (snapshots.isNotEmpty()) {
                    item { LocalSnapshotsSection(snapshots, filePath, projectDir, snapScope) }
                }
                if (legacySnapshots.isNotEmpty()) {
                    item { LegacySnapshotSection(legacySnapshots) }
                }
            }
        }
    }

    // SG08 (P4g): tap target — commit diff for this file, rendered by the same
    // DiffViewerDialog the SCM pane uses.
    commitDiff?.let { (label, d) ->
        DiffViewerDialog(
            filePath = label,
            diff = d,
            onDismiss = { commitDiff = null },
        )
    }
}


/**
 * I1 — CHECKPOINT TIMELINE (VS Code chatEditingCheckpointTimeline analog): lists
 * the file's .versionhistory snapshots — both the Explorer 20s loop captures and
 * the AI pre-apply checkpoints ("_prechat.bak") written by PendingChangesStore.
 * Restore = copy back over the file + drop any staged overlay for it + bump the
 * store so open editors refresh through externalContentSync.
 */
@Composable
private fun LocalSnapshotsSection(
    snapshots: List<File>,
    filePath: String,
    projectDir: File?,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    // SG03 (P4g): restore is now a CONFIRMED, reversible action. The old tap
    // overwrote the working file instantly — no preview, no confirmation, and
    // every edit since the last capture was silently destroyed (no snapshot of
    // the CURRENT content was taken; VS Code's default timeline action opens a
    // diff and restore is a separate explicit command).
    var confirmTarget by remember { mutableStateOf<File?>(null) }

    fun doRestore(snap: File) {
        scope.launch(Dispatchers.IO) {
            // REQUIRED assertion (plan v3 cond. 1): the snapshot
            // must live under .versionhistory/v2/<rel of THIS file>/
            // — anything else (legacy, another file's dir) = do nothing.
            if (!VersionHistoryV2.isSnapshotOf(projectDir, filePath, snap)) return@launch
            // SG03 (P4g): safety capture FIRST — the CURRENT content becomes the
            // newest restorable entry, so the confirmed overwrite can be undone
            // from this same list.
            VersionHistoryV2.captureSnapshot(projectDir, filePath)
            // SG02 (P1): copy FIRST — the old order discarded the staged
            // overlay BEFORE the copy, so a failed copy destroyed the
            // overlay AND kept disk stale AND stayed silent. The overlay is
            // now dropped only after the restore is verified; failures
            // surface as an ERROR notification instead of a swallow.
            try {
                snap.copyTo(File(filePath), overwrite = true)
                com.codespace.ide.chat.PendingChangesStore.discard(filePath)
                com.codespace.ide.chat.PendingChangesStore.bumpExternalRestore(filePath)  // G02: record the restored path so the open tab refreshes
                com.codespace.ide.data.NotificationStore.add(
                    "Restore", "Restored ${File(filePath).name} ✓",
                    com.codespace.ide.data.NotificationStore.Severity.SUCCESS,
                    com.codespace.ide.data.NotificationStore.Source.WORKSPACE,
                    priority = com.codespace.ide.data.NotificationStore.Priority.LOW)
            } catch (e: Exception) {
                com.codespace.ide.data.NotificationStore.add(
                    "Restore failed", "Could not restore ${File(filePath).name}: ${e.message} — file unchanged",
                    com.codespace.ide.data.NotificationStore.Severity.ERROR,
                    com.codespace.ide.data.NotificationStore.Source.WORKSPACE,
                    priority = com.codespace.ide.data.NotificationStore.Priority.HIGH)
            }
        }
    }

    confirmTarget?.let { snap ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmTarget = null },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            title = { Text("Restore snapshot?", fontSize = 13.sp, color = TlText) },
            text = {
                Text(
                    "Restoring replaces the current content of ${File(filePath).name} with this snapshot.\n\nA snapshot of the CURRENT content is saved first, so this restore can be undone from the same list.",
                    fontSize = 11.sp, color = TlText,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    doRestore(snap)
                    confirmTarget = null
                }) { Text("Restore", fontSize = 12.sp, color = TlIcon) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmTarget = null }) { Text("Cancel", fontSize = 12.sp, color = TlMuted) }
            },
        )
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "Local snapshots",
            fontSize = 11.sp, color = TlIcon,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        snapshots.forEach { snap ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (snap.name.endsWith("_prechat.bak")) "\u25cf " else "") + snap.name,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (snap.name.endsWith("_prechat.bak")) TlIcon else TlText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "\u2022 " + (snap.length() / 1024) + " KB \u00b7 " +
                            java.text.SimpleDateFormat("MMM d, HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date(snap.lastModified())),
                        fontSize = 9.sp,
                        color = TlMuted,
                        maxLines = 1,
                    )
                }
                Text(
                    "Restore",
                    fontSize = 10.sp,
                    color = TlIcon,
                    modifier = Modifier
                        .background(TlHeaderBg, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        // SG03 (P4g): tap ARMS the confirmation dialog (assertion +
                        // safety capture + verified copy run in doRestore on confirm).
                        .clickable { confirmTarget = snap }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            HorizontalDivider(color = TlDivider, thickness = 0.5.dp)
        }
    }
}
