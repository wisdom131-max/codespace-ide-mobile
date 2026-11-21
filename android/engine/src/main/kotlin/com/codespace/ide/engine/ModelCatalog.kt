package com.codespace.ide.engine

/**
 * A(b) WP5: model catalog + capability data.
 *
 * The bundled TRIMMED table's context windows and input modalities are
 * VERIFIED from the live OpenRouter /models dump (fetched 2026-10-28 from
 * openrouter.ai/api/v1/models — its per-model context_length and
 * architecture.input_modalities fields; that endpoint returned 464 models).
 * Entries match ANY provider by exact id or lowercase substring, because the
 * same vendor model surfaces under several providers (openai/... on OpenRouter
 * vs the vendor's own id on the vendor API).
 *
 * supportsTools is NOT in that dump and is left false everywhere until the
 * vendor docs are verified for it (lands with the tools/attachments work).
 * supportsReasoning is set true only where the reasoning CONTROL itself was
 * verified to exist for that family (gemini thinkingBudget, anthropic
 * thinking budget, openai/xai reasoning_effort).
 *
 * The catalog feeds the BudgetGuard: the send budget SCALES with the model's
 * context window (chars ~= 3 per token, 60% headroom for the response and
 * system), capped by the user's max and floored at 8k. Everything about the
 * estimate is labeled ESTIMATE — providers without a balance API (Hugging Face
 * router et al.) never see anything better than an estimate.
 *
 * User override: withOverride(bundled, custom) lets WP7 Settings replace any
 * bundled row with an owner-entered capability (persisted by the settings
 * layer, keyed provider:model).
 */
data class ModelCapability(
    val idMatch: String,
    val contextTokens: Int,
    val supportsImages: Boolean,
    val supportsPdf: Boolean,
    /**
     * Tri-state (advisor ruling 2026-11-04). true = the model does native tool calling,
     * false = it is known NOT to, null = unknown. Unknown is the default and means "offer the
     * tool wire exactly as before": VN Code routes every request through user-supplied
     * OpenAI-compatible endpoints, and a model the catalog has never heard of must not be
     * silently stripped of its tools. Only an explicit false withholds them, and then the chat
     * says so in an activity row instead of pretending the model refused.
     */
    val supportsTools: Boolean? = null,
    val supportsReasoning: Boolean = false,
    /** Provider ids this row is verified for. Empty means "this id names the model, whoever serves it". */
    val providers: Set<String> = emptySet(),
)

object ModelCatalog {

    /** chars per token, the same estimator BudgetGuard's docs use. */
    const val CHARS_PER_TOKEN_ESTIMATE = 3

    /** Owner override wins over any bundled row (WP7 Settings edits it). */
    fun withOverride(bundled: ModelCapability?, custom: ModelCapability?): ModelCapability? =
        custom ?: bundled

    /**
     * P5: exact id first, then the MOST SPECIFIC substring match.
     *
     * The old rule was `BUNDLED.firstOrNull { id.contains(it.idMatch) }`, which is first-match-wins on a
     * list ordered by nothing in particular. Two rows can match one id - "gpt-6.1-terra-pro-mini"
     * contains "gpt-6.1-terra-pro" AND (once any shorter family row exists) the family name - and the
     * winner was whichever sat higher in the file. The loser's context window then set the payload
     * budget, so the model was silently under- or over-fed. Longest match is the one the model id
     * actually names.
     */
    fun capabilityFor(providerId: String, modelId: String): ModelCapability? = resolve(providerId, modelId).capability

    /** How the capability was found, so the UI can be honest about which numbers are verified. */
    enum class Source { EXACT, SUBSTRING, UNKNOWN }

    data class Resolution(val capability: ModelCapability?, val source: Source)

    fun resolve(providerId: String, modelId: String): Resolution {
        val m = modelId.lowercase()
        if (m.isBlank()) return Resolution(null, Source.UNKNOWN)
        val scoped = if (providerId.isBlank()) BUNDLED else BUNDLED.filter { providerId in it.providers || it.providers.isEmpty() }
        scoped.firstOrNull { it.idMatch == m }?.let { return Resolution(it, Source.EXACT) }
        val best = scoped.filter { m.contains(it.idMatch) }.maxByOrNull { it.idMatch.length }
            ?: return Resolution(null, Source.UNKNOWN)
        return Resolution(best, Source.SUBSTRING)
    }

