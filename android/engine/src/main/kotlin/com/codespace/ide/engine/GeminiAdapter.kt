package com.codespace.ide.engine

/**
 * A(b) commit 2: Google Gemini native wire format (Generative Language API).
 * POST {base}/v1beta/models/{model}:streamGenerateContent?alt=sse; auth via the
 * x-goog-api-key header (key never in the URL); contents[] with user/model
 * roles; generationConfig carries a BOUNDED thinkingConfig.thinkingBudget
 * (default 1024 — the -1 "dynamic" value is never sent).
 * SSE chunks: candidates[0].content.parts[]; parts with "thought":true are
 * thinking summaries, NOT visible content. No [DONE] sentinel: the server
 * closes the stream (the engine's DONE-less completion path covers it).
 */
class GeminiAdapter : ProviderAdapter {
    override val providerId = "gemini"

    override fun endpoint(config: EngineConfig): String {
        val base = config.baseUrl.trimEnd('/')
        return base + "/v1beta/models/" + config.modelId + ":streamGenerateContent?alt=sse"
    }

    override fun headers(config: EngineConfig): Map<String, String> =
        mapOf("x-goog-api-key" to config.apiKey)

    override fun payload(window: List<ChatMessage>, userText: String, config: EngineConfig): String {
        val contents = StringBuilder()
        for (m in window) {
            if (contents.isNotEmpty()) contents.append(',')
            contents.append("{\"role\":\"").append(roleJson(m.role)).append("\",\"parts\":[{\"text\":\"")
                .append(escapeJson(m.text)).append("\"}]}")
        }
        return "{\"contents\":[" + contents + "],\"generationConfig\":{\"maxOutputTokens\":" +
            config.maxResponseTokens + ",\"thinkingConfig\":{\"thinkingBudget\":" + thinkingBudget(config) + "}}}"
    }

    /**
     * WP8: final user turn carries the text part plus inline_data parts for
     * every staged attachment (REST accepts snake_case inline_data; images and
     * PDF both ride the SAME field per docs verified 2026-10-28).
     */
    override fun payload(
        window: List<ChatMessage>,
        userText: String,
        attachments: List<ChatAttachment>,
        config: EngineConfig,
    ): String {
        if (attachments.isEmpty()) return payload(window, userText, config)
        val contents = StringBuilder()
        for (m in window) {
            if (contents.isNotEmpty()) contents.append(',')
            contents.append("{\"role\":\"").append(roleJson(m.role)).append("\",\"parts\":[{\"text\":\"")
                .append(escapeJson(m.text)).append("\"}]}")
        }
        val parts = StringBuilder()
        val combined = userText + textBlocks(attachments)
        if (combined.isNotEmpty()) parts.append("{\"text\":\"").append(escapeJson(combined)).append("\"}")
        for (a in binaryAttachments(attachments)) {
            if (!attachmentSupported(a.mimeType)) {
                throw IllegalArgumentException(
                    "Attachment '" + a.name + "' (" + a.mimeType + ") is not supported by the Gemini wire format (images and PDF only)")
            }
            if (parts.isNotEmpty()) parts.append(',')
            parts.append("{\"inline_data\":{\"mime_type\":\"").append(a.mimeType)
                .append("\",\"data\":\"").append(a.base64Data).append("\"}}")
        }
        if (contents.isNotEmpty()) contents.append(',')
        contents.append("{\"role\":\"user\",\"parts\":[").append(parts).append("]}")
        return "{\"contents\":[" + contents + "],\"generationConfig\":{\"maxOutputTokens\":" +
            config.maxResponseTokens + ",\"thinkingConfig\":{\"thinkingBudget\":" + thinkingBudget(config) + "}}}"
    }

    internal fun attachmentSupported(mimeType: String): Boolean =
        mimeType.startsWith("image/") || mimeType == "application/pdf"

    /** One reasoning control, bounded: 0 = off is allowed; -1 (dynamic) never sent. */
    internal fun thinkingBudget(config: EngineConfig): Int =
        if (config.reasoningBudget < 0) 1024 else config.reasoningBudget

    override fun extractDelta(data: String): String? {
        if (data.length < 2 || data[0] != '{') return null
        if (!data.contains("\"parts\"")) return null
        val key = "\"text\":\""
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val t = data.indexOf(key, i)
            if (t < 0) break
            val partStart = data.lastIndexOf('{', t)
            val partEnd = data.indexOf('}', t)
            val thought = partStart >= 0 && partEnd > t &&
                data.substring(partStart, partEnd).contains("\"thought\":true")
            if (!thought) sb.append(decodeJsonStringValue(data, t + key.length))
            i = t + key.length
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    override fun isDoneSentinel(data: String): Boolean = false

    internal fun roleJson(role: ChatRole): String =
        if (role == ChatRole.USER) "user" else "model"

    internal fun escapeJson(s: String): String {
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
