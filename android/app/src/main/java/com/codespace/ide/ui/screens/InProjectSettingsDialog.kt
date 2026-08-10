package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codespace.ide.editor.FeatureToggleStore
import com.codespace.ide.editor.FlowMode
import com.codespace.ide.editor.ProjectSettingsStore

/**
 * P-FLOW: "In-Project Settings" floating page — accessible from the gear menu.
 * Dark-themed, VS Code-style settings dialog with:
 *   - AI Agent Flow section (Flow Mode dropdown + Verbose Tool Output checkbox)
 *   - Editor Features section (11 FeatureToggleStore toggles as checkbox rows)
 */
@Composable
fun InProjectSettingsDialog(onDismiss: () -> Unit) {
    val bg       = Color(0xFF1E1E1E)
    val surface  = Color(0xFF252526)
    val textPri  = Color(0xFFE0E0E0)
    val textSec  = Color(0xFF888888)
    val accent   = Color(0xFF4FC3F7)
    val divider  = Color(0xFF333333)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = bg, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxSize()) {
                // Title bar
                Row(
                    Modifier.fillMaxWidth().background(surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("In-Project Settings",
                        color = textPri, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, tint = textSec, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(color = divider)

                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    // ── AI Agent Flow ──────────────────────────────────
                    item {
                        SectionHeader("AI Agent Flow", textPri)
                    }
                    item {
                        FlowModeRow(accent, textPri, textSec, surface, divider)
                    }
                    item { HorizontalDivider(color = divider, modifier = Modifier.padding(vertical = 4.dp)) }
                    item {
                        VerboseToolOutputRow(textPri, textSec, divider)
                    }

                    item { Spacer(Modifier.height(16.dp)) }

                    // ── Editor Features ───────────────────────────────
                    item {
                        SectionHeader("Editor Features", textPri)
                    }
                    items(FeatureToggleStore.toggles.size) { index ->
                        val toggle = FeatureToggleStore.toggles[index]
                        val state = remember(toggle.key) { FeatureToggleStore.state(toggle.key) }
                        ToggleRow(
                            label = toggle.label,
                            description = toggle.description,
                            checked = state.value,
                            onCheckedChange = { state.value = it; FeatureToggleStore.set(toggle.key, it) },
                            textPri = textPri,
                            textSec = textSec,
                            divider = divider,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, color: Color) {
    Text(text,
        color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun FlowModeRow(accent: Color, textPri: Color, textSec: Color, surface: Color, divider: Color) {
    var expanded by remember { mutableStateOf(false) }
    val currentMode = ProjectSettingsStore.flowMode.value
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Flow Mode", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                if (currentMode == FlowMode.AUTO)
                    "Tool calls execute immediately (default)"
                else
                    "Each tool call pauses for your approval",
                color = textSec, fontSize = 11.sp,
            )
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(if (currentMode == FlowMode.AUTO) "Auto" else "Manual",
                    fontSize = 12.sp, color = accent)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Auto — execute immediately") },
                    onClick = {
                        ProjectSettingsStore.setFlowMode(FlowMode.AUTO)
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Manual — approve each step") },
                    onClick = {
                        ProjectSettingsStore.setFlowMode(FlowMode.MANUAL)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun VerboseToolOutputRow(textPri: Color, textSec: Color, divider: Color) {
    val verbose = ProjectSettingsStore.verboseToolOutput
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Verbose Tool Output", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Show full JSON args/results in agent chat", color = textSec, fontSize = 11.sp)
        }
        Checkbox(
            checked = verbose.value,
            onCheckedChange = { ProjectSettingsStore.setVerboseToolOutput(it) },
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4FC3F7)),
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    textPri: Color,
    textSec: Color,
    divider: Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = textSec, fontSize = 11.sp)
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4FC3F7)),
        )
    }
    HorizontalDivider(color = divider)
}
