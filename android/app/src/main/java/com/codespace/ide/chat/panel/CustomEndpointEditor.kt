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
fun CustomEndpointEditor(
    tokenStore: SecureTokenStore,
    onClose: () -> Unit,
    /** U1: called after a save that actually landed, so the panel can SELECT it. */
    onSaved: (providerId: String, baseUrl: String) -> Unit = { _, _ -> },
) {
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
            onSaved = onSaved,
            onDone = { version = store.version(); adding = false },
        )
        editing != null -> EndpointFormDialog(
            tokenStore = tokenStore,
            existing = editing,
            onSaved = onSaved,
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
    onSaved: (providerId: String, baseUrl: String) -> Unit,
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
    // BATCH 9 (2026-10-29 audit): THE MISSING CAPABILITY. The store always supported many
    // model ids per endpoint (manualModels/addManualModel/removeManualModel) and the picker
    // always read them - but this form had no way to author them: the only path was "Test
    // connection" on an ALREADY SAVED endpoint, which replaced the list wholesale. So a
    // custom provider could carry one endpoint, one key and, in practice, one model id.
    // The list now lives here: type an id and Add, remove any row, or fold in the fetched list.
    // P0-B (2026-10-30): a failed read must NOT look like "this endpoint has no models". The
    // store now reports the outcome, and an unreadable list disables Save: setManualModels
    // REPLACES the row, so saving an assumed-empty list would erase ids we never saw.
    val firstRead = remember(existing?.id) {
        existing?.id?.let { store.readManualModels(it) }
            ?: CustomEndpointStore.ModelRead.Loaded(emptyList())   // a NEW endpoint is really empty
    }
    var modelsUnreadable by remember(existing?.id) {
        mutableStateOf(firstRead is CustomEndpointStore.ModelRead.Unreadable)
    }
    var models by remember(existing?.id) {
        mutableStateOf(
            (firstRead as? CustomEndpointStore.ModelRead.Loaded)?.ids ?: emptyList()
        )
    }
    var newModel by remember { mutableStateOf("") }
    // P0-A: discovery results land HERE and stay here until Save. Nothing is written to the
    // live cache by a fetch, so Cancel discards them and a fetch behind a dialog cannot change
    // persisted state. On Save they are committed together with the manual list.
    var fetched by remember(existing?.id) { mutableStateOf(emptyList<String>()) }
    // U1: a write that did not land says so instead of closing on a lie.
    var saveError by remember { mutableStateOf<String?>(null) }

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
                    isError = baseUrl.isNotBlank() &&
                        !com.codespace.ide.engine.EndpointUrls.validBase(baseUrl),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Text(
                    if (baseUrl.isNotBlank() &&
                        !com.codespace.ide.engine.EndpointUrls.validBase(baseUrl))
                        "Needs http:// or https:// and a host."
                    else "Requests go to <base>/chat/completions; a full path is accepted too.",
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    color = c.textSecondary,
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
                saveError?.let { msg ->
                    Text(
                        msg,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        color = c.warning,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                Text(
                    "Models this endpoint serves",
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (modelsUnreadable) {
                    // P0-B: say what is wrong and offer a retry instead of showing "no models".
                    Text(
                        "Could not read this endpoint's saved model ids. Saving is disabled so " +
                            "they cannot be erased. Retry, or close without saving.",
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        color = c.warning,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    TextButton(onClick = {
                        val again = existing?.id?.let { store.readManualModels(it) }
                        if (again is CustomEndpointStore.ModelRead.Loaded) {
                            models = again.ids
                            modelsUnreadable = false
                            saveError = null
                        }
                    }) { Text("Retry", fontSize = ChatTokens.FONT_SMALL.sp) }
                }
                models.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            m,
                            fontSize = ChatTokens.FONT_SMALL.sp,
                            color = c.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { models = models.filter { it != m } }) {
                            Text("Remove", fontSize = ChatTokens.FONT_SMALL.sp)
                        }
                    }
                }
                if (models.isEmpty()) {
                    Text(
                        "No model ids yet. Add at least one, or fetch this endpoint's list below.",
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        color = c.textSecondary,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newModel,
                        onValueChange = { newModel = it },
                        label = { Text("Model id (exactly as the server names it)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newModel.isNotBlank(),
                        onClick = {
                            val id = newModel.trim()
                            if (id.isNotEmpty()) models = (models + id).distinct()
                            newModel = ""
                        },
                    ) { Text("Add") }
                }
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
                                val res = ModelListFetcher.fetch(baseUrl, effective)
                                testResult = res
                                // A FAILED fetch writes nothing at all: no manual id, no live id,
                                // no timestamp. Existing configuration cannot be destroyed by a
                                // 401, a 404 or an unreachable host.
                                if (res is ModelListFetcher.FetchResult.Models) {
                                    // P0-A: editor state ONLY. No store write here - the live
                                    // cache is committed on Save, so Cancel really cancels.
                                    fetched = res.ids
                                }
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
                    if (r is ModelListFetcher.FetchResult.Models && r.ids.isNotEmpty()) {
                        Text(
                            "Found " + r.ids.size + " model ids. Add the ones this endpoint should serve:",
                            fontSize = ChatTokens.FONT_SMALL.sp,
                            color = c.textSecondary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        // One tap per id: the discovered list is a MENU, never a destructive
                        // replacement of what the user already configured.
                        r.ids.take(25).forEach { id ->
                            val already = id in models
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    id,
                                    fontSize = ChatTokens.FONT_SMALL.sp,
                                    color = if (already) c.textSecondary else c.text,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    enabled = !already,
                                    onClick = { models = (models + id).distinct() },
                                ) {
                                    Text(
                                        if (already) "Added" else "Add",
                                        fontSize = ChatTokens.FONT_SMALL.sp,
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { models = (models + r.ids).distinct() }) {
                                Text("Add all " + r.ids.size + " models")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && baseUrl.isNotBlank() && !modelsUnreadable &&
                    com.codespace.ide.engine.EndpointUrls.validBase(baseUrl),
                onClick = {
                    if (modelsUnreadable) {
                        saveError = "This endpoint's saved model ids could not be read, so " +
                            "nothing was saved. Retry the read first."
                        return@TextButton
                    }
                    if (!com.codespace.ide.engine.EndpointUrls.validBase(baseUrl)) {
                        saveError = "The base URL needs http:// or https:// and a host, e.g. " +
                            "https://api.example.com/v1. Nothing was saved."
                        return@TextButton
                    }
                    val savedId: String? = if (existing == null) {
                        store.add(label, baseUrl)?.id
                    } else {
                        if (store.update(existing.id, label, baseUrl)) existing.id else null
                    }
                    if (savedId == null) {
                        saveError = "Could not save this endpoint. The write to local storage failed, " +
                            "so it was NOT kept. Try again."
                        return@TextButton
                    }
                    // P0-A/P0-B: ONE commit for both lists, and only here. Manual always,
                    // live only when this session's fetch produced ids (so an untouched cache
                    // survives Save and keeps working offline).
                    val res = EndpointModelCommit.commit(
                        store = store,
                        id = savedId,
                        manual = models,
                        fetched = fetched,
                        readWasUnresolved = modelsUnreadable,
                    )
                    if (!res.manualSaved) {
                        saveError = "The endpoint was saved, but its model ids were NOT. " +
                            "Local storage refused the write; try Save again."
                        return@TextButton
                    }
                    if (!res.liveSaved) {
                        saveError = "Model ids were saved, but the discovered list could not be " +
                            "cached. The endpoint still works; fetch again to retry."
                        return@TextButton
                    }
                    val provider = store.providerIdFor(savedId)
                    if (apiKeyTouched) {
                        tokenStore.setAiKey(
                            com.codespace.ide.chat.keys.SlotNaming.customStoreKey(provider),
                            apiKey.ifBlank { null })
                    }
                    saveError = null
                    onSaved(provider, baseUrl.trim())
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
