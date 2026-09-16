package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One custom endpoint's model group in the picker (MK-RESTRUCTURE B, 2026-09-16).
 * entries are full "providerId:model" strings, split by source for labeling.
 */
data class CustomMenuGroup(
    val providerId: String,
    val label: String,
    val live: List<String>,
    val manual: List<String>,
    val fetchedAtLabel: String,
)

/**
 * ChatModelMenuButton — chat model picker (VS Code model-picker parity).
 *
 * MK-RESTRUCTURE B (2026-09-16): custom endpoints get their own GROUP in the
 * menu — "Custom · <label>" — split into "From server (HH:MM)" (live-cached
 * entries) and "Manual" (user-typed entries, each deletable, Cline-style: type
 * any model ID, the server is the judge). Inline Add-model row + Refetch per
 * endpoint. The flat "Models" list EXCLUDES custom entries (they render grouped).
 *
 *  - Pinned (starred) models sort above the rest; pin/unpin the current
 *    model from the footer (R5).
 *  - CUSTOM-ENDPOINT-FIX: providers whose live model list FAILED show a
 *    visible warning row — never a silent placeholder entry.
 */
@Composable
internal fun ChatModelMenuButton(
    selectedModel: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    availModels: List<String>,
    errors: List<Triple<String, String, String>>,
    pinned: List<String>,
    onPick: (String) -> Unit,
    onTogglePin: () -> Unit,
    customGroups: List<CustomMenuGroup> = emptyList(),
    onDeleteManualModel: (String) -> Unit = { },
    onAddManualModel: (String, String) -> Unit = { _, _ -> },
    onRefetchCustom: () -> Unit = { },
    colors: ChatPanelColors,
) {
    val isAuto = selectedModel == com.codespace.ide.chat.ChatModelSelection.AUTO_MODEL
    val label = if (isAuto) "Auto" else selectedModel.take(12)
    // MK-B: inline "add model id" state (all remember() at top — CI rule)
    var addFor by remember { mutableStateOf<String?>(null) }
    var addText by remember { mutableStateOf("") }
    val customPrefixes = remember(customGroups) { customGroups.map { it.providerId + ":" } }
    Box {
        Text(
            label,
            color = colors.accent, fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(colors.surface, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clickable { onExpandedChange(true) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            // Auto first — VS Code's default picker entry
            DropdownMenuItem(
                text = { Text("Auto" + if (isAuto) "  ✓" else "", fontSize = 12.sp) },
                onClick = { onPick(com.codespace.ide.chat.ChatModelSelection.AUTO_MODEL); onExpandedChange(false) },
            )
            // CUSTOM-ENDPOINT-FIX: providers whose live model list FAILED get a
            // visible warning row — never a silent placeholder entry.
            errors.filter { (eid, _, _) -> availModels.none { m -> m.startsWith(eid + ":") } }
                .forEach { (_, name, reason) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "\u26A0 " + name + " — no models: " + reason,
                                fontSize = 10.sp,
                                color = colors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = { },
                        enabled = false,
                    )
                }
            if (pinned.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Pinned", fontSize = 10.sp, color = colors.textSecondary) },
                    onClick = { },
                    enabled = false,
                )
                pinned.forEach { m ->
                    DropdownMenuItem(
                        text = { Text("★ " + m, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Star, null, tint = colors.accent) },
                        onClick = { onPick(m); onExpandedChange(false) },
                    )
                }
            }
            // ── MK-B: custom endpoint groups — labeled live/manual, per-entry delete,
            // inline add, refetch (VS Code Language Models editor pattern) ──
            customGroups.forEach { g ->
                DropdownMenuItem(
                    text = { Text("Custom · " + g.label, fontSize = 10.sp, color = colors.textSecondary) },
                    onClick = { },
                    enabled = false,
                )
                if (g.live.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("From server (" + g.fetchedAtLabel + ")", fontSize = 9.sp, color = colors.textSecondary) },
                        onClick = { },
                        enabled = false,
                    )
                    g.live.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.substringAfter(':'), fontSize = 12.sp) },
                            onClick = { onPick(m); onExpandedChange(false) },
                        )
                    }
                }
                if (g.manual.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Manual", fontSize = 9.sp, color = colors.textSecondary) },
                        onClick = { },
                        enabled = false,
                    )
                    g.manual.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.substringAfter(':'), fontSize = 12.sp) },
                            trailingIcon = {
                                TextButton(onClick = { onDeleteManualModel(m) }) {
                                    Icon(Icons.Default.Delete, null, tint = colors.textSecondary)
                                }
                            },
                            onClick = { onPick(m); onExpandedChange(false) },
                        )
                    }
                }
                // Inline "Type a model ID" add row (Cline-style escape hatch)
                if (addFor == g.providerId) {
                    DropdownMenuItem(
                        text = {
                            OutlinedTextField(
                                value = addText,
                                onValueChange = { addText = it },
                                label = { Text("Model ID", fontSize = 10.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(0.7f),
                            )
                        },
                        onClick = { },
                        enabled = false,
                    )
                    DropdownMenuItem(
                        text = {
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    shape = RoundedCornerShape(10.dp),
                                    onClick = {
                                        val id = addText.trim()
                                        if (id.isNotEmpty()) onAddManualModel(g.providerId, id)
                                        addText = ""; addFor = null
                                    },
                                ) { Text("Add") }
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton(
                                    shape = RoundedCornerShape(10.dp),
                                    onClick = { addText = ""; addFor = null },
                                ) { Text("Cancel") }
                            }
                        },
                        onClick = { },
                        enabled = false,
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("＋ Add model ID…", fontSize = 11.sp, color = colors.textSecondary) },
                        onClick = { addText = ""; addFor = g.providerId },
                    )
                    DropdownMenuItem(
                        text = { Text("↻ Refetch " + g.label, fontSize = 11.sp, color = colors.textSecondary) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, tint = colors.textSecondary) },
                        onClick = { onRefetchCustom() },
                    )
                }
            }
            val rest = availModels.filter { it !in pinned && customPrefixes.none { p -> it.startsWith(p) } }
            if (rest.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Models", fontSize = 10.sp, color = colors.textSecondary) },
                    onClick = { },
                    enabled = false,
                )
                rest.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m, fontSize = 12.sp) },
                        onClick = { onPick(m); onExpandedChange(false) },
                    )
                }
            }
            DropdownMenuItem(
                text = {
                    Text(
                        if (selectedModel in pinned) "Unpin current model" else "Pin current model",
                        fontSize = 12.sp,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Star,
                        null,
                        tint = if (selectedModel in pinned) colors.accent else colors.textSecondary,
                    )
                },
                onClick = { onTogglePin(); onExpandedChange(false) },
            )
        }
    }
}