    /**
     * One honest line about how much this model's limits are known, or null when nothing needs saying.
     * Shown when the user picks a model, so an unknown model is not presented with invented numbers.
     */
    fun honestyNote(providerId: String, modelId: String): String? = when (resolve(providerId, modelId).source) {
        Source.EXACT -> null
        Source.SUBSTRING -> "This model is matched to a known family, so its context window is an estimate."
        Source.UNKNOWN -> "This model is not in the catalog: context and attachment limits fall back to your settings."
    }

    /**
     * Send budget in PAYLOAD CHARS, scaled to the model's context window:
     * tokens * 3 chars * 0.6 headroom, capped at the user's max (default 48k,
     * the pre-catalog cap, so big-window models behave exactly as before) and
     * floored at 8k. Unknown model -> the user's max. ESTIMATE by definition.
     */
    /**
     * PROJECT EIGHT 8.6 (catalog 21) — the per-send payload budget, scaled to the MODEL.
     *
     * The previous version looked model-aware and was not: it computed the window allowance and then
     * clamped it with `coerceIn(8_000, userMaxChars)`, whose default ceiling was 48_000. A model with
     * a 1,050,000-token window and one with 131,072 both came out at 48,000 characters, so a million
     * tokens of context were paid for and 12k of them used. That is the real shape of the "cramped
     * workspace" complaint: not a missing allocator, an allocator that threw its own answer away.
     *
     * [userMaxChars] is now an OVERRIDE, not the ceiling: 0 (the default) means "let the model
     * decide". A caller that knows a provider limit can still lower it, and an explicit value still
     * wins in both directions.
     *
     * [SAFETY_MAX_CHARS] is deliberately NOT the model window. Sending a million-token prefix from a
     * phone is a cost and latency event, not a capability: on a metered API one such turn can cost
     * more than a month of ordinary use, and a mid-range device spends minutes streaming it. The
     * window sets what is POSSIBLE; this sets what is SANE per send.
     */
    fun payloadBudgetChars(capability: ModelCapability?, userMaxChars: Int = 0): Int {
        val windowChars = if (capability != null && capability.contextTokens > 0) {
            (capability.contextTokens * CHARS_PER_TOKEN_ESTIMATE * 0.6).toInt()
        } else {
            SAFETY_MAX_CHARS
        }
        val sane = windowChars.coerceAtMost(SAFETY_MAX_CHARS)
        val chosen = if (userMaxChars > 0) userMaxChars else sane
        return chosen.coerceAtLeast(MIN_PAYLOAD_CHARS)
    }

    /** Below this a request cannot carry a useful instruction plus a file, so it is never the answer. */
    const val MIN_PAYLOAD_CHARS: Int = 8_000

    /** Per-send ceiling regardless of window: a cost and latency guard, not a model limit. */
    const val SAFETY_MAX_CHARS: Int = 160_000

    // Verified 2026-10-28 from the live OpenRouter /models dump (context_length
    // + architecture.input_modalities). No entry here is from memory.
    val BUNDLED = listOf(
        ModelCapability("gpt-6.1-terra-pro", 1_050_000, supportsImages = true, supportsPdf = true, supportsReasoning = true),
        ModelCapability("gemini-3.8-flash", 1_048_576, supportsImages = true, supportsPdf = true, supportsReasoning = true),
        ModelCapability("claude-sonnet-5.5", 1_000_000, supportsImages = true, supportsPdf = true, supportsReasoning = true),
        ModelCapability("grok-4.7", 500_000, supportsImages = true, supportsPdf = true, supportsReasoning = true),
        ModelCapability("deepseek-v4.1", 1_048_576, supportsImages = true, supportsPdf = false, supportsReasoning = false),
        ModelCapability("llama-3.3-70b", 131_072, supportsImages = false, supportsPdf = false, supportsReasoning = false),
    )
}
