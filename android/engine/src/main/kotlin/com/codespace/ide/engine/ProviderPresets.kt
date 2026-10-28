package com.codespace.ide.engine

/**
 * A(b) WP3: OpenAI-compatible provider PRESETS — one row per ready-made
 * provider; every preset rides the OpenAIAdapter wire format (POST
 * chat/completions, SSE choices[0].delta.content, [DONE]).
 *
 * Wire facts verified 2026-10-28 (official docs pages + live endpoint probes
 * from the build sandbox; evidence per row in [notes]):
 *  - Groq: official API reference curl, console.groq.com/docs/api-reference.
 *  - Mistral: official docs curl, docs.mistral.ai/api ("POST /v1/chat/completions").
 *  - xAI: official docs, docs.x.ai (base_url "https://api.x.ai/v1").
 *  - OpenRouter: live probe POST /api/v1/chat/completions -> 401 (path live,
 *    Bearer needed) and GET /api/v1/models -> 200 with live model data.
 *  - DeepSeek: docs site unreachable from the sandbox; live probes returned
 *    401 (auth wall, path exists) on BOTH /chat/completions and /v1/chat/completions.
 *  - Ollama: GitHub ollama/ollama docs/openai.md — OpenAI compatibility at
 *    http://localhost:11434/v1, no key. Local-only, never a cloud call.
 *
 * NOT presets: Hugging Face router, LM Studio, and any other OpenAI-compatible
 * URL — those are WP4 custom endpoints by the owner's ruling. Custom headers
 * (OpenRouter X-Title/HTTP-Referer attribution) are PARKED until the security
 * review; presets send Authorization only.
 *
 * Reasoning control (one control, mapped per provider): reasoningKind
 * "effort" = OpenAI reasoning_effort family; "none" = provider has no wire
 * reasoning parameter we send. Gemini thinkingBudget and Anthropic
 * thinking.budget_tokens are native adapters, not presets.
 */
data class ProviderPreset(
    val providerId: String,
    val label: String,
    val baseUrl: String,
    /** "effort" (OpenAI reasoning_effort) or "none" (send nothing). */
    val reasoningKind: String,
    val requiresKey: Boolean,
    /** Blank = the panel keeps an editable free-text model id (WP5 catalog replaces). */
    val defaultModel: String,
    val notes: String,
)

object ProviderPresets {

    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            providerId = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1/chat/completions",
            reasoningKind = "effort",
            requiresKey = true,
            defaultModel = "",
            notes = "reasoning_effort only on reasoning-capable models; default stays OFF (budget 0).",
        ),
        ProviderPreset(
            providerId = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1/chat/completions",
            reasoningKind = "effort",
            requiresKey = true,
            defaultModel = "",
            notes = "Attribution headers (X-Title/HTTP-Referer) PARKED pending security review; Bearer only.",
        ),
        ProviderPreset(
            providerId = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/chat/completions",
            reasoningKind = "none",
            requiresKey = true,
            defaultModel = "",
            notes = "Docs site unreachable from sandbox; live 401 path probes 2026-10-28. No reasoning param sent.",
        ),
        ProviderPreset(
            providerId = "groq",
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1/chat/completions",
            reasoningKind = "none",
            requiresKey = true,
            defaultModel = "",
            notes = "Official API reference curl verified 2026-10-28. No reasoning param sent.",
        ),
        ProviderPreset(
            providerId = "mistral",
            label = "Mistral",
            baseUrl = "https://api.mistral.ai/v1/chat/completions",
            reasoningKind = "none",
            requiresKey = true,
            defaultModel = "",
            notes = "Official docs curl verified 2026-10-28. No reasoning param sent.",
        ),
        ProviderPreset(
            providerId = "xai",
            label = "xAI (Grok)",
            baseUrl = "https://api.x.ai/v1/chat/completions",
            reasoningKind = "effort",
            requiresKey = true,
            defaultModel = "",
            notes = "Official docs base_url verified 2026-10-28; reasoning_effort on grok reasoning models.",
        ),
        ProviderPreset(
            providerId = "ollama",
            label = "Ollama (local)",
            baseUrl = "http://localhost:11434/v1/chat/completions",
            reasoningKind = "none",
            requiresKey = false,
            defaultModel = "",
            notes = "Local-only OpenAI compatibility (ollama/docs/openai.md). No key; never a cloud call.",
        ),
    )

    fun byId(providerId: String): ProviderPreset? = ALL.firstOrNull { it.providerId == providerId }

    /** Provider ids that are preset-backed (custom endpoints are not). */
    fun isPreset(providerId: String): Boolean = byId(providerId) != null
}
