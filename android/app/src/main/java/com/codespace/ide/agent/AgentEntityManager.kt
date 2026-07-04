package com.codespace.ide.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * AgentEntityManager — local SQLite-backed data CRUD for the AI agent.
 * Mirrors Superagent's entity capability.
 * Stores JSON records as files in internal storage (lightweight, no SQLite overhead on 3GB device).
 * Each entity type is a directory; each record is a JSON file.
 */
object AgentEntityManager {

    private fun entityDir(entity: String, context: Context): File {
        val dir = File(context.filesDir, "agent_data/$entity")
        dir.mkdirs()
        return dir
    }

    fun create(entity: String, data: String, context: Context): String {
        val dir = entityDir(entity, context)
        val json = JSONObject(data)
        val id = System.currentTimeMillis().toString()
        json.put("id", id)
        json.put("created_date", System.currentTimeMillis())
        val file = File(dir, "$id.json")
        file.writeText(json.toString(2))
        return "Created $entity record: $id\n${json.toString(2).take(2000)}"
    }

    fun read(entity: String, filter: String?, context: Context): String {
        val dir = entityDir(entity, context)
        val files = dir.listFiles()?.sortedBy { it.name } ?: return "No $entity records found."
        if (files.isEmpty()) return "No $entity records found."

        val filterJson = filter?.let { JSONObject(it) }
        val results = mutableListOf<JSONObject>()
        for (file in files) {
            try {
                val record = JSONObject(file.readText())
                var matches = true
                if (filterJson != null) {
                    for (key in filterJson.keys()) {
                        if (record.optString(key) != filterJson.getString(key)) {
                            matches = false; break
                        }
                    }
                }
                if (matches) results.add(record)
            } catch (_: Exception) {}
        }
        return if (results.isEmpty()) "No matching $entity records."
               else "Found ${results.size} ${entity}(s):\n${results.joinToString("\n") { it.toString(2).take(500) }.take(6000)}"
    }

    fun update(entity: String, filter: String, data: String, context: Context): String {
        val dir = entityDir(entity, context)
        val files = dir.listFiles() ?: return "No $entity records found."
        val filterJson = JSONObject(filter)
        val updateJson = JSONObject(data)
        var updated = 0
        for (file in files) {
            try {
                val record = JSONObject(file.readText())
                var matches = true
                for (key in filterJson.keys()) {
                    if (record.optString(key) != filterJson.getString(key)) { matches = false; break }
                }
                if (matches) {
                    for (key in updateJson.keys()) record.put(key, updateJson.get(key))
                    record.put("updated_date", System.currentTimeMillis())
                    file.writeText(record.toString(2))
                    updated++
                }
            } catch (_: Exception) {}
        }
        return "Updated $updated $entity record(s)."
    }

    fun delete(entity: String, filter: String, context: Context): String {
        val dir = entityDir(entity, context)
        val files = dir.listFiles() ?: return "No $entity records found."
        val filterJson = JSONObject(filter)
        var deleted = 0
        for (file in files) {
            try {
                val record = JSONObject(file.readText())
                var matches = true
                for (key in filterJson.keys()) {
                    if (record.optString(key) != filterJson.getString(key)) { matches = false; break }
                }
                if (matches) { file.delete(); deleted++ }
            } catch (_: Exception) {}
        }
        return "Deleted $deleted $entity record(s)."
    }
}
