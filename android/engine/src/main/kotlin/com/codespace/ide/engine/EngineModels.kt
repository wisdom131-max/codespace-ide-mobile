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
    /**
     * The part of the instruction that CHANGES between sends - in practice the clock.
     *
     * PROJECT EIGHT 8.10: this used to be appended to systemInstruction by the caller, which made
     * the whole system prompt change every minute. That is fatal to prompt caching: a cached block
     * that never repeats is a guaranteed miss, and on Anthropic it also invalidates the tool
     * definitions cached ahead of it. Split out, the static instruction stays byte-stable and the
     * clock rides beside it, uncached and costing a few dozen tokens.
     */
    val volatileSystemTail: String = "",
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
    /**
     * P0.1 (2026-11-19): does this endpoint accept OpenAI-style CONTENT PARTS
     * (`"content":[{"type":"text",...},{"type":"image_url",...}]`)?
     *
     * Cloudflare Workers AI `/ai/v1` validates `messages[].content` as a STRING and rejects the
     * array form with `oneOf ... 'array' not in 'string'` (live diagnostic, 2026-11-19). Default
     * true keeps every existing provider byte-identical; only the Cloudflare send path sets false.
     */
    val supportsContentParts: Boolean = true,
    /** Provider API key — sent via adapter headers, NEVER in the URL. */
    val apiKey: String = "test-key",
    /** One reasoning control, mapped per provider (Gemini: thinkingBudget; bounded, never -1/dynamic). */
    val reasoningBudget: Int = 1024,
    /**
     * PROJECT NINE 9.91 (catalog 87): let the complexity router choose the reasoning budget for the
     * request instead of one fixed number for every question. Off by default, so nothing changes
     * until it is switched on - the same pattern as the fallback chain and the answer cache.
     */
    val routeReasoning: Boolean = false,
    /**
     * PROJECT NINE 9.80 (catalog 88 WIRED): replay an identical question instead of paying for it
     * twice. Off by default: a cached answer is only safe once the stamp matches, and the stamp is
     * the model plus the conversation length, so a changed conversation never replays.
     */
    val answerCacheEnabled: Boolean = false,

    /**
     * CATALOG 11: when a reply comes back with no text and no tool ran, ask once more and let the
     * consensus picker choose between the candidates. Off by default because it costs a second
     * request, and because a tool-only reply also has no text - the no-tool guard is what makes it
     * safe rather than a duplicate of the user's own actions.
     */
    val secondOpinionOnEmptyAnswer: Boolean = false,
    /**
     * U4-d: the conversation-mode instruction (Ask / Edit / Agent). Carried on the wire
     * in the provider's OWN system slot - OpenAI as a leading system message, Anthropic
     * as the top-level "system" field, Gemini as "systemInstruction" - because Gemini and
     * Anthropic reject a system role inside the message list. Empty means "send nothing",
     * so a caller that does not set a mode keeps byte-identical payloads.
     */
    val systemInstruction: String = "",
    /**
     * PROJECT EIGHT 8.2: tools advertised natively in this send. Empty means "send no tools array",
     * which keeps every existing request byte-identical - the same contract [systemInstruction] has.
     */
    val toolDeclarations: List<ToolDeclaration> = emptyList(),
    /**
     * PROJECT NINE 9.58 (catalog 15): models to try, in order, when a send fails for a reason the
     * provider itself calls retryable. Empty by default, which is the owner's decision and also the
     * safe one: a request with no chain behaves exactly as it did before.
     *
     * Same provider only. The adapter, endpoint and headers are chosen before the attempt loop, so a
     * model from another provider would be sent to the wrong endpoint - worse than failing.
     */
    val fallbackModels: List<String> = emptyList(),
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
    /**
     * P3-A (2026-11-19): the provider reported token usage for this attempt. Reported values
     * only: a provider that says nothing emits no event, so nothing is ever invented.
     */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : EngineEvent()

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
