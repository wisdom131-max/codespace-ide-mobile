package com.codespace.ide.engine

/**
 * PROJECT FIVE, STAGE 5.4 — one conversation, three wire formats.
 *
 * Until this stage the request history was `List<ChatMessage>`, which is role + text only. A tool call
 * and its result therefore had no representation at all: the model saw prose about a tool run, not a
 * call/result pair, and a provider that requires pairing (Anthropic) or ids (OpenAI) could not be given
 * one. This file is the canonical bridge.
 *
 * Rules that are deliberately encoded here rather than left to each adapter:
 *  - A call is rendered only when it arrived COMPLETE; an incomplete call is never executable and is
 *    never shown to the model as one.
 *  - The result is paired with the call by id, in order, and a call without a result is rendered as a
 *    call that has no result yet instead of being padded with an invented one.
 *  - FAILED, UNCERTAIN and REFUSED results keep their meaning: the content says so in plain words, so
 *    no provider is told a write succeeded when it did not.
 *  - A provider whose format cannot carry exchanges reports a refusal instead of dropping them
 *    silently, and the refusal names what could not be represented.
 */
enum class ExchangeStatus {
    OK,
    FAILED,
    /** The executor did not come back cleanly: it may or may not have landed. */
    UNCERTAIN,
    /** Refused before the executor was reached (approval, permission, unknown tool). */
    REFUSED,
}

data class ToolExchange(
    val callId: String,
    val name: String,
    val argumentsJson: String,
    val status: ExchangeStatus,
    val output: String,
    /** False when the call never fully arrived; such a call is shown as unstarted, never as a call. */
    val complete: Boolean = true,
) {
    /** What the model is told the result was. Status words are never softened. */
    fun resultText(): String {
        val head = when (status) {
            ExchangeStatus.OK -> ""
            ExchangeStatus.FAILED -> "FAILED - "
            ExchangeStatus.UNCERTAIN -> "UNSURE - the outcome was not confirmed - "
            ExchangeStatus.REFUSED -> "REFUSED - "
        }
        return head + output
    }
}

/** Why a history could not be sent, in words a user can act on. */
data class HistoryRefusal(val reason: String)

sealed interface RenderedHistory {
    data class Ok(val json: String, val notes: List<String> = emptyList()) : RenderedHistory
    data class Refused(val refusal: HistoryRefusal) : RenderedHistory
}

object ConversationContinuity {

    /**
     * Which exchanges a provider can actually carry. An unknown custom endpoint is not assumed to
     * speak any of them: it gets no exchange array at all, and the caller is told.
     */
    fun supportsExchanges(kind: StreamProvider?): Boolean = when (kind) {
        StreamProvider.GEMINI, StreamProvider.ANTHROPIC, StreamProvider.OPENAI -> true
        null -> false
    }

    /** Gemini: functionCall / functionResponse parts, the response in a following "user" turn. */
    fun gemini(exchanges: List<ToolExchange>): RenderedHistory {
        val usable = exchanges.filter { it.complete }
        if (exchanges.any { !it.complete }) {
            return RenderedHistory.Ok(geminiJson(usable),
                listOf("An incomplete tool call was left out of the request."))
        }
        return RenderedHistory.Ok(geminiJson(usable))
    }

