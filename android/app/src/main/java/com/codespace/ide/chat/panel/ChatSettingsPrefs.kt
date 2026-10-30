package com.codespace.ide.chat.panel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * WP6/WP7: rebuilt-chat settings, persisted in plain SharedPreferences
 * ("chat_settings" - NOTHING here is a credential; keys stay in the
 * EncryptedSharedPreferences store). Lazy init - nothing runs at Application
 * start (owner rule). Holds:
 *  - failover opt-in (WP6; automatic key switching is OFF by default)
 *  - last applied provider/model selection (WP7)
 *  - per provider:model context-window overrides feeding ModelCatalog (WP7)
 * resetAll() clears every chat_settings row (the "reset chat settings" row).
 */

/** Pure: selection <-> JSON (JVM-testable, no Context). */
fun encodeSelection(sel: PanelSelection): String = JSONObject()
    .put("providerId", sel.providerId)
    .put("keySlot", sel.keySlot)
    .put("modelId", sel.modelId)
    .put("baseUrl", sel.baseUrl)
    .toString()

/** Pure: null on unreadable/incomplete JSON - caller falls back to defaults. */
fun decodeSelection(json: String): PanelSelection? {
    return try {
        val o = JSONObject(json)
        val pid = o.optString("providerId")
        if (pid.isBlank()) null
        else PanelSelection(
            providerId = pid,
            keySlot = o.optString("keySlot"),
            modelId = o.optString("modelId"),
            baseUrl = o.optString("baseUrl").ifBlank { PanelSelection().baseUrl },
        )
    } catch (_: Exception) {
        null
    }
}

/** Pure: override map key - providerId + lowercase model id (matching is case-insensitive). */
fun overrideKey(providerId: String, modelId: String): String = providerId + ":" + modelId.lowercase()

/** Pure: overrides <-> JSON array of {k, ctx} rows. */
fun encodeOverrides(map: Map<String, Int>): String {
    val arr = JSONArray()
    for ((k, v) in map) arr.put(JSONObject().put("k", k).put("ctx", v))
    return arr.toString()
}

/** Pure: unreadable JSON -> empty map (all bundled capabilities stand). */
fun decodeOverrides(json: String): Map<String, Int> {
    val out = HashMap<String, Int>()
    return try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k")
            val ctx = o.optInt("ctx", -1)
            if (k.isNotBlank() && ctx > 0) out[k] = ctx
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}

object ChatSettingsPrefs {
    private const val PREFS = "chat_settings"
    private const val KEY_FAILOVER = "failover_opt_in"
    private const val KEY_SELECTION = "last_selection"
    private const val KEY_OVERRIDES = "context_overrides"

    /**
     * M3 "Show View by Default": whether the chat panel is open when a project is opened.
     * A preference about the NEXT open, not a live visibility switch - the header's own
     * toggle still controls the current window.
     */
    private const val KEY_SHOW_BY_DEFAULT = "show_chat_by_default"

    /** M5: "current_session" | "all_sessions". Read at every memory access, not cached. */
    private const val KEY_MEMORY_SCOPE = "memory_scope"

    @Volatile private var prefs: SharedPreferences? = null

    fun ensure(ctx: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun failoverOptIn(): Boolean = prefs?.getBoolean(KEY_FAILOVER, false) ?: false

    fun memoryScopeId(): String =
        prefs?.getString(KEY_MEMORY_SCOPE, com.codespace.ide.agent.MemoryScope.DEFAULT.id)
            ?: com.codespace.ide.agent.MemoryScope.DEFAULT.id

    fun setMemoryScopeId(id: String) {
        prefs?.edit()?.putString(KEY_MEMORY_SCOPE, id)?.apply()
    }

    fun showChatByDefault(): Boolean = prefs?.getBoolean(KEY_SHOW_BY_DEFAULT, false) ?: false

    fun setShowChatByDefault(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SHOW_BY_DEFAULT, enabled)?.apply()
    }

    fun setFailoverOptIn(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_FAILOVER, enabled)?.apply()
    }

    /** WP7: last applied selection, or null (panel falls back to defaults). */
    fun selection(): PanelSelection? =
        prefs?.getString(KEY_SELECTION, null)?.let { decodeSelection(it) }

    fun setSelection(sel: PanelSelection) {
        prefs?.edit()?.putString(KEY_SELECTION, encodeSelection(sel))?.apply()
    }

    /** WP7: user context-window override (tokens) for a provider:model, or null. */
    fun contextOverride(providerId: String, modelId: String): Int? {
        val m = overrides()
        return m[overrideKey(providerId, modelId)]
    }

    fun setContextOverride(providerId: String, modelId: String, tokens: Int?) {
        val m = HashMap(overrides())
        val k = overrideKey(providerId, modelId)
        if (tokens != null && tokens > 0) m[k] = tokens else m.remove(k)
        prefs?.edit()?.putString(KEY_OVERRIDES, encodeOverrides(m))?.apply()
    }

    /** The "reset chat settings" row: failover, selection, overrides. Keys are NOT touched. */
    fun resetAll() {
        prefs?.edit()?.clear()?.apply()
    }

    private fun overrides(): Map<String, Int> =
        prefs?.getString(KEY_OVERRIDES, null)?.let { decodeOverrides(it) } ?: emptyMap()
}
