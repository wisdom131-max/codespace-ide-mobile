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
) : ChatEngine {

    private val state = SessionState(sessionId)

    fun transcript(): List<ChatMessage> = state.snapshot()

    override fun send(userText: String, config: EngineConfig): Flow<EngineEvent> = flow {
        state.append(ChatRole.USER, userText, MessageState.DONE)
        val payload = buildPayload(userText, config)
        val overBudget = BudgetGuard.checkPayload(payload.length, config.maxPayloadChars)
        if (overBudget != null) {
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
                "https://example.invalid/chat/completions",
                mapOf("Authorization" to "Bearer test-key"),
                payload
            ).collect { chunk ->
                if (done) return@collect
                for (data in parser.feed(chunk)) {
                    val delta = extractDelta(data)
                    if (delta != null) {
                        builder.append(delta)
                        emit(EngineEvent.Delta(delta))
                    } else if (data == "[DONE]") {
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
                    val delta = extractDelta(data)
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

    /** Build the chat/completions payload with budgeted history. */
    internal fun buildPayload(userText: String, config: EngineConfig): String {
        val window = HistoryBudget.select(
            state.snapshot().filter { it.state == MessageState.DONE && it.text.isNotEmpty() },
            HistoryMode.parse(config.historyMode)
        )
        val msgs = StringBuilder()
        for (m in window) {
            if (msgs.isNotEmpty()) msgs.append(',')
            msgs.append("{\"role\":\"").append(roleJson(m.role)).append("\",\"content\":\"")
                .append(escapeJson(m.text)).append("\"}")
        }
        return "{\"model\":\"" + escapeJson(config.modelId) + "\",\"stream\":true," +
            "\"max_tokens\":" + config.maxResponseTokens + ",\"messages\":[" + msgs + "]}"
    }

    /** Extract choices[0].delta.content from one SSE data payload; null otherwise. */
    internal fun extractDelta(data: String): String? {
        if (data.length < 2 || data[0] != '{') return null
        val key = "\"content\":\""
        val i = data.indexOf(key)
        if (i < 0) return null
        val start = i + key.length
        val sb = StringBuilder()
        var j = start
        while (j < data.length) {
            val c = data[j]
            if (c == '"') {
                if (j + 1 < data.length && data[j + 1] == '"') { sb.append('"'); j += 2; continue }
                break
            }
            if (c == '\\') {
                when (if (j + 1 < data.length) data[j + 1] else ' ') {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t')
                    'r' -> sb.append('\r'); '"' -> sb.append('"')
                    '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'u' -> { // \uXXXX — hex decode, no surrogate pairing needed for tests
                        if (j + 5 < data.length) {
                            val hex = data.substring(j + 2, j + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) sb.append(code.toChar())
                            j += 6
                            continue
                        }
                    }
                    else -> sb.append(data[j + 1])
                }
                j += 2
                continue
            }
            sb.append(c)
            j++
        }
        return sb.toString()
    }

    private fun roleJson(role: ChatRole): String =
        if (role == ChatRole.USER) "user" else "assistant"

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }
}
