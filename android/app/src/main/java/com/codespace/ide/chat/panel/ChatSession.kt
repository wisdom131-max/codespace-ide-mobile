package com.codespace.ide.chat.panel

import com.codespace.ide.chat.keys.StoredCredentialsReader
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatEngineImpl
import com.codespace.ide.engine.ChatError
import com.codespace.ide.engine.EngineConfig
import com.codespace.ide.engine.EngineEvent
import com.codespace.ide.engine.AnthropicAdapter
import com.codespace.ide.engine.GeminiAdapter
import com.codespace.ide.engine.HttpTransport
import com.codespace.ide.engine.OpenAIAdapter
import com.codespace.ide.engine.ProviderAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * WP2 (d-min): one engine-bound chat session per project. The send runs in the
 * MANAGER's scope, never in a composable - a tab switch or closing the panel
 * never cancels an in-flight send (advisor requirement). Rows are the panel's
 * own UI history; the engine transcript is only the wire window.
 */
data class ChatPanelUiState(
    val rows: List<ChatRowModel> = emptyList(),
    val streaming: Boolean = false,
    val streamStartedMs: Long = 0L,
    val firstDeltaMs: Long = 0L,
    val lastUserText: String = "",
)

/** Minimal provider/model/key selection (WP7 Settings replaces this UI). */
/**
 * WP8: staged attachment held by the PANEL between attach and send.
 * In-memory only (redaction rule): never persisted, never logged; the
 * base64 lives until the send consumes it. 10 MB cap enforced at attach.
 */
data class PanelAttachment(
    val name: String,
    val mimeType: String,
    val base64: String,
    val sizeBytes: Long,
    /** WP9: text/context attachment content (null for binaries). */
    val text: String? = null,
) {
    companion object { const val MAX_BYTES: Long = 10L * 1024 * 1024 }
}

data class PanelSelection(
    val providerId: String = "gemini",
    val keySlot: String = "ai_GEMINI",
    val modelId: String = "gemini-3.8-flash",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
) {
    companion object {
        const val OPENAI_BASE = "https://example.invalid/chat/completions"
        /** WP3: Anthropic Messages API (verified from official docs 2026-10-28). */
        const val ANTHROPIC_BASE = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_DEFAULT_MODEL = "claude-opus-5-5"
    }
}

