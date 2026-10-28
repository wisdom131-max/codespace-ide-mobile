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

    fun transcript(): List<ChatMessage> = state.snapshot()

    override fun send(userText: String, attachments: List<ChatAttachment>, config: EngineConfig): Flow<EngineEvent> = flow {
        state.append(ChatRole.USER, userText, MessageState.DONE)
        val window = HistoryBudget.select(
            state.snapshot().filter { it.state == MessageState.DONE && it.text.isNotEmpty() },
            HistoryMode.parse(config.historyMode)
        )
        val payload = adapter.payload(window, userText, attachments, config)
        val overBudget = BudgetGuard.checkPayload(payload.length, config.maxPayloadChars)
        if (overBudget != null && !config.confirmOverBudget) {
            state.append(ChatRole.ASSISTANT, "", MessageState.ERROR)
            emit(EngineEvent.Failed(overBudget))
            return@flow
        }
        val responseMsg = state.append(ChatRole.ASSISTANT, "", MessageState.STREAMING)
        val builder = StringBuilder()
        val parser = SseParser()
        var done = false
        try {
            transport.stream(
                adapter.endpoint(config),
                adapter.headers(config),
                payload
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
        } catch (t: TransportHttpException) {
            state.update(responseMsg.id) { it.copy(state = MessageState.ERROR) }
            emit(EngineEvent.Failed(ErrorTaxonomy.classify(t.status, t.headers, t.body)))
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
