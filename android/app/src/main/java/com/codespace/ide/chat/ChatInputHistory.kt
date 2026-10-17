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
 *
 * BATCH4-P4 (S01 fix, SK01 model, ChatPlanStore pattern): writes go through
 * the commit()-based ChatPrefsBackend and push() is TYPED — it returns true
 * only when the entry actually reached storage; a failed save leaves the disk
 * untouched and says so in the chat output channel instead of pretending
 * success. A corrupt payload is QUARANTINED (content-deduped copy parked
 * under a .corrupt-<ts> sibling) and the original is NEVER deleted or
 * overwritten; the list reads as honest-empty.
 */
object ChatInputHistory {

    private const val PREFS = "chat_input_history"
    private const val MAX_ENTRIES = 100

    // BATCH4-P4: JVM-test seam — when set, ALL reads/writes go through it.
    @Volatile internal var testBackend: ChatPrefsBackend? = null

    private fun keyFor(projectRoot: String?): String =
        "hist_" + (projectRoot ?: "global").hashCode()

    internal fun backend(context: Context?): ChatPrefsBackend? {
        testBackend?.let { return it }
        val ctx = context?.applicationContext ?: return null
        return AndroidChatPrefsBackend(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    /**
     * Typed push: true = the history on storage now contains [text] (or
     * already did — a consecutive duplicate is a no-op success, nothing to
     * write). false = the save failed; the disk keeps its previous contents.
     */
    fun push(context: Context?, projectRoot: String?, text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return true // nothing to persist; state is already consistent
        val b = backend(context) ?: return false
        return try {
            val cur = list(context, projectRoot).toMutableList()
            if (cur.firstOrNull() == t) return true // consecutive duplicate: no write needed
            cur.add(0, t)
            val arr = JSONArray()
            cur.take(MAX_ENTRIES).forEach { arr.put(it) }
            val ok = b.putString(keyFor(projectRoot), arr.toString())
            if (!ok) {
                com.codespace.ide.diagnostics.AppOutputLog.log(
                    "[CHAT-HISTORY] save FAILED - the entry was NOT persisted; the on-disk history is unchanged (BATCH4-P4)",
                    "chat",
                )
            }
            ok
        } catch (e: Exception) {
            com.codespace.ide.diagnostics.AppOutputLog.log("[CHAT-HISTORY] save FAILED - " + e.message, "chat")
            false
        }
    }

    /** Newest-first. Empty when nothing sent yet in this project. */
    fun list(context: Context?, projectRoot: String?): List<String> {
        val b = backend(context) ?: return emptyList()
        val key = keyFor(projectRoot)
        val raw = b.getString(key) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            // BATCH4-P4: never silently empty — quarantine the payload, read honest-empty.
            quarantineCorrupt(b, key, raw)
            emptyList()
        }
    }

    /**
     * BATCH4-P4 (SK01 model): the corrupt payload is NEVER deleted and NEVER
     * overwritten — the original key keeps its bytes untouched, and a
     * content-deduped copy parks under a ".corrupt-<ts>" sibling key so
     * repeated launches do not accumulate duplicates.
     */
    private fun quarantineCorrupt(b: ChatPrefsBackend, key: String, raw: String) {
        val prefix = "$key.corrupt-"
        try {
            val existing = b.allWithPrefix(prefix)
            if (!existing.containsValue(raw)) {
                b.putString(prefix + System.currentTimeMillis(), raw)
            }
        } catch (_: Exception) { /* quarantine write failure must not crash the read */ }
        com.codespace.ide.diagnostics.AppOutputLog.log(
            "[CHAT-HISTORY] the stored input history for this project is unreadable - the payload is PRESERVED under a quarantine key and the original is left untouched (nothing deleted); history starts empty",
            "chat",
        )
    }
}
