package com.codespace.ide.lsp

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/**
 * P41 Phase B — Completion History Store
 *
 * Tracks MRU (most-recently-used) and usage frequency for accepted completions.
 * Backed by a JSON file in context.filesDir — per-device, synchronous-fast.
 *
 * Schema: { [label:String]: { count: Int, lastUsedEpochMs: Long, contextLanguage: String } }
 *
 * Loaded once per file-open into memory. Save is debounced to avoid disk thrashing
 * on every keystroke. Cap at 2000 entries (LRU-evict) to keep the file small.
 */

object CompletionHistoryStore {

    private const val FILE_NAME = "completion_history.json"
    private const val MAX_ENTRIES = 2000

    // In-memory map: label → entry
    @Volatile
    private var entries: Map<String, HistoryEntry> = emptyMap()

    @Volatile
    private var loaded = false

    @Volatile
    private var dirty = false

    private data class HistoryEntry(
        val count: Int,
        val lastUsedEpochMs: Long,
        val contextLanguage: String,
    )

    /** Load from disk — call once per file-open, not per-keystroke. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                entries = HashMap()
                loaded = true
                return
            }
            val json = JSONObject(file.readText())
            val map = HashMap<String, HistoryEntry>(json.length())
            val keys = json.keys()
            while (keys.hasNext()) {
                val label = keys.next()
                val obj = json.optJSONObject(label) ?: continue
                map[label] = HistoryEntry(
                    count = obj.optInt("count", 1),
                    lastUsedEpochMs = obj.optLong("lastUsedEpochMs", 0L),
                    contextLanguage = obj.optString("contextLanguage", ""),
                )
            }
            entries = map
        } catch (e: Exception) {
            entries = HashMap()
        }
        loaded = true
    }

    /** Record that a completion was accepted. Call from the accept handler. */
    @Synchronized
    fun recordAccepted(label: String, language: String, context: Context) {
        if (!loaded) load(context)

        val current = entries[label]
        val now = System.currentTimeMillis()
        val newEntry = HistoryEntry(
            count = (current?.count ?: 0) + 1,
            lastUsedEpochMs = now,
            contextLanguage = language,
        )

        val mutableMap = HashMap(entries)
        mutableMap[label] = newEntry

        // LRU-evict if over cap
        if (mutableMap.size > MAX_ENTRIES) {
            val sorted = mutableMap.entries.sortedBy { it.value.lastUsedEpochMs }
            val toRemove = sorted.take(mutableMap.size - MAX_ENTRIES)
            for ((key) in toRemove) {
                mutableMap.remove(key)
            }
        }

        entries = mutableMap
        dirty = true
        save(context)
    }

    /**
     * IC12 (P4c): MRU map SCOPED to the active language. The old map was global
     * by label, so accepting "size" in Kotlin boosted it while typing Python —
     * the last-recorded language silently won every cross-language collision.
     * Entries recorded for OTHER languages no longer contribute boosts;
     * legacy entries with a blank language stay visible everywhere.
     */
    fun mruMap(language: String? = null): Map<String, Long> {
        return entries
            .filter { language == null || it.value.contextLanguage.isBlank() || it.value.contextLanguage == language }
            .mapValues { it.value.lastUsedEpochMs }
    }

    /** Usage frequency map (label → count), scoped like mruMap (IC12). */
    fun usageMap(language: String? = null): Map<String, Int> {
        return entries
            .filter { language == null || it.value.contextLanguage.isBlank() || it.value.contextLanguage == language }
            .mapValues { it.value.count }
    }

    /** Persist to disk. Called from recordAccepted (debounced naturally — only on accept). */
    @Synchronized
    fun save(context: Context) {
        if (!dirty) return
        try {
            val json = JSONObject()
            for ((label, entry) in entries) {
                val obj = JSONObject()
                obj.put("count", entry.count)
                obj.put("lastUsedEpochMs", entry.lastUsedEpochMs)
                obj.put("contextLanguage", entry.contextLanguage)
                json.put(label, obj)
            }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(json.toString())
            dirty = false
        } catch (e: Exception) {
            // IC12/S01 (P4c): best-effort, but no longer INVISIBLE — the Output tab
            // shows why the MRU history stopped persisting.
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[CompletionHistory] save FAILED (history stays in-memory for this session): " +
                (e.message ?: e.javaClass.simpleName), "lsp")
        }
    }

    /** Clear all history (for settings/debug). */
    @Synchronized
    fun clear(context: Context) {
        entries = HashMap()
        dirty = true
        save(context)
    }

    /** Get total entry count (for settings display). */
    fun entryCount(): Int = entries.size
}
