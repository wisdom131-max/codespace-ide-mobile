package com.codespace.ide.agent

import org.json.JSONObject

/**
 * STAGE 1 (2026-11-20) — one normalized model-facing tool call, whatever dialect produced it.
 *
 * The old path parsed with a lazy regex (`\{.*?\}`), so any argument containing a nested object,
 * or a `}` inside a string, ended the match early and produced either a broken call or a fake call
 * named "error". This scanner walks the text with real JSON string/escape state instead.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
    val source: Source,
) {
    enum class Source { TEXT_JSON, HERMES_XML, NATIVE }
}

/** What scanning a response produced: calls, or a typed failure that must never execute. */
sealed class ScanOutcome {
    data class Calls(val calls: List<ToolCall>, val visible: String) : ScanOutcome()

    data class Failure(val reason: Reason, val detail: String, val visible: String) : ScanOutcome() {
        enum class Reason { MALFORMED_PROTOCOL, INVALID_JSON, MISSING_NAME, INVALID_ARGS }
    }
}

object ToolCallScan {

    private val OPENERS = listOf("<tool>", "<tool_call>")

    /**
     * Index just past the `}` that closes the object starting at [from], or -1 when the object is
     * unterminated. Understands strings, escapes and nesting, which is the whole point: a brace
     * inside a quoted string is data, not structure.
     */
    fun jsonObjectEnd(text: String, from: Int): Int {
        if (from < 0 || from >= text.length || text[from] != '{') return -1
        var depth = 0
        var inString = false
        var escaped = false
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return -1
    }

    /** Every supported dialect, in one pass. [visible] is the text with protocol removed. */
    fun scan(text: String): ScanOutcome {
        val calls = ArrayList<ToolCall>(2)
        val visible = StringBuilder(text.length)
        var cursor = 0
        var ordinal = 0
        while (cursor < text.length) {
            val open = OPENERS
                .map { text.indexOf(it, cursor) }
                .filter { it >= 0 }
                .minOrNull() ?: break
            visible.append(text, cursor, open)
            val opener = if (text.startsWith("<tool_call>", open)) "<tool_call>" else "<tool>"
            val bodyStart = open + opener.length
            // "<tool>" -> "</tool>", "<tool_call>" -> "</tool_call>". Interpolating the OPENER
            // produced "</<tool>" and no call ever closed.
            val tagName = opener.substring(1, opener.length - 1)
            val closeTag = "</$tagName"
            val close = text.indexOf(closeTag, bodyStart)

            // Hermes/Qwen: <tool_call><function=name><parameter=x>v</parameter></function>
            val fnAt = text.indexOf("<function=", bodyStart)
            val isHermes = fnAt in 0 until (if (close >= 0) close else text.length)

            if (isHermes) {
                val end = close
                if (end < 0) {
                    return ScanOutcome.Failure(
                        ScanOutcome.Failure.Reason.MALFORMED_PROTOCOL,
                        "a tool call opened but never closed",
                        visible.toString(),
                    )
                }
                val nameEnd = text.indexOf('>', fnAt)
                if (nameEnd < 0) {
                    return ScanOutcome.Failure(
                        ScanOutcome.Failure.Reason.MALFORMED_PROTOCOL,
                        "function name was never terminated",
                        visible.toString(),
                    )
                }
                val name = text.substring(fnAt + "<function=".length, nameEnd).trim()
                if (name.isEmpty()) {
                    return ScanOutcome.Failure(
                        ScanOutcome.Failure.Reason.MISSING_NAME,
                        "a tool call had no function name",
                        visible.toString(),
                    )
                }
                val args = JSONObject()
                var p = nameEnd
                while (true) {
                    val pOpen = text.indexOf("<parameter=", p)
                    if (pOpen < 0 || pOpen > end) break
                    val pNameEnd = text.indexOf('>', pOpen)
                    if (pNameEnd < 0 || pNameEnd > end) break
                    val pName = text.substring(pOpen + "<parameter=".length, pNameEnd).trim()
                    val pClose = text.indexOf("</parameter>", pNameEnd)
                    if (pClose < 0 || pClose > end) break
                    val value = text.substring(pNameEnd + 1, pClose)
                    if (pName.isNotEmpty()) args.put(pName, value)
                    p = pClose + "</parameter>".length
                }
                calls.add(ToolCall("t" + ordinal++, name, args, ToolCall.Source.HERMES_XML))
                cursor = text.indexOf('>', close) + 1
                continue
            }

            // JSON dialect: <tool>{"name":"x","arguments":{...}}</tool>
            val braceAt = text.indexOf('{', bodyStart)
            if (braceAt < 0 || (close >= 0 && braceAt > close)) {
                return ScanOutcome.Failure(
                    ScanOutcome.Failure.Reason.MALFORMED_PROTOCOL,
                    "a tool call opened with no JSON object",
                    visible.toString(),
                )
            }
            val objEnd = jsonObjectEnd(text, braceAt)
            if (objEnd < 0) {
                return ScanOutcome.Failure(
                    ScanOutcome.Failure.Reason.MALFORMED_PROTOCOL,
                    "the argument object never closed",
                    visible.toString(),
                )
            }
            if (close < 0) {
                return ScanOutcome.Failure(
                    ScanOutcome.Failure.Reason.MALFORMED_PROTOCOL,
                    "a tool call opened but never closed",
                    visible.toString(),
                )
            }
            val raw = text.substring(braceAt, objEnd)
            val json = try {
                JSONObject(raw)
            } catch (_: Throwable) {
                return ScanOutcome.Failure(
                    ScanOutcome.Failure.Reason.INVALID_JSON,
                    "the arguments were not valid JSON",
                    visible.toString(),
                )
            }
            val name = json.optString("name", "").trim()
            if (name.isEmpty()) {
                return ScanOutcome.Failure(
                    ScanOutcome.Failure.Reason.MISSING_NAME,
                    "a tool call had no name",
                    visible.toString(),
                )
            }
            val args = json.optJSONObject("arguments") ?: JSONObject()
            calls.add(ToolCall("t" + ordinal++, name, args, ToolCall.Source.TEXT_JSON))
            cursor = text.indexOf('>', close) + 1
        }
        visible.append(text, cursor, text.length)
        return ScanOutcome.Calls(calls, visible.toString())
    }

    /** Protocol-free text for display. Never throws, never returns protocol fragments. */
    fun visible(text: String): String = when (val r = scan(text)) {
        is ScanOutcome.Calls -> r.visible
        is ScanOutcome.Failure -> r.visible
    }
}