class ChatSession(
    private val projectId: String,
    private val keyFor: (slotName: String) -> String?,
    private val scope: CoroutineScope,
    /** Injectable for JVM tests; the real panel uses HttpTransport(). */
    private val transportFactory: () -> com.codespace.ide.engine.ChatTransport = { HttpTransport() },
    /**
     * AUDIT DEFECT D: the base URL a request must use RIGHT NOW, resolved from the endpoint
     * store. A selection carries a CACHE of the URL, and after an endpoint edit that cache is
     * stale, so the send path must not trust it. Default keeps the stored value (JVM tests).
     */
    private val baseUrlForProvider: (providerId: String, fallback: String) -> String =
        { _, fallback -> fallback },
    /** WP6: ordered key-slot names with stored keys for a provider (primary first). */
    private val poolSlotsFor: (providerId: String) -> List<String> = { emptyList() },
    /** WP6: automatic key failover is OPT-IN (silent switching burns credits). */
    private val failoverOptIn: () -> Boolean = { false },
    /** WP7: user context-window override (tokens) for provider:model, or null. */
    private val contextOverrideFor: (providerId: String, modelId: String) -> Int? = { _, _ -> null },
    /**
     * Section 6: tool execution and the approval gate both need a Context. Null means
     * "no tools here" (JVM tests, or a build without it) and says so rather than
     * pretending to run something.
     */
    private val appContext: android.content.Context? = null,
    /**
     * MISSION part 1: the ACTIVE workspace block (roots, current file, tree,
     * instructions). Read at SEND time so a project switch or a new open file is
     * reflected in the next request without rebuilding the session. Cached upstream
     * (WorkspaceContextSource), so this call does no IO on the common path and an
     * empty string (JVM tests, no context) simply means "no workspace block".
     */
    private val workspaceBlock: () -> String = { "" },
    /**
     * MULTI-SESSION (M1): conversation identity. Public because the manager keys sessions by
     * (projectId, conversationId) and the edit journal attributes its change sets to it.
     * Defaults to the legacy id so every existing caller and test keeps working unchanged.
     */
    val conversationId: String = "main",
    /** BATCH 4: persistence hooks; null means "this session keeps nothing" (JVM tests). */
    private val transcripts: com.codespace.ide.chat.TranscriptSink? = null,
) {
    private val streamBuf = StringBuilder()
    private var sendJob: Job? = null
    private var failoverAttempts = 0
    /** Completed tool exchanges in THIS request; capped by AgentToolWire.MAX_ROUNDS. */
    private var toolRound = 0
    private val keySlotsSeen = HashSet<String>()

    val ui = MutableStateFlow(ChatPanelUiState())
    val selection = MutableStateFlow(PanelSelection())

    // Init-order (run 3387 NPE): buildEngine's default providerId reads
    // selection, so the engine initializer MUST come AFTER selection exists.
    private var engine: ChatEngineImpl = buildEngine()

    /**
     * BATCH 4: true once the restore ATTEMPT has finished (successfully or not). Nothing
     * is written to disk before that, or a conversation opened and immediately saved by
     * the collector below would overwrite the very history it is about to load.
     */
    @Volatile private var restoreFinished = false

    /**
     * BATCH 6: identifies the CURRENT send. A pending 429 retry only fires while its own
     * generation is still current, so a new message, a Stop or a cleared conversation can
     * never be resurrected by a timer that was armed a minute ago (that is the duplicate
     * submission this guards against).
     */
    @Volatile private var sendGeneration = 0
    /** Automatic 429 re-attempts used for the CURRENT send. */
    private var autoRetries = 0
    /** A save failure is shown once, not once per delta. */
    private var storageErrorShown = false

    /**
     * MULTI-SESSION (M1): true when this session's reply was STOPPED by a session switch.
     *
     * Sticky on purpose. [cancel] clears `streaming`, and the row collector saves immediately
     * afterwards; a save that recomputed `complete` from `streaming` would therefore rewrite
     * the half-finished reply as if it had finished. Holding the flag until the next send is
     * what keeps the "that reply was cut off" note truthful across every later save.
     */
    @Volatile private var interruptedReplyPending = false

    /**
     * MULTI-SESSION (M1): set when this conversation has been DELETED. A deleted session must
     * not be able to write its transcript back from under the delete, so every save after this
     * point is a no-op. (The store already removed the record and the bytes; a straggling
     * collector would recreate the file and resurrect the conversation.)
     */
    @Volatile private var disposed = false

    init {
        if (transcripts == null) {
            restoreFinished = true
        } else {
            scope.launch {
                val loaded = try {
                    transcripts.load(projectId, conversationId)
                } catch (t: Throwable) {
                    com.codespace.ide.chat.TranscriptLoad(
                        emptyList(),
                        "Could not read the saved conversation: " + (t.message ?: t.javaClass.simpleName),
                    )
                }
                val stored = com.codespace.ide.chat.ChatTranscript.messagesToRows(loaded.messages)
                if (com.codespace.ide.chat.RestorePolicy.shouldApplyRestore(
                        restoreFinished, ui.value.rows.size, loaded.messages.size)) {
                    ui.value = ui.value.copy(rows = stored)
                }
                loaded.note?.let { appendRow(ChatRowModel.InfoNote(it)) }
                restoreFinished = true
                persistTranscript()
            }
            // Every row change (send, delta, completion, clear) schedules a save; the
            // store debounces, and the scope is the store's, not this session's.
            scope.launch { ui.collect { persistTranscript() } }
        }
    }

    /** BATCH 4: hand the current transcript to the store (no-op before the restore ends). */
    private fun persistTranscript() {
        if (disposed) return
        val sink = transcripts ?: return
        if (!com.codespace.ide.chat.RestorePolicy.mayPersist(restoreFinished)) return
        val st = ui.value
        // M1: an interruption stays "incomplete" until the next send rewrites the transcript.
        val streaming = st.streaming || interruptedReplyPending
        sink.save(
            projectId,
            conversationId,
            com.codespace.ide.chat.ChatTranscript.rowsToMessages(st.rows, streaming),
        ) { error ->
            if (!storageErrorShown) {
                storageErrorShown = true
                appendRow(ChatRowModel.InfoNote("Could not save this conversation: " + error))
            }
        }
    }

    /** Switching provider rebuilds the engine (fresh wire window); UI rows stay. */
    fun select(sel: PanelSelection) {
        if (sel.providerId != selection.value.providerId) engine = buildEngine(sel.providerId)
        selection.value = sel
    }

    private fun buildEngine(providerId: String = selection.value.providerId): ChatEngineImpl =
        ChatEngineImpl(projectId, transportFactory(), adapterFor(providerId))

    private fun adapterFor(providerId: String): ProviderAdapter =
        if (providerId == "gemini") GeminiAdapter()
        else if (providerId == "anthropic") AnthropicAdapter()
        else OpenAIAdapter()

    private val pendingAttachments = kotlinx.coroutines.flow.MutableStateFlow<List<PanelAttachment>>(emptyList())
    val pending: kotlinx.coroutines.flow.StateFlow<List<PanelAttachment>> get() = pendingAttachments

    /**
     * WP8: stage one attachment. Refuses honestly (>10 MB, unsupported kind
     * for the SELECTED model when the capability table is known). Base64 is
     * never logged; the chip shows name + size only.
     */
    fun addPending(name: String, mimeType: String, base64: String, sizeBytes: Long, text: String? = null) {
        if (sizeBytes > PanelAttachment.MAX_BYTES) {
            appendRow(ChatRowModel.InfoNote(
                "Attachment refused: '" + name + "' is over the 10 MB cap (" +
                    (sizeBytes / (1024L * 1024L)) + " MB). The on-device payload budget would reject it anyway."))
            return
        }
        if (mimeType.isBlank()) {
            appendRow(ChatRowModel.InfoNote(
                "Attachment refused: could not read this file or determine its type."))
            return
        }
        // WP9 redaction gate: credential-shaped files never leave the device.
        if (looksLikeCredentialFile(name)) {
            appendRow(ChatRowModel.InfoNote(
                "Attachment refused: '" + name + "' looks like a credential file. Keys and secrets are never sent to a provider."))
            return
        }
        val isTextKind = text != null || mimeType.startsWith("text/") ||
            mimeType == "application/json" || mimeType == "application/xml" ||
            mimeType == "application/yaml" || mimeType == "application/x-yaml"
        if (!isTextKind && mimeType != "application/pdf" && !mimeType.startsWith("image/")) {
            appendRow(ChatRowModel.InfoNote(
                "Attachment refused: images, PDF and text files can be attached (got " + mimeType + ")."))
            return
        }
        // Capability gate applies to IMAGES/PDF only - every text model takes text.
        if (!isTextKind) {
            val sel = selection.value
            val cap = resolveCapability(sel)
            val unsupported = (mimeType.startsWith("image/") && cap != null && !cap.supportsImages) ||
                (mimeType == "application/pdf" && cap != null && !cap.supportsPdf)
            if (unsupported) {
                appendRow(ChatRowModel.InfoNote(
                    "Attachment refused: " + sel.modelId + " (capability estimate) does not take " +
                        (if (mimeType == "application/pdf") "PDF" else "image") + " attachments."))
                return
            }
        }
        val stagedText = if (isTextKind) (text ?: "") else null
        pendingAttachments.value = pendingAttachments.value +
            PanelAttachment(name, mimeType, if (stagedText != null) "" else base64, sizeBytes, stagedText)
    }

    fun removePending(name: String) {
        pendingAttachments.value = pendingAttachments.value.filterNot { it.name == name }
    }

    /** WP8: chips for the pending row (name + size; the data itself never leaves memory). */
    fun pendingChips(): List<com.codespace.ide.chat.ui.AttachmentChipModel> =
        pendingAttachments.value.map {
            com.codespace.ide.chat.ui.AttachmentChipModel(it.name, formatSizeBytes(it.sizeBytes))
        }

    /** Credential-shaped names are refused at attach (redaction rule). */
    internal fun looksLikeCredentialFile(name: String): Boolean {
        val n = name.lowercase()
        val base = n.substringAfterLast('/')
        return base == ".env" || base.startsWith(".env.") || base == "id_rsa" ||
            base == "id_ed25519" || base == "id_dsa" || base.endsWith(".pem") ||
            base.endsWith(".p12") || base.endsWith(".keystore") || base.endsWith(".jks") ||
            base.contains("credentials") || base.contains("secret") || base.contains("password")
    }

    internal fun formatSizeBytes(bytes: Long): String =
        if (bytes >= 1024L * 1024L) String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else String.format("%.0f KB", bytes / 1024.0)

    /** Staged attachments of the LAST send - retries (retry / Send anyway)
     *  re-send them instead of silently dropping the attachments. */
    private var lastStaged: List<PanelAttachment> = emptyList()

    /** U4-d conversation mode. Ask by default: least directive, no tools assumed. */
    internal var mode: ChatMode = ChatMode.ASK

    fun send(userText: String, confirmOverBudget: Boolean = false) =
        sendCore(userText, confirmOverBudget, reuseStaged = false)

    private fun sendCore(userText: String, confirmOverBudget: Boolean, reuseStaged: Boolean) {
        if (ui.value.streaming || userText.isBlank()) return
        // A new turn supersedes a previous interruption: from here on the transcript is live
        // again and must not keep carrying the "cut off" marker.
        interruptedReplyPending = false
        val sel = selection.value
        // U1 (657 finding d): a blank model id is a pair that cannot send - say so
        // instead of putting a request on the wire that is certain to be rejected.
        if (sel.modelId.isBlank()) {
            appendRow(ChatRowModel.Failed(
                "No model selected",
                "Pick a model for " + sel.providerId + ". For a custom endpoint, open Settings -> " +
                    "AI providers, edit the endpoint and add the model ids it serves (or fetch them " +
                    "with Test connection); the picker lists them here.", false))
            return
        }
        // WP3: presets like Ollama (local) need no key; everyone else does.
        val needsKey = com.codespace.ide.engine.ProviderPresets.byId(sel.providerId)?.requiresKey ?: true
        val effectiveSlot = sel.keySlot
        val key = if (needsKey) keyFor(effectiveSlot) else null
        if (needsKey && key.isNullOrBlank()) {
            appendRow(ChatRowModel.Failed(
                "No key stored for this provider",
                "The rebuilt chat uses your existing stored keys (read-only WP1 migration). " +
                    "Key management returns to Settings in WP7.", false))
            return
        }
        // WP3 reasoning control: bounded 1024 for native thinking providers,
        // OFF for effort-kind (a default reasoning_effort 400s non-reasoning models).
        val reasoningBudget = if (sel.providerId == "gemini" || sel.providerId == "anthropic") 1024 else 0
        // MISSION part 1: the model is told WHERE it is before it is asked anything. The
        // block is capped to a quarter of the payload budget so a big tree can never
        // crowd out the conversation, and a cut block says it was cut.
        val payloadChars = com.codespace.ide.engine.ModelCatalog.payloadBudgetChars(resolveCapability(sel))
        val wsBlock = WorkspaceContext.fit(
            workspaceBlock().trim(),
            (payloadChars / 4).coerceIn(0, WorkspaceContext.MAX_CHARS),
        )
        // AUDIT DEFECT D: resolve the endpoint the way it is configured NOW. The selection holds
        // a cache of the URL, and a stale cache must never decide where the request goes.
        val liveBaseUrl = baseUrlForProvider(sel.providerId, sel.baseUrl)
        val cfg = EngineConfig(
            modelId = sel.modelId,
            // U4-d: the mode rides the provider's own system slot (see ChatModePrompts).
            systemInstruction = ChatModePrompts.instruction(mode) +
                (if (AgentToolWire.modeRunsTools(mode)) AgentToolWire.protocol(mode) else "") +
                (if (wsBlock.isEmpty()) "" else "\n\n" + wsBlock),
            providerId = sel.providerId,
            baseUrl = liveBaseUrl,
            apiKey = key ?: "",   // no-key presets (Ollama): blank key, header omitted
            reasoningBudget = reasoningBudget,
            // WP5: the send budget scales with the model's context window (estimate);
            // WP7: the user override replaces the bundled row (estimate either way).
            maxPayloadChars = com.codespace.ide.engine.ModelCatalog.payloadBudgetChars(resolveCapability(sel)),
        ).copy(confirmOverBudget = confirmOverBudget)
        streamBuf.setLength(0)
        failoverAttempts = 0
        toolRound = 0
        keySlotsSeen.clear()
        keySlotsSeen.add(effectiveSlot)
        // WP8: staged attachments ride THIS send (and its failover retries).
        // Retry / Send anyway REUSE the staged copies; history keeps text
        // only, so nothing attachment-shaped persists anywhere.
        val staged = if (reuseStaged) lastStaged else pendingAttachments.value
        lastStaged = staged
        val attachments = staged.map {
            com.codespace.ide.engine.ChatAttachment(
                id = java.util.UUID.randomUUID().toString(),
                name = it.name, mimeType = it.mimeType,
                base64Data = it.base64, textData = it.text)
        }
        val chips = staged.map { com.codespace.ide.chat.ui.AttachmentChipModel(it.name, formatSizeBytes(it.sizeBytes)) }
        sendGeneration++
        val gen = sendGeneration
        autoRetries = 0
        val newRows = ArrayList<ChatRowModel>(2)
        // BATCH 5: status cards from the PREVIOUS attempt ("provider is rate limiting",
        // "offline") describe a moment that has passed; a new attempt must not inherit
        // them. Conversation content and notes are untouched.
        ChatStatusMapping.dropStaleStatus(ui.value.rows).let { kept ->
            if (kept.size != ui.value.rows.size) ui.value = ui.value.copy(rows = kept)
        }
        newRows.add(ChatRowModel.User(userText))
        if (chips.isNotEmpty()) newRows.add(ChatRowModel.Attachments(chips))
        if (!reuseStaged) pendingAttachments.value = emptyList()
        ui.value = ui.value.copy(
            rows = ui.value.rows + newRows,
            streaming = true,
            streamStartedMs = System.currentTimeMillis(),
            firstDeltaMs = 0L,
            lastUserText = userText,
        )
        attemptSend(cfg, effectiveSlot, userText, attachments, confirmOverBudget, gen)
    }

    /** One engine attempt; the failover path re-invokes this with the next key. */
    private fun attemptSend(
        cfg: EngineConfig,
        slotName: String,
        userText: String,
        attachments: List<com.codespace.ide.engine.ChatAttachment>,
        confirmOverBudget: Boolean,
        gen: Int = 0,
    ) {
        sendJob = scope.launch {
            try {
                engine.send(userText, attachments, cfg).collect { ev -> onEvent(ev, cfg, slotName, userText, attachments, confirmOverBudget, gen) }
            } catch (_: CancellationException) {
                // Cooperative cancel: partial text is already in the rows.
                ui.value = ui.value.copy(streaming = false)
            } catch (t: Throwable) {
                appendRow(ChatRowModel.Failed(t.message ?: "Send failed", "", true))
                ui.value = ui.value.copy(streaming = false)
            }
        }
    }

    /**
     * BATCH 6: Stop must also kill an ARMED retry, not just an in-flight read. Bumping the
     * generation is what makes the pending timer inert even if it wakes up afterwards.
     */
    fun cancel() {
        sendGeneration++
        sendJob?.cancel()
        // Stop means stopped. When a 429 retry is ARMED there is no active collect to notice
        // the cancellation, so streaming would otherwise stay true and the composer would
        // look busy forever - the user could never send again.
        if (ui.value.streaming) ui.value = ui.value.copy(streaming = false)
    }

    /**
     * MULTI-SESSION (M1) — SWITCHING AWAY FROM A STREAMING SESSION.
     *
     * Owner rule: do not run sessions in parallel yet, and do not lose what the model already
     * produced. So the stream is stopped and the partial reply is kept, marked incomplete by
     * the same rule that already handles "the app closed mid-stream" — on the next open the
     * user sees their text plus the honest note instead of a reply that pretends to have
     * finished.
     *
     * Returns true when a stream was actually stopped, so the caller can say so.
     */
    fun stopForSwitch(): Boolean {
        if (!ui.value.streaming) return false
        interruptedReplyPending = true
        cancel()
        // Save now rather than waiting for the debounce: the user is leaving this session and
        // the partial reply must already be on disk when the next one is shown.
        persistTranscript()
        return true
    }

    fun retry() {
        val t = ui.value.lastUserText
        if (t.isNotBlank() && !ui.value.streaming) sendCore(t, confirmOverBudget = false, reuseStaged = true)
    }

    /** Over-budget override: the owner-ruled explicit "Send anyway?" per send. */
    /** Over-budget card dismissed without sending: drop the pending card row. */
    fun dismissOverBudget() {
        val st = ui.value
        if (st.rows.lastOrNull() is ChatRowModel.OverBudget) {
            ui.value = st.copy(rows = st.rows.dropLast(1))
        }
    }

    /**
     * MULTI-SESSION (M1): this conversation is gone. Stop the stream and refuse further saves.
     * Called by the manager after a successful delete, never by the UI.
     */
    fun dispose() {
        disposed = true
        sendJob?.cancel()
    }

    /** WP7: settings "Clear conversation" - rows gone, keys and settings kept. */
    fun clearConversation() {
        sendJob?.cancel()
        interruptedReplyPending = false
        streamBuf.setLength(0)
        failoverAttempts = 0
        toolRound = 0
        keySlotsSeen.clear()
        lastStaged = emptyList()
        pendingAttachments.value = emptyList()
        ui.value = ui.value.copy(rows = emptyList(), streaming = false, lastUserText = "")
    }

    /** Bundled capability with the WP7 user override applied (unknown model + override works too). */
    private fun resolveCapability(sel: PanelSelection): com.codespace.ide.engine.ModelCapability? {
        val bundled = com.codespace.ide.engine.ModelCatalog.capabilityFor(sel.providerId, sel.modelId)
        val userTok = contextOverrideFor(sel.providerId, sel.modelId) ?: return bundled
        val base = bundled ?: com.codespace.ide.engine.ModelCapability(
            sel.modelId, userTok, supportsImages = false, supportsPdf = false)
        return base.copy(contextTokens = userTok)
    }

    fun sendAnyway() {
        val t = ui.value.lastUserText
        if (t.isNotBlank() && !ui.value.streaming) sendCore(t, confirmOverBudget = true, reuseStaged = true)
    }

    private fun appendRow(row: ChatRowModel) {
        ui.value = ui.value.copy(rows = ui.value.rows + listOf(row))
    }

    private fun onEvent(
        ev: EngineEvent,
        cfg: EngineConfig,
        slotName: String,
        userText: String,
        attachments: List<com.codespace.ide.engine.ChatAttachment>,
        confirmOverBudget: Boolean,
        gen: Int = 0,
    ) {
        when (ev) {
            is EngineEvent.Delta -> {
                streamBuf.append(ev.text)
                val text = streamBuf.toString()
                val st = ui.value
                // BATCH 6: an answer arriving means the wait is over - the "rate limited,
                // retrying in Ns" card must not sit above a reply that is streaming fine.
                // Walks back over a Thinking row, which sits between the card and the text.
                // The cleaned list is the BASE for this frame: assigning st.rows afterwards
                // would put the resolved wait card straight back.
                val rowsNow = ChatStatusMapping.clearResolvedWait(st.rows)
                val rows = if (rowsNow.lastOrNull() is ChatRowModel.AssistantStream)
                    rowsNow.dropLast(1) + listOf(ChatRowModel.AssistantStream(text, true))
                else rowsNow + listOf(ChatRowModel.AssistantStream(text, true))
                ui.value = st.copy(rows = rows,
                    firstDeltaMs = if (st.firstDeltaMs == 0L) System.currentTimeMillis() else st.firstDeltaMs)
            }
            is EngineEvent.Completed -> {
                val st = ui.value
                // Section 6: a finished reply is either an ANSWER or an ACTION request. The
                // decision is pure (AgentToolWire); this only carries it out. The raw
                // <tool> block never reaches the transcript.
                val decision = AgentToolWire.decide(mode, streamBuf.toString(), toolRound + 1)
                val shown = when (decision) {
                    is ToolDecision.Answer -> decision.text
                    is ToolDecision.Refused -> decision.visibleText
                    is ToolDecision.Run -> decision.visibleText
                }
                val rows = if (st.rows.lastOrNull() is ChatRowModel.AssistantStream)
                    st.rows.dropLast(1) + listOf(ChatRowModel.AssistantStream(shown, false))
                else st.rows + listOf(ChatRowModel.AssistantStream(shown, false))
                ui.value = st.copy(rows = rows, streaming = false)
                when (decision) {
                    is ToolDecision.Answer -> Unit
                    is ToolDecision.Refused -> appendRow(ChatRowModel.InfoNote(decision.reason))
                    is ToolDecision.Run -> runTools(decision.calls, cfg, slotName, confirmOverBudget, gen)
                }
            }
            is EngineEvent.HistoryTrimmed -> {
                // BATCH 5: TrimmedHistory was in the model and the renderer but nothing
                // ever produced it; a silently narrowed window made the model answer about
                // a conversation it could not see.
                if (ev.droppedCount > 0) appendRow(ChatRowModel.TrimmedHistory(ev.droppedCount))
            }
            is EngineEvent.Failed -> {
                val err = ev.error
                // WP6: OPT-IN key failover on auth/credits rejection - visible,
                // never silent, once per stored key, no wraparound.
                val nextSlot: String? = if (err is ChatError.Auth || err is ChatError.OutOfCredits) {
                    if (failoverOptIn()) chooseNextSlot(poolSlotsFor(selection.value.providerId).filter { it !in keySlotsSeen }) else null
                } else null
                if (nextSlot != null) {
                    failoverAttempts++
                    keySlotsSeen.add(nextSlot)
                    appendRow(ChatRowModel.InfoNote(
                        "Key rejected - switching to the next stored key (visible, opt-in, not silent)"))
                    val key2 = keyFor(nextSlot)
                    attemptSend(cfg.copy(apiKey = key2 ?: ""), nextSlot, userText, attachments, confirmOverBudget, gen)
                    return
                }
                // BATCH 6: a 429 gets a BOUNDED automatic retry. Everything else keeps its
                // own card immediately - retrying an auth failure or an empty account is
                // not a time problem, and pretending it is would burn the user's credits.
                val decision = RetryPolicy.decide(err, autoRetries)
                if (decision.retry && gen == sendGeneration) {
                    autoRetries++
                    appendRow(ChatRowModel.RateLimited(
                        decision.waitSec, decision.attempt, decision.maxAttempts))
                    // streaming stays TRUE: the request is still in flight (a Stop must be
                    // able to cancel the wait), and a new send would change the generation.
                    sendJob = scope.launch {
                        kotlinx.coroutines.delay(decision.waitSec * 1000L)
                        if (gen == sendGeneration) {
                            attemptSend(cfg, slotName, userText, attachments, confirmOverBudget, gen)
                        }
                    }
                    return
                }
                decision.refusal?.let { appendRow(ChatRowModel.InfoNote(it)) }
                // BATCH 5: the engine's taxonomy decides WHICH card, so 429 / 402 /
                // offline are no longer flattened into one generic red card.
                appendRow(ChatStatusMapping.rowForError(err))
                ui.value = ui.value.copy(streaming = false)
            }
        }
    }
    /**
     * Batch 2: hand a proposed write to the EXISTING staged-change store.
     *
     * Reuse, not a second mechanism: PendingChangesStore already owns the pending model,
     * the buffer-first base content, the drift check, the in-editor AiReviewStrip, Apply
     * and Discard. This only supplies the missing producer. Scope is set here so the strip
     * (which looks up "activeSessionId ?: default") and the chat agree on the same
     * session, and a project switch cannot leak proposals between projects.
     */
    private fun stageProposal(ctx: android.content.Context, call: ParsedToolCall): String {
        val args = org.json.JSONObject(call.argumentsJson)
        val rawPath = args.optString("path", "")
        val content = args.optString("content", "")
        if (rawPath.isBlank()) return "FAILED: write_file needs a path"
        com.codespace.ide.chat.PendingChangesStore.activeSessionId = projectId
        com.codespace.ide.chat.PendingChangesStore.activeProjectRoot =
            com.codespace.ide.security.TrustState.activeProjectRoot(ctx)
        val staged = com.codespace.ide.chat.PendingChangesStore.stage(rawPath, content, ctx)
        val rel = WorkspaceContext.relativeTo(
            com.codespace.ide.security.TrustState.activeProjectRoot(ctx), rawPath) ?: rawPath
        return if (staged.startsWith("staged:")) AgentToolWire.stagedNote(rel, content.length) else staged
    }

    /**
     * Section 6: run the calls in order, each behind the EXISTING approval gate
     * (AgentFlowGate -> ChatPermissionStore), then hand the results back so the model can
     * continue. Nothing here invents success: a refusal is reported as skipped, a throw as
     * FAILED, and what is displayed is the real output.
     */
    private fun runTools(
        calls: List<ParsedToolCall>,
        cfg: EngineConfig,
        slotName: String,
        confirmOverBudget: Boolean,
        // BATCH 6: the tool continuation is part of the SAME send, so it keeps that send's
        // generation - a retry armed for it still belongs to this message.
        gen: Int,
    ) {
        val ctx = appContext
        if (ctx == null) {
            appendRow(ChatRowModel.InfoNote("Tool execution is unavailable in this build."))
            return
        }
        toolRound++
        // Section 5: one change set per tool round, so two separate operations can never be
        // merged into one misleading Files Changed row.
        com.codespace.ide.agent.AgentWriteJournal.beginOperation(conversationId)
        ui.value = ui.value.copy(streaming = true)
        scope.launch {
            val results = StringBuilder()
            for (call in calls) {
                val summary = AgentToolWire.summary(call)
                appendRow(ChatRowModel.InfoNote(AgentToolWire.stepLabel(call) + ": " + summary))
                // Batch 2: EDIT-mode writes are PROPOSALS. Staging cannot touch the
                // user's files (it writes a private pending record), so it needs no
                // approval prompt - the user's Apply in the editor strip IS the
                // authorisation, and it runs through PendingChangesStore.apply's existing
                // drift-safe path. Agent mode keeps the gate and the direct write.
                if (AgentToolWire.isStagedWrite(mode, call.name)) {
                    val staged = try {
                        stageProposal(ctx, call)
                    } catch (t: Throwable) {
                        "FAILED: " + (t.message ?: "could not stage the proposal")
                    }
                    appendRow(ChatRowModel.InfoNote(
                        if (staged.startsWith("FAILED")) staged
                        else AgentToolWire.firstLine(staged)
                    ))
                    results.append(AgentToolWire.resultMessage(call, staged))
                    results.append("\n\n")
                    continue
                }
                val verdict = com.codespace.ide.agent.AgentFlowGate.awaitApproval(ctx, call.name, summary)
                if (verdict != com.codespace.ide.agent.AgentFlowGate.ApprovalVerdict.APPROVED) {
                    val why =
                        if (verdict == com.codespace.ide.agent.AgentFlowGate.ApprovalVerdict.TIMEOUT)
                            "no answer in 10 minutes" else "you declined"
                    appendRow(ChatRowModel.InfoNote("Skipped " + call.name + " - " + why))
                    results.append(AgentToolWire.resultMessage(call, "NOT RUN (" + why + ")"))
                    results.append("\n\n")
                    continue
                }
                val out = try {
                    com.codespace.ide.agent.AgentTools.executeTool(
                        call.name, org.json.JSONObject(call.argumentsJson), ctx)
                } catch (t: Throwable) {
                    "FAILED: " + (t.message ?: "unknown error")
                }
                appendRow(ChatRowModel.InfoNote(
                    (if (out.startsWith("FAILED")) "Failed - " else "Done - ") +
                        AgentToolWire.firstLine(out)
                ))
                results.append(AgentToolWire.resultMessage(call, out))
                results.append("\n\n")
            }
            // Internal continuation: no user bubble, same key and model, no re-sent files.
            streamBuf.setLength(0)
            failoverAttempts = 0
            ui.value = ui.value.copy(
                streaming = true,
                streamStartedMs = System.currentTimeMillis(),
                firstDeltaMs = 0L,
            )
            attemptSend(cfg, slotName, results.toString().trim(), emptyList(), confirmOverBudget, gen)
        }
    }
}

