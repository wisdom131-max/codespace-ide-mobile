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

    private fun writeMap(map: JSONObject, context: Context) {
        memoryFile(context).writeText(map.toString(2))
    }

    fun save(key: String, value: String, context: Context): String {
        val map = readMap(context)
        map.put(key, value)
        writeMap(map, context)
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
        writeMap(map, context)
        return "Deleted memory: '$key'"
    }

    fun get(key: String, context: Context): String? {
        val v = readMap(context).optString(key)
        return v.ifBlank { null }
    }
}
