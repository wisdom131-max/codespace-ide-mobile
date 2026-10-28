package com.codespace.ide.engine

/**
 * OpenAI-compatible wire format (POST chat/completions, SSE
 * choices[0].delta.content, [DONE] sentinel). Behavior IDENTICAL to the
 * pre-adapter engine: same payload bytes, same delta extraction — this is a
 * pure extraction, not a redesign.
 */
class OpenAIAdapter : ProviderAdapter {
    override val providerId = "openai"

    override fun endpoint(config: EngineConfig): String = config.baseUrl

    /** Blank key = no Authorization header at all (Ollama/local presets). */
    override fun headers(config: EngineConfig): Map<String, String> =
        if (config.apiKey.isBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer " + config.apiKey)

    override fun payload(window: List<ChatMessage>, userText: String, config: EngineConfig): String {
        val msgs = StringBuilder()
        for (m in window) {
            if (msgs.isNotEmpty()) msgs.append(',')
            msgs.append("{\"role\":\"").append(roleJson(m.role)).append("\",\"content\":\"")
                .append(escapeJson(m.text)).append("\"}")
        }
        val effort = reasoningEffort(config)
        return "{\"model\":\"" + escapeJson(config.modelId) + "\",\"stream\":true," +
            "\"max_tokens\":" + config.maxResponseTokens + ",\"messages\":[" + msgs + "]" +
            (if (effort != null) ",\"reasoning_effort\":\"" + effort + "\"" else "") + "}"
    }

    /**
     * WP8: the final user turn becomes a content ARRAY - text plus image_url
     * parts carrying data URLs (docs verified 2026-10-28: chat/completions
     * vision takes image_url with data: URIs). Inline PDF is NOT part of the
     * chat/completions wire format - refused BEFORE the request.
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
        val parts = StringBuilder()
        val combined = userText + textBlocks(attachments)
        if (combined.isNotEmpty()) parts.append("{\"type\":\"text\",\"text\":\"")
            .append(escapeJson(combined)).append("\"}")
        for (a in binaryAttachments(attachments)) {
            if (!attachmentSupported(a.mimeType)) {
                throw IllegalArgumentException(
                    "Attachment '" + a.name + "' (" + a.mimeType + ") is not supported by this provider's wire format (images only; inline PDF is not part of chat/completions)")
            }
            if (parts.isNotEmpty()) parts.append(',')
            parts.append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:")
                .append(a.mimeType).append(";base64,").append(a.base64Data).append("\"}}")
        }
        if (msgs.isNotEmpty()) msgs.append(',')
        msgs.append("{\"role\":\"user\",\"content\":[").append(parts).append("]}")
        val effort = reasoningEffort(config)
        return "{\"model\":\"" + escapeJson(config.modelId) + "\",\"stream\":true," +
            "\"max_tokens\":" + config.maxResponseTokens + ",\"messages\":[" + msgs + "]" +
            (if (effort != null) ",\"reasoning_effort\":\"" + effort + "\"" else "") + "}"
    }

    internal fun attachmentSupported(mimeType: String): Boolean = mimeType.startsWith("image/")

    /**
     * WP3: the ONE reasoning control for effort-kind presets (openai, openrouter,
     * xai). Mapped from EngineConfig.reasoningBudget: omitted when 0/off or when
     * the provider's preset is kind none (deepseek/groq/mistral/ollama) or when
     * the provider is unknown (custom endpoints). Off by default - sending
     * reasoning_effort to a non-reasoning model is a 400, so the panel sets 0
     * unless the owner opts in (WP7 Settings reasoning control).
     */
    internal fun reasoningEffort(config: EngineConfig): String? {
        if (ProviderPresets.byId(config.providerId)?.reasoningKind != "effort") return null
        val b = config.reasoningBudget
        if (b <= 0) return null
        return if (b < 4096) "low" else if (b < 16384) "medium" else "high"
    }

    override fun extractDelta(data: String): String? {
        if (data.length < 2 || data[0] != '{') return null
        val key = "\"content\":\""
        val i = data.indexOf(key)
        if (i < 0) return null
        return decodeJsonStringValue(data, i + key.length)
    }

    override fun isDoneSentinel(data: String): Boolean = data == "[DONE]"

    internal fun roleJson(role: ChatRole): String =
        if (role == ChatRole.USER) "user" else "assistant"

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
