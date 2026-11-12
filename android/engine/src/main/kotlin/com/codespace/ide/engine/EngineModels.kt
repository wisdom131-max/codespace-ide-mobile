package com.codespace.ide.engine

/**
 * Domain model for the rebuilt chat engine. Pure JVM, no Android imports.
 * A message is text-only for v1 — attachments come back in a later rebuild
 * phase, never as blobs inside these models.
 */
enum class ChatRole { USER, ASSISTANT, SYSTEM }

enum class MessageState { PENDING, STREAMING, DONE, ERROR }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val state: MessageState = MessageState.DONE,
)

/**
 * WP8: staged attachment for ONE send. base64 is captured at ATTACH time
 * (staging) - later file edits never change what is sent. Redaction rule:
 * the data is never logged and never persisted; it lives in memory for the
 * send and is then dropped. History keeps TEXT only.
 */
data class ChatAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    /** Binary payload (images/PDF); empty for text attachments. */
    val base64Data: String = "",
    /**
     * WP9: text/context attachment content - sent as a quoted text block
     * (file context), never as a binary part. Staged at attach time like the
     * binary path; the payload budget guard counts it either way.
     */
    val textData: String? = null,
) {
    /** Text attachments travel as a text block; everything else as binary parts. */
    val isText: Boolean get() = textData != null
}

/** Per-send configuration: model id, request caps, budget knobs. */
data class EngineConfig(
    val modelId: String,
    val maxResponseTokens: Int = 4096,
    /** Outgoing payload char cap (BudgetGuard). */
    val maxPayloadChars: Int = 48_000,
    /** History budget mode name: COMPACT / BALANCED / FULL (HistoryBudget). */
    val historyMode: String = "BALANCED",
    /**
     * Owner-ruled send-budget UX: the UI shows the estimate and asks
     * "Send anyway?" — an explicit per-send override that bypasses the
     * BudgetGuard refusal ONCE. Default false = refusal stands.
     */
    val confirmOverBudget: Boolean = false,
    /**
     * Hard character budget for the whole request. 0 = "nothing known, keep today's behaviour".
     * Set by the engine from a LEARNED provider limit, or explicitly by an owner who knows one.
     */
    val requestCharsBudget: Int = 0,
    /** Tools advertised in this send, recorded in the Chat Log so size can be attributed. */
    val toolsOffered: Int = 0,
    /**
     * Per-endpoint API version override. Anthropic is the only provider that versions its API
     * this way: the header is REQUIRED and an endpoint pinned to an older version must be able to
     * say so. Null = the adapter's documented default.
     */
    val apiVersionOverride: String? = null,
    /**
     * Which answer-ceiling field this model accepts: "max_tokens" or "max_completion_tokens".
     * Newer OpenAI reasoning models reject the old name with 400 unsupported_parameter, so the
     * CATALOG decides (ModelCatalog.maxTokensField) instead of one name being assumed for all.
     */
    val maxTokensField: String = "max_tokens",
    /** Provider wire format id — selects the ProviderAdapter at engine construction. */
    val providerId: String = "openai",
    /**
     * P0-1: the SLOT NAME the key in [apiKey] was read from ("ai_GEMINI", "ai_CUSTOM_GROK_3").
     * A name only - it is what a 401 verdict quotes so the owner can tell which key was used.
     * Never a value: nothing here may be logged with the key next to it.
     */
    val keySlotName: String = "",
    /** Full endpoint URL for the provider's wire format (adapter shapes the final path). */
    val baseUrl: String = "https://example.invalid/chat/completions",
    /** Provider API key — sent via adapter headers, NEVER in the URL. */
    val apiKey: String = "test-key",
    /** One reasoning control, mapped per provider (Gemini: thinkingBudget; bounded, never -1/dynamic). */
    val reasoningBudget: Int = 1024,
    /**
     * U4-d: the conversation-mode instruction (Ask / Edit / Agent). Carried on the wire
     * in the provider's OWN system slot - OpenAI as a leading system message, Anthropic
     * as the top-level "system" field, Gemini as "systemInstruction" - because Gemini and
     * Anthropic reject a system role inside the message list. Empty means "send nothing",
     * so a caller that does not set a mode keeps byte-identical payloads.
     */
    val systemInstruction: String = "",
)

/** Events emitted while a send runs. Cold flow: nothing starts until collect. */
sealed class EngineEvent {
    /** One streamed text delta. */
    data class Delta(val text: String) : EngineEvent()
    /** Send finished normally. totalChars is the streamed length. */
    data class Completed(
        val totalChars: Int,
        /**
         * Area 6: WHY the stream ended. Defaults to UNKNOWN, never NATURAL, so an adapter that
         * says nothing cannot make a cut-off answer look finished.
         */
        val finish: FinishReason = FinishReason.UNKNOWN,
    ) : EngineEvent()
    /** Send failed with a classified error from the taxonomy. */
    data class Failed(val error: ChatError) : EngineEvent()
    /**
     * The reply ENDED EARLY - the provider's own end reason says the ceiling was hit, or the stream
     * was cut. The text already streamed is kept and shown; this is one line saying it is partial.
     *
     * It is NOT the removed HistoryTrimmed: that narrated an engine decision the owner could not act
     * on. This one says "what you are reading is not the whole answer", which is actionable.
     */
    data class Truncated(val reason: String, val visibleText: String) : EngineEvent()
    // 2026-11-02: HistoryTrimmed was deleted, not silenced. The window is still narrowed by
    // HistoryBudget before each send - VS Code narrows its own (summarizedConversationHistory.tsx
    // exists precisely for "when the context window is exceeded") - but neither product narrates
    // it in the transcript, and a row saying "3 older messages not sent" told the user about an
    // engine decision they cannot act on from inside the chat.
}

/**
 * The engine contract. send() returns a COLD Flow: the transport call starts
 * on collection and a cancelled collection cancels the transport read, so
 * cancellation is cooperative end-to-end. Session state is engine-owned and
 * session-bound; no global singletons.
 */
interface ChatEngine {
    val sessionId: String
    fun send(userText: String, attachments: List<ChatAttachment>, config: EngineConfig): kotlinx.coroutines.flow.Flow<EngineEvent>

    /**
     * Seeds this engine with a conversation that was already had (restored from the
     * on-disk transcript). Default is a no-op so a stateless implementation stays valid.
     */
    fun restoreHistory(history: List<ChatMessage>) {}

    /** WP2-era single-arg convenience: send without attachments. */
    fun send(userText: String, config: EngineConfig): kotlinx.coroutines.flow.Flow<EngineEvent> =
        send(userText, emptyList(), config)
}
