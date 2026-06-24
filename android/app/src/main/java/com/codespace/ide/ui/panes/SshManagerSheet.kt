package com.codespace.ide.ui.panes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.terminal.SshProfile
import com.codespace.ide.terminal.SshProfileStore

/**
 * Bottom-sheet SSH Manager — ported from NewTermux SshManagerActivity.
 * Shows saved SSH profiles; tap to connect, long-press for edit/delete.
 * Emits [onConnect] with the ssh command string to run in a new terminal tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshManagerSheet(
    onDismiss: () -> Unit,
    onConnect: (label: String, command: String) -> Unit
) {
    val ctx = LocalContext.current
    var profiles by remember { mutableStateOf(SshProfileStore.load(ctx)) }
    var showAddEdit by remember { mutableStateOf<SshProfile?>(null) }   // null = hidden, non-null = editing
    var addNew    by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SshProfile?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E)
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SSH Manager", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { addNew = true; showAddEdit = SshProfile() }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Color(0xFF89B4FA))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (profiles.isEmpty()) {
                Text("No SSH profiles yet.\nTap + to add one.", color = Color(0xFF888888), fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth())
            } else {
                LazyColumn {
                    items(profiles, key = { it.id }) { profile ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .clickable {
                                    onConnect(profile.nickname.ifEmpty { profile.displayLabel() }, profile.buildCommand())
                                    onDismiss()
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(profile.nickname.ifEmpty { profile.displayLabel() }, color = Color.White, fontWeight = FontWeight.Medium)
                                    Text(profile.displayLabel(), color = Color(0xFF888888), fontSize = 12.sp)
                                    profile.tunnelLabel()?.let { Text("Tunnel: $it", color = Color(0xFF89B4FA), fontSize = 11.sp) }
                                }
                                Row {
                                    IconButton(onClick = { showAddEdit = profile; addNew = false }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { confirmDelete = profile }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add/Edit dialog
    showAddEdit?.let { editing ->
        SshProfileDialog(
            initial = editing,
            isNew = addNew,
            onSave = { updated ->
                val list = profiles.toMutableList()
                val idx = list.indexOfFirst { it.id == updated.id }
                if (idx >= 0) list[idx] = updated else list.add(updated)
                SshProfileStore.save(ctx, list)
                profiles = SshProfileStore.load(ctx)
                showAddEdit = null; addNew = false
            },
            onDismiss = { showAddEdit = null; addNew = false }
        )
    }

    // Confirm delete
    confirmDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete Profile") },
            text = { Text("Delete \"${profile.nickname.ifEmpty { profile.displayLabel() }}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    val list = profiles.toMutableList().also { it.removeAll { p -> p.id == profile.id } }
                    SshProfileStore.save(ctx, list)
                    profiles = SshProfileStore.load(ctx)
                    confirmDelete = null
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SshProfileDialog(initial: SshProfile, isNew: Boolean, onSave: (SshProfile) -> Unit, onDismiss: () -> Unit) {
    var nickname by remember { mutableStateOf(initial.nickname) }
    var host     by remember { mutableStateOf(initial.host) }
    var port     by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var keyPath  by remember { mutableStateOf(initial.keyPath) }
    var tunnel   by remember { mutableStateOf(initial.tunnelEnabled) }
    var tunnelType   by remember { mutableStateOf(initial.tunnelType) }
    var tunnelLPort  by remember { mutableStateOf(initial.tunnelLocalPort.toString()) }
    var tunnelRHost  by remember { mutableStateOf(initial.tunnelRemoteHost) }
    var tunnelRPort  by remember { mutableStateOf(initial.tunnelRemotePort.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add SSH Profile" else "Edit Profile") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = nickname, onValueChange = { nickname = it }, label = { Text("Nickname (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host / IP *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username *") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.width(80.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(value = keyPath, onValueChange = { keyPath = it }, label = { Text("Key path (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                        OutlinedTextField(value = tunnelLPort, onValueChange = { tunnelLPort = it }, label = { Text("Local port") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = tunnelRHost, onValueChange = { tunnelRHost = it }, label = { Text("Remote host") }, modifier = Modifier.weight(2f), singleLine = true)
                        OutlinedTextField(value = tunnelRPort, onValueChange = { tunnelRPort = it }, label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
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
                    tunnelRemotePort = tunnelRPort.toIntOrNull() ?: 8080
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