    private fun geminiJson(exchanges: List<ToolExchange>): String {
        if (exchanges.isEmpty()) return ""
        val sb = StringBuilder()
        // Calls first, as one model turn, then every response as one user turn: Gemini requires the
        // response to follow the call that produced it.
        sb.append("{\"role\":\"model\",\"parts\":[")
        exchanges.forEachIndexed { i, ex ->
            if (i > 0) sb.append(',')
            sb.append("{\"functionCall\":{\"name\":\"").append(escape(ex.name))
                .append("\",\"args\":").append(ex.argumentsJson.ifBlank { "{}" }).append("}}")
        }
        sb.append("]},{\"role\":\"user\",\"parts\":[")
        exchanges.forEachIndexed { i, ex ->
            if (i > 0) sb.append(',')
            sb.append("{\"functionResponse\":{\"name\":\"").append(escape(ex.name))
                .append("\",\"response\":{\"result\":\"").append(escape(ex.resultText()))
                .append("\",\"status\":\"").append(ex.status.name.lowercase()).append("\"}}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** OpenAI-compatible: assistant tool_calls, then one role:"tool" message per call id. */
    fun openAI(exchanges: List<ToolExchange>): RenderedHistory {
        val usable = exchanges.filter { it.complete }
        if (usable.isEmpty()) {
            return if (exchanges.isEmpty()) RenderedHistory.Ok("")
            else RenderedHistory.Ok("", listOf("An incomplete tool call was left out of the request."))
        }
        val sb = StringBuilder()
        sb.append("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[")
        usable.forEachIndexed { i, ex ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(escape(ex.callId)).append("\",\"type\":\"function\",\"function\":{\"name\":\"")
                .append(escape(ex.name)).append("\",\"arguments\":\"").append(escape(ex.argumentsJson.ifBlank { "{}" }))
                .append("\"}}")
        }
        sb.append("]}")
        for (ex in usable) {
            sb.append(",{\"role\":\"tool\",\"tool_call_id\":\"").append(escape(ex.callId))
                .append("\",\"content\":\"").append(escape(ex.resultText())).append("\"}")
        }
        return RenderedHistory.Ok(sb.toString())
    }

    /** Anthropic: tool_use blocks in the assistant turn, tool_result blocks in the user turn. */
    fun anthropic(exchanges: List<ToolExchange>): RenderedHistory {
        val usable = exchanges.filter { it.complete }
        if (usable.isEmpty()) {
            return if (exchanges.isEmpty()) RenderedHistory.Ok("")
            else RenderedHistory.Ok("", listOf("An incomplete tool call was left out of the request."))
        }
        val sb = StringBuilder()
        sb.append("{\"role\":\"assistant\",\"content\":[")
        usable.forEachIndexed { i, ex ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"tool_use\",\"id\":\"").append(escape(ex.callId)).append("\",\"name\":\"")
                .append(escape(ex.name)).append("\",\"input\":").append(ex.argumentsJson.ifBlank { "{}" }).append("}")
        }
        sb.append("]},{\"role\":\"user\",\"content\":[")
        usable.forEachIndexed { i, ex ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"tool_result\",\"tool_use_id\":\"").append(escape(ex.callId))
                .append("\",\"content\":\"").append(escape(ex.resultText()))
                .append("\"").append(if (ex.status == ExchangeStatus.OK) "" else ",\"is_error\":true").append("}")
        }
        sb.append("]}")
        return RenderedHistory.Ok(sb.toString())
    }

    /** The renderer for a dialect, or a refusal for one that cannot carry exchanges at all. */
    fun render(kind: StreamProvider?, exchanges: List<ToolExchange>): RenderedHistory = when (kind) {
        StreamProvider.GEMINI -> gemini(exchanges)
        StreamProvider.OPENAI -> openAI(exchanges)
        StreamProvider.ANTHROPIC -> anthropic(exchanges)
        null -> if (exchanges.isEmpty()) RenderedHistory.Ok("")
        else RenderedHistory.Refused(
            HistoryRefusal(
                "This endpoint's format is unknown, so the ${exchanges.size} tool result(s) in this turn " +
                    "cannot be sent in a form it is known to accept."))
    }

    /**
     * Duplicate-id defence for a history being assembled from logs: the first occurrence wins, so a
     * replayed stream cannot produce two calls with one id.
     */
    fun dedupe(exchanges: List<ToolExchange>): List<ToolExchange> {
        val seen = HashSet<String>()
        return exchanges.filter { seen.add(it.callId) }
    }

    private fun escape(s: String): String = JsonLite.write(s).removeSurrounding("\"")

    /**
     * Appends already-rendered message objects to the array named [key] in a provider payload.
     *
     * This is how the exchanges reach the wire without each adapter growing a second request builder:
     * the adapter keeps building the history exactly as before, and this adds the call/result pair at
     * the END of that array, which is where the most recent exchange belongs. Bracket- and
     * string-aware, so a `]` inside message text cannot truncate the request.
     */
    fun splice(payloadJson: String, key: String, fragment: String): String {
        if (fragment.isEmpty()) return payloadJson
        val marker = "\"" + key + "\":["
        val start = payloadJson.indexOf(marker)
        if (start < 0) return payloadJson
        var i = start + marker.length
        var depth = 1
        var inString = false
        var escaped = false
        while (i < payloadJson.length) {
            val c = payloadJson[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        if (i >= payloadJson.length) return payloadJson
        val needsComma = i > start + marker.length
        val insert = (if (needsComma) "," else "") + fragment
        return payloadJson.substring(0, i) + insert + payloadJson.substring(i)
    }
}
