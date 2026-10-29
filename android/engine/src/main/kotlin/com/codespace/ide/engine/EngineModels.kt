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
    /** Provider wire format id — selects the ProviderAdapter at engine construction. */
    val providerId: String = "openai",
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
    data class Completed(val totalChars: Int) : EngineEvent()
    /** Send failed with a classified error from the taxonomy. */
    data class Failed(val error: ChatError) : EngineEvent()
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

    /** WP2-era single-arg convenience: send without attachments. */
    fun send(userText: String, config: EngineConfig): kotlinx.coroutines.flow.Flow<EngineEvent> =
        send(userText, emptyList(), config)
}
