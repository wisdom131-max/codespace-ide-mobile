package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
    val session = remember(projectId) { manager.session(projectId) }
    // U1 (build 657 findings a/d): the OPENING selection is RESOLVED from what the
    // owner actually has a key for - never an unconfigured preset, never a pair whose
    // provider and model disagree (the 657 "ollama · gemini-3.8-flash" bug).
    var resolved by remember(projectId) { mutableStateOf(initialResolvedSelection(manager.reader)) }
    LaunchedEffect(resolved) {
        (resolved as? ResolvedSelection.Ready)?.let { session.select(it.selection) }
    }
    val state by session.ui.collectAsState()
    val selection by session.selection.collectAsState()
    var input by remember { mutableStateOf("") }
    // WP8: system picker; bytes are STAGED at attach time (read fully once),
    // then only name + mime + base64 live in memory until the send.
    val attachLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) readAttachment(ctx, uri, session) }
    // Dictation: the same RecognizerIntent the terminal's STT button uses, so a device
    // without a recognizer says so instead of silently doing nothing.
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val said = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!said.isNullOrBlank()) {
                input = if (input.isBlank()) said else input.trimEnd() + " " + said
            }
        }
    }
    val onMic: () -> Unit = {
        try {
            micLauncher.launch(
                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Dictate a message")
                },
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Dictation not available on this device", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val pending by session.pending.collectAsState()
    // U3: composer mode. Ask is the only mode that works without the tool wire.
    var chatMode by remember { mutableStateOf(ChatMode.ASK) }
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
            // U3: the model picker moved DOWN into the composer toolbar where the
            // reference puts it. The header keeps only the surface title and one
            // reachable management entry point.
            Text(
                "Chat",
                color = c.text,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Column(
            Modifier.weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp)
        ) {
            if (resolved is ResolvedSelection.NeedsSetup) {
                SetupNeededState(onOpenProviders = { showSelect = true })
            } else {
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
        }
        ChatInputComposer(
            value = input,
            onValueChange = { input = it },
            placeholder = if (resolved is ResolvedSelection.NeedsSetup)
                "Set up a provider to start" else "Message " + selection.providerId,
            streaming = state.streaming,
            needsSetup = resolved is ResolvedSelection.NeedsSetup,
            modelLabel = selection.modelId,
            effortLabel = null,
            mode = chatMode,
            onModeChange = { chatMode = it },
            onAddContext = { attachLauncher.launch("*/*") },
            onMic = onMic,
            onOpenModel = { showSelect = true },
            onTools = { showKeys = true },
            onSend = { if (input.isNotBlank()) { session.send(input); input = "" } },
            onStop = { session.cancel() },
            // Real approval level, not a decorative label: this is the same store the
            // flow gate consults before a tool may run.
            approvalsLabel = when (com.codespace.ide.agent.ChatPermissionStore.level(ctx)) {
                com.codespace.ide.agent.ChatFlowLevel.MANUAL -> "Manual"
                com.codespace.ide.agent.ChatFlowLevel.AUTO_SAFE -> "Auto-safe"
                com.codespace.ide.agent.ChatFlowLevel.AUTO_ALL -> "Auto-all"
            },
        )
    }
    if (showSelect) {
        ProviderModelDialog(
            manager = manager,
            tokenStore = tokenStore,
            current = selection,
            onApply = { sel ->
                session.select(sel)
                // A pair that cannot actually send (no key / foreign model / wrong base
                // URL) is applied for this view but NEVER persisted (657 regression).
                persistSelectionIfCoherent(manager.reader, sel)
                resolved = ResolvedSelection.Ready(sel)
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

/** U1: the real stored slot name for a provider (ai_<UPPERCASE>, claude for Anthropic). */
private fun slotKeyFor(providerId: String): String =
    com.codespace.ide.chat.keys.SlotNaming.primarySlot(providerId)

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
        CustomEndpointEditor(
            tokenStore = tokenStore,
            onClose = { showEndpoints = false; refresh++ },
            onSaved = { providerId, baseUrl ->
                // U1 (657 finding c): a saved endpoint is SELECTED, not left behind while
                // the old default stays on screen. Model = its first known id, else blank
                // ("Pick model" prompts for one) - never another provider's model.
                val epId = com.codespace.ide.chat.CustomEndpointStore.endpointIdForProvider(providerId) ?: ""
                val model = com.codespace.ide.chat.CustomEndpointStore.manualModels(epId).firstOrNull()
                    ?: com.codespace.ide.chat.CustomEndpointStore.liveModels(epId).firstOrNull()
                    ?: ""
                onApply(PanelSelection(providerId, slotKeyFor(providerId), model, baseUrl))
            },
        )
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
    // U1: the coherence rules the dialog obeys (never invent a mixed pair).
    val cfg = remember(snapshot) { configuredProvidersFrom(snapshot) }
    // WP3: native adapters (Gemini/Anthropic) come from stored keys;
    // OpenAI-compatible presets carry their own base URLs; Ollama needs no key.
    val nativeRows: List<StoredKeySlot> = keyProviders.filter {
        it.providerId == "gemini" || it.providerId == "anthropic"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Provider and model") },
        text = {
            val dialogScroll = rememberScrollState()
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(dialogScroll)
            ) {
                nativeRows.forEach { slot ->
                    Text(
                        slot.providerId + (if (slot.poolIndex != null) " (key " + slot.poolIndex + ")" else ""),
                        color = if (slot.slotName == current.keySlot) c.accent else c.text,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // U1: the previous provider's model is NEVER carried across
                                // (657 bug); a typed model only counts if it fits this family.
                                val typed = model.takeIf {
                                    it.isNotBlank() &&
                                        !com.codespace.ide.chat.panel.SelectionResolver
                                            .modelBelongsToAnotherFamily(slot.providerId, it)
                                }
                                val next = com.codespace.ide.chat.panel.SelectionResolver.applyProvider(
                                    slot.providerId, current, typed, cfg,
                                ) { pid -> slotKeyFor(pid) }
                                if (next != null) {
                                    model = next.modelId
                                    onApply(next.copy(keySlot = slot.slotName))
                                }
                            }
                            .padding(vertical = ChatTokens.GAP.dp),
                    )
                }
                com.codespace.ide.engine.ProviderPresets.ALL.forEach { p ->
                    // U1: real slot shape is ai_<UPPERCASE>; presence is per PROVIDER
                    // (pool slots included), so a saved key is never shown as missing.
                    val hasKey = keyProviders.any { it.providerId == p.providerId }
                    Text(
                        p.label + (if (!p.requiresKey) " (no key needed)"
                                   else if (hasKey) " (key stored)" else " (no key stored)"),
                        color = if (p.providerId == current.providerId) c.accent else c.text,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val typed = model.takeIf {
                                    it.isNotBlank() &&
                                        !com.codespace.ide.chat.panel.SelectionResolver
                                            .modelBelongsToAnotherFamily(p.providerId, it)
                                }
                                val next = com.codespace.ide.chat.panel.SelectionResolver.applyProvider(
                                    p.providerId, current, typed, cfg,
                                ) { pid -> slotKeyFor(pid) }
                                if (next != null) {
                                    model = next.modelId
                                    onApply(next)
                                }
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
                                val typed = model.takeIf { it.isNotBlank() }
                                val next = com.codespace.ide.chat.panel.SelectionResolver.applyProvider(
                                    ep.providerId,
                                    current,
                                    typed,
                                    configuredProvidersFrom(snapshot),
                                ) { pid -> slotKeyFor(pid) }
                                if (next != null) {
                                    model = next.modelId
                                    onApply(next.copy(baseUrl = ep.baseUrl))
                                }
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
                // U1: a model id from another native family is refused here instead of
                // being saved as a pair that can only fail on the wire.
                val applyModel =
                    if (com.codespace.ide.chat.panel.SelectionResolver
                            .modelBelongsToAnotherFamily(current.providerId, model)
                    ) com.codespace.ide.chat.panel.SelectionResolver.defaultModelFor(current.providerId)
                    else model
                onApply(current.copy(modelId = applyModel))
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
