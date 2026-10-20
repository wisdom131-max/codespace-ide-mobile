package com.codespace.ide.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.codespace.ide.lsp.LspManager
import com.codespace.ide.lsp.LspStatusRow

/**
 * P1e (advisor 2026-10-20): READ-ONLY "Language servers" status section in
 * Settings. One row per configured language: state, project, uptime since the
 * last start, restart count, last stop reason — all from LspManager.statusRows()
 * (the [LSP-LIFECYCLE] state that already exists). NO BEHAVIOR CHANGE: nothing
 * here starts, stops, or retries anything; the section is rebuilt from a fresh
 * snapshot every time it is composed.
 */
@Composable
fun LspServersStatusSection() {
    // Fresh read-only snapshot at composition time (no polling loop — P4b's
    // per-editor poll lesson; Settings re-composes on every navigation).
    val rows = remember { LspManager.statusRows() }
    Text(
        "Language Servers",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    )
    Text(
        "Read-only status from the running app. State, project, uptime since the last start, restarts, and why the server last stopped.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
    rows.forEach { row -> LspStatusRowCard(row) }
}

/** MANDATORY UI RULE: rounded corners (8-12dp) + padding (12dp horiz, 10dp vert). */
@Composable
private fun LspStatusRowCard(row: LspStatusRow) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    row.languageName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    row.stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "Project: ${row.project}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "Uptime: ${row.uptimeLabel}   Restarts: ${row.restartCount}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "Last stop: ${row.lastStopReason}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
