package com.codespace.ide.ui.panes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.terminal.SshProfile
import com.codespace.ide.terminal.SshProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom-sheet SSH Manager.
 *
 * P3 fixes:
 *  - Profile list loaded in LaunchedEffect (background) instead of main-thread remember()
 *  - isNew derived from profile ID presence — addNew flag removed (was desync-prone)
 *  - SshProfileStore.save() result checked; snackbar shown on write failure
 *  - orientation var scoped only where needed (AlertDialogs)
 */
// ─────────────────────────────────────────────────────────────────────────────
// IG06 (P3d): known-hosts surface on the ONE live trust store
//
// The orphaned SSHJ+TOFU app-side stack (ssh/SshManager.kt + SshFingerprintStore,
// 264 zero-caller lines) is deleted. Trust in the live ssh-CLI path lives in the
// Ubuntu rootfs known_hosts: StrictHostKeyChecking=accept-new auto-trusts the
// FIRST connect and hard-refuses any later fingerprint MISMATCH — genuine openssh
// TOFU. What was missing was visibility: this section reads/clears that store via
// ssh-keygen (-F lookup, -R forget) in the rootfs, so one trust store has one
// visible surface.
// ─────────────────────────────────────────────────────────────────────────────

private data class KnownHostInfo(val trusted: Boolean, val note: String? = null)

