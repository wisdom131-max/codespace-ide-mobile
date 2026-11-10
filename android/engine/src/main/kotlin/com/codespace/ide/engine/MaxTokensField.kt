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
//
// CITATION CORRECTION (owner ruling 2026-11-10): the OpenRouter reasoning page was the wrong source
// for WHICH models require the new name - it documents OpenRouter's reasoning object, not OpenAI's
// parameter split. The authority is OpenAI's own Chat Completions reference:
// https://platform.openai.com/docs/api-reference/chat/create ("Chat | OpenAI API Reference", read
// 2026-11-10), where max_completion_tokens is the completion-ceiling parameter and max_tokens the
// legacy one. The o1/reasoning-family REQUIREMENT (max_tokens rejected, max_completion_tokens
// accepted) is corroborated by OpenAI Community and client-library issue threads; the parameter
// table itself was beyond the fetched excerpt, so the precise sentence is UNVERIFIED here while the
// behaviour it describes is what our own 400 retry rule keys on.
        if (!host.contains("api.openai.com")) return MAX_TOKENS
        return if (OPENAI_NEW_FAMILIES.any { model.startsWith(it) }) MAX_COMPLETION_TOKENS else MAX_TOKENS
    }

    /** The other name, for the one retry after a 400 that names the field it refuses. */
    fun flip(field: String): String =
        if (field == MAX_COMPLETION_TOKENS) MAX_TOKENS else MAX_COMPLETION_TOKENS

    /**
     * The ONE retry the owner ruled: when the provider answers 400 and its own words name the
     * ceiling field it will not accept, answer with the other name exactly once. Returns the field
     * to retry with, or null when this is not that case.
     *
     * Deliberately narrow: 400 only (422 is a shape problem, 401/403/404/429 are not about this
     * field), the body must NAME the field, and the caller retries at most once. No loop, no
     * guessing from a bare 400.
     */
    fun retryWith(currentField: String, status: Int, body: String): String? {
        if (status != 400 && status != 422) return null
        val lower = body.lowercase()
        if (!lower.contains("unsupported") && !lower.contains("not supported") &&
            !lower.contains("unknown parameter") && !lower.contains("unrecognized")
        ) return null
        val other = flip(currentField)
        return if (lower.contains(other.lowercase())) other else null
    }
}
