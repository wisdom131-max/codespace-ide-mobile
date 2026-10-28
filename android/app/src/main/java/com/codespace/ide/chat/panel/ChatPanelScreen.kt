package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.keys.StoredEndpoint
import com.codespace.ide.chat.keys.StoredKeySlot
import com.codespace.ide.chat.ui.ChatRenderState
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.chat.ui.NativeChatListRenderer
import kotlinx.coroutines.delay

/**
 * WP2 (d-min): the chat panel on the rebuilt engine. Native renderer behind
 * the ChatListRenderer interface (WebView can replace it later), input row
 * with send/cancel, retry, provider/model/key selection from the WP1
 * read-only migration, "Thinking Ns" until the first delta, send budget with
 * "Send anyway?". Attachment chips are a WP8 placeholder (rendered by the
 * same component library when attachments return).
 */
@Composable
fun ChatPanelScreen(projectId: String, tokenStore: com.codespace.ide.data.SecureTokenStore) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val manager = remember {
        com.codespace.ide.chat.panel.ChatSettingsPrefs.ensure(ctx)
        ChatSessionManager(com.codespace.ide.chat.keys.StoredCredentialsReader(
            tokenStore, com.codespace.ide.chat.CustomEndpointStore))
    }
    DisposableEffect(Unit) { onDispose { manager.close() } }
    // WP7: restore the persisted selection once per session (defaults if none).
    val session = remember(projectId) {
        val s = manager.session(projectId)
        ChatSettingsPrefs.selection()?.let { s.select(it) }
        s
    }
    val state by session.ui.collectAsState()
    val selection by session.selection.collectAsState()
    var input by remember { mutableStateOf("") }
    // WP8: system picker; bytes are STAGED at attach time (read fully once),
    // then only name + mime + base64 live in memory until the send.
    val attachLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) readAttachment(ctx, uri, session) }
    val pending by session.pending.collectAsState()
    var showSelect by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    val c = ChatTokens.LocalChatColors.current
    val scroll = rememberScrollState()

    // "Thinking Ns" ticker: recompute while streaming with no visible delta yet.
    var thinkTickMs by remember { mutableStateOf(0L) }
    LaunchedEffect(state.streaming, state.firstDeltaMs) {
        while (state.streaming && state.firstDeltaMs == 0L) {
            thinkTickMs = System.currentTimeMillis()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selection.providerId + "  ·  " + selection.modelId,
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier
                    .weight(1f)
                    .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .clickable { showSelect = true }
                    .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.GAP.dp),
            )
        }
        Column(
            Modifier.weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp)
        ) {
            var rows = if (state.streaming && state.firstDeltaMs == 0L)
                state.rows + listOf(ChatRowModel.Thinking(((thinkTickMs - state.streamStartedMs) / 1000L).coerceAtLeast(0L).toInt()))
            else state.rows
            if (pending.isNotEmpty()) rows = rows + ChatRowModel.Attachments(session.pendingChips())
            NativeChatListRenderer.MessageList(
                ChatRenderState(
                    rows = rows,
                    onRetry = { session.retry() },
                    onSendAnyway = { session.sendAnyway() },
                    onCancel = { session.dismissOverBudget() },
                    onRemoveChip = { chip -> session.removePending(chip.name) },
                ),
                Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message " + selection.providerId, fontSize = ChatTokens.FONT_SMALL.sp) },
                singleLine = false,
                maxLines = 4,
            )
            Text(
                "Attach",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier
                    .padding(start = ChatTokens.GAP.dp)
                    .clickable { attachLauncher.launch("*/*") },
            )
            Text(
                if (state.streaming) "Stop" else "Send",
                color = if (state.streaming) c.warning else c.accent,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier
                    .padding(start = ChatTokens.GAP.dp)
                    .clickable {
                        if (state.streaming) session.cancel()
                        else if (input.isNotBlank()) { session.send(input); input = "" }
                    },
            )
        }
    }
    if (showSelect) {
        ProviderModelDialog(
            manager = manager,
            tokenStore = tokenStore,
            current = selection,
            onApply = {
                session.select(it)
                ChatSettingsPrefs.setSelection(it)
                showSelect = false
            },
            onClearConversation = { session.clearConversation(); showSelect = false },
            onManageKeys = { showKeys = true; showSelect = false },
            onDismiss = { showSelect = false },
        )
    }
    if (showKeys) {
        KeysManagerDialog(tokenStore = tokenStore, onDismiss = { showKeys = false })
    }
}

