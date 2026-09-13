package com.codespace.ide.chat

import android.content.Context
import org.json.JSONArray

/**
 * R10-B — per-project chat input history (VS Code chat input history analog).
 *
 * Every SENT input (plain messages and slash commands alike) is pushed here.
 * The panel's history button walks backwards through the list, newest first,
 * and wraps back to the live draft. Per-project storage in plain prefs
 * (JSON array, MAX 100 entries, consecutive duplicates collapsed).
 */
object ChatInputHistory {

    private const val PREFS = "chat_input_history"
    private const val MAX_ENTRIES = 100

    private fun keyFor(projectRoot: String?): String =
        "hist_" + (projectRoot ?: "global").hashCode()

    fun push(context: Context, projectRoot: String?, text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        try {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cur = list(context, projectRoot).toMutableList()
            if (cur.firstOrNull() == t) return // consecutive duplicate
            cur.add(0, t)
            val arr = JSONArray()
            cur.take(MAX_ENTRIES).forEach { arr.put(it) }
            p.edit().putString(keyFor(projectRoot), arr.toString()).apply()
        } catch (_: Exception) { }
    }

    /** Newest-first. Empty when nothing sent yet in this project. */
    fun list(context: Context, projectRoot: String?): List<String> = try {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(keyFor(projectRoot), null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }
}
