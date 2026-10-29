package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.agent.AgentWriteJournal
import com.codespace.ide.chat.ui.ChatTokens

/**
 * The Files Changed row (owner spec section 5).
 *
 * It represents ACTUAL file changes the agent made - an entry exists only after a write
 * returned - so the approval card (work not yet authorized) and this row (work already done)
 * can never be confused with each other.
 *
 * Collapsed by default: one line with the count, the +/- totals and the two actions, because
 * conversation space is the scarce resource here. Expanding shows each path and its diff.
 * Diff lines carry a leading '-' or '+' as well as colour, so the direction of a change
 * survives a colour-blind reader or a washed-out screen.
 */
@Composable
internal fun ChatFilesChangedRow() {
    val sets = AgentWriteJournal.sets
    if (sets.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
        for (set in sets.toList()) {
            key(set.id) {
                if (set.settled) {
                    // Only a set that needs explaining is kept on screen: a conflict the user
                    // must know about. A clean keep/undo just disappears.
                    if (set.note != null) {
                        SettledNote(set = set, onDismiss = { AgentWriteJournal.dismiss(set.id) })
                    }
                } else {
                    ChangeSetCard(set = set)
                }
            }
        }
    }
}

@Composable
private fun ChangeSetCard(set: AgentWriteJournal.ChangeSet) {
    val c = ChatTokens.LocalChatColors.current
    var expanded by remember(set.id) { mutableStateOf(false) }

    val totals = remember(set) {
        var add = 0
        var rem = 0
        for (e in set.entries) {
            val d = AgentWriteJournal.diff(e.before, e.after)
            add += d.added
            rem += d.removed
        }
        add to rem
    }
    val fileCount = set.entries.size

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .border(1.dp, c.border, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { expanded = !expanded },
            )
            Spacer(Modifier.width(4.dp))
            Text(
                fileCount.toString() + " file" + (if (fileCount == 1) "" else "s") + " changed",
                color = c.text,
                fontSize = ChatTokens.FONT_SMALL.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            Spacer(Modifier.width(6.dp))
            // Neutral, sign-prefixed counts: readable without relying on the colour.
            Text(
                "+" + totals.first + " \u2212" + totals.second,
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            RowAction(
                label = "Keep",
                filled = true,
                color = c.accent,
                textColor = Color(0xFFFFFFFF),
                onClick = { AgentWriteJournal.keep(set.id) },
            )
            Spacer(Modifier.width(4.dp))
            RowAction(
                label = "Undo",
                filled = false,
                color = c.border,
                textColor = c.text,
                onClick = { AgentWriteJournal.undo(set.id) },
            )
        }
        if (expanded) {
            Spacer(Modifier.height(2.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
            ) {
                for (entry in set.entries) {
                    FileEntryBlock(entry)
                }
            }
        }
    }
}

@Composable
private fun FileEntryBlock(entry: AgentWriteJournal.Entry) {
    val c = ChatTokens.LocalChatColors.current
    val diff = remember(entry) { AgentWriteJournal.diff(entry.before, entry.after) }
    Column(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (entry.kind == AgentWriteJournal.Kind.CREATED) "new" else "modified",
                color = if (entry.kind == AgentWriteJournal.Kind.CREATED) DIFF_ADD else c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                entry.path,
                color = c.text,
                fontSize = ChatTokens.FONT_MICRO.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                AgentWriteJournal.summaryLabel(entry),
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                maxLines = 1,
            )
        }
        for (line in diff.lines) {
            val marker = when (line.kind) {
                AgentWriteJournal.DiffKind.ADDED -> "+"
                AgentWriteJournal.DiffKind.REMOVED -> "-"
                AgentWriteJournal.DiffKind.CONTEXT -> " "
            }
            val tint = when (line.kind) {
                AgentWriteJournal.DiffKind.ADDED -> DIFF_ADD
                AgentWriteJournal.DiffKind.REMOVED -> DIFF_REMOVE
                AgentWriteJournal.DiffKind.CONTEXT -> c.textSecondary
            }
            Text(
                marker + " " + line.text,
                color = tint,
                fontSize = ChatTokens.FONT_MICRO.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun SettledNote(set: AgentWriteJournal.ChangeSet, onDismiss: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            set.note ?: "",
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "\u2715",
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
            modifier = Modifier.clickable { onDismiss() },
        )
    }
}

@Composable
private fun RowAction(
    label: String,
    filled: Boolean,
    color: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(if (filled) color else color.copy(alpha = 0.35f), RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, fontSize = ChatTokens.FONT_MICRO.sp, maxLines = 1)
    }
}

// VS Code's diff colours. Not yet part of chat-tokens.json (the palette carries surfaces and
// the accent only), so they are named here rather than inlined, and diff rows also carry a
// +/- marker so meaning never depends on these.
private val DIFF_ADD = Color(0xFF89D185)
private val DIFF_REMOVE = Color(0xFFF48771)
