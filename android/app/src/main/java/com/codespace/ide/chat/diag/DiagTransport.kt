package com.codespace.ide.chat.diag

import com.codespace.ide.agent.CopilotDiag
import com.codespace.ide.engine.ChatTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * P4.3: the one place every provider request passes through, so all providers are covered by one
 * decorator instead of per-adapter hooks that could disagree or double-record.
 *
 * It is a pure pass-through. The chunks emitted are exactly the delegate's chunks, in order; a
 * failure is rethrown unchanged; cancellation is rethrown as [CancellationException] so the
 * collector's semantics are untouched. The only side effect is a diagnostic event, and every
 * record call is already wrapped internally, so diagnostics can never break a request.
 *
 * Never recorded: headers, the payload, the URL, response bodies. Only provider id, model id,
 * elapsed time and a short redacted reason.
 */
class DiagTransport(
    private val delegate: ChatTransport,
    private val provider: () -> String,
    private val model: () -> String,
) : ChatTransport {

    override fun stream(
        url: String,
        headers: Map<String, String>,
        payloadJson: String,
    ): Flow<String> = flow {
        val startedAt = System.currentTimeMillis()
        val p = safeProvider()
        CopilotDiag.record(CopilotDiag.Kind.PROVIDER_REQUEST_START, p, true, safeModel())
        try {
            delegate.stream(url, headers, payloadJson).collect { chunk -> emit(chunk) }
            val ms = System.currentTimeMillis() - startedAt
            CopilotDiag.record(CopilotDiag.Kind.PROVIDER_REQUEST_SUCCESS, p, true, ms.toString() + "ms")
        } catch (c: CancellationException) {
            // A cancelled request is neither a success nor a provider failure.
            CopilotDiag.record(CopilotDiag.Kind.PROVIDER_REQUEST_CANCELLED, p, false, "cancelled")
            throw c
        } catch (t: Throwable) {
            val ms = System.currentTimeMillis() - startedAt
            CopilotDiag.record(
                CopilotDiag.Kind.PROVIDER_REQUEST_FAILURE, p, false,
                (t.javaClass.simpleName + " after " + ms + "ms"),
            )
            throw t
        }
    }

    private fun safeProvider(): String = try { provider() } catch (_: Throwable) { "unknown" }
    private fun safeModel(): String = try { model() } catch (_: Throwable) { "" }
}