/** Pure: first unused slot in pool order, or null when exhausted. */
internal fun chooseNextSlot(remaining: List<String>): String? = remaining.firstOrNull()

/**
 * MULTI-SESSION (M1) — the session registry.
 *
 * Keyed by (projectId, conversationId) now, not by project alone: two conversations in one
 * project are two independent [ChatSession]s, each with its own rows, its own stream and its
 * own transcript file. Switching between them cannot merge messages, because nothing is
 * copied: you get a different object backed by a different file.
 *
 * WHAT IS DELIBERATELY NOT HERE: any UI. The active session is persisted and restored, the
 * operations exist and are tested, but nothing on screen chooses a session yet (that is M2).
 * The panel keeps calling `session(projectId)`, which resolves to the ACTIVE session - so the
 * single-conversation app behaves exactly as before while the multi-session machinery is in
 * place underneath it.
 */
class ChatSessionManager internal constructor(
    val reader: StoredCredentialsReader,
    /** Section 6: needed by the approval gate and the proot executor; null disables tools. */
    private val appContext: android.content.Context? = null,
    /**
     * M1: injected by tests so session behaviour can be proven without a Context. In the app
     * it is derived from [appContext], sharing one transcript store with the sink below so the
     * index and the transcripts always describe the same directory.
     */
    private val sessionStore: com.codespace.ide.chat.session.SessionIndexStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = HashMap<String, ChatSession>()

    /**
     * BATCH 4: one store per manager. Its writer scope is its own, so the pending save of
     * the turn the user just finished still lands after the panel is disposed.
     */
    private val transcriptStore: com.codespace.ide.chat.ChatTranscriptStore? =
        appContext?.let { com.codespace.ide.chat.ChatTranscriptStore(it.applicationContext) }

    private val transcripts: com.codespace.ide.chat.TranscriptSink? = transcriptStore

    /** The session table. Same directory as the transcripts (see ChatTranscriptStore.ROOT_DIR). */
    private val index: com.codespace.ide.chat.session.SessionIndexStore? =
        sessionStore ?: transcriptStore?.let { store ->
            val ctx = appContext
            if (ctx == null) null
            else com.codespace.ide.chat.session.SessionIndexStore(
                java.io.File(ctx.applicationContext.filesDir, com.codespace.ide.chat.ChatTranscriptStore.ROOT_DIR),
                transcripts = store,
            )
        }

    /** Active session id per project, cached: the index is read once per project per process. */
    private val activeIds = HashMap<String, String>()

    /**
     * M2: the active id as an OBSERVABLE value, so the panel can re-key its ChatSession when
     * the user picks another session. Kept beside [activeIds] rather than replacing it: the
     * map stays the source of truth for the (non-Compose) callers that already read it, and
     * this only mirrors it for composition.
     */
    private val activeFlows = HashMap<String, MutableStateFlow<String>>()

    /** Anything the user deserves to be told about the session table (adoption, recovery). */
    private val notes = HashMap<String, String?>()

    // ---------------------------------------------------------------- identity

    private fun key(projectId: String, conversationId: String): String =
        projectId + "\u0000" + conversationId

    /**
     * The id of the conversation this project is currently on.
     *
     * Resolved once per project per process and then cached, because this is called from
     * composition. A project that has never opened before is adopted here: the legacy "main"
     * conversation gets one metadata record and nothing else happens to it.
     */
    fun activeSessionId(projectId: String): String {
        val cached = synchronized(activeIds) { activeIds[projectId] }
        if (cached != null) return cached
        val store = index ?: return LEGACY_CONVERSATION_ID
        // ONE ensure: it may write the adoption record, so calling it twice would be a second
        // write for no reason (and a second chance to fail).
        val read = store.ensure(projectId)
        val resolved = when (read) {
            is com.codespace.ide.chat.session.SessionIndexRead.Ok -> read.index.resolvedActiveId
            is com.codespace.ide.chat.session.SessionIndexRead.Unreadable -> null
        }
        val id = resolved ?: LEGACY_CONVERSATION_ID
        synchronized(activeIds) { activeIds[projectId] = id }
        synchronized(notes) {
            notes[projectId] = when (read) {
                is com.codespace.ide.chat.session.SessionIndexRead.Ok -> read.note
                is com.codespace.ide.chat.session.SessionIndexRead.Unreadable -> read.reason
            }
        }
        return id
    }

    /**
     * The active conversation of a project as a flow. Collected by the panel so a switch, a new
     * session or a delete re-keys the chat instead of leaving it pointed at the old id.
     */
    fun activeSessionFlow(projectId: String): MutableStateFlow<String> = synchronized(activeFlows) {
        activeFlows.getOrPut(projectId) { MutableStateFlow(activeSessionId(projectId)) }
    }

    private fun publishActive(projectId: String, conversationId: String) {
        synchronized(activeIds) { activeIds[projectId] = conversationId }
        synchronized(activeFlows) {
            activeFlows.getOrPut(projectId) { MutableStateFlow(conversationId) }.value = conversationId
        }
    }

    /**
     * The last stored message of a conversation, or null when it has none.
     *
     * M2 needs it for two honest signals per row: whether the session has any content at all,
     * and whether its last reply finished. Callers must treat this as I/O and keep it off the
     * main thread; a failed read is reported as "no tail", never as an error the row shows.
     */
    internal fun transcriptTail(projectId: String, conversationId: String): com.codespace.ide.chat.StoredMessage? =
        try {
            transcriptStore?.readNow(projectId, conversationId)?.messages?.lastOrNull()
        } catch (_: Throwable) {
            null
        }

    /** The last thing worth saying about this project's session table, or null. */
    fun sessionNote(projectId: String): String? = synchronized(notes) { notes[projectId] }

    /**
     * The session table for a project, for the (not yet built) picker and for tests. Read is
     * strict: an unreadable table is reported, never presented as "no sessions".
     */
    internal fun sessionIndex(projectId: String): com.codespace.ide.chat.session.SessionIndexRead =
        index?.read(projectId) ?: com.codespace.ide.chat.session.SessionIndexRead.Ok(
            com.codespace.ide.chat.session.SessionIndex(),
        )

    fun containsSession(projectId: String, conversationId: String): Boolean {
        val r = sessionIndex(projectId)
        return r is com.codespace.ide.chat.session.SessionIndexRead.Ok && r.index.contains(conversationId)
    }

    // ---------------------------------------------------------------- sessions

    /** The active session for a project (unchanged signature: existing callers keep working). */
    fun session(projectId: String): ChatSession = session(projectId, activeSessionId(projectId))

    @Synchronized
    fun session(projectId: String, conversationId: String): ChatSession {
        val sessionKey = key(projectId, conversationId)
        // Attribution: whichever session is being handed out is the one the panel is showing,
        // so it is the one the edit journal must attribute new change sets to.
        com.codespace.ide.agent.AgentWriteJournal.activeSessionId = conversationId
        val existing = sessions[sessionKey]
        if (existing != null) return existing
        val created = ChatSession(
            projectId,
            { slot -> reader.keyValue(slot) },
            scope,
            baseUrlForProvider = { pid, fallback ->
                SelectionResolver.currentBaseUrl(
                    pid, fallback,
                    configuredProvidersFrom(reader.snapshot()),
                )
            },
            poolSlotsFor = { pid ->
                reader.snapshot().keySlots
                    .filter { it.hasKey && it.providerId == pid }
                    .sortedBy { it.poolIndex ?: 0 }
                    .map { it.slotName }
            },
            failoverOptIn = { ChatSettingsPrefs.failoverOptIn() },
            contextOverrideFor = { pid, mid -> ChatSettingsPrefs.contextOverride(pid, mid) },
            appContext = appContext,
            // Batch 1: same block for every request path that goes through chat; the
            // HTTP agent already builds its own from the same providers.
            workspaceBlock = { WorkspaceContextSource.block(appContext, projectId) },
            conversationId = conversationId,
            transcripts = transcripts,
        )
        sessions[sessionKey] = created
        return created
    }

    /** True while the given conversation is streaming (used by the switch rule below). */
    fun isStreaming(projectId: String, conversationId: String): Boolean =
        synchronized(this) { sessions[key(projectId, conversationId)] }?.ui?.value?.streaming == true

    /**
     * Creates an independent conversation and makes it active. Returns the new session, or a
     * reason. No messages are copied from the current one; a new session starts empty.
     */
    internal fun createSession(projectId: String, title: String? = null): com.codespace.ide.chat.session.SessionCreate {
        val store = index ?: return com.codespace.ide.chat.session.SessionCreate.Failed(
            "Session storage is unavailable in this build.",
        )
        return when (val created = store.create(projectId, title)) {
            is com.codespace.ide.chat.session.SessionCreate.Failed -> created
            is com.codespace.ide.chat.session.SessionCreate.Created -> {
                publishActive(projectId, created.meta.id)
                created
            }
        }
    }

    /**
     * Switches the active conversation, honouring the owner's streaming rule.
     *
     * The outgoing session's stream is STOPPED and its partial reply KEPT (see
     * [ChatSession.stopForSwitch]); nothing is run in parallel. Returns null on success, or a
     * reason the switch could not happen - in which case the old session stays active, so the
     * UI can never end up pointing at nothing.
     */
    fun switchTo(projectId: String, conversationId: String): String? {
        val store = index ?: return "Session storage is unavailable in this build."
        val currentId = activeSessionId(projectId)
        if (currentId == conversationId) return null

        // 1. The conversation must really exist; switching to a phantom would show an empty
        //    chat that saves over nothing.
        if (!containsSession(projectId, conversationId)) return "That session no longer exists."

        // 2. Persist the choice FIRST. If this fails, stop here with the old session still
        //    active: a switch that only happened in memory would silently revert on restart.
        when (val w = store.setActive(projectId, conversationId)) {
            is com.codespace.ide.chat.session.SessionWrite.Failed -> return w.reason
            is com.codespace.ide.chat.session.SessionWrite.Ok -> Unit
        }

        // 3. Then stop the outgoing stream, keeping what it produced.
        synchronized(this) { sessions[key(projectId, currentId)] }?.stopForSwitch()

        publishActive(projectId, conversationId)
        // The new session owns the edit journal from now on, so an undo cannot reach across.
        com.codespace.ide.agent.AgentWriteJournal.activeSessionId = conversationId
        return null
    }

    fun renameSession(projectId: String, conversationId: String, title: String): String? =
        when (val w = requireStore().rename(projectId, conversationId, title)) {
            is com.codespace.ide.chat.session.SessionWrite.Ok -> null
            is com.codespace.ide.chat.session.SessionWrite.Failed -> w.reason
        }

    fun archiveSession(projectId: String, conversationId: String, archived: Boolean): String? =
        when (val w = requireStore().setArchived(projectId, conversationId, archived)) {
            is com.codespace.ide.chat.session.SessionWrite.Ok -> null
            is com.codespace.ide.chat.session.SessionWrite.Failed -> w.reason
        }

    /**
     * Item 18: the mode this session was last used in. Read through the same store every other
     * session operation uses, so the mode cannot drift into a second source of truth. Any
     * unreadable state answers ASK, the mode that cannot write a file.
     */
    internal fun sessionMode(projectId: String, conversationId: String): com.codespace.ide.chat.session.SessionMode {
        val store = index ?: return com.codespace.ide.chat.session.SessionMode.ASK
        val read = store.read(projectId)
        if (read !is com.codespace.ide.chat.session.SessionIndexRead.Ok) {
            return com.codespace.ide.chat.session.SessionMode.ASK
        }
        return read.index.find(conversationId)?.mode
            ?: com.codespace.ide.chat.session.SessionMode.ASK
    }

    /** null on success, the reason otherwise: the caller reports it rather than swallowing it. */
    internal fun setSessionMode(
        projectId: String,
        conversationId: String,
        mode: com.codespace.ide.chat.session.SessionMode,
    ): String? =
        when (val w = requireStore().setMode(projectId, conversationId, mode)) {
            is com.codespace.ide.chat.session.SessionWrite.Ok -> null
            is com.codespace.ide.chat.session.SessionWrite.Failed -> w.reason
        }

    /** Marks a conversation as looked at, which is what "Read" is derived from. */
    fun markRead(projectId: String, conversationId: String): String? =
        when (val w = requireStore().markRead(projectId, conversationId)) {
            is com.codespace.ide.chat.session.SessionWrite.Ok -> null
            is com.codespace.ide.chat.session.SessionWrite.Failed -> w.reason
        }

    /**
     * Deletes a conversation.
     *
     * Order matters and is enforced by the store: metadata first, bytes second, so a failed
     * index write leaves the transcript recoverable. Only after that is the session's edit
     * journal dropped, and finally the in-memory session is closed - otherwise a deleted
     * conversation would keep a live ChatSession that could still write its transcript back.
     */
    internal fun deleteSession(projectId: String, conversationId: String): com.codespace.ide.chat.session.SessionDelete {
        val store = requireStore()
        val result = store.delete(projectId, conversationId)
        if (result is com.codespace.ide.chat.session.SessionDelete.Failed) return result
        com.codespace.ide.agent.AgentWriteJournal.forgetSession(conversationId)
        val removed = synchronized(this) { sessions.remove(key(projectId, conversationId)) }
        // Refuse further saves BEFORE dropping the reference: the record and the transcript are
        // already gone, and a pending write would recreate the file.
        removed?.dispose()
        if (activeSessionIdIfCached(projectId) == conversationId) {
            val nextActive = (sessionIndex(projectId) as? com.codespace.ide.chat.session.SessionIndexRead.Ok)
                ?.index?.resolvedActiveId ?: LEGACY_CONVERSATION_ID
            publishActive(projectId, nextActive)
        }
        return result
    }

    private fun activeSessionIdIfCached(projectId: String): String? =
        synchronized(activeIds) { activeIds[projectId] }

    private fun requireStore(): com.codespace.ide.chat.session.SessionIndexStore = index
        ?: throw IllegalStateException("Session storage is unavailable in this build.")

    fun close() {
        scope.cancel()
        synchronized(this) { sessions.clear() }
        synchronized(activeIds) { activeIds.clear() }
        synchronized(notes) { notes.clear() }
    }

    private companion object {
        /**
         * The conversation id used by every project before multi-session existed. Kept as the
         * fallback so a build without session storage - and the pre-upgrade conversation -
         * still resolve to the transcript that is already on disk (main.txt).
         */
        const val LEGACY_CONVERSATION_ID = com.codespace.ide.chat.session.SessionMeta.LEGACY_ID
    }
}
