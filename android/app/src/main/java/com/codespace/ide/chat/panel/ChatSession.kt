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
data class PanelSelection(
    val providerId: String = "gemini",
    val keySlot: String = "ai_google",
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
) {
    private val streamBuf = StringBuilder()
    private var sendJob: Job? = null

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

    fun send(userText: String, confirmOverBudget: Boolean = false) {
        if (ui.value.streaming || userText.isBlank()) return
        val sel = selection.value
        val key = keyFor(sel.keySlot)
        if (key.isNullOrBlank()) {
            appendRow(ChatRowModel.Failed(
                "No key stored for this provider",
                "The rebuilt chat uses your existing stored keys (read-only WP1 migration). " +
                    "Key management returns to Settings in WP7.", false))
            return
        }
        val cfg = EngineConfig(
            modelId = sel.modelId,
            providerId = sel.providerId,
            baseUrl = sel.baseUrl,
            apiKey = key,
        ).copy(confirmOverBudget = confirmOverBudget)
        streamBuf.setLength(0)
        ui.value = ui.value.copy(
            rows = ui.value.rows + listOf(ChatRowModel.User(userText)),
            streaming = true,
            streamStartedMs = System.currentTimeMillis(),
            firstDeltaMs = 0L,
            lastUserText = userText,
        )
        sendJob = scope.launch {
            try {
                engine.send(userText, cfg).collect { ev -> onEvent(ev) }
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
        if (t.isNotBlank() && !ui.value.streaming) send(t)
    }

    /** Over-budget override: the owner-ruled explicit "Send anyway?" per send. */
    /** Over-budget card dismissed without sending: drop the pending card row. */
    fun dismissOverBudget() {
        val st = ui.value
        if (st.rows.lastOrNull() is ChatRowModel.OverBudget) {
            ui.value = st.copy(rows = st.rows.dropLast(1))
        }
    }

    fun sendAnyway() {
        val t = ui.value.lastUserText
        if (t.isNotBlank() && !ui.value.streaming) send(t, confirmOverBudget = true)
    }

    private fun appendRow(row: ChatRowModel) {
        ui.value = ui.value.copy(rows = ui.value.rows + listOf(row))
    }

    private fun onEvent(ev: EngineEvent) {
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

/** Per-project session registry. Owns the scope sends live in. */
class ChatSessionManager(val reader: StoredCredentialsReader) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = HashMap<String, ChatSession>()

    @Synchronized
    fun session(projectId: String): ChatSession =
        sessions.getOrPut(projectId) { ChatSession(projectId, { slot -> reader.keyValue(slot) }, scope) }

    fun close() = scope.cancel()
}
