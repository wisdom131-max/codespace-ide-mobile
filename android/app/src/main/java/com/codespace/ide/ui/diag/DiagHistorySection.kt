package com.codespace.ide.ui.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * P4.5: diagnostic history, rendered from [CopilotDiag] itself. No second list is kept, so what
 * is shown is exactly what was recorded (and already redacted). Bounded to
 * [DiagHistoryModel.MAX_ROWS] rows so a long history cannot exhaust a low-memory device.
 */
@Composable
fun DiagHistorySection() {
    var kind by remember { mutableStateOf<com.codespace.ide.agent.CopilotDiag.Kind?>(null) }
    var onlyFailures by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var showClear by remember { mutableStateOf(false) }
    // Bumped after a clear so the list re-reads the (now empty) recorder.
    var version by remember { mutableStateOf(0) }

    val rows = remember(kind, onlyFailures, version) { DiagHistoryModel.rows(kind, onlyFailures) }
    val kinds = remember(version) { DiagHistoryModel.availableKinds() }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Diagnostics", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            DiagHistoryModel.summary(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.heightIn(min = 6.dp))
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { kind = null }) { Text("All", fontSize = 12.sp) }
            TextButton(onClick = { onlyFailures = !onlyFailures }) {
                Text(if (onlyFailures) "Failures: on" else "Failures only", fontSize = 12.sp)
            }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { showClear = true }) { Text("Clear", fontSize = 12.sp) }
        }
        if (kinds.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 72.dp)) {
                items(kinds) { k ->
                    Text(
                        DiagHistoryModel.label(k),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { kind = if (kind == k) null else k }
                            .padding(vertical = 2.dp),
                        fontWeight = if (kind == k) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        if (rows.isEmpty()) {
            Text(
                if (onlyFailures || kind != null) "No events match this filter"
                else "No diagnostic events yet. Events appear as Copilot works.",
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(rows, key = { it.at.toString() + it.name + it.kind.name }) { r ->
                    val open = expanded == r.at
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (open) null else r.at }
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                DiagHistoryModel.time(r.at),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(DiagHistoryModel.label(r.kind), fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                DiagHistoryModel.outcome(r),
                                fontSize = 11.sp,
                                color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        if (open) {
                            Text(r.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            if (r.detail.isNotBlank()) {
                                Text(r.detail, fontSize = 11.sp)
                            }
                            if (r.session.isNotBlank()) {
                                Text("session " + r.session, fontSize = 10.sp)
                            }
                        }
                    }
                    Spacer(Modifier.heightIn(min = 4.dp))
                }
            }
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("Clear diagnostic history?") },
            text = { Text("This removes the recorded diagnostic events. Project files are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    DiagHistoryModel.clear()
                    version++
                    showClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("Cancel") } },
        )
    }
}
