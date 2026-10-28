package com.codespace.ide.chat.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.chat.keys.ModelListFetcher
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.data.SecureTokenStore
import kotlinx.coroutines.launch

/**
 * WP4: custom endpoints editor (any OpenAI-compatible URL: Hugging Face router,
 * Ollama on another host, LM Studio, anything). Form fields: name, base URL,
 * API key (masked, secure slot, never displayed back), model ids manual or
 * fetched via GET /models (a test NEVER sends a completion), enable/disable,
 * edit, delete. Deleting an endpoint NEVER deletes the stored key unless the
 * owner confirms it in the delete dialog. Custom headers stay PARKED
 * (security review pending). Hosted here until WP7 Settings lands.
 */
@Composable
fun CustomEndpointEditor(tokenStore: SecureTokenStore, onClose: () -> Unit) {
    val store = CustomEndpointStore
    var version by remember { mutableStateOf(store.version()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CustomEndpointStore.Endpoint?>(null) }
    var deleteTarget by remember { mutableStateOf<CustomEndpointStore.Endpoint?>(null) }

    when {
        deleteTarget != null -> DeleteEndpointDialog(
            tokenStore = tokenStore,
            ep = deleteTarget!!,
            onDone = { version = store.version(); deleteTarget = null },
        )
        adding -> EndpointFormDialog(
            tokenStore = tokenStore,
            existing = null,
            onDone = { version = store.version(); adding = false },
        )
        editing != null -> EndpointFormDialog(
            tokenStore = tokenStore,
            existing = editing,
            onDone = { version = store.version(); editing = null },
        )
        else -> EndpointListDialog(
            version = version,
            onAdd = { adding = true },
            onEdit = { editing = it },
            onDelete = { deleteTarget = it },
            onToggle = { ep, en ->
                store.setEnabled(ep.id, en)
                version = store.version()
            },
            onClose = onClose,
        )
    }
}

@Composable
private fun EndpointListDialog(
    version: Int,
    onAdd: () -> Unit,
    onEdit: (CustomEndpointStore.Endpoint) -> Unit,
    onDelete: (CustomEndpointStore.Endpoint) -> Unit,
    onToggle: (CustomEndpointStore.Endpoint, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val c = ChatTokens.LocalChatColors.current
    val endpoints = remember(version) { CustomEndpointStore.list() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Custom endpoints", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (endpoints.isEmpty()) {
                    Text(
                        "No custom endpoints yet. Any OpenAI-compatible URL works.",
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        color = c.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                endpoints.forEach { ep ->
                    EndpointRow(ep, onEdit, onDelete, onToggle)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd) { Text("Add endpoint") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun EndpointRow(
    ep: CustomEndpointStore.Endpoint,
    onEdit: (CustomEndpointStore.Endpoint) -> Unit,
    onDelete: (CustomEndpointStore.Endpoint) -> Unit,
    onToggle: (CustomEndpointStore.Endpoint, Boolean) -> Unit,
) {
    val c = ChatTokens.LocalChatColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            ep.label + if (!ep.enabled) "  (disabled)" else "",
            fontSize = ChatTokens.FONT_BODY.sp,
            color = if (ep.enabled) c.text else c.textSecondary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            ep.baseUrl,
            fontSize = ChatTokens.FONT_SMALL.sp,
            color = c.textSecondary,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onToggle(ep, !ep.enabled) }) {
                Text(if (ep.enabled) "Disable" else "Enable")
            }
            TextButton(onClick = { onEdit(ep) }) { Text("Edit") }
            TextButton(onClick = { onDelete(ep) }) { Text("Delete") }
        }
    }
}

@Composable
private fun EndpointFormDialog(
    tokenStore: SecureTokenStore,
    existing: CustomEndpointStore.Endpoint?,
    onDone: () -> Unit,
) {
    val store = CustomEndpointStore
    val c = ChatTokens.LocalChatColors.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyTouched by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ModelListFetcher.FetchResult?>(null) }
    var testing by remember { mutableStateOf(false) }

    val keySlotProvider = existing?.let { store.providerIdFor(it.id) } ?: ""

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "Add endpoint" else "Edit endpoint") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL (OpenAI-compatible)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; apiKeyTouched = true },
                    label = {
                        Text(if (existing == null) "API key (optional)" else "API key (leave blank to keep)")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions.Default,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = baseUrl.isNotBlank() && !testing,
                        onClick = {
                            testing = true
                            scope.launch {
                                val stored = if (keySlotProvider.isNotBlank())
                                    tokenStore.aiKey(
                                        com.codespace.ide.chat.keys.SlotNaming.customStoreKey(keySlotProvider)
                                    ) ?: "" else ""
                                val effective = if (apiKeyTouched && apiKey.isNotBlank()) apiKey else stored
                                testResult = ModelListFetcher.fetch(baseUrl, effective)
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "Testing..." else "Test connection") }
                }
                testResult?.let { r ->
                    Text(
                        ModelListFetcher.verdictOf(r),
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        color = if (r is ModelListFetcher.FetchResult.Models) c.text else c.textSecondary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    if (r is ModelListFetcher.FetchResult.Models && r.ids.isNotEmpty() && existing != null) {
                        Text(
                            "Example models: " + r.ids.take(3).joinToString(", "),
                            fontSize = ChatTokens.FONT_SMALL.sp,
                            color = c.textSecondary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        TextButton(onClick = {
                            store.setManualModels(existing.id, r.ids.joinToString("\n"))
                        }) { Text("Save " + r.ids.size + " models") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    val savedId: String? = if (existing == null) {
                        store.add(label, baseUrl)?.id
                    } else {
                        if (store.update(existing.id, label, baseUrl)) existing.id else null
                    }
                    if (savedId != null) {
                        val provider = store.providerIdFor(savedId)
                        if (apiKeyTouched) {
                            tokenStore.setAiKey(
                                com.codespace.ide.chat.keys.SlotNaming.customStoreKey(provider),
                                apiKey.ifBlank { null })
                        }
                    }
                    onDone()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteEndpointDialog(
    tokenStore: SecureTokenStore,
    ep: CustomEndpointStore.Endpoint,
    onDone: () -> Unit,
) {
    val store = CustomEndpointStore
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Delete endpoint?") },
        text = {
            Column {
                Text(ep.label, fontWeight = FontWeight.Medium)
                Text(
                    "The endpoint and its model list are removed. The stored key is " +
                        "KEPT unless you delete it too.",
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.delete(ep.id)
                onDone()
            }) { Text("Delete, keep key") }
        },
        dismissButton = {
            TextButton(onClick = {
                store.delete(ep.id)
                tokenStore.setAiKey(
                    com.codespace.ide.chat.keys.SlotNaming.customStoreKey(store.providerIdFor(ep.id)), null)
                onDone()
            }) { Text("Delete key too") }
        },
    )
}
