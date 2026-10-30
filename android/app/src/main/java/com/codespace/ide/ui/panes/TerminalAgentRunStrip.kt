package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
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
import com.codespace.ide.agent.AgentTerminalFeed

/**
 * U6-c — the agent's command record, inside the terminal pane.
 *
 * Why this exists in this shape: agent commands run through ProotInstaller.execOnce, a
 * one-shot exec that is NOT the interactive shell. Replaying them into the live session to
 * "show" them would run them twice, so the pane shows the real record instead: the exact
 * command, its status, its duration and its real output. That is the closest native
 * equivalent to VS Code's visible terminal run, and it never fakes a live shell.
 *
 * Collapsed by default (one row) so it does not fight the composer for height; tap expands
 * the output in place. Only the most recent few runs are listed, newest last, each keyed by
 * its own id so a recomposition or pane re-open cannot duplicate a row.
 */
@Composable
internal fun TerminalAgentRunStrip() {
    val runs = AgentTerminalFeed.runs
    if (runs.isEmpty()) return
    // Expansion is per-run and survives recomposition; a NEW run starts collapsed.
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val latestFirst = runs.toList().takeLast(3).reversed()

    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) {
        for (run in latestFirst) {
            key(run.id) {
                AgentRunRow(
                    run = run,
                    expanded = expanded[run.id] == true,
                    onToggle = { expanded[run.id] = expanded[run.id] != true },
                )
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

@Composable
private fun AgentRunRow(
    run: AgentTerminalFeed.Run,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val statusColor = when (run.status) {
        AgentTerminalFeed.Status.RUNNING -> Color(0xFF3794FF)
        AgentTerminalFeed.Status.DONE ->
            if (run.exitCode != null && run.exitCode != 0) Color(0xFFCCA700) else Color(0xFF89D185)
        AgentTerminalFeed.Status.FAILED -> Color(0xFFF48771)
        AgentTerminalFeed.Status.CANCELLED -> Color(0xFFCCCCCC).copy(alpha = 0.6f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
            .clickable { onToggle() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                color = Color(0xFF858585),
                fontSize = 10.sp,
            )
            Spacer(Modifier.width(4.dp))
            // A glyph AND a word for the agent origin: never color-alone signalling.
            Text(
                "\u2699 Agent",
                color = statusColor,
                fontSize = 10.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$ " + run.command,
                color = Color(0xFFCCCCCC),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                AgentTerminalFeed.statusLabel(run) +
                    " \u00B7 " + AgentTerminalFeed.durationLabel(run.startedAtMs, run.endedAtMs),
                color = statusColor,
                fontSize = 9.sp,
                maxLines = 1,
            )
            // Owner ruling 2026-10-30: the rows carry the chevron ONLY. No X here, because a
            // close glyph next to a running command reads as "cancel", which this strip must
            // never do. AgentTerminalFeed.dismiss() is kept (and tested) so the capability is
            // still there if it is ever asked for again.
        }
        if (run.workdir != null && expanded) {
            Text(
                "in " + run.workdir,
                color = Color(0xFF858585),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded && AgentTerminalFeed.showsOutput(run)) {
            Text(
                run.output ?: "",
                color = Color(0xFFCCCCCC),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .padding(top = 3.dp),
            )
        }
    }
}