/** Minimal provider/model/key picker over the WP1 snapshot (WP7 replaces it). */
@Composable
private fun ProviderModelDialog(
    manager: ChatSessionManager,
    tokenStore: com.codespace.ide.data.SecureTokenStore,
    current: PanelSelection,
    onApply: (PanelSelection) -> Unit,
    onClearConversation: () -> Unit,
    onManageKeys: () -> Unit,
    onDismiss: () -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val snapshot = remember(refresh) { manager.reader.snapshot() }
    var showEndpoints by remember { mutableStateOf(false) }
    if (showEndpoints) {
        CustomEndpointEditor(tokenStore) { showEndpoints = false; refresh++ }
        return
    }
    var model by remember { mutableStateOf(current.modelId) }
    // WP7: context-window override state (dialog-body scope; confirmButton reads it).
    var overrideText by remember(current) {
        mutableStateOf(
            ChatSettingsPrefs.contextOverride(current.providerId, model)?.toString() ?: "")
    }
    val c = ChatTokens.LocalChatColors.current
    // Providers with stored keys (WP1 read-only migration), custom endpoints last.
    val keyProviders: List<StoredKeySlot> = snapshot.keySlots.filter { it.hasKey }
    // WP3: native adapters (Gemini/Anthropic) come from stored keys;
    // OpenAI-compatible presets carry their own base URLs; Ollama needs no key.
    val nativeRows: List<StoredKeySlot> = keyProviders.filter {
        it.providerId == "gemini" || it.providerId == "anthropic"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Provider and model") },
        text = {
            Column {
                nativeRows.forEach { slot ->
                    Text(
                        slot.providerId + (if (slot.poolIndex != null) " (key " + slot.poolIndex + ")" else ""),
                        color = if (slot.slotName == current.keySlot) c.accent else c.text,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                model = when (slot.providerId) {
                                        "gemini" -> current.modelId
                                        "anthropic" -> PanelSelection.ANTHROPIC_DEFAULT_MODEL
                                        else -> model
                                    }
                                onApply(PanelSelection(
                                    providerId = if (slot.providerId.startsWith("custom")) slot.providerId else slot.providerId,
                                    keySlot = slot.slotName,
                                    modelId = model,
                                    baseUrl = when (slot.providerId) {
                                        "gemini" -> "https://generativelanguage.googleapis.com"
                                        "anthropic" -> PanelSelection.ANTHROPIC_BASE
                                        else -> PanelSelection.OPENAI_BASE
                                    },
                                ))
                            }
                            .padding(vertical = ChatTokens.GAP.dp),
                    )
                }
                com.codespace.ide.engine.ProviderPresets.ALL.forEach { p ->
                    val hasKey = keyProviders.any { it.slotName == "ai_" + p.providerId }
                    Text(
                        p.label + (if (!p.requiresKey) " (no key needed)"
                                   else if (hasKey) " (key stored)" else " (no key stored)"),
                        color = if (p.providerId == current.providerId) c.accent else c.text,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onApply(PanelSelection(
                                    providerId = p.providerId,
                                    keySlot = if (p.requiresKey) "ai_" + p.providerId else "",
                                    modelId = if (p.providerId == current.providerId) model
                                              else p.defaultModel.ifBlank { model },
                                    baseUrl = p.baseUrl,
                                ))
                            }
                            .padding(vertical = ChatTokens.GAP.dp),
                    )
                }
                // WP6: automatic key failover is OPT-IN (silent switching burns credits).
                Text(
                    "Automatic key switch on key rejection: " +
                        (if (com.codespace.ide.chat.panel.ChatSettingsPrefs.failoverOptIn()) "ON" else "OFF"),
                    color = c.textSecondary,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            com.codespace.ide.chat.panel.ChatSettingsPrefs.setFailoverOptIn(
                                !com.codespace.ide.chat.panel.ChatSettingsPrefs.failoverOptIn())
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                // WP7: key management returns home (keys never displayed).
                Text(
                    "Manage keys",
                    color = c.accent,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onManageKeys() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Text(
                    "Clear conversation",
                    color = c.text,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClearConversation() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                // WP7: two-tap confirm - clearing ALL chat settings is destructive.
                var resetArmed by remember { mutableStateOf(false) }
                Text(
                    if (resetArmed) "Tap again to reset chat settings" else "Reset chat settings (selection, failover, overrides)",
                    color = if (resetArmed) c.warning else c.text,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (resetArmed) { ChatSettingsPrefs.resetAll(); resetArmed = false; onClearConversation() }
                            else resetArmed = true
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                // WP4: entry into the custom endpoints editor.
                Text(
                    "Add / manage custom endpoints",
                    color = c.accent,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showEndpoints = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                snapshot.endpoints.forEach { ep: StoredEndpoint ->
                    Text(
                        ep.label + " (custom endpoint)",
                        color = if (ep.providerId == current.providerId) c.accent else c.text,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onApply(PanelSelection(
                                    providerId = ep.providerId,
                                    keySlot = "ai_" + ep.providerId,
                                    modelId = model,
                                    baseUrl = ep.baseUrl,
                                ))
                            }
                            .padding(vertical = ChatTokens.GAP.dp),
                    )
                }
                TextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model id") },
                    singleLine = true,
                )
                // WP7: user context-window override (WP5 estimate honors it).
                TextField(
                    value = overrideText,
                    onValueChange = { overrideText = it },
                    label = { Text("Context tokens override (blank = bundled)", fontSize = ChatTokens.FONT_MICRO.sp) },
                    singleLine = true,
                )
                // WP5: capability line — verified bundled table, always an ESTIMATE.
                val cap = com.codespace.ide.engine.ModelCatalog.capabilityFor(current.providerId, model)
                if (cap != null) {
                    Text(
                        "context ~" + (cap.contextTokens / 1000) + "k tokens (estimate)" +
                            (if (cap.supportsImages) " — images in" else "") +
                            (if (cap.supportsPdf) ", PDF in" else ""),
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        color = c.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // WP7: persist the user override keyed to the applied provider:model
                // (blank = clear override, bundled row stands).
                ChatSettingsPrefs.setContextOverride(
                    current.providerId, model, overrideText.trim().toIntOrNull())
                onApply(current.copy(modelId = model))
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/**
 * WP8: read one picked file into a staged attachment. The 10 MB cap and kind
 * gate live in ChatSession.addPending (honest refusal notes there); this
 * reader only resolves bytes/mime/name and never logs file contents.
 */
private fun readAttachment(
    ctx: android.content.Context,
    uri: android.net.Uri,
    session: ChatSession,
) {
    try {
        val resolver = ctx.contentResolver
        val stream = resolver.openInputStream(uri) ?: return
        // Capped read: never buffer more than the cap + 1 byte, so a huge
        // pick cannot OOM before the honest size refusal.
        val cap = com.codespace.ide.chat.panel.PanelAttachment.MAX_BYTES.toInt() + 1
        val buf = java.io.ByteArrayOutputStream()
        stream.use { ins ->
            val chunk = ByteArray(64 * 1024)
            var n = ins.read(chunk)
            while (n > 0 && buf.size() <= cap) {
                buf.write(chunk, 0, n)
                n = ins.read(chunk)
            }
        }
        val bytes = buf.toByteArray()
        val name = uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri) ?: guessMimeFromName(name)
        // WP9: text kinds travel as TEXT (no base64 inflation); binaries as base64.
        val isTextKind = mime.startsWith("text/") || mime == "application/json" ||
            mime == "application/xml" || mime == "application/yaml" || mime == "application/x-yaml"
        session.addPending(
            name = name,
            mimeType = mime,
            base64 = if (isTextKind) "" else android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            sizeBytes = bytes.size.toLong(),
            text = if (isTextKind) String(bytes, Charsets.UTF_8) else null,
        )
    } catch (t: Throwable) {
        session.addPending("unreadable", "", "", 0L) // kind gate prints the honest unreadable note
    }
}

private fun guessMimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
    "webp" -> "image/webp"; "pdf" -> "application/pdf"; else -> ""
}
