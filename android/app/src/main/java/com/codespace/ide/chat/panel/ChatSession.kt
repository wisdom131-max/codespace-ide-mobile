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
    companion object {
        /**
         * The visible join. A continuation must not look like one uninterrupted answer: the user
         * is told exactly where the first response stopped.
         */
 const val MAX_BYTES: Long = 10L * 1024 * 1024 }
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

class ChatSession internal constructor(
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
    /**
     * 2026-11-04 (device finding #1) — the mode this session STARTS in.
     *
     * The session's mode used to be a hardcoded ASK and was only corrected when the owner
     * touched the chip. After a reopen the panel restored its chip from disk (Agent) while the
     * session - and therefore the request - stayed on ASK: the UI said "Agent", the engine sent
     * Ask instructions with no write/execute tools, and the assistant answered that it had no
     * access to tools. The manager now passes the STORED mode in at construction, so the chip
     * and the request cannot start out disagreeing.
     */
    initialMode: com.codespace.ide.chat.session.SessionMode =
        com.codespace.ide.chat.session.SessionMode.ASK,
) {
    private val streamBuf = StringBuilder()

    /**
     * 2026-11-04 guard (c): everything this TURN showed the model - tool results, file reads,
     * attachment text, injected workspace context. A text-dialect call that also appears in here
     * is an echo of untrusted content, never the model's own intent. Cleared at the start of
     * every send so nothing leaks across turns.
     */
    private val turnContext = ArrayList<String>()

    /** The capability note is context, not a per-send nag. */
    private var toolPlanNoted = false

    /**
     * Characters already shown to the user when the previous attempt failed and the user
     * pressed Retry. The resent request streams its own text from the beginning, so the
     * buffer is truncated back to this length on its first delta - otherwise the transcript
     * shows the partial answer followed by a second copy of it, which is what "the response
     * gets cut off" looks like from the outside.
     */
    private var retryKeepLen = 0
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
    private val requestGate = RequestGate()
    private val sendGeneration: Int
        get() = requestGate.current
    /** Automatic 429 re-attempts used for the CURRENT send. */
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
     * Area 6: what the composer should offer. A StateFlow so the bar recomposes on its own; the
     * rules live in [ContinuationState], not in the composable.
     */
    private val _continuationUi = kotlinx.coroutines.flow.MutableStateFlow(ContinuationState.COMPLETE)
    internal val continuationUi: kotlinx.coroutines.flow.StateFlow<ContinuationState> = _continuationUi

    /** True between "Continue" and the first event of the continuation stream. */
    @Volatile private var continuationInFlight = false

    /** Area 6: the plan built for the most recent continuation attempt, for the UI state. */
    @Volatile internal var lastContinuation: ContinuationPlan? = null

    /** Area 6: the last provider verdict for THIS session, for the turn summary and continuation. */
    @Volatile internal var lastFinish: com.codespace.ide.engine.FinishReason =
        com.codespace.ide.engine.FinishReason.UNKNOWN

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
                restoreFinish(loaded.messages)
                seedEngineFrom(loaded.messages)
                loaded.note?.let { appendRow(ChatRowModel.InfoNote(it)) }
                restoreFinished = true
                persistTranscript()
            }
            // Every row change (send, delta, completion, clear) schedules a save; the
            // store debounces, and the scope is the store's, not this session's.
            scope.launch { ui.collect { persistTranscript() } }
        }
    }

    /**
     * MEMORY CONTINUITY (2026-10-31) — the conversation outlives the panel.
     *
     * Symptom this fixes: close the chat tab, reopen it, ask about the same thing, and the
     * model answered as if you had never spoken. The rows were on disk and were even restored
     * to the screen, but the ENGINE was new and empty, so nothing from before was ever sent.
     *
     * Rules: only real dialogue is replayed (user and assistant). "note" rows are this UI's
     * own annotations, and replaying them would spend context on text the user never said.
     * An interrupted reply is replayed as it was stored, marker included, so the model sees
     * exactly what the user was looking at.
     */
    private fun seedEngineFrom(stored: List<com.codespace.ide.chat.StoredMessage>) {
        if (stored.isEmpty()) return
        val seed = com.codespace.ide.chat.ChatSeed.dialogue(stored)
        if (seed.isNotEmpty()) engine.restoreHistory(seed)
    }

    /** BATCH 4: hand the current transcript to the store (no-op before the restore ends). */
    /**
     * Area 6 restoration: the FINISH VERDICT comes back from the saved conversation, so a reopened
     * turn offers Continue exactly when it did before the app closed.
     *
     * Migration rule, stated once: a conversation with no recorded verdict restores as UNKNOWN.
     * That is neither "complete" nor "continuable", so an older cut-off reply keeps its incomplete
     * marker and its warning, but the Continue action stays hidden rather than being guessed from
     * the sticky flag.
     */
    private fun restoreFinish(messages: List<com.codespace.ide.chat.StoredMessage>) {
        val stored = messages.lastOrNull { it.role == "assistant" }?.finish
        lastFinish = com.codespace.ide.engine.FinishReason.fromStored(stored)
        _continuationUi.value = continuationStateFor(lastFinish)
    }

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
            com.codespace.ide.chat.ChatTranscript.rowsToMessages(
                st.rows,
                streaming,
                lastFinish = lastFinish.storedName.takeIf { lastFinish != com.codespace.ide.engine.FinishReason.UNKNOWN },
            ),
        ) { error ->
            if (!storageErrorShown) {
                storageErrorShown = true
                appendRow(ChatRowModel.InfoNote("Could not save this conversation: " + error))
            }
        }
    }

    /**
     * Switching provider rebuilds the engine; the CONVERSATION must survive it.
     *
     * Device finding 2026-11-04 #6/#12: an owner who hit a quota switched to another key or
     * provider and the assistant answered as if they had never spoken. The rows were still on
     * screen and on disk, but the engine - the only stateful part - was replaced empty.
     *
     * The engine is an implementation detail of where the bytes go, not of what was said, so a
     * rebuild replays exactly what a cold start replays: the stored dialogue, notes excluded.
     * Switching only the key slot leaves the engine alone (same wire), so nothing is replayed
     * there and no tokens are spent re-sending a conversation the provider already has.
     */
    fun select(sel: PanelSelection) {
        if (sel.providerId != selection.value.providerId) {
            engine = buildEngine(sel.providerId)
            val dialogue = com.codespace.ide.chat.ChatSeed.dialogue(
                com.codespace.ide.chat.ChatTranscript.rowsToMessages(ui.value.rows, false),
            )
            if (dialogue.isNotEmpty()) engine.restoreHistory(dialogue)
        }
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
        // Android's resolver returns null for plenty of ordinary files (.md, .kt, .json from
        // some providers), so a blank type is not an unreadable file - it is an unanswered
        // question. The name answers it, and it is already what the chip shows the user.
        val mime = mimeType.ifBlank { mimeForName(name) }
        // WP9 redaction gate: credential-shaped files never leave the device.
        if (looksLikeCredentialFile(name)) {
            appendRow(ChatRowModel.InfoNote(
                "Attachment refused: '" + name + "' looks like a credential file. Keys and secrets are never sent to a provider."))
            return
        }
        val isTextKind = text != null || mime.startsWith("text/") ||
            mime == "application/json" || mime == "application/xml" ||
            mime == "application/yaml" || mime == "application/x-yaml"
        if (!isTextKind && mime != "application/pdf" && !mime.startsWith("image/")) {
            appendRow(ChatRowModel.InfoNote(
                "Attachment refused: images, PDF and text files can be attached (got " + mime + ")."))
            return
        }
        // Capability gate applies to IMAGES/PDF only - every text model takes text.
        if (!isTextKind) {
            val sel = selection.value
            val cap = resolveCapability(sel)
            val unsupported = (mime.startsWith("image/") && cap != null && !cap.supportsImages) ||
                (mime == "application/pdf" && cap != null && !cap.supportsPdf)
            if (unsupported) {
                appendRow(ChatRowModel.InfoNote(
                    "Attachment refused: " + sel.modelId + " (capability estimate) does not take " +
                        (if (mime == "application/pdf") "PDF" else "image") + " attachments."))
                return
            }
        }
        val stagedText = if (isTextKind) (text ?: "") else null
        pendingAttachments.value = pendingAttachments.value +
            PanelAttachment(name, mime, if (stagedText != null) "" else base64, sizeBytes, stagedText)
    }

    companion object {
        /**
         * The visible join. A continuation must not look like one uninterrupted answer: the user
         * is told exactly where the first response stopped.
         */
        /** Test seam: the composer's mapping, without building a session. */
        internal fun continuationStateForTest(
            finish: com.codespace.ide.engine.FinishReason,
        ): ContinuationState = when {
            finish.complete -> ContinuationState.COMPLETE
            finish.continuable -> ContinuationState.NEEDS_CONTINUATION
            else -> ContinuationState.UNKNOWN_OR_INTERRUPTED
        }

        internal const val SEAM = "\n\n[continued after the output limit]\n\n"

        /** How many characters of accumulated tool output one turn may carry into the next call. */
        internal const val TOOL_RESULT_BUDGET_CHARS = 6_000

    /** Extension to MIME, used when the picker could not tell us. */
        internal fun mimeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "yml", "yaml" -> "application/yaml"
            "md", "markdown" -> "text/markdown"
            "txt", "log", "csv", "ini", "toml", "properties",
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp",
            "hpp", "cs", "go", "rs", "rb", "php", "swift", "sh", "bash", "zsh",
            "html", "css", "scss", "sql", "gradle", "patch", "diff" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
    }

    /** The x on an activity row: drop that one line, nothing else in the transcript moves. */
    /**
     * 2026-11-05 (tool discovery): let the panel answer a `/` command in the transcript.
     *
     * A plain InfoNote, because that is what this is - something the user must be able to read, and
     * deliberately NOT a ToolActivity row, which groups, collapses and is about work the assistant
     * did rather than an answer to a question.
     */
    fun note(text: String) {
        appendRow(ChatRowModel.InfoNote(text))
    }

    fun dismissRow(row: ChatRowModel) {
        val st = ui.value
        val at = st.rows.indexOfFirst { it === row }
        if (at < 0) return
        ui.value = st.copy(rows = st.rows.filterIndexed { i, _ -> i != at })
    }

    fun removePending(name: String) {
        pendingAttachments.value = pendingAttachments.value.filterNot { it.name == name }
    }

    /**
     * Remove exactly ONE pending attachment, by position.
     *
     * Owner item 5 requires that removing one chip leaves every other attachment and the draft text
     * untouched. Filtering by name cannot promise that: the same file attached twice would lose both
     * rows. The list is what the chips were built from, so an index addresses the row the user
     * actually tapped. Out-of-range (a stale chip after a rebuild) removes nothing.
     */
    fun removePendingAt(index: Int) {
        val next = PendingRemoval.withoutAt(pendingAttachments.value, index)
        if (next.size != pendingAttachments.value.size) pendingAttachments.value = next
    }

    /** WP8: chips for the pending row (name + size; the data itself never leaves memory). */
    fun pendingChips(): List<com.codespace.ide.chat.ui.AttachmentChipModel> =
        pendingAttachments.value.mapIndexed { i, it ->
            com.codespace.ide.chat.ui.AttachmentChipModel(it.name, formatSizeBytes(it.sizeBytes), i)
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

    /**
     * U4-d conversation mode. Initialised from the SESSION's stored mode (see [initialMode]),
     * never assumed: a restored Agent session must send Agent tools on its very first message.
     */
    internal var mode: ChatMode = ChatModePersistence.toUi(initialMode)

    fun send(userText: String, confirmOverBudget: Boolean = false) =
        sendCore(userText, confirmOverBudget, reuseStaged = false)

    /**
     * Area 6 - CONTINUE THE CUT-OFF ANSWER.
     *
     * Goes through the SAME dispatch as [send]: one lifecycle, one request gate, one persistence
     * path. What makes it a continuation is the prefix (the text already delivered, marked at the
     * seam) and the instruction from [Continuation.plan], which forbids repetition and forbids
     * re-running an executed tool.
     *
     * Returns the state to show. It refuses everything that is not an output ceiling: a filter, a
     * dropped connection, a user Stop and a silent stream ending all stay incomplete rather than
     * being "continued" into a second answer.
     */
    internal fun continueTurn(providerSupportsContinuation: Boolean = true): ContinuationState {
        // 2026-11-12 audit: `continuationInFlight` was written and cleared but never READ, so a
        // second Continue tap between "sent" and the first stream event started a second
        // continuation. The flag is now the guard it was always meant to be.
        if (!Continuation.mayStart(streaming = ui.value.streaming, inFlight = continuationInFlight)) {
            return ContinuationState.CONTINUING
        }
        val delivered = streamBuf.toString()
        val plan = Continuation.plan(
            finish = lastFinish,
            deliveredText = delivered,
            mode = mode.name.lowercase(),
            providerSupportsContinuation = providerSupportsContinuation,
        )
        lastContinuation = plan
        if (!plan.allowed) return plan.state
        lastFinish = com.codespace.ide.engine.FinishReason.UNKNOWN
        continuationInFlight = true
        _continuationUi.value = ContinuationState.CONTINUING
        val prefix = if (delivered.isBlank()) "" else delivered + SEAM
        sendCore(
            plan.instruction,
            confirmOverBudget = false,
            reuseStaged = true,
            replaceTurn = true,
            continuationPrefix = prefix,
        )
        return ContinuationState.CONTINUING
    }

    /** Area 6: finish verdict -> what the composer may offer. Pure and total. */
    internal fun continuationStateFor(finish: com.codespace.ide.engine.FinishReason): ContinuationState =
        when {
            finish.complete -> ContinuationState.COMPLETE
            finish.continuable -> ContinuationState.NEEDS_CONTINUATION
            else -> ContinuationState.UNKNOWN_OR_INTERRUPTED
        }

    /** What the composer should say about continuation right now. */
    internal val continuationState: ContinuationState
        get() = when {
            ui.value.streaming && lastContinuation?.state == ContinuationState.CONTINUING -> ContinuationState.CONTINUING
            lastFinish.complete -> ContinuationState.COMPLETE
            lastFinish.continuable -> ContinuationState.NEEDS_CONTINUATION
            lastFinish == com.codespace.ide.engine.FinishReason.UNKNOWN -> ContinuationState.UNKNOWN_OR_INTERRUPTED
            else -> ContinuationState.UNKNOWN_OR_INTERRUPTED
        }

    /**
     * [replaceTurn] is Retry. A retry re-asks the SAME turn: the user message and its attachment
     * row are already in the transcript and must not be added again (device finding #14 - three
     * "Read wire-test/app.conf" bubbles after one rate-limited send). The failed status row is
     * still dropped by dropStaleStatus, so the retry replaces the outcome, not the question.
     */
    private fun sendCore(
        userText: String,
        confirmOverBudget: Boolean,
        reuseStaged: Boolean,
        replaceTurn: Boolean = false,
        /**
         * Area 6: text the answer already has. Present only for a continuation, and it seeds the
         * stream buffer so the continuation APPENDS to the cut-off answer instead of replacing it.
         * A null value is an ordinary send.
         */
        continuationPrefix: String? = null,
    ) {
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
            // P0-1: this is the LOCAL case and it must never look like a provider rejection.
            // It names the slot that was read, so a misattributed slot is visible immediately.
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[keys] send: no key in slot " + effectiveSlot + " for provider " + sel.providerId, "warn")
            appendRow(ChatRowModel.Failed(
                com.codespace.ide.engine.ChatError.Auth.missingKeyVerdict(
                    sel.providerId, effectiveSlot),
                "The chat reads the key slot named above. Settings > AI keys lists every slot and " +
                    "shows which ones hold a key.", false))
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
        // Tri-state capability (advisor ruling 2026-11-04): unknown keeps today's behaviour,
        // an explicit "no" withholds the wire and says so once, a tool-less mode never sends it.
        val toolPlan = ToolOffering.plan(mode, resolveCapability(sel)?.supportsTools)
        if (toolPlan.note != null && !toolPlanNoted) {
            toolPlanNoted = true
            appendRow(ChatRowModel.InfoNote(toolPlan.note))
        }
        // L2/L4 (owner approval 2026-11-11): a fresh task starts with a lean offer. The loadout is
        // per TASK, so tools discovered for the previous message are not silently still there.
        ToolLoadout.beginTask(mode)
        val offer = ToolOffer.plan(mode, userText)
        // 2026-11-11: the offer note does NOT become a chat row. One note per message is noise the
        // user learns to ignore; the toolset line lives in the instruction (below), and every load
        // is already visible as a tool_search activity row.
        offeredAtSend = offer.names
        wsBlockForRefresh = wsBlock
        // FACTS: retrieved once per turn, from the message that actually asked for it.
        memoryAtSend = memoryBlockFor(userText)
        requestAtSend = userText
        loadoutVersionAtSend = ToolLoadout.version()
        val cfg = EngineConfig(
            modelId = sel.modelId,
            // U4-d: the mode rides the provider's own system slot (see ChatModePrompts).
            systemInstruction = buildInstruction(
                mode, toolPlan.sendTools, wsBlock, offer.names, memoryAtSend),
            providerId = sel.providerId,
            baseUrl = liveBaseUrl,
            apiKey = key ?: "",   // no-key presets (Ollama): blank key, header omitted
            // Capability row, not a guess: newer OpenAI reasoning models refuse max_tokens.
            maxTokensField = com.codespace.ide.engine.MaxTokensField.forModel(
                sel.providerId, liveBaseUrl, sel.modelId),
            keySlotName = effectiveSlot,   // P0-1: the NAME travels with the config, never the value
            reasoningBudget = reasoningBudget,
            // WP5: the send budget scales with the model's context window (estimate);
            // WP7: the user override replaces the bundled row (estimate either way).
            maxPayloadChars = com.codespace.ide.engine.ModelCatalog.payloadBudgetChars(resolveCapability(sel)),
        ).copy(confirmOverBudget = confirmOverBudget)
        streamBuf.setLength(0)
        if (continuationPrefix != null) streamBuf.append(continuationPrefix)
        turnContext.clear()
        wsBlock.takeIf { it.isNotBlank() }?.let { turnContext.add(it) }
        failoverAttempts = 0
        toolRound = 0
        keySlotsSeen.clear()
        keySlotsSeen.add(effectiveSlot)
        // P0-1 debug line: which slot answered, MEMORY-ONLY, name only. This is the line that
        // tells a misattributed slot apart from a provider rejection in one glance.
        com.codespace.ide.diagnostics.AppOutputLog.log(
            "[keys] send: provider=" + sel.providerId + " slot=" + effectiveSlot +
                " keyPresent=" + !key.isNullOrBlank() + " model=" + sel.modelId, "info")
        // WP8: staged attachments ride THIS send (and its failover retries).
        // Retry / Send anyway REUSE the staged copies; history keeps text
        // only, so nothing attachment-shaped persists anywhere.
        val staged = if (reuseStaged) lastStaged else pendingAttachments.value
        lastStaged = staged
        // view_image cannot return bytes from a tool (a String return), so it queues them;
        // they become attachments on THIS send, which the adapter already sends as image parts.
        val toolImages = runCatching { com.codespace.ide.agent.ToolImageQueue.drain() }.getOrDefault(emptyList())
        val attachments = staged.map {
            com.codespace.ide.engine.ChatAttachment(
                id = java.util.UUID.randomUUID().toString(),
                name = it.name, mimeType = it.mimeType,
                base64Data = it.base64, textData = it.text)
        } + toolImages.map {
            com.codespace.ide.engine.ChatAttachment(
                id = java.util.UUID.randomUUID().toString(),
                name = it.name, mimeType = it.mimeType, base64Data = it.base64)
        }
        for (a in attachments) a.textData?.let { if (it.isNotBlank()) turnContext.add(it) }
        val chips = staged.map { com.codespace.ide.chat.ui.AttachmentChipModel(it.name, formatSizeBytes(it.sizeBytes)) }
        // DUPLICATE REPLIES: a send that arrives while a request is streaming must replace it, not
        // stack a second stream writing the same transcript. An identical tap within the double-tap
        // window is ignored outright; the answer already on its way is the answer.
        val elapsedRunning = System.currentTimeMillis() - ui.value.streamStartedMs
        when (ReplyOwnership.startAction(sendJob?.isActive == true, userText == ui.value.lastUserText, elapsedRunning)) {
            ReplyOwnership.Start.IGNORE_DUPLICATE_TAP -> return
            ReplyOwnership.Start.REPLACE_RUNNING -> {
                streamBuf.setLength(0)
                sendJob?.cancel()
            }
            ReplyOwnership.Start.FRESH -> Unit
        }
        requestGate.bump()
        val gen = requestGate.bump()
        // Every send marks the ledger: a source recorded during this turn is shown under THIS
        // answer. Reading the size (not clearing) keeps a source from an earlier turn intact for
        // the answer that already cites it.
        ledgerMark = com.codespace.ide.agent.research.SourceLedger.all().size
        val newRows = ArrayList<ChatRowModel>(2)
        // BATCH 5: status cards from the PREVIOUS attempt ("provider is rate limiting",
        // "offline") describe a moment that has passed; a new attempt must not inherit
        // them. Conversation content and notes are untouched.
        ChatStatusMapping.dropStaleStatus(ui.value.rows).let { kept ->
            if (kept.size != ui.value.rows.size) ui.value = ui.value.copy(rows = kept)
        }
        // A continuation is not a new question, so it adds no user bubble.
        if (!replaceTurn && continuationPrefix == null) {
            newRows.add(ChatRowModel.User(userText))
            if (chips.isNotEmpty()) newRows.add(ChatRowModel.Attachments(chips))
        }
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
        requestGate.bump()
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

    /**
     * Retry the last turn.
     *
     * 2026-11-04 (device finding #14): a retry REPLACES the turn's outcome; passing the same
     * question through the normal send path appended another user row each time.
     *
     * A turn whose tools already RAN is retried as it stands, and that is deliberate. Side effects
     * (a file written, a deletion staged) really happened and are not rolled back - the journal
     * keeps them, Files Changed keeps listing them, and Undo Last Edit is the way back. What a
     * retry must never do is duplicate the record of them: no second user row, no second
     * Files-Changed entry, because the change set belongs to the turn, not to the attempt.
     * VS Code behaves the same way: a retried fetch replaces the response while the tool results
     * already collected for the round stay in the session (`toolCallResults`, keyed by tool call
     * id - intents/node/toolCallingLoop.ts:170, 1824; the retry itself is
     * prompt/node/chatMLFetcher.ts:313-345).
     */
    fun retry() {
        val t = ui.value.lastUserText
        if (t.isNotBlank() && !ui.value.streaming) {
            sendCore(t, confirmOverBudget = false, reuseStaged = true, replaceTurn = true)
        }
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
        // MEMORY CONTINUITY: the rows are the visible half of a clear; the engine holds the
        // other half. Without this the transcript looks empty while the model still remembers
        // everything that was just cleared - the worst of both states.
        engine.restoreHistory(emptyList())
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

    /**
     * 2026-11-11: the ledger index at the moment this turn began. Sources recorded after it belong
     * to THIS answer, which is what keeps a source attached to the turn that fetched it instead of
     * to whichever turn happens to finish last.
     */
    @Volatile private var ledgerMark: Int = 0

    /** L4: what this turn was offered, and the loadout version it was built from. */
    private var offeredAtSend: List<String> = emptyList()
    private var wsBlockForRefresh: String = ""
    /** FACTS: the memory block this turn was built with, so a loadout refresh keeps it. */
    private var memoryAtSend: String = ""
    /** P4.5: the request the current instruction was built for, so MCP advice matches this turn. */
    private var requestAtSend: String = ""
    /** DUPLICATE REPLIES: the last generation whose completion was already applied. */
    private var lastCompletedGen: Int = 0
    private var loadoutVersionAtSend: Int = 0

    /**
     * L2/L4: the system instruction for a turn.
     *
     * Rebuilt (not mutated) whenever the loadout changes, which is exactly what makes a discovered
     * tool usable on the NEXT continuation: the wire protocol, the mode rules and the approval gate
     * are untouched, only the advertised list grows. A provider that never supported function
     * calling still sees plain text, so nothing here is provider-specific.
     */
    private fun buildInstruction(
        mode: ChatMode,
        sendTools: Boolean,
        wsBlock: String,
        offered: List<String>,
        memory: String = "",
        request: String = requestAtSend,
    ): String {
        // 2026-11-11 (Wave 1 Group A budget fix): this used to append AgentToolWire.protocol(mode)
        // - which advertised the ENTIRE mode catalogue - and then the filtered block on top, so the
        // prompt carried both (Agent 13,170 chars, Ask 5,848, measured). Composition now lives in
        // ChatInstructions, which the budget tests exercise directly, and definitions enter the
        // prompt in exactly one place: the offer plus whatever was loaded.
        val loaded = ToolLoadout.extra()
        val mcpLoaded = loaded.filter { it.startsWith("mcp_") }
        // LIFECYCLE: a null appContext no longer erases the docs - the manager answers from its
        // last enabled snapshot, so a headless continuation still sees the tool it discovered.
        val mcpDocs = if (sendTools && mcpLoaded.isNotEmpty()) {
            try {
                com.codespace.ide.agent.McpClientManager.docsFor(appContext, mcpLoaded)
            } catch (e: Exception) {
                ""
            }
        } else ""
        // P4.5: when the request could be served by an MCP tool that is not loaded, the prompt says
        // so in one line - it is never loaded automatically, and the approval gate is unchanged.
        val mcpHint = if (sendTools) {
            runCatching {
                com.codespace.ide.agent.McpClientManager.suggestFor(appContext, request, loaded)
            }.getOrNull().orEmpty()
        } else ""
        return ChatInstructions.build(
            mode, sendTools, offered, wsBlock,
            if (mcpHint.isBlank()) mcpDocs else mcpDocs + "\n\n" + mcpHint,
            loaded, memory,
        )
    }

    /**
     * FACTS (2026-11-11): the remembered facts relevant to THIS message, already bounded by
     * [com.codespace.ide.agent.facts.FactRetrieval]. Retrieval is scoped to the open project plus
     * the user's own facts, so a fact learned in another repository cannot reach this prompt.
     */
    private fun memoryBlockFor(request: String): String {
        val ctx = appContext ?: return ""
        return try {
            com.codespace.ide.agent.facts.FactRetrieval.blockFor(
                context = ctx,
                projectId = projectId,
                request = request,
                maxChars = com.codespace.ide.agent.facts.FactRetrieval.MAX_PROMPT_CHARS,
            )
        } catch (_: Throwable) {
            ""
        }
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
        // Area 5: an event that belongs to a request which is no longer current is DROPPED here,
        // before it can touch rows, tool calls or the buffer. This is what stops a stopped or
        // replaced stream from appending to a newer answer.
        if (requestGate.stale(gen)) return
        when (ev) {
            is EngineEvent.Delta -> {
                if (retryKeepLen > 0) {
                    streamBuf.setLength(retryKeepLen)
                    retryKeepLen = 0
                }
                streamBuf.append(ev.text)
                val text = streamBuf.toString()
                val st = ui.value
                val rowsNow = st.rows
                val rows = if (rowsNow.lastOrNull() is ChatRowModel.AssistantStream)
                    rowsNow.dropLast(1) + listOf(ChatRowModel.AssistantStream(text, true))
                else rowsNow + listOf(ChatRowModel.AssistantStream(text, true))
                ui.value = st.copy(rows = rows,
                    firstDeltaMs = if (st.firstDeltaMs == 0L) System.currentTimeMillis() else st.firstDeltaMs)
            }
            is EngineEvent.Truncated -> {
                // One line, after the reply, saying what the reader has is partial. The streamed
                // text is untouched: a cut answer is still evidence, and deleting it helps nobody.
                val st = ui.value
                ui.value = st.copy(rows = st.rows + listOf(ChatRowModel.InfoNote(ev.visibleText)))
            }
            is EngineEvent.Completed -> {
                if (!ReplyOwnership.mayComplete(gen, lastCompletedGen, !requestGate.stale(gen))) return
                // Area 6: the provider's verdict decides whether this turn may be called
                // finished. A ceiling marks the reply incomplete through the SAME sticky flag
                // that already survives every later save, so a reopen cannot upgrade a cut-off
                // answer into a complete one.
                lastCompletedGen = gen
                lastFinish = ev.finish
                continuationInFlight = false
                _continuationUi.value = continuationStateFor(ev.finish)
                if (!ev.finish.complete) interruptedReplyPending = true
                val st = ui.value
                // Section 6: a finished reply is either an ANSWER or an ACTION request. The
                // decision is pure (AgentToolWire); this only carries it out. The raw
                // <tool> block never reaches the transcript.
                val decision = AgentToolWire.decide(
                    mode,
                    streamBuf.toString(),
                    toolRound + 1,
                    toolsOffered = AgentToolWire.modeRunsTools(mode),
                    turnContext = turnContext.toList(),
                )
                val shown = when (decision) {
                    is ToolDecision.Answer -> decision.text
                    is ToolDecision.Refused -> decision.visibleText
                    is ToolDecision.Run -> decision.visibleText
                }
                // PROGRESS when the turn is about to run tools: that narration is part of the
                // task's work, so it belongs INSIDE the tree. An answer stays a turn-level row.
                val kind = if (decision is ToolDecision.Run) ChatRowModel.StreamKind.PROGRESS
                else ChatRowModel.StreamKind.ANSWER
                val rows = if (st.rows.lastOrNull() is ChatRowModel.AssistantStream)
                    st.rows.dropLast(1) + listOf(ChatRowModel.AssistantStream(shown, false, kind))
                else st.rows + listOf(ChatRowModel.AssistantStream(shown, false, kind))
                val allSources = com.codespace.ide.agent.research.SourceLedger.all()
                val turnSources = if (decision is ToolDecision.Answer && allSources.size > ledgerMark)
                    com.codespace.ide.agent.research.ResearchSources
                        .fromLedger(allSources.drop(ledgerMark)) else emptyList()
                val rowsWithSources =
                    if (turnSources.isEmpty()) rows else rows + listOf(ChatRowModel.Sources(turnSources))
                ui.value = st.copy(rows = rowsWithSources, streaming = false)
                when (decision) {
                    // 2026-11-05: a panel that asked a question (Source Control's commit message)
                    // takes the answer back. Only a real ANSWER qualifies - a tool round is not a
                    // reply to anyone, and delivering mid-loop would hand over half a turn.
                    is ToolDecision.Answer -> ChatHandoff.deliverAnswer(decision.text)
                    is ToolDecision.Refused -> appendRow(ChatRowModel.InfoNote(decision.reason))
                    is ToolDecision.Run -> runTools(decision.calls, cfg, slotName, confirmOverBudget, gen)
                }
            }
            is EngineEvent.Failed -> {
                // The continuation never produced a Completed event; without this the flag would
                // stay set and Continue would be refused for the rest of the session.
                continuationInFlight = false
                _continuationUi.value = ContinuationState.CONTINUATION_FAILED
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
                    attemptSend(
                        cfg.copy(apiKey = key2 ?: "", keySlotName = nextSlot),
                        nextSlot, userText, attachments, confirmOverBudget, gen,
                    )
                    return
                }
                // Owner ruling 2026-11-01: no automatic retry ladder, no invented wait cap.
                // VS Code's chat has neither - it renders the provider's error with a Retry
                // button and resends only when that button is pressed
                // (chatErrorConfirmationPart.ts:79). The wait is whatever the provider said.
                // The already-streamed prefix is kept, so a pressed Retry continues from it.
                retryKeepLen = streamBuf.length
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
        if (call.name == "grep_replace_all") return stageGrepReplace(ctx, call)
        // Wave 1 Group B: a copy is a multi-file write, so in EDIT mode it becomes N proposals -
        // one per text file - through the same store and the same Apply/Discard strip. A binary
        // makes planStagedCopy refuse the whole operation instead of half-writing a tree.
        if (call.name == "copy_path") return stageCopyPath(ctx, call)
        val args = org.json.JSONObject(call.argumentsJson)
        val rawPath = args.optString("path", "")
        val content = args.optString("content", "")
        if (rawPath.isBlank()) return "FAILED: write_file needs a path"
        com.codespace.ide.chat.PendingChangesStore.activeSessionId = projectId
        // 17b: activate() sets the scope AND reloads proposals that survived the last run.
        com.codespace.ide.chat.PendingChangesStore.activate(
            ctx, com.codespace.ide.security.TrustState.activeProjectRoot(ctx))
        val staged = com.codespace.ide.chat.PendingChangesStore.stage(rawPath, content, ctx)
        val rel = WorkspaceContext.relativeTo(
            com.codespace.ide.security.TrustState.activeProjectRoot(ctx), rawPath) ?: rawPath
        return if (staged.startsWith("staged:")) AgentToolWire.stagedNote(rel, content.length) else staged
    }

    /** Wave 1 Group B: EDIT-mode copy_path. Nothing here writes to the workspace. */
    private fun stageCopyPath(ctx: android.content.Context, call: ParsedToolCall): String {
        com.codespace.ide.chat.PendingChangesStore.activeSessionId = projectId
        com.codespace.ide.chat.PendingChangesStore.activate(
            ctx, com.codespace.ide.security.TrustState.activeProjectRoot(ctx))
        val out = com.codespace.ide.agent.AgentTools.planStagedCopy(call.argumentsJson, ctx)
        if (!out.startsWith("staged:")) return out
        val count = out.removePrefix("staged:").substringBefore('|').toIntOrNull() ?: 0
        val to = out.substringAfter('|')
        return "Proposed copy staged: " + count + " file" + (if (count == 1) "" else "s") +
            " -> " + to + ". Nothing has been written to disk. Review and Apply or Discard " +
            "each proposal in the editor strip."
    }

    /**
     * Wave 0.1: a multi-file replace becomes N proposals, never a write.
     *
     * PendingChangesStore is keyed by session and path, so several proposals for one operation are
     * representable without new machinery: each file is staged on its own, applied on its own, and
     * the user keeps the same Apply/Discard strip they already trust. The plan is the SAME one the
     * Agent-mode executor uses, so what is proposed is exactly what would have been written.
     */
    private fun stageGrepReplace(ctx: android.content.Context, call: ParsedToolCall): String {
        val plan = com.codespace.ide.agent.AgentTools.planGrepReplaceAll(call.argumentsJson, ctx)
        plan.refused?.let { return "FAILED: " + it }
        if (plan.changes.isEmpty()) return "No matches, so nothing was staged."
        com.codespace.ide.chat.PendingChangesStore.activeSessionId = projectId
        com.codespace.ide.chat.PendingChangesStore.activate(
            ctx, com.codespace.ide.security.TrustState.activeProjectRoot(ctx))
        var staged = 0
        val failures = ArrayList<String>()
        for (change in plan.changes) {
            val updated = change.updated
            if (updated == null) {
                failures.add(change.rel + " (no content to stage)")
                continue
            }
            val outcome = try {
                com.codespace.ide.chat.PendingChangesStore.stage(
                    change.file.absolutePath, updated, ctx)
            } catch (t: Throwable) {
                "FAILED: " + (t.message ?: "could not stage")
            }
            if (outcome.startsWith("staged:")) staged++ else failures.add(change.rel + " (" + outcome + ")")
        }
        val head = "staged:" + staged + " file(s), " + plan.total + " replacement(s). Nothing has been " +
            "written to disk. Review each proposal in the editor strip and Apply or Discard there."
        return if (failures.isEmpty()) head
        else head + " Not staged: " + failures.joinToString(", ")
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
            // P4.6/4.7: the same results, itemised, so a long tool chain can be bounded instead of
            // growing without a ceiling. Text stays authoritative; this is what gets trimmed.
            val resultItems = ArrayList<com.codespace.ide.agent.ToolContextTrim.Item>()
            // P4.A: the batch is PLANNED before it runs. ToolPlanner existed and computed dependency
            // waves that nothing on the request path ever asked for. Here its waves decide the
            // sequence, and an exact repeat of a call already answered in this batch reuses that
            // result instead of running the side effect again.
            val planNames = calls.map { it.name }
            val batchPlan = com.codespace.ide.agent.ToolPlanner.plan(
                planNames.joinToString(" "), planNames.toSet())
            val planOrder = com.codespace.ide.agent.ToolBatchPlan.order(batchPlan, planNames)
            val repeats = com.codespace.ide.agent.ToolBatchPlan.duplicates(
                planNames, calls.map { it.argumentsJson })
            val answered = HashMap<Int, String>()
            com.codespace.ide.agent.ToolBatchPlan.note(batchPlan, planNames)?.let { appendRow(ChatRowModel.InfoNote(it)) }
            // P4.B: execution itself is coordinated. Independent read-only calls run together and
            // bounded; every write, command, connector and MCP call stays strictly sequential and in
            // plan order. Answers are collected by index, so the transcript order is the plan's.
            suspend fun executeOnce(idx: Int): String = try {
                com.codespace.ide.agent.AgentTools.executeTool(
                    calls[idx].name, org.json.JSONObject(calls[idx].argumentsJson), ctx)
            } catch (t: Throwable) {
                "FAILED: " + (t.message ?: "unknown error")
            }

            fun noteAndRecord(idx: Int, out: String) {
                val call = calls[idx]
                appendRow(ChatRowModel.InfoNote(
                    (if (out.startsWith("FAILED")) "Failed - " else "Done - ") +
                        AgentToolWire.firstLine(out)
                ))
                answered[idx] = out
                // Guard (c): a tool result joins the echo corpus for the REST of this turn.
                turnContext.add(out)
                results.append(AgentToolWire.resultMessage(call, out))
                results.append("\n\n")
                // Pinned when the result carries a citation marker: evidence the answer must keep.
                resultItems.add(
                    com.codespace.ide.agent.ToolContextTrim.Item(
                        call.name, out, pinned = out.contains("[S"),
                    ),
                )
            }

            val readyReads = ArrayList<Int>()
            suspend fun flushReads() {
                if (readyReads.isEmpty()) return
                val queued = readyReads.toList()
                readyReads.clear()
                val outs = com.codespace.ide.agent.ToolExecutionCoordinator.collect(
                    listOf(queued)) { i -> executeOnce(i) }
                for (i in queued) noteAndRecord(i, outs.getValue(i))
            }

            for (idx in planOrder) {
                val call = calls[idx]
                val summary = AgentToolWire.summary(call)
                // Stable id: the tree collapses by identity, so a streaming update cannot reset it.
                appendRow(ChatRowModel.ToolActivity(
                    AgentToolWire.stepLabel(call), summary,
                    id = "r" + toolRound + "c" + (idx + 1)))
                val already = repeats[idx]?.takeIf { answered.containsKey(it) }
                if (already != null) {
                    val reused = answered.getValue(already)
                    appendRow(ChatRowModel.InfoNote(
                        "Same call as step " + (already + 1) + " - reused that result instead of repeating it."))
                    results.append(AgentToolWire.resultMessage(call, reused))
                    results.append("\n\n")
                    resultItems.add(com.codespace.ide.agent.ToolContextTrim.Item(
                        call.name, reused, pinned = reused.contains("[S")))
                    continue
                }
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
                    answered[idx] = staged
                    results.append(AgentToolWire.resultMessage(call, staged))
                    results.append("\n\n")
                    resultItems.add(com.codespace.ide.agent.ToolContextTrim.Item(call.name, staged))
                    continue
                }
                // Wave 0.2 (owner approval 2026-11-11): a connector call egresses to a third
                // party with the user's stored token, so it asks even under AUTO_ALL. The rule is
                // the gate's own (requiresForcedApproval); passing it here keeps the intent visible
                // at the call site without letting this call site define the policy.
                val verdict = com.codespace.ide.agent.AgentFlowGate.awaitApproval(
                    ctx, call.name, summary,
                    forceApproval = com.codespace.ide.agent.AgentFlowGate.requiresForcedApproval(call.name),
                )
                if (verdict != com.codespace.ide.agent.AgentFlowGate.ApprovalVerdict.APPROVED) {
                    val why =
                        if (verdict == com.codespace.ide.agent.AgentFlowGate.ApprovalVerdict.TIMEOUT)
                            "no answer in 10 minutes" else "you declined"
                    appendRow(ChatRowModel.InfoNote("Skipped " + call.name + " - " + why))
                    answered[idx] = "NOT RUN (" + why + ")"
                    results.append(AgentToolWire.resultMessage(call, "NOT RUN (" + why + ")"))
                    results.append("\n\n")
                    continue
                }
                if (com.codespace.ide.agent.ToolExecutionCoordinator.parallelSafe(call.name)) {
                    readyReads.add(idx)
                    if (readyReads.size >= com.codespace.ide.agent.ToolExecutionCoordinator.MAX_PARALLEL_READS) {
                        flushReads()
                    }
                    continue
                }
                // Anything not provably independent runs alone, after everything queued.
                flushReads()
                noteAndRecord(idx, executeOnce(idx))
            }
            flushReads()
            // Internal continuation: no user bubble, same key and model, no re-sent files.
            streamBuf.setLength(0)
            failoverAttempts = 0
            ui.value = ui.value.copy(
                streaming = true,
                streamStartedMs = System.currentTimeMillis(),
                firstDeltaMs = 0L,
            )
            // L4: if a tool_search loaded something, the next continuation must SEE it. Rebuilding
            // the config is the whole mechanism - nothing about the engine call changes.
            val refreshed = if (ToolLoadout.version() != loadoutVersionAtSend) {
                loadoutVersionAtSend = ToolLoadout.version()
                cfg.copy(systemInstruction = buildInstruction(
                    mode, true, wsBlockForRefresh, offeredAtSend, memoryAtSend))
            } else cfg
            // P4.6/4.7: a chain of ten tools used to send all ten results again, unbounded, on a
            // phone. Past the ceiling the OLDER results are compressed with an explicit marker and
            // exact repeats dropped; the newest result, anything pinned and anything cited survive.
            val payload = if (results.length > TOOL_RESULT_BUDGET_CHARS) {
                com.codespace.ide.agent.ToolContextTrim.trim(resultItems, TOOL_RESULT_BUDGET_CHARS).render().trim()
            } else {
                results.toString().trim()
            }
            attemptSend(refreshed, slotName, payload, emptyList(), confirmOverBudget, gen)
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
        com.codespace.ide.agent.AgentWriteJournal.activate(projectId, conversationId)
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
            initialMode = sessionMode(projectId, conversationId),
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

    /** Item 17: the pinned ids for this project (empty when nothing is pinned). */
    internal fun pinnedSessions(projectId: String): Set<String> =
        index?.readPins(projectId) ?: emptySet()

    /** Item 17: pin or unpin one conversation. Returns null on success, a reason on failure. */
    internal fun setPinned(projectId: String, conversationId: String, pinned: Boolean): String? =
        when (val w = requireStore().setPinned(projectId, conversationId, pinned)) {
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
