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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * R10-A — Copilot status sheet (VS Code chatStatusEntry dashboard analog).
 * A one-glance panel: provider + model resolution, key-pool health (active
 * slot, cooling keys after 401/403 failover), custom mode, agent permission
 * level, MCP servers/tools/prompts, and the context gauge.
 * UI rule: rounded 8dp rows, 12dp horizontal / 10dp vertical padding.
 */
@Composable
internal fun ChatStatusSheet(
    onDismiss: () -> Unit,
    colors: ChatPanelColors,
    context: android.content.Context,
    tokenStore: com.codespace.ide.data.SecureTokenStore?,
    projectRootPath: String?,
    selectedModel: String,
    modeName: String,
    customModeId: String?,
    implicitCtxOn: Boolean,
    ctxUsed: Int?,
    ctxMax: Int?,
    onOpenSettings: (() -> Unit)?,
) {
    // Snapshot everything at open time — the sheet reflects current state,
    // it does not live-track (reopen to refresh).
    val snap = remember {
        try {
            val resolved = com.codespace.ide.chat.ChatModelSelection.resolveAuto(
                context, selectedModel, tokenStore)
            val pid = resolved.substringBefore(':')
            val provider = com.codespace.ide.chat.ChatProviderRegistry.byId(pid)
            val available = provider != null && provider.isAvailable(tokenStore)
            val isAuto = selectedModel == com.codespace.ide.chat.ChatModelSelection.AUTO_MODEL
            val keyRows = com.codespace.ide.chat.ChatProviderRegistry.all().mapNotNull { p ->
                val keys = com.codespace.ide.chat.ChatKeyPool.keys(tokenStore, p.id)
                if (keys.isEmpty()) null
                else {
                    val act = com.codespace.ide.chat.ChatKeyPool.activeSuffix(p.id)
                    val actLabel = act?.let {
                        com.codespace.ide.chat.ChatKeyPool.label(it).ifEmpty { it }
                    } ?: "first slot"
                    Triple(p.displayName, keys.size, actLabel)
                }
            }
            val cooling = com.codespace.ide.chat.ChatKeyFailover.coolingLabels()
            val level = com.codespace.ide.agent.ChatPermissionStore.level(context)
            val allowCount = com.codespace.ide.agent.ChatPermissionStore.autoApprovedTools(context).size
            val mcpTools = com.codespace.ide.agent.McpClientManager.cachedToolNames(context).size
            val mcpPrompts = com.codespace.ide.agent.McpClientManager.cachedPrompts().size
            val mcpServers = com.codespace.ide.agent.McpClientManager.enabledServerNames(context)
            val customName = customModeId?.let {
                com.codespace.ide.chat.CustomModeStore.findById(projectRootPath, it)?.name
            }
            listOfNotNull(
                resolved, provider?.displayName, available, isAuto,
                keyRows, cooling, level, allowCount,
                mcpTools, mcpPrompts, mcpServers, customName,
            )
        } catch (_: Exception) { emptyList<Any>() }
    }
    val resolved = snap.getOrNull(0) as? String ?: selectedModel
    val providerName = snap.getOrNull(1) as? String ?: "?"
    val available = snap.getOrNull(2) as? Boolean ?: false
    val isAuto = snap.getOrNull(3) as? Boolean ?: false
    @Suppress("UNCHECKED_CAST")
    val keyRows = snap.getOrNull(4) as? List<Triple<String, Int, String>> ?: emptyList()
    val cooling = snap.getOrNull(5) as? List<String> ?: emptyList()
    val level = snap.getOrNull(6) as? com.codespace.ide.agent.ChatFlowLevel
    val allowCount = snap.getOrNull(7) as? Int ?: 0
    val mcpTools = snap.getOrNull(8) as? Int ?: 0
    val mcpPrompts = snap.getOrNull(9) as? Int ?: 0
    @Suppress("UNCHECKED_CAST")
    val mcpServers = snap.getOrNull(10) as? List<String> ?: emptyList()
    val customName = snap.getOrNull(11) as? String

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
            ) {
                // ── header ──
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Copilot status",
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        Icons.Default.Close, "Close",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp).clickable { onDismiss() },
                    )
                }
                StatusRow(colors, "Provider", providerName + if (available) "" else " \u2014 NOT available")
                StatusRow(colors, "Model",
                    (if (isAuto) "Auto \u2192 " else "") + resolved.substringAfter(':', resolved))
                if (customName != null) StatusRow(colors, "Custom mode", customName)
                StatusRow(colors, "Chat mode", modeName)
                // ── key pool health ──
                if (keyRows.isNotEmpty()) {
                    keyRows.forEach { (name, count, act) ->
                        StatusRow(colors, "Keys \u00b7 " + name, count.toString() + " \u00b7 active: " + act)
                    }
                    if (cooling.isNotEmpty()) {
                        StatusRow(colors, "Cooling down", cooling.joinToString(", "))
                    }
                } else {
                    StatusRow(colors, "Keys", "none configured")
                }
                StatusRow(colors, "Agent permission",
                    (level?.name ?: "?") + " \u00b7 always-allow: " + allowCount)
                StatusRow(colors, "MCP servers",
                    mcpServers.size.toString() + " enabled \u00b7 " + mcpTools + " tools \u00b7 " + mcpPrompts + " prompts cached")
                StatusRow(colors, "Context gauge",
                    if (ctxUsed != null && ctxMax != null)
                        ctxUsed.toString() + " / " + ctxMax + " tokens"
                    else "not computed yet")
                StatusRow(colors, "Implicit workspace context", if (implicitCtxOn) "ON" else "OFF")
                // ── settings shortcut (R10-B surface) ──
                if (onOpenSettings != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(8.dp))
                            .clickable { onOpenSettings(); onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Settings, null,
                            tint = colors.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Open Settings", fontSize = 12.sp, color = colors.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(colors: ChatPanelColors, label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(colors.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = colors.textSecondary, modifier = Modifier.width(140.dp))
        Text(
            value,
            fontSize = 11.sp,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
    }
}
