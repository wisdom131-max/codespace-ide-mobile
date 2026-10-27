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
    fun send(userText: String, config: EngineConfig): kotlinx.coroutines.flow.Flow<EngineEvent>
}
