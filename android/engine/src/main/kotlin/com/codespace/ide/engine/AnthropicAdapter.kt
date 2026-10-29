package com.codespace.ide.engine

/**
 * A(b) WP3: Anthropic native Messages API adapter.
 * POST {config.baseUrl} (the panel passes https://api.anthropic.com/v1/messages).
 * Headers: x-api-key + anthropic-version (official SDK default, verified
 * 2026-10-28 from anthropic-sdk-python _constants.py ANTHROPIC_API_VERSION).
 * Body: model, max_tokens (REQUIRED per SDK MessageCreateParams), messages[]
 * roles user/assistant with string content, stream: true, optional
 * thinking {type: "enabled", budget_tokens} — bounded like Gemini; 0 = off.
 * SSE: event names ride `event:` lines (the parser ignores them per spec);
 * data JSON carries the event type. Visible text arrives as
 * content_block_delta with delta {"type":"text_delta","text":...};
 * thinking_delta events are NOT visible content.
 * No [DONE]: message_stop, then the server closes the stream (the engine's
 * DONE-less completion path covers it).
 * Docs verified 2026-10-28: docs.anthropic.com/en/api/messages-streaming —
 * official curl uses the x-api-key header; event flow message_start ->
 * content_block_start -> content_block_delta(text_delta) -> message_stop;
 * example body {"model":"claude-opus-5-5","messages":[...],"max_tokens":256,
 * "stream":true}. Thinking: SDK ThinkingConfigEnabledParam = type "enabled" +
 * budget_tokens Required[int] (docs floor: 1024).
 */
class AnthropicAdapter : ProviderAdapter {
    override val providerId = "anthropic"

    override fun endpoint(config: EngineConfig): String = config.baseUrl

    override fun headers(config: EngineConfig): Map<String, String> =
        mapOf("x-api-key" to config.apiKey, "anthropic-version" to "2026-10-21")

    override fun payload(window: List<ChatMessage>, userText: String, config: EngineConfig): String {
        val msgs = StringBuilder()
        for (m in window) {
            if (msgs.isNotEmpty()) msgs.append(',')
            msgs.append("{\"role\":\"").append(roleJson(m.role)).append("\",\"content\":\"")
                .append(escapeJson(m.text)).append("\"}")
        }
        val thinking = thinkingJson(config)
        return "{\"model\":\"" + escapeJson(config.modelId) + "\",\"max_tokens\":" +
            config.maxResponseTokens + ",\"stream\":true,\"messages\":[" + msgs + "]" + systemField(config) +
            (if (thinking != null) ",\"thinking\":" + thinking else "") + "}"
    }

    /**
     * WP8: the final user turn becomes a content-block ARRAY - image blocks
     * (base64 source) and PDF document blocks per the Messages API docs
     * verified 2026-10-28. Supported media types mirror the docs:
     * image/jpeg|png|gif|webp and application/pdf. Anything else is REFUSED
     * before the request (never sent, then 400ed).
     */
    override fun payload(
        window: List<ChatMessage>,
        userText: String,
        attachments: List<ChatAttachment>,
        config: EngineConfig,
    ): String {
        if (attachments.isEmpty()) return payload(window, userText, config)
        val msgs = StringBuilder()
        for (m in window) {
            if (msgs.isNotEmpty()) msgs.append(',')
            msgs.append("{\"role\":\"").append(roleJson(m.role)).append("\",\"content\":\"")
                .append(escapeJson(m.text)).append("\"}")
        }
        val blocks = StringBuilder()
        for (a in binaryAttachments(attachments)) {
            if (!attachmentSupported(a.mimeType)) {
                throw IllegalArgumentException(
                    "Attachment '" + a.name + "' (" + a.mimeType + ") is not supported by the Anthropic wire format (JPEG/PNG/GIF/WEBP images and PDF)")
            }
            val blockType = if (a.mimeType == "application/pdf") "document" else "image"
            if (blocks.isNotEmpty()) blocks.append(',')
            blocks.append("{\"type\":\"").append(blockType)
                .append("\",\"source\":{\"type\":\"base64\",\"media_type\":\"").append(a.mimeType)
                .append("\",\"data\":\"").append(a.base64Data).append("\"}}")
        }
        val combined = userText + textBlocks(attachments)
        if (combined.isNotEmpty()) {
            if (blocks.isNotEmpty()) blocks.append(',')
            blocks.append("{\"type\":\"text\",\"text\":\"").append(escapeJson(combined)).append("\"}")
        }
        if (msgs.isNotEmpty()) msgs.append(',')
        msgs.append("{\"role\":\"user\",\"content\":[").append(blocks).append("]}")
        val thinking = thinkingJson(config)
        return "{\"model\":\"" + escapeJson(config.modelId) + "\",\"max_tokens\":" +
            config.maxResponseTokens + ",\"stream\":true,\"messages\":[" + msgs + "]" + systemField(config) +
            (if (thinking != null) ",\"thinking\":" + thinking else "") + "}"
    }

    internal fun attachmentSupported(mimeType: String): Boolean =
        mimeType == "image/jpeg" || mimeType == "image/png" ||
            mimeType == "image/gif" || mimeType == "image/webp" ||
            mimeType == "application/pdf"

    /** One reasoning control: bounded budget_tokens, floor 1024; 0 = thinking off (field omitted). */
    internal fun thinkingJson(config: EngineConfig): String? {
        if (config.reasoningBudget <= 0) return null
        val budget = if (config.reasoningBudget < 1024) 1024 else config.reasoningBudget
        return "{\"type\":\"enabled\",\"budget_tokens\":" + budget + "}"
    }

    override fun extractDelta(data: String): String? {
        if (data.length < 2 || data[0] != '{') return null
        val key = "\"delta\":{\"type\":\"text_delta\",\"text\":\""
        val i = data.indexOf(key)
        if (i < 0) return null
        return decodeJsonStringValue(data, i + key.length)
    }

    /** No [DONE] sentinel; the server closes after message_stop. */
    override fun isDoneSentinel(data: String): Boolean = false

    internal fun roleJson(role: ChatRole): String =
        if (role == ChatRole.USER) "user" else "assistant"

    /** Anthropic takes the instruction OUTSIDE the message list; blank adds nothing. */
    internal fun systemField(config: EngineConfig): String =
        if (config.systemInstruction.isBlank()) ""
        else ",\"system\":\"" + escapeJson(config.systemInstruction) + "\""

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
