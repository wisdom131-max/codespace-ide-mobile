package com.codespace.ide.engine

/**
 * OpenAI-compatible wire format (POST chat/completions, SSE
 * choices[0].delta.content, [DONE] sentinel). Behavior IDENTICAL to the
 * pre-adapter engine: same payload bytes, same delta extraction — this is a
 * pure extraction, not a redesign.
 */
class OpenAIAdapter : ProviderAdapter {

    /**
     * PROJECT EIGHT 8.2: advertise the tools as an OpenAI `tools` array.
     *
     * `tool_choice: auto` lets the model decide, which is what the text protocol already implied.
     * The `parameters` value is passed through verbatim so a schema the caller built survives intact.
     */
    override fun withTools(body: String, config: EngineConfig): String {
        if (config.toolDeclarations.isEmpty()) return body
        val sb = StringBuilder(",\"tools\":[")
        config.toolDeclarations.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"function\",\"function\":{\"name\":\"")
                .append(escapeJson(t.name))
                .append("\",\"description\":\"")
                .append(escapeJson(t.description))
                .append("\",\"parameters\":")
                .append(t.schema)
                .append("}}")
        }
        sb.append("],\"tool_choice\":\"auto\"")
        return spliceTools(body, sb.toString())
    }

    override val providerId = "openai"
    override val streamKind = StreamProvider.OPENAI

    /** Base OR full path: EndpointUrls accepts both and never duplicates a segment. */
    override fun endpoint(config: EngineConfig): String =
        EndpointUrls.chatCompletions(config.baseUrl)

    /** Blank key = no Authorization header at all (Ollama/local presets). */
    override fun headers(config: EngineConfig): Map<String, String> =
        // Accept advertises SSE on every request: a proxy that only sees application/json may
        // buffer or rewrite a stream, and some gateways answer differently.
        //
        // CITATION STATUS: UNVERIFIED as a vendor requirement. No OpenAI/Anthropic/Google page was
        // found stating that a client MUST send this pair; the justification is our own reference
        // client (endpoint-lab.html:1675), which sends it on every send and works. Marked UNVERIFIED
        // rather than dressed up as documented.
        if (config.apiKey.isBlank()) mapOf("Accept" to "application/json, text/event-stream")
        else mapOf(
            "Accept" to "application/json, text/event-stream",
            "Authorization" to "Bearer " + config.apiKey,
        )

    override fun payload(window: List<ChatMessage>, userText: String, config: EngineConfig): String {
        val msgs = StringBuilder()
        for (m in window) {
            if (msgs.isNotEmpty()) msgs.append(',')
            msgs.append("{\"role\":\"").append(roleJson(m.role)).append("\",\"content\":\"")
                .append(escapeJson(m.text)).append("\"}")
        }
        val effort = reasoningEffort(config)
        return "{\"model\":\"" + escapeJson(config.modelId) + "\",\"stream\":true," +
            "\"" + config.maxTokensField + "\":" + config.maxResponseTokens + ",\"messages\":[" + systemMsg(config) + msgs + "]" +
            reasoningJson(config) + cacheKeyJson(config) + "}"
    }

    /**
     * PROJECT EIGHT 8.13 (catalog 27, second provider): steer requests that share a prefix onto the
     * same OpenAI prompt cache.
     *
     * OpenAI caches automatically above roughly 1024 tokens, so unlike Anthropic there is nothing to
     * mark. `prompt_cache_key` only improves ROUTING: two requests with the same key are more likely
     * to land on a machine that already holds the prefix.
     *
     * Sent ONLY for the official OpenAI preset. Every third-party "OpenAI-compatible" server in the
     * preset list is a different implementation, and several reject unknown top-level fields; a 400
     * on every send is far worse than a missed cache hit.
     */
    internal fun cacheKeyJson(config: EngineConfig): String {
        if (config.providerId != "openai") return ""
        if (config.systemInstruction.isBlank() && config.toolDeclarations.isEmpty()) return ""
        val basis = config.modelId + "|" + config.systemInstruction.hashCode() + "|" +
            config.toolDeclarations.size
        return ",\"prompt_cache_key\":\"" + escapeJson(basis) + "\""
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
        // OWNER REPORT (2026-11-23), Cloudflare HTTP 400 "Type mismatch of '/messages/0/content',
        // 'array' not in 'string'". The switch that marks Cloudflare text-only was already set
        // correctly (ChatSession sets supportsContentParts=false for api.cloudflare.com). The bug was
        // HERE: with no attachments this returned early to the other overload, which has no idea about
        // that switch and wrote every message's content as an array - hence messages 0..6 all
        // rejected. The flattening path below handles an empty attachment list perfectly well, so a
        // text-only provider now always takes it.
        if (attachments.isEmpty() && config.supportsContentParts) return payload(window, userText, config)
        val msgs = StringBuilder()
        for (m in window) {
            if (msgs.isNotEmpty()) msgs.append(',')
            msgs.append("{\"role\":\"").append(roleJson(m.role)).append("\",\"content\":\"")
                .append(escapeJson(m.text)).append("\"}")
        }
        val parts = StringBuilder()
        val combined = userText + textBlocks(attachments)
        // P0.1: a provider that validates `content` as a STRING (Cloudflare /ai/v1) gets the
        // flattened text. The attachment text is already inside `combined`, so nothing is lost;
        // an image is named explicitly rather than dropped in silence.
        if (!config.supportsContentParts) {
            val binaries = binaryAttachments(attachments)
            val note = if (binaries.isEmpty()) "" else
                "\n\n[Attachment not sent: this provider accepts text only (" +
                    binaries.joinToString(", ") { it.name } + ")]"
            if (msgs.isNotEmpty()) msgs.append(',')
            msgs.append("{\"role\":\"user\",\"content\":\"")
                .append(escapeJson(combined + note)).append("\"}")
            val effortFlat = reasoningEffort(config)
            return "{\"model\":\"" + escapeJson(config.modelId) + "\",\"stream\":true," +
                "\"" + config.maxTokensField + "\":" + config.maxResponseTokens +
                ",\"messages\":[" + systemMsg(config) + msgs + "]" + reasoningJson(config) + "}"
        }
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
            "\"" + config.maxTokensField + "\":" + config.maxResponseTokens + ",\"messages\":[" + systemMsg(config) + msgs + "]" +
            reasoningJson(config) + "}"
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
    /**
     * Reasoning travels in a DIFFERENT field depending on who receives it.
     *
     * - OpenRouter documents one consolidated object, `reasoning: { effort | max_tokens | enabled,
     *   exclude }`, for both Chat Completions and Responses, with effort values
     *   max|xhigh|high|medium|low|minimal|none. Source: openrouter.ai/docs/use-cases/reasoning-tokens,
     *   read 2026-11-09. Its Chat Completions page keeps max_tokens/max_completion_tokens as the
     *   same shared budget.
     * - OpenAI-compatible endpoints take the flat `reasoning_effort` string.
     *
     * Sending the flat field to OpenRouter is the kind of mismatch that comes back as a parameter
     * error on a request that otherwise looks fine, so the host decides the shape, not the preset.
     */
    internal fun reasoningJson(config: EngineConfig): String {
        val effort = reasoningEffort(config) ?: return ""
        val host = config.baseUrl.lowercase()
        return if (host.contains("openrouter.ai")) {
            ",\"reasoning\":{\"effort\":\"" + effort + "\"}"
        } else {
            ",\"reasoning_effort\":\"" + effort + "\""
        }
    }

    internal fun reasoningEffort(config: EngineConfig): String? {
        if (ProviderPresets.byId(config.providerId)?.reasoningKind != "effort") return null
        val b = config.reasoningBudget
        if (b <= 0) return null
        return if (b < 4096) "low" else if (b < 16384) "medium" else "high"
    }

    /** "finish_reason":"length" arrives in the last chunk before [DONE]. */
    override fun endReason(data: String): String? {
        val key = "\"finish_reason\":\""
        val i = data.indexOf(key)
        if (i < 0) return null
        val rest = data.substring(i + key.length)
        val end = rest.indexOf('"')
        if (end <= 0) return null
        val v = rest.substring(0, end)
        return if (v == "null" || v.isBlank()) null else v.lowercase()
    }

    override fun extractDelta(data: String): String? {
        if (data.length < 2 || data[0] != '{') return null
        val key = "\"content\":\""
        val i = data.indexOf(key)
        if (i < 0) return null
        return decodeJsonStringValue(data, i + key.length)
    }

    override fun isDoneSentinel(data: String): Boolean = data == "[DONE]"

    internal fun roleJson(role: ChatRole): String = when (role) {
        ChatRole.USER -> "user"
        // A system turn must stay a system turn; mapping it to assistant silently
        // turned instructions into a fake reply the model had supposedly written.
        ChatRole.SYSTEM -> "system"
        ChatRole.ASSISTANT -> "assistant"
    }

    /** The mode instruction as a leading system message; blank adds nothing. */
    internal fun systemMsg(config: EngineConfig): String {
        // PROJECT EIGHT 8.10: the clock arrives as a separate volatile tail so Anthropic can cache
        // the static instruction. OpenAI-compatible endpoints have no equivalent breakpoint, so the
        // two parts are simply rejoined here and the model sees the same text it always did.
        val text = if (config.volatileSystemTail.isBlank()) config.systemInstruction
        else config.systemInstruction + "\n\n" + config.volatileSystemTail
        return if (text.isBlank()) ""
        else "{\"role\":\"system\",\"content\":\"" + escapeJson(text) + "\"},"
    }

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

    /** STAGE 5.4: the same body, with this turn's tool call/result pairs spliced in. */
    override fun payloadWithHistory(
        window: List<ChatMessage>,
        userText: String,
        attachments: List<ChatAttachment>,
        config: EngineConfig,
        exchanges: List<ToolExchange>,
    ): String {
        val base = payload(window, userText, attachments, config)
        val rendered = ConversationContinuity.render(StreamProvider.OPENAI, exchanges)
        if (rendered !is RenderedHistory.Ok) return base
        return ConversationContinuity.splice(base, "messages", rendered.json)
    }
}
