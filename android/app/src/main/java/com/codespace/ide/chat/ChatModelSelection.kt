package com.codespace.ide.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * CROSS-ROUTING FIX (2026-09-06): single, persisted source of truth for which
 * chat model (and therefore which provider) answers a chat message.
 * Settings' provider switch, both chat panels' model pickers, and every send
 * all read/write this one persisted value.
 *
 * ROUND 5 (2026-09-12) — VS Code model-picker parity on top:
 *  - AUTO sentinel: picker default. At send time it resolves to the user's
 *    active provider's current default model (Settings' provider switch), or
 *    the first available provider. Never dispatched literally.
 *  - PER-MODE memory: each chat mode (ASK/AGENT/PLAN) remembers its own model
 *    ("selected_model_<MODE>"); falls back to the global selection.
 *  - PINNED favorites: starred models sort to the top of the picker.
 */
object ChatModelSelection {
    private const val PREFS = "chat_model_selection"
    private const val KEY_SELECTED = "selected_model"
    private const val KEY_SELECTED_PREFIX = "selected_model_"
    private const val KEY_PINNED = "pinned_models"

    /** Sentinel picker entry meaning "let the app pick the provider default". */
    const val AUTO_MODEL = "auto"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Current persisted "provider:model" selection (or the AUTO sentinel), or null if never set. */
    fun get(context: Context): String? =
        try {
            prefs(context).getString(KEY_SELECTED, null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

    /** Persist the global selection (also used for the AUTO sentinel itself). */
    fun set(context: Context, model: String) {
        try {
            prefs(context).edit().putString(KEY_SELECTED, model).apply()
        } catch (_: Exception) { }
    }

    /** Per-mode selection (modeName: "ASK" / "AGENT" / "PLAN"), or null -> use global. */
    fun getForMode(context: Context, modeName: String): String? =
        try {
            prefs(context).getString(KEY_SELECTED_PREFIX + modeName.uppercase(), null)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

    /** Persist this mode's own model memory. */
    fun setForMode(context: Context, modeName: String, model: String) {
        try {
            prefs(context).edit()
                .putString(KEY_SELECTED_PREFIX + modeName.uppercase(), model).apply()
        } catch (_: Exception) { }
    }

    /** Pinned (starred) models, in pin order. */
    fun getPinned(context: Context): List<String> =
        try {
            prefs(context).getString(KEY_PINNED, "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        } catch (_: Exception) { emptyList() }

    /** Pin if not pinned, unpin if pinned. Returns the new pinned list. */
    fun togglePin(context: Context, model: String): List<String> {
        val cur = getPinned(context).toMutableList()
        if (model in cur) cur.remove(model) else cur.add(model)
        try {
            prefs(context).edit().putString(KEY_PINNED, cur.joinToString(",")).apply()
        } catch (_: Exception) { }
        return cur
    }

    /**
     * ROUND-5 AUTO: resolve the AUTO sentinel to a concrete "provider:model".
     * Non-Auto selections pass through untouched. Resolution order mirrors
     * the Settings provider switch: active provider's default, then the first
     * available provider's default.
     */
    fun resolveAuto(context: Context, model: String, tokenStore: com.codespace.ide.data.SecureTokenStore?): String {
        if (model != AUTO_MODEL) return model
        return try {
            val activeId = tokenStore?.aiKey("active")?.lowercase()
            val active = activeId?.let { ChatProviderRegistry.byId(it) }
            if (active != null && active.isAvailable(tokenStore)) {
                active.id + ":" + active.defaultModel
            } else {
                ChatProviderRegistry.available(tokenStore).firstOrNull()
                    ?.let { it.id + ":" + it.defaultModel } ?: model
            }
        } catch (_: Exception) { model }
    }
}
