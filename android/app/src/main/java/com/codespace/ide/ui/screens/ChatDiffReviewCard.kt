package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.PendingChangesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * R6-PENDING-EDITS (decision #3/#5): the multi-diff review card.
 *
 * Rides at the end of the chat transcript while the active session has staged
 * proposals. Per-file inline diffs via the EXISTING GitDiffAnalyzer (no new
 * diff engine), gated by the diff budget (~2M LCS cell product — VS Code runs
 * their diff under maxComputationTimeMs:5000; a phone needs a cell-count
 * equivalent). Above budget: summary row only, Apply/Discard still available.
 *
 * Conflict handling mirrors the store's fail-closed rule:
 *  - DRIFT  — disk changed after staging; base auto-rebased to current disk so
 *             the diff shown IS the apply-over-current-disk preview.
 *             "Apply anyway" / "Discard" are the user's call.
 *  - BLOCKED — verification itself failed (unreadable/ambiguous). Apply is
 *             refused; only an explicit "Force apply" (manual decision) or
 *             Discard can resolve it. Never a silent write.
 */
private const val DIFF_BUDGET_CELLS = 2_000_000L
private const val MAX_DIFF_ROWS = 300

@Composable
internal fun ChatDiffReviewCard(
    pending: List<PendingChangesStore.PendingChange>,
    sessionId: String,
    colors: ChatPanelColors,
) {
    val scope = rememberCoroutineScope()
    val expanded = remember { mutableStateSetOf<String>() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.assistantBubble,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            // ── Header: count + bulk actions ─────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Proposed changes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    Text("${pending.size} file(s) pending — nothing on disk yet",
                        fontSize = 10.sp, color = colors.textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardAction("Apply All", colors.accent, Color.White) {
                        scope.launch { withContext(Dispatchers.IO) { PendingChangesStore.applyAll(sessionId) } }
                    }
                    CardAction("Discard All", colors.surface, colors.textSecondary) {
                        scope.launch { withContext(Dispatchers.IO) { PendingChangesStore.discardAll(sessionId) } }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            pending.forEach { entry ->
                PendingFileRow(entry = entry, expanded = entry.path in expanded, colors = colors,
                    onToggleExpand = {
                        if (entry.path in expanded) expanded.remove(entry.path) else expanded.add(entry.path)
                    },
                    onApply = { scope.launch { withContext(Dispatchers.IO) { PendingChangesStore.apply(entry.path) } } },
                    onForceApply = { scope.launch { withContext(Dispatchers.IO) { PendingChangesStore.forceApply(entry.path) } } },
                    onRediff = { scope.launch { withContext(Dispatchers.IO) { PendingChangesStore.rediff(entry.path) } } },
                    onDiscard = { scope.launch { withContext(Dispatchers.IO) { PendingChangesStore.discard(entry.path) } } },
                )
                Spacer(Modifier.height(6.dp))
            }

            // ── Footer: one-tap disk-level undo of the last apply batch ─────
            val lastBatch = PendingChangesStore.lastApplied.value
            if (lastBatch.isNotEmpty()) {
                CardAction("Undo last apply (${lastBatch.size} file(s))", colors.surface, colors.text) {
                    scope.launch { withContext(Dispatchers.IO) { PendingChangesStore.undoLastApply() } }
                }
            }
        }
    }
}

@Composable
private fun PendingFileRow(
    entry: PendingChangesStore.PendingChange,
    expanded: Boolean,
    colors: ChatPanelColors,
    onToggleExpand: () -> Unit,
    onApply: () -> Unit,
    onForceApply: () -> Unit,
    onRediff: () -> Unit,
    onDiscard: () -> Unit,
) {
    val baseLines = remember(entry.baseContent) { entry.baseContent.lines() }
    val newLines = remember(entry.newContent) { entry.newContent.lines() }
    val budget = baseLines.size.toLong() * newLines.size.toLong()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            Row(
                Modifier.fillMaxWidth().clickable { onToggleExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(File(entry.path).name, fontSize = 12.sp, color = colors.text, fontWeight = FontWeight.Medium)
                        if (entry.isNewFile) {
                            Spacer(Modifier.width(6.dp))
                            Tag("NEW", colors.accent)
                        }
                    }
                    Text(entry.path, fontSize = 9.sp, color = colors.textSecondary, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardAction("Apply", colors.accent, Color.White, enabled = entry.status != PendingChangesStore.Status.BLOCKED, onClick = onApply)
                    CardAction("Discard", colors.surface, colors.textSecondary, onClick = onDiscard)
                }
            }

            // ── Status messages ────────────────────────────────────────────
            if (entry.status == PendingChangesStore.Status.DRIFT) {
                Spacer(Modifier.height(4.dp))
                Text(entry.statusNote + " — the diff below now shows what Apply would do to the file as it is on disk.",
                    fontSize = 10.sp, color = Color(0xFFF59E0B))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardAction("Apply anyway", colors.accent, Color.White, onClick = onApply)
                    CardAction("Discard", colors.surface, colors.textSecondary, onClick = onDiscard)
                }
            } else if (entry.status == PendingChangesStore.Status.BLOCKED) {
                Spacer(Modifier.height(4.dp))
                Text("Blocked — could not verify the file on disk (" + entry.statusNote + "). Apply is refused; choose manually.",
                    fontSize = 10.sp, color = Color(0xFFEF4444))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardAction("Force apply", colors.accent, Color.White, onClick = onForceApply)
                    CardAction("Discard", colors.surface, colors.textSecondary, onClick = onDiscard)
                }
            }

            // ── Inline diff (budget-gated, decision #5) ─────────────────────
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                if (budget > DIFF_BUDGET_CELLS) {
                    Text("Diff too large to expand inline (${baseLines.size} × ${newLines.size} lines). Apply/Discard still available.",
                        fontSize = 10.sp, color = colors.textSecondary)
                } else {
                    InlineDiff(baseLines = baseLines, newLines = newLines, colors = colors)
                }
                if (entry.status == PendingChangesStore.Status.DRIFT) {
                    CardAction("Re-diff against disk now", colors.surface, colors.text, onClick = onRediff)
                }
            }
        }
    }
}

