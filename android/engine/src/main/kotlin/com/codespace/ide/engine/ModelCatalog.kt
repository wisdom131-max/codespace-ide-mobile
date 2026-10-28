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
    val supportsTools: Boolean = false,
    val supportsReasoning: Boolean = false,
)

object ModelCatalog {

    /** chars per token, the same estimator BudgetGuard's docs use. */
    const val CHARS_PER_TOKEN_ESTIMATE = 3

    /** Owner override wins over any bundled row (WP7 Settings edits it). */
    fun withOverride(bundled: ModelCapability?, custom: ModelCapability?): ModelCapability? =
        custom ?: bundled

    /** Exact id or lowercase substring match against the bundled table. */
    fun capabilityFor(providerId: String, modelId: String): ModelCapability? {
        val m = modelId.lowercase()
        return BUNDLED.firstOrNull { m.contains(it.idMatch) }
    }

    /**
     * Send budget in PAYLOAD CHARS, scaled to the model's context window:
     * tokens * 3 chars * 0.6 headroom, capped at the user's max (default 48k,
     * the pre-catalog cap, so big-window models behave exactly as before) and
     * floored at 8k. Unknown model -> the user's max. ESTIMATE by definition.
     */
    fun payloadBudgetChars(capability: ModelCapability?, userMaxChars: Int = 48_000): Int {
        if (capability == null || capability.contextTokens <= 0) return userMaxChars
        val scaled = (capability.contextTokens * CHARS_PER_TOKEN_ESTIMATE * 0.6).toInt()
        return scaled.coerceIn(8_000, userMaxChars)
    }

    // Verified 2026-10-28 from the live OpenRouter /models dump (context_length
    // + architecture.input_modalities). No entry here is from memory.
    val BUNDLED = listOf(
        ModelCapability("gpt-6.1-terra-pro", 1_050_000, supportsImages = true, supportsPdf = true, tools = false, reasoning = true),
        ModelCapability("gemini-3.8-flash", 1_048_576, supportsImages = true, supportsPdf = true, tools = false, reasoning = true),
        ModelCapability("claude-sonnet-5.5", 1_000_000, supportsImages = true, supportsPdf = true, tools = false, reasoning = true),
        ModelCapability("grok-4.7", 500_000, supportsImages = true, supportsPdf = true, tools = false, reasoning = true),
        ModelCapability("deepseek-v4.1", 1_048_576, supportsImages = true, supportsPdf = false, tools = false, reasoning = false),
        ModelCapability("llama-3.3-70b", 131_072, supportsImages = false, supportsPdf = false, tools = false, reasoning = false),
    )
}
