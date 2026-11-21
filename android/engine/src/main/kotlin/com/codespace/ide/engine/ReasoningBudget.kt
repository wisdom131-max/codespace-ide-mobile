package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.1 — one place that turns the user's reasoning choice into a number.
 *
 * Why this exists. The chat panel did this:
 *
 *     val reasoningBudget = if (sel.providerId == "gemini" || sel.providerId == "anthropic") 1024 else 0
 *
 * so the effort menu in the model picker changed a value that nothing read. Two consequences,
 * both observable:
 *
 *  - Effort-kind providers (OpenAI, OpenRouter, xAI) were always given 0. [OpenAIAdapter.reasoningEffort]
 *    returns null for a budget of 0, so `reasoning_effort` was NEVER sent to them. The menu offered
 *    Low/Medium/High and the wire carried nothing.
 *  - Native-thinking providers (Gemini, Anthropic) were pinned at 1024 forever, which is the lowest
 *    setting. Choosing "High" in the picker sent exactly what "Low" sent.
 *
 * The budget is the single currency. Native adapters send it as a token budget
 * (`thinkingConfig.thinkingBudget`, `thinking.budget_tokens`); effort-kind adapters derive
 * low/medium/high from the same number (see [OpenAIAdapter.reasoningEffort]). One number, three
 * wire formats, no second mapping to drift.
 *
 * Providers whose preset says `reasoningKind = "none"` (DeepSeek, Groq, Mistral, Ollama) get 0,
 * because sending a reasoning parameter they do not accept is a 400 on a request that otherwise
 * looks correct. A custom endpoint gets 0 too: its capability is unknown, and this type refuses to
 * guess rather than promise a feature it cannot verify.
 */
object ReasoningBudget {

    /** Thinking off. The only value that means "do not send a reasoning parameter". */
    const val OFF = 0

    /** ~1k thinking tokens. The old hardcoded value, kept as the lowest real setting. */
    const val LOW = 1024

    /** ~4k. What the picker calls the default. */
    const val MEDIUM = 4096

    /** ~16k. Deep reasoning, and the most expensive. */
    const val HIGH = 16384

    /**
     * Providers with a native thinking budget rather than a preset row. The preset table documents
     * this split itself: "Gemini thinkingBudget and Anthropic thinking.budget_tokens are native
     * adapters, not presets."
     */
    val NATIVE_THINKING: Set<String> = setOf("gemini", "anthropic")

    /**
     * Map the picker's effort name to a token budget.
     *
     * Null means "the caller did not say", and it resolves to [LOW] on purpose: that is what every
     * native-thinking request sent before this existed, so an un-migrated caller keeps today's
     * behaviour instead of silently losing thinking. An explicit "off"/"none" is the only way to
     * turn it off, so the two cases can never be confused.
     */
    fun forEffort(effort: String?): Int = when (effort?.trim()?.lowercase()) {
        "off", "none", "disabled", "0" -> OFF
        null, "", "low", "minimal" -> LOW
        "medium", "med", "default" -> MEDIUM
        "high", "max", "xhigh", "highest" -> HIGH
        // An unrecognised name is a caller bug, not a reason to disable reasoning entirely.
        else -> MEDIUM
    }

    /** True when this provider can actually receive a reasoning control. */
    fun supports(providerId: String?): Boolean {
        val id = providerId?.trim()?.lowercase() ?: return false
        if (id in NATIVE_THINKING) return true
        return ProviderPresets.byId(id)?.reasoningKind == "effort"
    }

    /**
     * What a provider gets when the user has not chosen anything.
     *
     * This is deliberately NOT [forEffort]'s answer, and the difference is the whole safety story:
     *
     *  - Native-thinking providers (Gemini, Anthropic) default to [LOW], because that is exactly
     *    what every request sent before this type existed. An un-migrated caller keeps today's
     *    behaviour rather than silently losing thinking.
     *  - Effort-kind providers default to [OFF], because the preset table's own note says
     *    "reasoning_effort only on reasoning-capable models; default stays OFF". Sending
     *    reasoning_effort to a model that does not accept it is a 400 on a request that otherwise
     *    looks correct, so opting in has to be a deliberate act, not a default.
     *  - Anything unknown defaults to [OFF]: never guess a capability.
     */
    fun defaultFor(providerId: String?): Int {
        val id = providerId?.trim()?.lowercase() ?: return OFF
        return if (id in NATIVE_THINKING) LOW else OFF
    }

    /**
     * The budget to put in [EngineConfig.reasoningBudget] for this provider and effort.
     *
     * A null or blank effort means "the user has not chosen", which resolves per provider through
     * [defaultFor] so wiring this in changes nothing until someone actually picks a setting.
     * 0 for any provider that cannot take a reasoning control at all.
     */
    fun forProvider(providerId: String?, effort: String?): Int {
        if (!supports(providerId)) return OFF
        if (effort.isNullOrBlank()) return defaultFor(providerId)
        return forEffort(effort)
    }

    /** The effort name a budget corresponds to, for showing the picker's current state honestly. */
    fun nameFor(budget: Int): String = when {
        budget <= 0 -> "off"
        budget < MEDIUM -> "low"
        budget < HIGH -> "medium"
        else -> "high"
    }
}