private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** known_hosts entry key: host for port 22, [host]:port otherwise (openssh format). */
private fun knownHostsKey(host: String, port: Int): String =
    if (port == 22) host else "[$host]:$port"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshManagerSheet(
    onDismiss: () -> Unit,
    onConnect: (label: String, command: String) -> Unit,
) {
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // P3: load profiles off main thread
    var profiles by remember { mutableStateOf<List<SshProfile>>(emptyList()) }
    var loadingProfiles by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        loadingProfiles = true
        profiles = withContext(Dispatchers.IO) { SshProfileStore.load(ctx) }
        loadingProfiles = false
    }

    // P3: showAddEdit holds the profile being edited; null = dialog hidden
    // isNew is derived: true when the profile id is not in the current list
    var showAddEdit by remember { mutableStateOf<SshProfile?>(null) }
    var confirmDelete by remember { mutableStateOf<SshProfile?>(null) }

    fun saveProfiles(updated: List<SshProfile>) {
        val result = SshProfileStore.save(ctx, updated)
        if (result.isFailure) {
            scope.launch {
                snackbarHostState.showSnackbar("Failed to save profiles: ${result.exceptionOrNull()?.message}")
            }
        }
        refreshKey++
    }

    // ── IG06: known-hosts (TOFU) state, on the live rootfs store ──
    var knownHosts by remember { mutableStateOf<Map<String, KnownHostInfo>>(emptyMap()) }
    var hostsBusy by remember { mutableStateOf(false) }

    fun refreshKnownHosts(list: List<SshProfile>) {
        if (list.isEmpty()) return
        hostsBusy = true
        scope.launch(Dispatchers.IO) {
            val out = mutableMapOf<String, KnownHostInfo>()
            for (p in list) {
                val key = shellQuote(knownHostsKey(p.host, p.port))
                val r = com.codespace.ide.terminal.ProotInstaller.execTyped(
                    ctx, "ssh-keygen -F $key", timeoutSeconds = 20,
                )
                out[p.id] = when {
                    r.launchError != null -> KnownHostInfo(false, "Ubuntu rootfs not installed")
                    r.timedOut -> KnownHostInfo(false, "check timed out")
                    r.exitCode == 0 -> KnownHostInfo(true)
                    else -> KnownHostInfo(false) // exit 1 = no entry recorded yet
                }
            }
            knownHosts = out
            hostsBusy = false
        }
    }

    fun forgetHostKey(profile: SshProfile) {
        hostsBusy = true
        scope.launch(Dispatchers.IO) {
            val key = shellQuote(knownHostsKey(profile.host, profile.port))
            val r = com.codespace.ide.terminal.ProotInstaller.execTyped(
                ctx, "ssh-keygen -R $key", timeoutSeconds = 20,
            )
            knownHosts = knownHosts + (profile.id to when {
                r.launchError != null -> KnownHostInfo(false, "Ubuntu rootfs not installed")
                r.timedOut -> KnownHostInfo(false, "forget timed out")
                else -> KnownHostInfo(false) // removed — next connect re-trusts (TOFU)
            })
            hostsBusy = false
        }
    }

    fun clearAllKnownHosts() {
        hostsBusy = true
        scope.launch(Dispatchers.IO) {
            // -R leaves .old backups; clear both so the store is truly empty.
            val r = com.codespace.ide.terminal.ProotInstaller.execTyped(
                ctx, "rm -f /root/.ssh/known_hosts /root/.ssh/known_hosts.old", timeoutSeconds = 20,
            )
            val note = when {
                r.launchError != null -> "Ubuntu rootfs not installed"
                r.timedOut -> "clear timed out"
                else -> null
            }
            knownHosts = knownHosts.mapValues { KnownHostInfo(false, note) }
            hostsBusy = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent,
        ) { padding ->
            Column(Modifier.padding(padding).padding(16.dp).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("SSH Manager", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { showAddEdit = SshProfile() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = Color(0xFF89B4FA))
                    }
                }
                Spacer(Modifier.height(8.dp))

                when {
                    loadingProfiles -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    profiles.isEmpty() -> Text(
                        "No SSH profiles yet.\nTap + to add one.",
                        color = Color(0xFF888888), fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                    )
                    else -> LazyColumn {
                        items(profiles, key = { it.id }) { profile ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        onConnect(
                                            profile.nickname.ifEmpty { profile.displayLabel() },
                                            profile.buildCommand(),
                                        )
                                        onDismiss()
                                    },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(profile.nickname.ifEmpty { profile.displayLabel() }, color = Color.White, fontWeight = FontWeight.Medium)
                                        Text(profile.displayLabel(), color = Color(0xFF888888), fontSize = 12.sp)
                                        profile.tunnelLabel()?.let { Text("Tunnel: $it", color = Color(0xFF89B4FA), fontSize = 11.sp) }
                                    }
                                    Row {
                                        IconButton(onClick = { showAddEdit = profile }) {
                                            Icon(Icons.Default.Edit, "Edit", tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(onClick = { confirmDelete = profile }) {
                                            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── IG06: Known hosts (TOFU) — the live rootfs trust store, made visible ──
                Spacer(Modifier.height(16.dp))
                KnownHostsSection(
                    profiles = profiles,
                    status = knownHosts,
                    busy = hostsBusy,
                    onCheck = { refreshKnownHosts(profiles) },
                    onForget = { forgetHostKey(it) },
                    onClearAll = { clearAllKnownHosts() },
                )
            }
        }
    }

    // Add/Edit dialog
    showAddEdit?.let { editing ->
        // P3: derive isNew from ID presence rather than a separate flag
        val isNew = profiles.none { it.id == editing.id }
        SshProfileDialog(
            initial = editing,
            isNew = isNew,
            onSave = { updated ->
                val list = profiles.toMutableList()
                val idx = list.indexOfFirst { it.id == updated.id }
                if (idx >= 0) list[idx] = updated else list.add(updated)
                saveProfiles(list)
                showAddEdit = null
            },
            onDismiss = { showAddEdit = null },
        )
    }

    // Confirm delete
    confirmDelete?.let { profile ->
        val orientation = LocalConfiguration.current.orientation
        key(orientation) {
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Delete Profile") },
                text = { Text("Delete \"${profile.nickname.ifEmpty { profile.displayLabel() }}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        val list = profiles.toMutableList().also { l -> l.removeAll { it.id == profile.id } }
                        saveProfiles(list)
                        confirmDelete = null
                    }) { Text("Delete", color = Color(0xFFFF6B6B)) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun SshProfileDialog(
    initial: SshProfile,
    isNew: Boolean,
    onSave: (SshProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val orientation = LocalConfiguration.current.orientation
    var nickname    by remember { mutableStateOf(initial.nickname) }
    var host        by remember { mutableStateOf(initial.host) }
    var port        by remember { mutableStateOf(initial.port.toString()) }
    var username    by remember { mutableStateOf(initial.username) }
    var keyPath     by remember { mutableStateOf(initial.keyPath) }
    var tunnel      by remember { mutableStateOf(initial.tunnelEnabled) }
    var tunnelType  by remember { mutableStateOf(initial.tunnelType) }
    var tunnelLPort by remember { mutableStateOf(initial.tunnelLocalPort.toString()) }
    var tunnelRHost by remember { mutableStateOf(initial.tunnelRemoteHost) }
    var tunnelRPort by remember { mutableStateOf(initial.tunnelRemotePort.toString()) }

    key(orientation) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (isNew) "Add SSH Profile" else "Edit Profile") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = nickname, onValueChange = { nickname = it },
                        label = { Text("Nickname (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = host, onValueChange = { host = it },
                        label = { Text("Host / IP *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = username, onValueChange = { username = it },
                            label = { Text("Username *") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = port, onValueChange = { port = it },
                            label = { Text("Port") }, modifier = Modifier.width(80.dp), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    OutlinedTextField(value = keyPath, onValueChange = { keyPath = it },
                        label = { Text("Key path (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = tunnel, onCheckedChange = { tunnel = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Port forward tunnel", fontSize = 13.sp)
                    }
                    if (tunnel) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("local", "remote").forEach { t ->
                                FilterChip(selected = tunnelType == t, onClick = { tunnelType = t }, label = { Text(t) })
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = tunnelLPort, onValueChange = { tunnelLPort = it },
                                label = { Text("Local port") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(value = tunnelRHost, onValueChange = { tunnelRHost = it },
                                label = { Text("Remote host") }, modifier = Modifier.weight(2f), singleLine = true)
                            OutlinedTextField(value = tunnelRPort, onValueChange = { tunnelRPort = it },
                                label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (host.isBlank() || username.isBlank()) return@TextButton
                    onSave(initial.copy(
                        nickname = nickname.trim(), host = host.trim(),
                        port = port.toIntOrNull() ?: 22, username = username.trim(),
                        keyPath = keyPath.trim(), tunnelEnabled = tunnel,
                        tunnelType = tunnelType,
                        tunnelLocalPort = tunnelLPort.toIntOrNull() ?: 8080,
                        tunnelRemoteHost = tunnelRHost.trim(),
                        tunnelRemotePort = tunnelRPort.toIntOrNull() ?: 8080,
                    ))
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KnownHostsSection(
    profiles: List<SshProfile>,
    status: Map<String, KnownHostInfo>,
    busy: Boolean,
    onCheck: () -> Unit,
    onForget: (SshProfile) -> Unit,
    onClearAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Known hosts (TOFU)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row {
                TextButton(onClick = onCheck, enabled = !busy) {
                    Text(if (busy) "Working…" else "Check", color = Color(0xFF89B4FA), fontSize = 12.sp)
                }
                TextButton(onClick = onClearAll, enabled = !busy) {
                    Text("Clear all", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }
            }
        }
        Text(
            "Trust lives in the Ubuntu rootfs known_hosts: first connect auto-trusts (openssh accept-new), a changed fingerprint is refused. Check reads the store; Forget forces a fresh trust on next connect.",
            color = Color(0xFF888888), fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        if (status.isEmpty()) {
            Text(
                "Tap Check to read the known_hosts store.",
                color = Color(0xFF888888), fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            profiles.forEach { profile ->
                val info = status[profile.id] ?: return@forEach
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(knownHostsKey(profile.host, profile.port), color = Color.White, fontSize = 13.sp)
                            Text(
                                when {
                                    info.note != null -> info.note
                                    info.trusted -> "Host key trusted (first connect recorded)"
                                    else -> "No host key recorded yet"
                                },
                                color = if (info.trusted && info.note == null) Color(0xFF4EC9B0) else Color(0xFF888888),
                                fontSize = 11.sp,
                            )
                        }
                        if (info.trusted && info.note == null) {
                            TextButton(onClick = { onForget(profile) }, enabled = !busy) {
                                Text("Forget", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
