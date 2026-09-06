package com.codespace.ide.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * CROSS-ROUTING FIX (2026-09-06): single, persisted source of truth for which
 * chat model (and therefore which provider) answers a chat message.
 *
 * Bug this fixes: dispatch in chat() is keyed on the model string's provider
 * prefix ("provider:model"), but BOTH chat panels (CopilotChatPanelOverlay +
 * CopilotChatPanelInline) kept their own local selectedModel that
 *   1. defaulted to the FIRST registry provider's model (not the provider
 *      the user activated in Settings), and
 *   2. snapped to live.firstOrNull() (first provider again) whenever the
 *      saved selection retired,
 * while the Settings screen's "active provider" switch wrote a tokenStore
 * "active" key that NOTHING ever read for dispatch.
 * Result: messages were intermittently routed to a DIFFERENT provider's
 * endpoint than the one shown active in the UI (on-device: openrouter active
 * -> first send hit Gemini, gemini active -> first send hit OpenAI).
 *
 * Now: Settings' provider switch, both chat panels' model pickers, and every
 * send all read/write this one persisted value.
 */
object ChatModelSelection {
    private const val PREFS = "chat_model_selection"
    private const val KEY_SELECTED = "selected_model"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Current persisted "provider:model" selection, or null if never set. */
    fun get(context: Context): String? =
        try {
            prefs(context).getString(KEY_SELECTED, null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

    /** Persist the selection. */
    fun set(context: Context, model: String) {
        try {
            prefs(context).edit().putString(KEY_SELECTED, model).apply()
        } catch (_: Exception) { }
    }
}
