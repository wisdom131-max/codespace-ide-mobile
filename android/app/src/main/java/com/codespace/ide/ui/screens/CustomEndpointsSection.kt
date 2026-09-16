package com.codespace.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codespace.ide.chat.ChatProviderRegistry
import com.codespace.ide.chat.CustomEndpointStore
import kotlinx.coroutines.launch

/**
 * MK-RESTRUCTURE B+A (2026-09-16): Custom endpoints management — the VS Code
 * "Language Models editor" analog. Endpoints are a CRUD LIST (label + base URL),
 * each with its own model registry: manual IDs (add/remove inline, Cline-style —
 * never client-side validated) + the cached live /models list with a fetch time
 * and a Refetch action. Deleting an endpoint removes its provider from the
 * registry and clears its models. Keys live in the per-provider rows below
 * (each endpoint is its own provider: "Custom · <label>").
 *
 * UI rule: rounded corners (10dp) + padding (12dp horizontal, 10dp vertical).
 */
@Composable
internal fun CustomEndpointsSection(
    tokenStore: com.codespace.ide.data.SecureTokenStore?,
    onRegistryChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // All remember() at top (CI rule). Endpoints re-read on every tick.
    var tick by remember { mutableStateOf(0) }
    val endpoints = remember(tick) { CustomEndpointStore.list() }
    var editing by remember { mutableStateOf<CustomEndpointStore.Endpoint?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomEndpointStore.Endpoint?>(null) }
    var labelDraft by remember { mutableStateOf("") }
    var urlDraft by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var modelDraftFor by remember { mutableStateOf<String?>(null) }
    var modelDraft by remember { mutableStateOf("") }
    var fetchNote by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Custom Endpoints",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        if (endpoints.isEmpty()) {
            Text(
                "No endpoints. Each endpoint is its own provider with its own key and model list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        endpoints.forEach { ep ->
            val manual = remember(tick, ep.id) { CustomEndpointStore.manualModels(ep.id) }
            val live = remember(tick, ep.id) { CustomEndpointStore.liveModels(ep.id) }
            val fetchedAt = remember(tick, ep.id) { CustomEndpointStore.liveModelsFetchedAt(ep.id) }
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(ep.label, style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = {
                                labelDraft = ep.label; urlDraft = ep.baseUrl; urlError = null; editing = ep
                            }) { Icon(Icons.Default.Edit, null) }
                            TextButton(onClick = { deleting = ep }) { Icon(Icons.Default.Delete, null) }
                        }
                    }
                    Text(
                        ep.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Text(
                        if (live.isEmpty()) "No live models cached" else
                            live.size.toString() + " live model(s) fetched " +
                                (if (fetchedAt > 0) formatFetchTime(fetchedAt) else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    // Manual model registry — per-entry delete + inline add (MK-B)
                    if (manual.isNotEmpty()) {
                        Text("Manual models", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        manual.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(m, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 10.dp))
                                TextButton(onClick = {
                                    CustomEndpointStore.removeManualModel(ep.id, m); tick++
                                }) { Icon(Icons.Default.Delete, null) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        if (modelDraftFor == ep.id) {
                            OutlinedTextField(
                                value = modelDraft,
                                onValueChange = { modelDraft = it },
                                label = { Text("Model ID") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                shape = RoundedCornerShape(10.dp),
                                onClick = {
                                    val m = modelDraft.trim()
                                    if (m.isNotEmpty()) {
                                        CustomEndpointStore.addManualModel(ep.id, m)
                                        modelDraft = ""; modelDraftFor = null; tick++
                                        onRegistryChanged()
                                    }
                                },
                            ) { Text("Add") }
                        } else {
                            OutlinedButton(
                                shape = RoundedCornerShape(10.dp),
                                onClick = { modelDraft = ""; modelDraftFor = ep.id },
                            ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add model ID") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                shape = RoundedCornerShape(10.dp),
                                onClick = {
                                    fetchNote = "Fetching " + ep.label + " ..."
                                    val pid = CustomEndpointStore.providerIdFor(ep.id)
                                    scope.launch {
                                        try {
                                            val provider = ChatProviderRegistry.byId(pid)

                                            val key = try {
                                                com.codespace.ide.chat.ChatKeyPool.keys(tokenStore, pid).firstOrNull()?.second
                                            } catch (_: Exception) { null }
                                            if (key == null) {
                                                fetchNote = "Set the key for " + ep.label + " first (provider row below)."
                                                return@launch
                                            }
                                            val models = provider.fetchModels(key)
                                            fetchNote = ep.label + ": " + models.size.toString() +
                                                " model(s) — " + CustomEndpointStore.liveModels(ep.id).size + " live cached."
                                            tick++
                                        } catch (e: Exception) {
                                            fetchNote = ep.label + " fetch failed: " + (e.message?.take(120) ?: "no detail")
                                        }
                                    }
                                },
                            ) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Refetch") }
                        }
                    }
                }
            }
        }
        fetchNote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
        Button(
            shape = RoundedCornerShape(10.dp),
            onClick = { labelDraft = ""; urlDraft = ""; urlError = null; adding = true },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add endpoint") }
    }

    // ── Add / Edit dialog ──
    if (adding || editing != null) {
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editing != null) "Edit endpoint" else "New endpoint") },
            text = {
                Column {
                    OutlinedTextField(
                        value = labelDraft,
                        onValueChange = { labelDraft = it },
                        label = { Text("Label (e.g. HF Router)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it; urlError = null },
                        label = { Text("Base URL") },
                        singleLine = true,
                        isError = urlError != null,
                        supportingText = { Text(urlError ?: "https://host/v1 — /chat/completions is appended automatically") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(10.dp),
                    onClick = {
                        val trimmed = urlDraft.trim()
                        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                            urlError = "Endpoint must start with http:// or https://"
                            return@Button
                        }
                        if (editing != null) {
                            CustomEndpointStore.update(editing!!.id, labelDraft, trimmed)
                        } else {
                            CustomEndpointStore.add(labelDraft, trimmed)
                        }
                        adding = false; editing = null; tick++
                        onRegistryChanged()
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") }
            },
        )
    }

    // ── Delete confirm ──
    deleting?.let { ep ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete endpoint?") },
            text = { Text("'" + ep.label + "' and its model list are removed. Its key stays in the key pool until removed there.") },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(10.dp),
                    onClick = {
                        CustomEndpointStore.delete(ep.id)
                        deleting = null; tick++
                        onRegistryChanged()
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun formatFetchTime(ts: Long): String {
    return try {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ts
        String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    } catch (_: Exception) { "" }
}
