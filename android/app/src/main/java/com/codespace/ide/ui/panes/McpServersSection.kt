package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.agent.McpClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Same palette as PackageManagerPane (its colors are file-private there).
private val McpSurface = Color(0xFF252526)
private val McpBorder = Color(0xFF3C3C3C)
private val McpMuted = Color(0xFF858585)
private val McpAccent = Color(0xFF007ACC)
private val McpGreen = Color(0xFF4EC994)
private val McpRed = Color(0xFFF44747)

/**
 * McpServersSection — config UI for EXTERNAL stdio MCP servers.
 * Called as a single line from McpPanel (64KB rule). Everything here drives
 * McpClientManager: add/remove servers, enable/disable, per-tool toggles,
 * env vars (SecureTokenStore), runtime-missing install prompt, Refresh.
 *
 * UI rules: rounded 8-12dp, padding 12dp horizontal / 10dp vertical minimum.
 */
@Composable
internal fun McpServersSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf(listOf<McpClientManager.McpServerConfig>()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addCommand by remember { mutableStateOf("") }
    var envDialogServer by remember { mutableStateOf<String?>(null) }
    var envVarName by remember { mutableStateOf("") }
    var envVarValue by remember { mutableStateOf("") }
    var missingRuntime by remember { mutableStateOf<String?>(null) }
    var runtimeInstalling by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("") }

    suspend fun reload() = withContext(Dispatchers.IO) {
        servers = McpClientManager.loadConfig(context)
    }

    suspend fun checkRuntimes() = withContext(Dispatchers.IO) {
        missingRuntime = servers
            .filter { it.enabled }
            .firstNotNullOfOrNull { cfg ->
                val rt = McpClientManager.requiredRuntime(cfg.command)
                if (rt != null && !McpClientManager.runtimePresent(context, rt)) rt else null
            }
    }

    // Initial load + runtime check; light polling only refreshes running state.
    LaunchedEffect(Unit) {
        reload()
        checkRuntimes()
        while (true) {
            delay(4000)
            // Touch state so running dots refresh without re-probing runtimes.
            servers = McpClientManager.loadConfig(context)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(McpSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "EXTERNAL MCP SERVERS",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = McpMuted,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    busy = true
                    scope.launch {
                        McpClientManager.refreshTools(context)
                        reload()
                        checkRuntimes()
                        busy = false
                        statusLine = "Refreshed"
                    }
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) { Text(if (busy) "..." else "Refresh", fontSize = 11.sp, color = McpAccent) }
            TextButton(
                onClick = { showAddDialog = true },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) { Text("Add", fontSize = 11.sp, color = McpAccent) }
        }

        if (statusLine.isNotEmpty()) {
            Text(statusLine, fontSize = 9.sp, color = McpMuted)
        }

        // Missing-runtime banner — install through the existing package-manager flow.
        val runtime = missingRuntime
        if (runtime != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x33F44747), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "$runtime is missing in the Ubuntu session",
                        fontSize = 11.sp, color = Color(0xFFF44747),
                    )
                    Text(
                        "An enabled MCP server needs it to start.",
                        fontSize = 9.sp, color = McpMuted,
                    )
                }
                if (runtimeInstalling) {
                    Text("Installing...", fontSize = 10.sp, color = McpMuted)
                } else {
                    TextButton(
                        onClick = {
                            runtimeInstalling = true
                            scope.launch(Dispatchers.IO) {
                                val result = McpClientManager.installRuntime(context, runtime)
                                runtimeInstalling = false
                                statusLine = result.lineSequence().firstOrNull()?.take(120) ?: "Install finished"
                                checkRuntimes()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text("Install", fontSize = 11.sp, color = McpAccent) }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = McpBorder, thickness = 0.5.dp)
        Spacer(Modifier.height(6.dp))

        if (servers.isEmpty()) {
            Text(
                "No external MCP servers configured. Tap Add to add one (e.g. npx -y @modelcontextprotocol/server-filesystem /root).",
                fontSize = 10.sp,
                color = McpMuted,
                lineHeight = 14.sp,
            )
        }

        for (cfg in servers) {
            McpServerRow(
                cfg = cfg,
                expanded = expanded == cfg.name,
                running = McpClientManager.isRunning(cfg.name),
                toolCount = McpClientManager.cachedToolsFor(cfg.name).size,
                onToggleEnabled = { enabled ->
                    scope.launch(Dispatchers.IO) {
                        McpClientManager.setEnabled(context, cfg.name, enabled)
                        reload()
                        checkRuntimes()
                    }
                },
                onExpand = { expanded = if (expanded == cfg.name) null else cfg.name },
                onRemove = {
                    scope.launch(Dispatchers.IO) {
                        McpClientManager.removeServer(context, cfg.name)
                        reload()
                        statusLine = "Removed ${cfg.name}"
                    }
                },
                onAddEnv = { envDialogServer = cfg.name },
                onToolToggled = { tool, disabled ->
                    scope.launch(Dispatchers.IO) {
                        McpClientManager.setToolDisabled(context, cfg.name, tool, disabled)
                        reload()
                    }
                },
            )
        }
    }

    // ── Add-server dialog ────────────────────────────────────────────────
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add MCP server") },
            shape = RoundedCornerShape(12.dp),
            containerColor = McpSurface,
            text = {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        label = { Text("Name (e.g. filesystem)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addCommand,
                        onValueChange = { addCommand = it },
                        label = { Text("Command (runs inside Ubuntu)") },
                        placeholder = { Text("npx -y @modelcontextprotocol/server-filesystem /root", fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Runs via the proot Ubuntu session. Env vars can be added after creation (stored encrypted).",
                        fontSize = 9.sp, color = McpMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val ok = McpClientManager.addServer(context, addName, addCommand)
                            reload()
                            checkRuntimes()
                            if (ok) {
                                showAddDialog = false
                                addName = ""; addCommand = ""
                                statusLine = "Server added"
                            } else {
                                statusLine = "Add failed: name blank or duplicate"
                            }
                        }
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } },
        )
    }

    // ── Add-env-var dialog (value -> SecureTokenStore, never plain config) ─
    val envServer = envDialogServer
    if (envServer != null) {
        AlertDialog(
            onDismissRequest = { envDialogServer = null },
            title = { Text("Add env var: $envServer") },
            shape = RoundedCornerShape(12.dp),
            containerColor = McpSurface,
            text = {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    OutlinedTextField(
                        value = envVarName,
                        onValueChange = { envVarName = it },
                        label = { Text("Variable name (e.g. API_KEY)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = envVarValue,
                        onValueChange = { envVarValue = it },
                        label = { Text("Value (stored encrypted)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            McpClientManager.setEnvVar(context, envServer, envVarName, envVarValue)
                            reload()
                            envDialogServer = null
                            envVarName = ""; envVarValue = ""
                            statusLine = "Env var saved (encrypted)"
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { envDialogServer = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun McpServerRow(
    cfg: McpClientManager.McpServerConfig,
    expanded: Boolean,
    running: Boolean,
    toolCount: Int,
    onToggleEnabled: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onRemove: () -> Unit,
    onAddEnv: () -> Unit,
    onToolToggled: (String, Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onExpand() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).background(
                    if (running) McpGreen else McpMuted,
                    RoundedCornerShape(4.dp),
                ),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(cfg.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                Text(
                    "${cfg.command.take(60)}${if (cfg.command.length > 60) "..." else ""}" +
                        " | tools: $toolCount" +
                        if (cfg.envKeys.isNotEmpty()) " | env: ${cfg.envKeys.size}" else "",
                    fontSize = 9.sp, color = McpMuted, fontFamily = FontFamily.Monospace,
                )
            }
            Switch(
                checked = cfg.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.height(24.dp),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Row {
                TextButton(onClick = onAddEnv, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("Add env var", fontSize = 10.sp, color = McpAccent)
                }
                TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("Remove", fontSize = 10.sp, color = McpRed)
                }
            }
            val tools = McpClientManager.cachedToolsFor(cfg.name)
            if (tools.isEmpty()) {
                Text(
                    "No tools yet — server starts on first use or Refresh.",
                    fontSize = 9.sp, color = McpMuted,
                )
            } else {
                for (tool in tools) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tool.name, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                            Text(
                                tool.description.trim().take(80) + if (tool.description.trim().length > 80) "..." else "",
                                fontSize = 8.sp, color = McpMuted,
                            )
                        }
                        Switch(
                            checked = !cfg.disabledTools.contains(tool.name),
                            onCheckedChange = { enabled -> onToolToggled(tool.name, !enabled) },
                            modifier = Modifier.height(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = McpBorder, thickness = 0.5.dp)
        }
    }
}
