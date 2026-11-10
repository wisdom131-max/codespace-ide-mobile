package com.codespace.ide.engine

/**
 * A(b) WP3: Anthropic native Messages API adapter.
 * POST {config.baseUrl} (the panel passes https://api.anthropic.com/v1/messages).
 * Headers: x-api-key + anthropic-version. The version value is the DOCUMENTED LATEST, read
 * from https://docs.anthropic.com/en/api/versioning on 2026-11-09: the page's own example is
 * "anthropic-version: 2026-10-21" and the version history lists 2026-10-21 as the current one
 * (2023-06-01 is the older stable version). It is NOT derived from the build date. A per-endpoint
 * override exists because an endpoint may legitimately be pinned to an older version.
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

    /** Base OR full path; the Messages path is appended exactly once. */
    override fun endpoint(config: EngineConfig): String =
        EndpointUrls.messages(config.baseUrl)

    /**
     * The one place the Anthropic API version lives. Verified against the official versioning
     * page on 2026-11-09 (see the class doc); an endpoint may override it, never invent it.
     */
    internal companion object {
        const val ANTHROPIC_VERSION = "2026-10-21"
    }

    override fun headers(config: EngineConfig): Map<String, String> =
        mapOf(
            "Accept" to "application/json, text/event-stream",
            "x-api-key" to config.apiKey,
            "anthropic-version" to (config.apiVersionOverride?.takeIf { it.isNotBlank() }
                ?: ANTHROPIC_VERSION),
        )

    override fun payload(window: List<ChatMessage>, userText: String, config: EngineConfig): String {
        val msgs = StringBuilder()
        for (m in window) {
            if (msgs.isNotEmpty()) msgs.append(',')
            msgs.append("{\"role\":\"").append(roleJson(m.role)).append("\",\"content\":\"")
                .append(escapeJson(m.text)).append("\"}")
        }
        val thinking = thinkingJson(config)
        return "{\"model\":\"" + escapeJson(config.modelId) + "\",\"max_tokens\":" +
            maxTokensFor(config, thinking) + ",\"stream\":true,\"messages\":[" + msgs + "]" + systemField(config) +
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
            maxTokensFor(config, thinking) + ",\"stream\":true,\"messages\":[" + msgs + "]" + systemField(config) +
            (if (thinking != null) ",\"thinking\":" + thinking else "") + "}"
    }

    internal fun attachmentSupported(mimeType: String): Boolean =
        mimeType == "image/jpeg" || mimeType == "image/png" ||
            mimeType == "image/gif" || mimeType == "image/webp" ||
            mimeType == "application/pdf"

    /** One reasoning control: bounded budget_tokens, floor 1024; 0 = thinking off (field omitted). */
    /**
     * max_tokens must EXCEED thinking.budget_tokens, or Anthropic 400s the request. Raise the
     * answer ceiling to budget + 1024 rather than shrinking the budget: a cut budget silently
     * degrades reasoning, while a raised ceiling costs nothing unless the tokens are used.
     * The reference client defends this the same way (endpoint-lab.html:470-475).
     *
     * BUDGET RULE CITATION (corrected 2026-11-10): "budget_tokens must be less than max_tokens" is
     * Anthropic's extended-thinking rule, documented at
     * https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking (page read 2026-11-10;
     * the rule is stated there, the exact sentence was beyond the fetched excerpt - UNVERIFIED as a
     * quotation, verified as the behaviour our 400s showed). The VERSIONING page is the wrong source
     * for it and is no longer cited for this rule.
     *
     * No temperature/top_p/top_k is ever sent. Two separate facts, both from
     * platform.claude.com/docs/en/api/versioning (owner re-checked 2026-11-10):
     *  (a) at version 2026-10-21 these three sampling parameters are REMOVED and any of them 400s on
     *      every model;
     *  (b) at the same version a document block inside an ASSISTANT message, and a text block
     *      carrying non-empty citations in a USER message, also 400.
     * Checked against our own payload builder, not assumed: document/image blocks are emitted only
     * into the final USER turn (payload() below appends them after the window loop), and we never
     * emit a citations field at all. The guard test asserts the payload contains none of the three
     * sampling keys, so wiring temperature into a shared settings port cannot silently break Anthropic.
     */
    internal fun maxTokensFor(config: EngineConfig, thinkingJson: String?): Int {
        val asked = config.maxResponseTokens
        if (thinkingJson == null) return asked
        val budget = Regex("\"budget_tokens\":([0-9]+)").find(thinkingJson)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return asked
        return if (asked <= budget) budget + 1024 else asked
    }

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

    /** "delta":{"stop_reason":"max_tokens"} arrives in the message_delta event. */
    override fun endReason(data: String): String? {
        val key = "\"stop_reason\":\""
        val i = data.indexOf(key)
        if (i < 0) return null
        val rest = data.substring(i + key.length)
        val end = rest.indexOf('"')
        if (end <= 0) return null
        val v = rest.substring(0, end)
        return if (v == "null" || v.isBlank()) null else v.lowercase()
    }

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
