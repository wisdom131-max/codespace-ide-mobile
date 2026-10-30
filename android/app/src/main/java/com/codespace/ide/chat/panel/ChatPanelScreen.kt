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
import kotlinx.coroutines.launch
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
fun ChatPanelScreen(
    projectId: String,
    tokenStore: com.codespace.ide.data.SecureTokenStore,
    /** Batch 2: opens a staged file in the editor so the review strip is reachable. */
    onOpenFile: (String) -> Unit = {},
    /** M2: the header's close control. Null when the host cannot close the panel. */
    onClosePanel: (() -> Unit)? = null,
    /** M3: "Move Chat into Editor Area". Null when this host has no editor area. */
    onMoveToEditor: (() -> Unit)? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // M3: ONE runtime per process, reference counted. A second window must share the same
    // manager (and therefore the same ChatSession per conversation = a single writer), and
    // closing one window must not cancel a stream running in the other.
    val manager = remember { ChatRuntime.acquire(ctx, tokenStore) }
    DisposableEffect(Unit) { onDispose { ChatRuntime.release() } }
    var showSessions by remember(projectId) { mutableStateOf(false) }
    val activeId by manager.activeSessionFlow(projectId).collectAsState()
    if (showSessions) {
        com.codespace.ide.chat.panel.sessions.SessionsPanel(
            projectId = projectId,
            manager = manager,
            activeId = activeId,
            onSwitched = { showSessions = false },
            onBackToChat = { showSessions = false },
        )
    } else {
        ChatBody(
            projectId = projectId,
            tokenStore = tokenStore,
            onOpenFile = onOpenFile,
            manager = manager,
            activeId = activeId,
            onOpenSessions = { showSessions = true },
            // A new session that could not be created opens the Sessions surface, which is the
            // one place that reports a session-table problem. Failures are never swallowed.
            onNewSession = {
                val created = manager.createSession(projectId)
                if (created is com.codespace.ide.chat.session.SessionCreate.Failed) showSessions = true
            },
            onClosePanel = onClosePanel,
            onMoveToEditor = onMoveToEditor,
        )
    }
}

