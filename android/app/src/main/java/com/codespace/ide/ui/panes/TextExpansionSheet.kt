package com.codespace.ide.ui.panes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.terminal.TextExpansion
import com.codespace.ide.terminal.TextExpansionStore

/**
 * Bottom-sheet for managing text expansions (shortcuts).
 * e.g. ;ll → ls -la   ;gs → git status
 * Ported from NewTermux TextExpansionStore + settings UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextExpansionSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // Rotation fix (#8): key on orientation so the AlertDialog below gets a fresh,
    // correctly-sized window on rotate (the ModalBottomSheet itself already resizes fine).
    val orientation = LocalConfiguration.current.orientation
    var expansions by remember { mutableStateOf(TextExpansionStore.load(ctx).toMutableList()) }
    var showAdd by remember { mutableStateOf(false) }
    var newTrigger by remember { mutableStateOf("") }
    var newExpansion by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E1E)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Text Expansions", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Type trigger + Space/Tab to expand", color = Color(0xFF888888), fontSize = 11.sp)
                }
                IconButton(onClick = { showAdd = true; newTrigger = ""; newExpansion = "" }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Color(0xFF89B4FA))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (expansions.isEmpty()) {
                Text("No expansions yet.\nExample: trigger \";gs\" → \"git status\"", color = Color(0xFF888888), fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn {
                    items(expansions, key = { it.trigger }) { ex ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(ex.trigger, color = Color(0xFF89B4FA), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(80.dp))
                                Text("→", color = Color(0xFF888888), modifier = Modifier.padding(horizontal = 8.dp))
                                Text(ex.expansion, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    val updated = expansions.toMutableList().also { it.removeAll { e -> e.trigger == ex.trigger } }
                                    TextExpansionStore.save(ctx, updated)
                                    expansions = TextExpansionStore.load(ctx).toMutableList()
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        key(orientation) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Expansion") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newTrigger, onValueChange = { newTrigger = it }, label = { Text("Trigger (e.g. ;gs)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newExpansion, onValueChange = { newExpansion = it }, label = { Text("Expansion (e.g. git status)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTrigger.isNotBlank() && newExpansion.isNotBlank()) {
                        val updated = expansions.toMutableList()
                        updated.removeAll { it.trigger == newTrigger.trim() }
                        updated.add(TextExpansion(newTrigger.trim(), newExpansion.trim()))
                        TextExpansionStore.save(ctx, updated)
                        expansions = TextExpansionStore.load(ctx).toMutableList()
                        showAdd = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
        }
    }
}
