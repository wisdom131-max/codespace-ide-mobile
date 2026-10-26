package com.codespace.ide.chat

import android.content.Context

/**
 * 1a (advisor 2026-10-26): explicit, BOUNDED thinking budget for Gemini.
 *
 * READ finding: our Gemini request sent NO thinkingConfig at all, and the
 * vendor default is DYNAMIC thinking (auto-scaling reasoning, up to 8,192
 * tokens — ai.google.dev/gemini-api/docs/thinking). On full prompts the model
 * thinks longest before the first visible token: that was the owner's
 * "Gemini is slow on full prompts".
 *
 * The setting is user-visible in Settings and shared by the native Gemini
 * provider path (custom OpenAI-compatible endpoints do not use this —
 * thinkingConfig is a Gemini-native field):
 *   OFF     = 0    -> thinking disabled where the model allows it (fastest)
 *   BOUNDED = 1024 -> DEFAULT: cap thinking at 1,024 tokens (fast first token,
 *                     still allows short reasoning)
 *   DYNAMIC = -1   -> vendor default (the old behavior, unbounded reasoning)
 *
 * Cached in memory (read per request without a Context), initialized at app
 * start next to the other chat settings; the Settings row updates both
 * atomically. Default: BOUNDED.
 */
object GeminiThinkingBudgetSetting {
    private const val PREFS = "copilot_chat"
    private const val KEY = "gemini_thinking_budget"

    const val OFF = 0
    const val BOUNDED = 1024
    const val DYNAMIC = -1

    @Volatile
    private var budget: Int = BOUNDED

    /** Read by chat() on every Gemini send. Default BOUNDED until init. */
    fun current(): Int = budget

    fun init(context: Context) {
        budget = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, BOUNDED)
    }

    fun set(context: Context, value: Int) {
        budget = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, value).apply()
    }

    /** Plain-words label for the Settings row. */
    fun label(value: Int): String = when (value) {
        OFF -> "Off (no thinking)"
        DYNAMIC -> "Dynamic (vendor default)"
        else -> "Bounded ($budget tokens)"
    }

    /** JVM tests only. */
    fun setForTest(value: Int) {
        budget = value
    }
}
