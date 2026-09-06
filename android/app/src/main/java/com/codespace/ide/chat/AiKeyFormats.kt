package com.codespace.ide.chat

/**
 * AI-KEY FORMAT RULES (Settings/credential redesign, phases 1-2, 2026-09-06)
 *
 * Per-provider API-key shape rules for (a) malformed-token rejection on save and
 * (b) paste-to-route: a key pasted into provider X's field whose format matches a
 * DIFFERENT provider gets an "apply to that provider?" prompt instead of a silent
 * wrong-slot write (the classic cause of stored-but-broken keys).
 *
 * Deliberately LOOSE (prefix + minimum length only): vendor key formats evolve
 * and over-strict validation would lock users out of VALID keys — worse than the
 * old no-validation behavior. These rules catch obvious mistakes, not forgeries.
 */
object AiKeyFormats {

    /** Is this key well-formed for the given provider id? */
    fun isValid(providerId: String, key: String): Boolean {
        val k = key.trim()
        return when (providerId) {
            "anthropic"  -> k.startsWith("sk-ant-") && k.length >= 30
            "openrouter" -> k.startsWith("sk-or-") && k.length >= 40
            "openai"     -> k.startsWith("sk-") && k.length >= 40
            "gemini"     -> k.startsWith("AIza") && k.length >= 35
            "deepseek"   -> k.startsWith("sk-") && k.length >= 30
            // Unknown / future providers: non-trivial length only.
            else -> k.length >= 8
        }
    }

    /**
     * Which provider(s) does this key's FORMAT look like? Ordered by prefix
     * specificity. Bare "sk-" is ambiguous (OpenAI legacy AND DeepSeek) — both are
     * returned; callers prompt instead of guessing.
     */
    fun detect(key: String): List<String> {
        val k = key.trim()
        val hits = mutableListOf<String>()
        if (k.startsWith("sk-ant-")) { hits.add("anthropic"); return hits }
        if (k.startsWith("sk-or-")) { hits.add("openrouter"); return hits }
        if (k.startsWith("sk-proj-")) { hits.add("openai"); return hits }
        if (k.startsWith("AIza")) { hits.add("gemini"); return hits }
        if (k.startsWith("sk-")) { hits.add("deepseek"); hits.add("openai") }
        return hits
    }
}
