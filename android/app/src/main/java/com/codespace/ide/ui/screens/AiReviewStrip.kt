package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.PendingChangesStore
import com.codespace.ide.editor.DiffStatus
import com.codespace.ide.editor.GitDiffAnalyzer

/**
 * I1 — IN-EDITOR REVIEW EXPERIENCE (2026-09-13, VS Code chatEditingEditorOverlay
 * analog): while a file has AI-staged (pending) edits, the editor shows a compact
 * review strip above the content — status badge, change stats, and three actions:
 * Review (inline line-diff dialog), Apply to disk, Discard. Changed-region gutter
 * marks ride the existing CodeEditor gutter via aiReviewAffectedLines().
 *
 * The strip observes PendingChangesStore.revision, so staging/applying/discarding
 * anywhere (agent tool, diff card, strip itself) recomposes it immediately.
 */
@Composable
internal fun AiReviewStrip(path: String) {
    // Subscribe to store changes (stage/apply/discard bump revision).
    val revision = PendingChangesStore.revision.value
    val pending = remember(path, revision) {
        val session = PendingChangesStore.activeSessionId ?: "default"
        PendingChangesStore.pendingFor(session).firstOrNull { it.path == path }
    }
    if (pending == null) return

    var reviewOpen by remember { mutableStateOf(false) }
    var resultNote by remember { mutableStateOf<String?>(null) }

    val stagedLines = remember(pending) { pending.newContent.lines() }
    val baseLines = remember(pending) { pending.baseContent.lines() }
    val diff = remember(pending) { GitDiffAnalyzer.diff(stagedLines, baseLines) }
    val adds = diff.lineStatus.count { it == DiffStatus.ADDED || it == DiffStatus.MODIFIED }
    val dels = diff.deletedBeforeLines.size

    val statusColor = when (pending.status) {
        PendingChangesStore.Status.PENDING -> Color(0xFFE5C07B)
        PendingChangesStore.Status.DRIFT -> Color(0xFFE06C75)
        PendingChangesStore.Status.BLOCKED -> Color(0xFFE06C75)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E1E1E),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (pending.status) {
                            PendingChangesStore.Status.PENDING -> "\u25cf AI edits pending"
                            PendingChangesStore.Status.DRIFT -> "\u25cf DRIFT \u2014 file changed on disk"
                            PendingChangesStore.Status.BLOCKED -> "\u25cf BLOCKED \u2014 apply unavailable"
                        },
                        color = statusColor,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "+" + adds + " \u2212" + dels + " lines \u00b7 staged " + relTime(pending.stagedAt),
                        color = Color(0xFF9DA0A6),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            if (resultNote != null) {
                Text(
                    resultNote ?: "",
                    color = Color(0xFF9DA0A6),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                OutlinedButton(
                    onClick = { reviewOpen = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("Review", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { resultNote = applyOutcomeText(PendingChangesStore.apply(path)) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("Apply to disk", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        PendingChangesStore.discard(path)
                        resultNote = null
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("Discard", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        }
    }

    if (reviewOpen) {
        AlertDialog(
            onDismissRequest = { reviewOpen = false },
            confirmButton = {
                Button(onClick = {
                    resultNote = applyOutcomeText(PendingChangesStore.apply(path))
                    reviewOpen = false
                }) { Text("Apply to disk") }
            },
            dismissButton = {
                TextButton(onClick = {
                    PendingChangesStore.discard(path)
                    reviewOpen = false
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            title = { Text("AI edits \u2014 " + (path.substringAfterLast('/')), fontSize = 14.sp) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    itemsIndexed(diff.lineStatus) { i, st ->
                        val line = stagedLines.getOrNull(i) ?: ""
                        val prefix: String
                        val color: Color
                        when (st) {
                            DiffStatus.ADDED -> { prefix = "+"; color = Color(0xFF4EC9B0) }
                            DiffStatus.MODIFIED -> { prefix = "~"; color = Color(0xFFE5C07B) }
                            DiffStatus.DELETED_BEFORE -> { prefix = "-"; color = Color(0xFFE06C75) }
                            DiffStatus.UNCHANGED -> { prefix = " "; color = Color(0xFF9DA0A6) }
                        }
                        Text(
                            prefix + line,
                            color = color,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
        )
    }
}

private fun applyOutcomeText(outcome: PendingChangesStore.ApplyOutcome): String = when (outcome) {
    is PendingChangesStore.ApplyOutcome.Applied -> "Applied to disk \u2713"
    is PendingChangesStore.ApplyOutcome.Drift -> "Drift detected \u2014 re-opened for review"
    is PendingChangesStore.ApplyOutcome.Blocked -> "Blocked: " + outcome.reason
    is PendingChangesStore.ApplyOutcome.NotFound -> "File not found"
    is PendingChangesStore.ApplyOutcome.Failed -> "Failed: " + outcome.message
}

private fun relTime(then: Long): String {
    val s = (System.currentTimeMillis() - then) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> (s / 60).toString() + "m ago"
        else -> (s / 3600).toString() + "h ago"
    }
}

/**
 * I1 gutter support: the set of CURRENT-BUFFER line numbers (1-based) inside the
 * region the staged proposal touches. Prefix/suffix trim between buffer and staged
 * text — robust for whole-file and mid-file AI edits, and never maps a staged line
 * onto a buffer line it does not correspond to.
 */
internal fun aiReviewAffectedLines(bufferText: String, stagedText: String): Set<Int> {
    val a = bufferText.lines()
    val b = stagedText.lines()
    if (a == b) return emptySet()
    var p = 0
    while (p < a.size && p < b.size && a[p] == b[p]) p++
    var s = 0
    while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
    val from = p + 1
    val to = a.size - s
    return if (to >= from) (from..to).toSet() else emptySet()
}
