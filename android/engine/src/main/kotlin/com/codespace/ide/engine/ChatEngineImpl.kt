package com.codespace.ide.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * OpenAI-compatible streaming chat engine (the same wire shape the archived
 * providers used: POST chat/completions, SSE deltas with
 * choices[0].delta.content, [DONE] sentinel). Pure JVM.
 *
 * Send pipeline (each step owned by one small function — the send loop stays
 * tiny by design): assemble -> budget guard -> history window -> transport ->
 * SSE parse -> delta extract -> event emission. Cancellation is cooperative:
 * the returned Flow is cold and the transport disconnects when collection is
 * cancelled.
 */
class ChatEngineImpl(
    override val sessionId: String,
    private val transport: ChatTransport,
    private val adapter: ProviderAdapter = OpenAIAdapter(),
) : ChatEngine {

    private val state = SessionState(sessionId)

    /**
     * LEARNED LIMITS: a limit the provider stated about itself, remembered per model+host.
     *
     * We never guess one. A PromptTooLarge verdict carries the number the provider printed, and from
     * the next send onward the history window is fitted to 85% of it, so the same wall is met once
     * instead of every time. Engine-scoped on purpose: the number belongs to the endpoint the owner
     * chose, and a different provider must not inherit it.
     */
    private val learnedLimitChars = mutableMapOf<String, Int>()

    /** Last send's drop count, for diagnostics. Never rendered as a transcript row. */
    var lastFitDroppedMessages: Int = 0
        private set
    var lastFitDroppedChars: Int = 0
        private set

    private fun limitKey(config: EngineConfig): String =
        config.baseUrl.lowercase() + "|" + config.modelId.lowercase()

    /** Hard character budget for this send, or 0 when nothing has been learned yet. */
    internal fun learnedBudgetChars(config: EngineConfig): Int =
        learnedLimitChars[limitKey(config)] ?: 0

    fun transcript(): List<ChatMessage> = state.snapshot()

    /**
     * MEMORY CONTINUITY: hand a restored conversation to a brand-new engine so the first
     * question after reopening the panel is answered with the same context as before.
     */
    override fun restoreHistory(history: List<ChatMessage>) = state.restore(history)

    override fun send(userText: String, attachments: List<ChatAttachment>, config: EngineConfig): Flow<EngineEvent> = flow {
        state.append(ChatRole.USER, userText, MessageState.DONE)
        val eligible = state.snapshot().filter { it.state == MessageState.DONE && it.text.isNotEmpty() }
        val mode = HistoryMode.parse(config.historyMode)
        // Fit to a LEARNED limit when we have one; systemChars 0 because the prompt rides outside
        // the window and the 85% headroom is the allowance for it.
        val fit = HistoryBudget.fit(
            eligible, mode,
            systemChars = 0,
            budgetChars = if (config.requestCharsBudget > 0) config.requestCharsBudget
                          else learnedBudgetChars(config),
        )
        val window = fit.messages
        // Deliberately NOT an event: HistoryTrimmed was removed on 2026-11-02 because a transcript
        // row narrating an engine decision gives the owner nothing to act on (see EngineModels).
        // The count is kept for the Chat Log, where it is diagnostic data rather than chat noise.
        lastFitDroppedMessages = fit.droppedMessages
        lastFitDroppedChars = fit.droppedChars
        val payload = adapter.payload(window, userText, attachments, config)
        val overBudget = BudgetGuard.checkPayload(payload.length, config.maxPayloadChars)
        if (overBudget != null && !config.confirmOverBudget) {
            state.append(ChatRole.ASSISTANT, "", MessageState.ERROR)
            emit(EngineEvent.Failed(overBudget))
            return@flow
        }
        val responseMsg = state.append(ChatRole.ASSISTANT, "", MessageState.STREAMING)
        val builder = StringBuilder()
        var parser = SseParser()
        var done = false
        // Resolved ONCE: the same URL is requested and, on failure, named in the verdict.
        val requestUrl = adapter.endpoint(config)
        var attemptCfg = config
        var attemptPayload = payload
        var retriedCeilingField = false
        try {
          while (true) {
            try {
            transport.stream(
                adapter.endpoint(attemptCfg),
                adapter.headers(attemptCfg),
                attemptPayload
            ).collect { chunk ->
                if (done) return@collect
                for (data in parser.feed(chunk)) {
                    val delta = adapter.extractDelta(data)
                    if (delta != null) {
                        builder.append(delta)
                        emit(EngineEvent.Delta(delta))
                    } else if (adapter.isDoneSentinel(data)) {
                        done = true
                        state.update(responseMsg.id) {
                            it.copy(text = builder.toString(), state = MessageState.DONE)
                        }
                        emit(EngineEvent.Completed(builder.length))
                        break
                    }
                }
            }
            if (!done) {
                for (data in parser.finish()) {
                    val delta = adapter.extractDelta(data)
                    if (delta != null) {
                        builder.append(delta)
                        emit(EngineEvent.Delta(delta))
                    }
                }
                state.update(responseMsg.id) {
                    it.copy(text = builder.toString(), state = MessageState.DONE)
                }
                emit(EngineEvent.Completed(builder.length))
            }
            // Stream ended without [DONE]: treat as completed (honest, no
            // invented failure); the parser finish() case is covered by the
            // transport ending the chunk flow.
            break   // the attempt finished; the retry loop may only re-run on a named refusal
            } catch (inner: TransportHttpException) {
                // ONE retry, and only when the provider's own 400/422 NAMES the answer-ceiling
                // field it refuses (MaxTokensField.retryWith). Safe because that rejection lands
                // before any delta, so nothing has been rendered and nothing can render twice.
                // Never a loop: the second rejection falls straight through to the outer handler.
                val flip = if (!retriedCeilingField && builder.isEmpty())
                    MaxTokensField.retryWith(attemptCfg.maxTokensField, inner.status, inner.body)
                else null
                if (flip == null) throw inner
                retriedCeilingField = true
                attemptCfg = attemptCfg.copy(maxTokensField = flip)
                attemptPayload = adapter.payload(window, userText, attachments, attemptCfg)
                parser = SseParser()
                done = false
            }
          }
        } catch (t: TransportHttpException) {
            state.update(responseMsg.id) { it.copy(state = MessageState.ERROR) }
            // The requested path rides the 404 verdict so "wrong model" and "wrong base
            // URL" stop sharing one misleading sentence. Path only: never the query.
            // P0-1: a 401/403 verdict must name WHO rejected the key and WHICH slot was read,
            // otherwise a provider-side rejection reads like the app refusing (owner report
            // 2026-11-08). Names only - the config carries no key value here.
            val err = ErrorTaxonomy.classify(
                t.status, t.headers, t.body, EndpointUrls.redactedPath(requestUrl),
                providerLabel = attemptCfg.providerId,
                keySlotName = attemptCfg.keySlotName)
            if (err is ChatError.PromptTooLarge && err.limit != null) {
                // 85% headroom: the reply needs room too, and re-hitting the wall by one token is
                // how an owner concludes the app is broken.
                learnedLimitChars[limitKey(attemptCfg)] = (err.limit * 4 * 85) / 100
            }
            emit(EngineEvent.Failed(err))
        } catch (t: kotlinx.coroutines.CancellationException) {
            state.update(responseMsg.id) {
                it.copy(text = builder.toString(), state = MessageState.DONE)
            }
            throw t
        } catch (t: Throwable) {
            state.update(responseMsg.id) { it.copy(state = MessageState.ERROR) }
            emit(EngineEvent.Failed(ErrorTaxonomy.network(t.message ?: "connection failed")))
        }
    }

}