/** Diff rows via the existing GitDiffAnalyzer — current = staged, saved = base. */
@Composable
private fun InlineDiff(
    baseLines: List<String>,
    newLines: List<String>,
    colors: ChatPanelColors,
) {
    val diff = remember(baseLines, newLines) {
        com.codespace.ide.editor.GitDiffAnalyzer.diff(newLines, baseLines)
    }
    // Adds = current lines marked ADDED/MODIFIED; dels = base lines that are
    // no longer UNCHANGED context (removed by the edit script).
    val adds = diff.lineStatus.count {
        it == com.codespace.ide.editor.DiffStatus.ADDED || it == com.codespace.ide.editor.DiffStatus.MODIFIED
    }
    val dels = (baseLines.size - diff.lineStatus.count { it == com.codespace.ide.editor.DiffStatus.UNCHANGED }).coerceAtLeast(0)

    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("+$adds", fontSize = 10.sp, color = Color(0xFF4ADE80))
        Spacer(Modifier.width(8.dp))
        Text("-$dels", fontSize = 10.sp, color = Color(0xFFF87171))
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            newLines.indices.take(MAX_DIFF_ROWS).forEach { i ->
                when (diff.lineStatus.getOrNull(i)) {
                    com.codespace.ide.editor.DiffStatus.ADDED -> DiffRow(newLines[i], Color(0x334ADE80), colors.text)
                    com.codespace.ide.editor.DiffStatus.MODIFIED -> DiffRow(newLines[i], Color(0x33F59E0B), colors.text)
                    else -> DiffRow(newLines[i], Color.Transparent, colors.textSecondary)
                }
                if (i in diff.deletedBeforeLines) {
                    DiffRow("· · · removed line(s)", Color(0x33F87171), colors.textSecondary)
                }
            }
            if (newLines.size > MAX_DIFF_ROWS) {
                Text("… ${newLines.size - MAX_DIFF_ROWS} more line(s) not shown",
                    fontSize = 9.sp, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun DiffRow(text: String, bg: Color, textColor: Color) {
    Text(
        text,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        color = textColor,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(2.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun CardAction(
    label: String,
    bg: Color,
    textColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = if (enabled) textColor else colors_disabled(textColor),
        maxLines = 1,
        modifier = Modifier
            .widthIn(min = 34.dp)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun colors_disabled(c: Color): Color = c.copy(alpha = 0.5f)
