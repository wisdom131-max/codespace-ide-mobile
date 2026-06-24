package com.codespace.ide.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TextExpansion(val trigger: String, val expansion: String)

object TextExpansionStore {
    private const val PREFS = "codespace_terminal_prefs"
    private const val KEY   = "text_expansions_json"
    private var cache: List<TextExpansion>? = null

    fun load(ctx: Context): List<TextExpansion> {
        cache?.let { return it }
        val list = mutableListOf<TextExpansion>()
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.optString("trigger", "")
                val e = o.optString("expansion", "")
                if (t.isNotEmpty()) list.add(TextExpansion(t, e))
            }
        } catch (_: Exception) {}
        return list.also { cache = it }
    }

    fun save(ctx: Context, list: List<TextExpansion>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().apply { put("trigger", it.trigger); put("expansion", it.expansion) }) }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
            cache = null
        } catch (_: Exception) {}
    }

    fun findExpansion(ctx: Context, trigger: String): String? =
        load(ctx).firstOrNull { it.trigger == trigger }?.expansion
}