/** The conversation surface: transcript, staged rows, composer. Unchanged apart from the header. */
@Composable
private fun ChatBody(
    projectId: String,
    tokenStore: com.codespace.ide.data.SecureTokenStore,
    onOpenFile: (String) -> Unit,
    manager: ChatSessionManager,
    activeId: String,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    onClosePanel: (() -> Unit)?,
    onMoveToEditor: (() -> Unit)?,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val session = remember(projectId, activeId) { manager.session(projectId, activeId) }
    // M3: voice transcripts live beside the transcripts, keyed by the SAME conversation, so a
    // dictation can never be shown under another session's name.
    val voiceStore = remember(projectId) {
        com.codespace.ide.chat.voice.VoiceTranscriptStore(java.io.File(ctx.applicationContext.filesDir, "chat/voice"))
    }
    var notice by remember(projectId) { mutableStateOf<String?>(null) }
    var showOverflow by remember(projectId) { mutableStateOf(false) }
    var showTranscripts by remember(projectId) { mutableStateOf(false) }
    var transcriptRead by remember(projectId) { mutableStateOf(com.codespace.ide.chat.voice.VoiceTranscriptRead(emptyList())) }
    var viewByDefault by remember { mutableStateOf(ChatSettingsPrefs.showChatByDefault()) }
    // Attribution: the journal is process-wide, so the session that owns this screen claims it.
    DisposableEffect(projectId, activeId) {
        com.codespace.ide.agent.AgentWriteJournal.activeSessionId = activeId
        // M5: memory reads/writes are attributed to this conversation, so "current session only"
        // has a session to be scoped to.
        com.codespace.ide.agent.MemoryScopeState.activeSessionId = activeId
        onDispose { }
    }
    // U1 (build 657 findings a/d): the OPENING selection is RESOLVED from what the
    // owner actually has a key for - never an unconfigured preset, never a pair whose
    // provider and model disagree (the 657 "ollama · gemini-3.8-flash" bug).
    // B1: a failed settings read is retryable. The nonce re-runs resolution in place; the
    // session keeps its current selection, so a storage hiccup never blanks the chat.
    var settingsRetry by remember(projectId) { mutableStateOf(0) }
    var resolved by remember(projectId, settingsRetry) {
        mutableStateOf(initialResolvedSelection(manager.reader))
    }
    LaunchedEffect(resolved) {
        (resolved as? ResolvedSelection.Ready)?.let { session.select(it.selection) }
    }
    val state by session.ui.collectAsState()
    val selection by session.selection.collectAsState()
    // Batch 1: warm the workspace block off the UI thread and refresh it on every
    // streaming->idle transition (turn finished, cancelled or failed). The send path
    // reads only the cache, so a slow filesystem can never delay a message, while the
    // block still reflects the file the user has open right now.
    LaunchedEffect(projectId, state.streaming) {
        if (!state.streaming) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                WorkspaceContextSource.warm(ctx.applicationContext, projectId)
            }
        }
    }
    // Batch 2: the staged store is scoped to THIS chat session, so proposals from two
    // projects can never be mixed, and the in-editor strip (which reads the same id)
    // always describes the same proposals the panel lists.
    DisposableEffect(projectId) {
        com.codespace.ide.chat.PendingChangesStore.activeSessionId = projectId
        onDispose { if (com.codespace.ide.chat.PendingChangesStore.activeSessionId == projectId)
            com.codespace.ide.chat.PendingChangesStore.activeSessionId = null }
    }
    val stagedRevision = com.codespace.ide.chat.PendingChangesStore.revision.value
    val staged = remember(projectId, stagedRevision) {
        com.codespace.ide.chat.PendingChangesStore.pendingFor(projectId)
    }
    var stagedNote by remember(projectId) { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    val dictate = rememberDictation { outcome ->
        when (outcome) {
            is DictationOutcome.Heard -> {
                val failure = voiceStore.append(projectId, activeId, outcome.text, System.currentTimeMillis())
                if (failure != null) {
                    notice = failure
                } else {
                    input = if (input.isBlank()) outcome.text else input.trimEnd() + " " + outcome.text
                    notice = "Dictated into the composer. Review it, then send."
                }
            }
            is DictationOutcome.Unavailable -> notice = outcome.reason
            is DictationOutcome.Failed -> notice = outcome.reason
            DictationOutcome.Cancelled -> Unit
        }
    }
    // WP8: system picker; bytes are STAGED at attach time (read fully once),
    // then only name + mime + base64 live in memory until the send.
    val attachLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) readAttachment(ctx, uri, session) }
    val pending by session.pending.collectAsState()
    // U3: composer mode. Ask is the only mode that works without the tool wire.
    var chatMode by remember { mutableStateOf(ChatMode.ASK) }
    var showSelect by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    val c = ChatTokens.LocalChatColors.current
    val scroll = rememberScrollState()
    // Item 3: shared follow flag + a scope for the one-tap jump back to the newest message.
    val follow = remember { FollowFlag() }
    val jumpScope = androidx.compose.runtime.rememberCoroutineScope()

    // "Thinking Ns" ticker: recompute while streaming with no visible delta yet.
    var thinkTickMs by remember { mutableStateOf(0L) }
    LaunchedEffect(state.streaming, state.firstDeltaMs) {
        while (state.streaming && state.firstDeltaMs == 0L) {
            thinkTickMs = System.currentTimeMillis()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // U3: the model picker lives in the composer toolbar where the reference puts it.
        // M2: the header carries the surface title and the session controls.
        ChatPanelHeaderRow(
            title = "Chat",
            colors = c,
            onSessions = onOpenSessions,
            onMic = dictate,
            onNewSession = onNewSession,
            onOverflow = { showOverflow = true },
            onClose = onClosePanel,
        )
        // A result the user must see (undo outcome, dictation problem, transcript state).
        // Cleared when they open another surface; never a silent failure.
        notice?.let { ChatNoticeLine(it, c) { notice = null } }
        // M4: an empty conversation shows the welcome composition instead of an empty list.
        // Only when NOTHING is happening: a stream, a staged attachment or a setup problem all
        // deserve their own surface, never a welcome screen on top of live state.
        if (resolved is ResolvedSelection.Ready && state.rows.isEmpty() && !state.streaming && pending.isEmpty()) {
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { ChatEmptyWelcome(c) }
        } else
        Column(
            Modifier.weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp)
        ) {
            if (resolved is ResolvedSelection.NeedsSetup) {
                SetupNeededState(onOpenProviders = { showSelect = true })
            } else if (resolved is ResolvedSelection.Unavailable) {
                // B1: the pair is intact and still selected; only the read failed.
                SettingsUnreadableState(
                    detail = (resolved as ResolvedSelection.Unavailable).reason,
                    onRetry = { settingsRetry++ },
                )
            } else {
                var rows = if (state.streaming && state.firstDeltaMs == 0L)
                    state.rows + listOf(ChatRowModel.Thinking(((thinkTickMs - state.streamStartedMs) / 1000L).coerceAtLeast(0L).toInt()))
                else state.rows
                if (pending.isNotEmpty()) rows = rows + ChatRowModel.Attachments(session.pendingChips())
                // Batch 3: follow the newest content while the user is at the bottom, and
                // never fight them once they scroll up. Emits nothing.
                TranscriptAutoScroll(scroll, transcriptGrowthKey(rows), follow)
                NativeChatListRenderer.MessageList(
                    ChatRenderState(
                        rows = rows,
                        onRetry = { session.retry() },
                        onSendAnyway = { session.sendAnyway() },
                        onCancel = { session.dismissOverBudget() },
                        onRemoveChip = { chip -> session.removePending(chip.name) },
                        // BATCH 7: Insert goes to whatever editor is on screen right now;
                        // false (no editor) is reported by the card, not swallowed here.
                        onInsertCode = { code -> com.codespace.ide.editor.EditorInsertBus.insert(code) },
                    ),
                    Modifier.fillMaxWidth(),
                )
            }
        }
        // Item 3: the way back, only while the user is scrolled away from the newest message.
        if (!follow.following) {
            JumpToLatestButton(c) { jumpScope.launch { follow.jumpToLatest(scroll) } }
        }
        // Section 7 — ORDER MATTERS and is deliberate: the approval card (work awaiting
        // authorization) sits ABOVE the Files Changed row (work already done). They are never
        // the same operation shown twice, so the eye reads "pending" then "completed".
        ChatApprovalCard()
        // Section 5: the Files Changed row exists only in Agent mode, and the row itself
        // renders nothing when the journal holds no real change.
        if (chatMode == ChatMode.AGENT) {
            ChatFilesChangedRow()
        }
        ChatStagedChangesRow(
        files = staged,
        onOpen = { path -> onOpenFile(path) },
        onApplyAll = {
            val out = com.codespace.ide.chat.PendingChangesStore.applyAll(projectId)
            stagedNote = stagedApplyNote(out)
        },
        onDiscardAll = {
            com.codespace.ide.chat.PendingChangesStore.discardAll(projectId)
            stagedNote = "Discarded " + staged.size + " proposal(s). Nothing was written."
        },
        note = stagedNote,
    )
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
            onModeChange = {
                chatMode = it
                // The chip and the request must never disagree.
                session.mode = it
            },
            onAddContext = { attachLauncher.launch("*/*") },
            onOpenModel = { showSelect = true },
            onTools = { showKeys = true },
            onSend = { if (input.isNotBlank()) { session.send(input); input = "" } },
            onStop = { session.cancel() },
            // The visible "Local Approvals: Auto-all" row is GONE at the owner's request
            // (spec section 4). Only the LABEL went: ChatPermissionStore is untouched and
            // AgentFlowGate still consults it before every tool call, and ChatApprovalCard
            // still asks when the level requires it.
        )
    }
    if (showOverflow) {
        ChatOverflowMenu(
            items = ChatOverflowSpec.items(
                canUndo = com.codespace.ide.chat.panel.edits.LastEditActions.canUndo(activeId),
                canRedo = com.codespace.ide.chat.panel.edits.LastEditActions.canRedo(activeId),
                viewByDefault = viewByDefault,
                canMoveToEditor = onMoveToEditor != null,
            ),
            colors = c,
            onPick = { action ->
                showOverflow = false
                when (action) {
                    ChatOverflowAction.UNDO_LAST_EDIT -> {
                        notice = com.codespace.ide.chat.panel.edits.LastEditActions.undo(activeId)
                    }
                    ChatOverflowAction.REDO_LAST_EDIT -> {
                        notice = com.codespace.ide.chat.panel.edits.LastEditActions.redo(activeId)
                    }
                    ChatOverflowAction.MOVE_TO_EDITOR_AREA -> onMoveToEditor?.invoke()
                    ChatOverflowAction.MOVE_TO_NEW_WINDOW -> {
                        val launched = runCatching {
                            ctx.startActivity(
                                com.codespace.ide.chat.window.ChatWindowActivity.intentFor(ctx, projectId),
                            )
                        }
                        notice = launched.exceptionOrNull()?.let {
                            "Could not open a chat window (" + (it.message ?: "unknown") + ")."
                        }
                    }
                    ChatOverflowAction.SHOW_VOICE_TRANSCRIPTS -> {
                        transcriptRead = voiceStore.read(projectId, activeId)
                        showTranscripts = true
                    }
                    ChatOverflowAction.SHOW_VIEW_BY_DEFAULT -> {
                        viewByDefault = !viewByDefault
                        ChatSettingsPrefs.setShowChatByDefault(viewByDefault)
                        notice = if (viewByDefault) {
                            "Chat will open by default when a project opens."
                        } else {
                            "Chat will stay closed until you open it."
                        }
                    }
                }
            },
            onDismiss = { showOverflow = false },
        )
    }
    if (showTranscripts) {
        VoiceTranscriptsDialog(
            transcripts = transcriptRead.items,
            skipped = transcriptRead.skipped,
            problem = transcriptRead.reason,
            colors = c,
            onClear = {
                notice = voiceStore.clear(projectId, activeId) ?: "Voice transcripts cleared."
                transcriptRead = voiceStore.read(projectId, activeId)
            },
            onDismiss = { showTranscripts = false },
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
                // B2: strict reads. An UNREADABLE list yields no model (blank = "pick one")
                // instead of pretending the endpoint has none.
                val manualRead = com.codespace.ide.chat.CustomEndpointStore.readManualModels(epId)
                val liveRead = com.codespace.ide.chat.CustomEndpointStore.readLiveModels(epId)
                val model = (manualRead as? com.codespace.ide.chat.CustomEndpointStore.ModelRead.Loaded)
                    ?.ids?.firstOrNull()
                    ?: (liveRead as? com.codespace.ide.chat.CustomEndpointStore.ModelRead.Loaded)
                        ?.ids?.firstOrNull()
                    ?: ""
                onApply(PanelSelection(providerId, slotKeyFor(providerId), model, baseUrl))
            },
        )
        return
    }
    var model by remember { mutableStateOf(current.modelId) }
    // B2: bump to re-read the endpoint's model lists after a failed read.
    var pickerRetry by remember { mutableStateOf(0) }
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
                // BATCH 8: the searchable model picker (ModelPickerSpec) finally has a
                // caller. It sits ABOVE the free-text id so the common path is a tap, and the
                // free-text box stays for ids this build has never heard of.
                val endpointId = com.codespace.ide.chat.CustomEndpointStore
                    .endpointIdForProvider(current.providerId) ?: ""
                // B2: strict reads. A failed read is shown as a retryable notice and the
                // bundled catalog still renders, so the picker is never a silent empty list.
                val manualRead = remember(current.providerId, cfg, pickerRetry) {
                    if (endpointId.isEmpty()) com.codespace.ide.chat.CustomEndpointStore.ModelRead.Loaded(emptyList())
                    else com.codespace.ide.chat.CustomEndpointStore.readManualModels(endpointId)
                }
                val liveRead = remember(current.providerId, cfg, pickerRetry) {
                    if (endpointId.isEmpty()) com.codespace.ide.chat.CustomEndpointStore.ModelRead.Loaded(emptyList())
                    else com.codespace.ide.chat.CustomEndpointStore.readLiveModels(endpointId)
                }
                val modelListsUnreadable =
                    manualRead is com.codespace.ide.chat.CustomEndpointStore.ModelRead.Unreadable ||
                        liveRead is com.codespace.ide.chat.CustomEndpointStore.ModelRead.Unreadable
                if (modelListsUnreadable) {
                    ModelListUnreadableNotice(onRetry = { pickerRetry++ })
                }
                val pickerModels = remember(current.providerId, cfg, manualRead, liveRead) {
                    ModelPickerData.modelsFor(
                        providerId = current.providerId,
                        providerDefault = com.codespace.ide.engine.ProviderPresets
                            .byId(current.providerId)?.defaultModel,
                        manual = (manualRead as? com.codespace.ide.chat.CustomEndpointStore.ModelRead.Loaded)
                            ?.ids ?: emptyList(),
                        live = (liveRead as? com.codespace.ide.chat.CustomEndpointStore.ModelRead.Loaded)
                            ?.ids ?: emptyList(),
                    )
                }
                ModelPickerSection(
                    providerId = current.providerId,
                    current = current,
                    models = pickerModels,
                    effort = ModelPickerData.effortFor(current.providerId),
                    onPick = { picked ->
                        // The SAME onApply the dialog already used, so the picked model is the
                        // model the next request uses - no second selection path.
                        model = picked.modelId
                        onApply(
                            ModelPickerData.selectionFor(picked, current, ::slotKeyFor) { pid, mid ->
                                // The dialog's own coherence rules resolve a provider change;
                                // the picker never invents a provider/model pair itself.
                                com.codespace.ide.chat.panel.SelectionResolver
                                    .applyProvider(pid, current, mid, cfg) { slotKeyFor(it) }
                            },
                        )
                    },
                )

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
