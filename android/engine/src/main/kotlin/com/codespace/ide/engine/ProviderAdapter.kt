package com.codespace.ide.engine

/**
 * A(b) provider seam: one adapter per provider WIRE FORMAT. The engine stays
 * provider-agnostic — it owns the pipeline (assemble -> budget guard ->
 * history window -> transport -> SSE parse -> event emission); the adapter
 * owns the endpoint shape, auth headers, request body, and SSE delta
 * extraction for its provider. Adding a provider adds an adapter, nothing else.
 */
interface ProviderAdapter {
    /** Stable provider id ("openai", "gemini", ...). */
    val providerId: String

    /** Full endpoint URL for this send (auth NEVER in the URL). */
    fun endpoint(config: EngineConfig): String

    /** HTTP headers for this send (auth lives here). */
    fun headers(config: EngineConfig): Map<String, String>

    /** Request body for the history window + this send. */
    fun payload(window: List<ChatMessage>, userText: String, config: EngineConfig): String

    /** WP8: payload with staged attachments (default: none, identical bytes). */
    fun payload(
        window: List<ChatMessage>,
        userText: String,
        attachments: List<ChatAttachment>,
        config: EngineConfig,
    ): String = payload(window, userText, config)

    /** Next visible text delta from one SSE data payload; null if none. */
    fun extractDelta(data: String): String?

    /** True when this payload is the provider's stream-end sentinel. */
    fun isDoneSentinel(data: String): Boolean
}

/**
 * WP9: context-attachment text blocks. Text attachments (source files, logs,
 * selections saved to a file) are quoted into the user text part with an
 * explicit header so the model can tell file content from the question.
 * Identical across providers (wire-format neutral).
 */
internal fun textBlocks(attachments: List<ChatAttachment>): String {
    val sb = StringBuilder()
    for (a in attachments) {
        val body = a.textData ?: continue
        sb.append("\n\n--- attached file: ").append(a.name).append(" ---\n").append(body)
    }
    return sb.toString()
}

/** Binary attachments only (text ones already ride [textBlocks]). */
internal fun binaryAttachments(attachments: List<ChatAttachment>): List<ChatAttachment> =
    attachments.filter { !it.isText }

/**
 * Shared JSON string-value decode (index-parsed, pure JVM, no org.json —
 * same deliberate style and exact semantics as the pre-adapter engine).
 * [start] points at the first byte INSIDE the opening quote.
 */
internal fun decodeJsonStringValue(data: String, start: Int): String {
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
                'u' -> {
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
