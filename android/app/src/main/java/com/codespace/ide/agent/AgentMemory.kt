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

    fun save(key: String, value: String, context: Context): String {
        val map = readMap(context)
        map.put(key, value)
        // IG10 (2026-09-27): over-cap or failed writes report HONESTLY instead of
        // claiming success — one huge save used to bloat every future read silently,
        // and a failed write still returned "Memory saved".
        if (!writeMap(map, context)) {
            return "Memory store is full (256 KB cap) or the write failed — delete some keys and retry."
        }
        return "Memory saved: '$key'"
    }

    fun readAll(context: Context): String {
        val map = readMap(context)
        if (map.length() == 0) return "No memories saved yet."
        val sb = StringBuilder("Saved memories (${map.length()}):\n")
        for (key in map.keys()) {
            sb.append("  $key: ${map.getString(key).take(200)}\n")
        }
        return sb.toString().trim()
    }

    fun delete(key: String, context: Context): String {
        val map = readMap(context)
        if (!map.has(key)) return "No memory found for key '$key'"
        map.remove(key)
        // IG10: same honesty — a failed delete-write says so.
        if (!writeMap(map, context)) {
            return "Deleted '$key' in memory but the store write FAILED — it may reappear after restart."
        }
        return "Deleted memory: '$key'"
    }

    fun get(key: String, context: Context): String? {
        val v = readMap(context).optString(key)
        return v.ifBlank { null }
    }
}
