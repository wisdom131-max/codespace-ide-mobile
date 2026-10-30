package com.codespace.ide.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * AgentMemory — persistent key-value memory for the AI agent.
 * Survives across sessions. Stored as JSON in app internal storage.
 * Mirrors Superagent's memory.md capability.
 */
object AgentMemory {
    private fun memoryFile(context: Context): File {
        val dir = File(context.filesDir, "agent_memory")
        dir.mkdirs()
        return File(dir, "memory.json")
    }

    private fun readMap(context: Context): JSONObject {
        val file = memoryFile(context)
        if (!file.exists()) return JSONObject()
        return try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
    }

    // IG16/IG10 (2026-09-27): non-atomic writeText DELETED — memory now goes through
    // the shared SK01-family atomic write (one truncated write + empty-catch used to
    // mean TOTAL silent memory loss), and the store is size-capped.
    private const val MAX_STORE_BYTES = 256 * 1024

    private fun writeMap(map: JSONObject, context: Context): Boolean {
        val content = map.toString(2)
        if (content.length > MAX_STORE_BYTES) return false
        return com.codespace.ide.util.AtomicJson.write(memoryFile(context), content)
    }

    /**
     * M5: [sessionId] is the conversation writing this memory, and [scope] decides whether the
     * memory is private to that conversation or shared with all of them. Attribution is written
     * only AFTER the value write succeeds, so a failed save never leaves a memory whose owner is
     * recorded but whose value is missing.
     */
    fun save(
        key: String,
        value: String,
        context: Context,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
    ): String {
        val map = readMap(context)
        map.put(key, value)
        // IG10 (2026-09-27): over-cap or failed writes report HONESTLY instead of
        // claiming success — one huge save used to bloat every future read silently,
        // and a failed write still returned "Memory saved".
        if (!writeMap(map, context)) {
            return "Memory store is full (256 KB cap) or the write failed — delete some keys and retry."
        }
        val owner = MemoryScopeFilter.attributionFor(scope, sessionId)
        if (!MemoryAttribution.put(key, owner, context)) {
            return "Memory saved: '$key', but its session scope could not be recorded; " +
                "it will be treated as global."
        }
        return if (owner.isBlank()) "Memory saved: '$key' (shared with all sessions)"
        else "Memory saved: '$key' (this session only)"
    }

    fun readAll(
        context: Context,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
    ): String {
        val map = readMap(context)
        if (map.length() == 0) return "No memories saved yet."
        // M5: the scope is enforced at the read, not just in the UI. A key the scope hides is
        // never rendered, so it cannot reach the model's context.
        val attr = MemoryAttribution.read(context)
        val allKeys = map.keys().asSequence().toList()
        val visible = MemoryScopeFilter.visibleKeys(allKeys, attr, scope, sessionId)
        if (visible.isEmpty()) {
            return "No memories are visible in this scope (" + scope.label + "). " +
                allKeys.size + " saved memor" + (if (allKeys.size == 1) "y belongs" else "ies belong") +
                " to other sessions."
        }
        val sb = StringBuilder("Saved memories (${visible.size}):\n")
        for (key in visible) {
            sb.append("  $key: ${map.getString(key).take(200)}\n")
        }
        if (scope == MemoryScope.CURRENT_SESSION) {
            sb.append("Scope: this session only.")
        }
        return sb.toString().trim()
    }

    fun delete(
        key: String,
        context: Context,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
    ): String {
        val map = readMap(context)
        if (!map.has(key)) return "No memory found for key '$key'"
        // M5: a scope that cannot SEE a memory cannot delete it either.
        if (!MemoryScopeFilter.isVisible(key, MemoryAttribution.read(context), scope, sessionId)) {
            return "No memory found for key '$key' in this scope."
        }
        map.remove(key)
        // IG10: same honesty — a failed delete-write says so.
        if (!writeMap(map, context)) {
            return "Deleted '$key' in memory but the store write FAILED — it may reappear after restart."
        }
        MemoryAttribution.remove(key, context)
        return "Deleted memory: '$key'"
    }

    fun get(
        key: String,
        context: Context,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
    ): String? {
        if (!MemoryScopeFilter.isVisible(key, MemoryAttribution.read(context), scope, sessionId)) {
            return null
        }
        val v = readMap(context).optString(key)
        return v.ifBlank { null }
    }
}
