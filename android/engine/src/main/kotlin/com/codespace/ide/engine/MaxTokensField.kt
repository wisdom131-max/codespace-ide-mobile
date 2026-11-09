package com.codespace.ide.engine

/**
 * Which field carries the answer ceiling for a given model.
 *
 * OpenAI's newer reasoning models reject `max_tokens` with 400 unsupported_parameter and require
 * `max_completion_tokens`; every OpenAI-compatible endpoint we ship presets for still speaks
 * `max_tokens`, and OpenRouter treats both as the SAME shared budget
 * (openrouter.ai/docs/use-cases/reasoning-tokens, read 2026-11-09: "max_tokens (or
 * max_completion_tokens, which shares the same budget)").
 *
 * So the rule is a capability row, not a guess:
 *  - api.openai.com  -> max_completion_tokens for the o-series and gpt-5 family, max_tokens otherwise
 *  - OpenRouter      -> max_tokens (documented equivalent, and it is the wider-compatibility name)
 *  - anything else   -> max_tokens
 *
 * The row can be overridden per endpoint later; the single-retry fallback for a 400 that names the
 * other field is a separate change, so a wrong row costs one failed request, never a loop.
 */
object MaxTokensField {

    const val MAX_TOKENS = "max_tokens"
    const val MAX_COMPLETION_TOKENS = "max_completion_tokens"

    private val OPENAI_NEW_FAMILIES = listOf("o1", "o3", "o4", "gpt-5", "gpt5")

    fun forModel(providerId: String, baseUrl: String, modelId: String): String {
        val host = baseUrl.lowercase()
        val model = modelId.lowercase()
        // HOST decides, never the preset id: a custom endpoint can be labelled 'openai' and still
        // be Groq, OpenRouter or a local server, and only api.openai.com itself enforces the split.
        if (!host.contains("api.openai.com")) return MAX_TOKENS
        return if (OPENAI_NEW_FAMILIES.any { model.startsWith(it) }) MAX_COMPLETION_TOKENS else MAX_TOKENS
    }

    /** The other name, for the one retry after a 400 that names the field it refuses. */
    fun flip(field: String): String =
        if (field == MAX_COMPLETION_TOKENS) MAX_TOKENS else MAX_COMPLETION_TOKENS
}
