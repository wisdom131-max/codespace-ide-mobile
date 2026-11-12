package com.codespace.ide.engine

/**
 * Area 6 - WHY a provider stopped talking.
 *
 * The adapters already emit [EngineEvent.Truncated] when they see a ceiling marker, but "the
 * stream ended" was otherwise taken to mean "the answer finished". That inference is wrong for
 * an output ceiling, a content filter and a dropped connection alike, so the raw provider value
 * is normalized here and carried explicitly.
 *
 * [raw] is kept for diagnostics. It is never shown to the model and never parsed by anything
 * downstream: providers add values without notice, and an unknown value must read as UNKNOWN
 * rather than being guessed into NATURAL.
 */
enum class FinishReason(
    /** True only when the provider said the answer was finished on its own terms. */
    val complete: Boolean,
) {
    /** Provider finished the answer naturally. */
    NATURAL(complete = true),
    /** Answer ran into the output ceiling. Continue, do not repeat. */
    OUTPUT_LIMIT(complete = false),
    /** Provider hit a stop sequence the caller supplied. Complete as far as we asked. */
    STOP_SEQUENCE(complete = true),
    /** Provider stopped to hand us tool calls. A turn, not an answer. */
    TOOL_CALLS(complete = true),
    /** Provider withheld content (safety filter, refusal). Never "continue". */
    CONTENT_FILTER(complete = false),
    /** Transport died mid-answer. Resumable but not a token ceiling. */
    INTERRUPTED(complete = false),
    /** The user stopped it. */
    CANCELLED(complete = false),
    /** Provider exposed nothing, or something we do not recognize. */
    UNKNOWN(complete = false),
    ;

    /**
     * Only a ceiling is a continuation case. A filter must not be "continued" around, and a
     * dropped connection is retried, not continued.
     */
    val continuable: Boolean get() = this == OUTPUT_LIMIT

    /**
     * The exact name written to a saved conversation. Persisting the ENUM NAME, not the raw
     * provider value, is what keeps a restored turn's eligibility identical to the one it had
     * before the app closed: the raw value's meaning is provider-specific and may change.
     */
    val storedName: String get() = name

    companion object {
        /**
         * Read a persisted verdict.
         *
         * [raw] null means the conversation predates finish-reason storage. That is UNKNOWN, not
         * NATURAL and not OUTPUT_LIMIT: an older reply must not become continuable just because it
         * looks unfinished, and must not be called complete when nothing recorded that it was.
         */
        fun fromStored(raw: String?): FinishReason {
            val v = raw?.trim() ?: return UNKNOWN
            if (v.isEmpty() || v == "-") return UNKNOWN
            return entries.firstOrNull { it.name == v } ?: UNKNOWN
        }

        /**
         * Normalize one provider's stop value.
         *
         * An empty or null raw value is UNKNOWN, NOT NATURAL: absence of a reason is absence of
         * evidence. Providers that expose no finish reason at all therefore land here, which is
         * the honest reading.
         */
        fun normalize(provider: String, raw: String?): FinishReason {
            val v = raw?.trim()?.lowercase() ?: return UNKNOWN
            if (v.isEmpty() || v == "null") return UNKNOWN
            return when (provider.lowercase()) {
                "openai", "openrouter", "groq", "deepseek", "mistral", "xai" -> when (v) {
                    "stop" -> NATURAL
                    "length" -> OUTPUT_LIMIT
                    "tool_calls", "function_call" -> TOOL_CALLS
                    "content_filter" -> CONTENT_FILTER
                    else -> UNKNOWN
                }
                "anthropic", "claude" -> when (v) {
                    "end_turn", "stop_sequence" -> if (v == "stop_sequence") STOP_SEQUENCE else NATURAL
                    "max_tokens" -> OUTPUT_LIMIT
                    "tool_use" -> TOOL_CALLS
                    "refusal" -> CONTENT_FILTER
                    else -> UNKNOWN
                }
                "gemini", "google" -> when (v) {
                    "stop" -> NATURAL
                    "max_tokens" -> OUTPUT_LIMIT
                    "safety", "recitation", "blocklist", "prohibited_content" -> CONTENT_FILTER
                    else -> UNKNOWN
                }
                else -> UNKNOWN
            }
        }
    }
}
