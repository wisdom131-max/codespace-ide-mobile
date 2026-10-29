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
    /** WP6: ordered key-slot names with stored keys for a provider (primary first). */
    private val poolSlotsFor: (providerId: String) -> List<String> = { emptyList() },
    /** WP6: automatic key failover is OPT-IN (silent switching burns credits). */
    private val failoverOptIn: () -> Boolean = { false },
    /** WP7: user context-window override (tokens) for provider:model, or null. */
    private val contextOverrideFor: (providerId: String, modelId: String) -> Int? = { _, _ -> null },
) {
    private val streamBuf = StringBuilder()
    private var sendJob: Job? = null
    private var failoverAttempts = 0
    private val keySlotsSeen = HashSet<String>()

    val ui = MutableStateFlow(ChatPanelUiState())
    val selection = MutableStateFlow(PanelSelection())

    // Init-order (run 3387 NPE): buildEngine's default providerId reads
    // selection, so the engine initializer MUST come AFTER selection exists.
    private var engine: ChatEngineImpl = buildEngine()

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
    var mode: ChatMode = ChatMode.ASK

    fun send(userText: String, confirmOverBudget: Boolean = false) =
        sendCore(userText, confirmOverBudget, reuseStaged = false)

    private fun sendCore(userText: String, confirmOverBudget: Boolean, reuseStaged: Boolean) {
        if (ui.value.streaming || userText.isBlank()) return
        val sel = selection.value
        // U1 (657 finding d): a blank model id is a pair that cannot send - say so
        // instead of putting a request on the wire that is certain to be rejected.
        if (sel.modelId.isBlank()) {
            appendRow(ChatRowModel.Failed(
                "No model selected",
                "Pick a model for " + sel.providerId + " (the model picker's Model id field, " +
                    "or a provider with a bundled default).", false))
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
        val cfg = EngineConfig(
            modelId = sel.modelId,
            // U4-d: the mode rides the provider's own system slot (see ChatModePrompts).
            systemInstruction = ChatModePrompts.instruction(mode),
            providerId = sel.providerId,
            baseUrl = sel.baseUrl,
            apiKey = key ?: "",   // no-key presets (Ollama): blank key, header omitted
            reasoningBudget = reasoningBudget,
            // WP5: the send budget scales with the model's context window (estimate);
            // WP7: the user override replaces the bundled row (estimate either way).
            maxPayloadChars = com.codespace.ide.engine.ModelCatalog.payloadBudgetChars(resolveCapability(sel)),
        ).copy(confirmOverBudget = confirmOverBudget)
        streamBuf.setLength(0)
        failoverAttempts = 0
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
        val newRows = ArrayList<ChatRowModel>(2)
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
        attemptSend(cfg, effectiveSlot, userText, attachments, confirmOverBudget)
    }

    /** One engine attempt; the failover path re-invokes this with the next key. */
    private fun attemptSend(
        cfg: EngineConfig,
        slotName: String,
        userText: String,
        attachments: List<com.codespace.ide.engine.ChatAttachment>,
        confirmOverBudget: Boolean,
    ) {
        sendJob = scope.launch {
            try {
                engine.send(userText, attachments, cfg).collect { ev -> onEvent(ev, cfg, slotName, userText, attachments, confirmOverBudget) }
            } catch (_: CancellationException) {
                // Cooperative cancel: partial text is already in the rows.
                ui.value = ui.value.copy(streaming = false)
            } catch (t: Throwable) {
                appendRow(ChatRowModel.Failed(t.message ?: "Send failed", "", true))
                ui.value = ui.value.copy(streaming = false)
            }
        }
    }

    fun cancel() { sendJob?.cancel() }

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

    /** WP7: settings "Clear conversation" - rows gone, keys and settings kept. */
    fun clearConversation() {
        sendJob?.cancel()
        streamBuf.setLength(0)
        failoverAttempts = 0
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
    ) {
        when (ev) {
            is EngineEvent.Delta -> {
                streamBuf.append(ev.text)
                val text = streamBuf.toString()
                val st = ui.value
                val rows = if (st.rows.lastOrNull() is ChatRowModel.AssistantStream)
                    st.rows.dropLast(1) + listOf(ChatRowModel.AssistantStream(text, true))
                else st.rows + listOf(ChatRowModel.AssistantStream(text, true))
                ui.value = st.copy(rows = rows,
                    firstDeltaMs = if (st.firstDeltaMs == 0L) System.currentTimeMillis() else st.firstDeltaMs)
            }
            is EngineEvent.Completed -> {
                val st = ui.value
                val rows = if (st.rows.lastOrNull() is ChatRowModel.AssistantStream)
                    st.rows.dropLast(1) + listOf(ChatRowModel.AssistantStream(streamBuf.toString(), false))
                else st.rows + listOf(ChatRowModel.AssistantStream(streamBuf.toString(), false))
                ui.value = st.copy(rows = rows, streaming = false)
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
                    attemptSend(cfg.copy(apiKey = key2 ?: ""), nextSlot, userText, attachments, confirmOverBudget)
                    return
                }
                if (err is ChatError.OverBudget) {
                    appendRow(ChatRowModel.OverBudget(err.detail))
                } else {
                    appendRow(ChatRowModel.Failed(err.userVerdict, "", err.retryable))
                }
                ui.value = ui.value.copy(streaming = false)
            }
        }
    }
}

/** Pure: first unused slot in pool order, or null when exhausted. */
internal fun chooseNextSlot(remaining: List<String>): String? = remaining.firstOrNull()

/** Per-project session registry. Owns the scope sends live in. */
class ChatSessionManager(val reader: StoredCredentialsReader) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = HashMap<String, ChatSession>()

    @Synchronized
    fun session(projectId: String): ChatSession =
        sessions.getOrPut(projectId) {
            ChatSession(
                projectId,
                { slot -> reader.keyValue(slot) },
                scope,
                poolSlotsFor = { pid ->
                    reader.snapshot().keySlots
                        .filter { it.hasKey && it.providerId == pid }
                        .sortedBy { it.poolIndex ?: 0 }
                        .map { it.slotName }
                },
                failoverOptIn = { ChatSettingsPrefs.failoverOptIn() },
                contextOverrideFor = { pid, mid -> ChatSettingsPrefs.contextOverride(pid, mid) },
            )
        }

    fun close() = scope.cancel()
}
