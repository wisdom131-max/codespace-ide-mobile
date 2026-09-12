package com.codespace.ide.ui.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R8-EXPORT-IMPORT (VS Code export/import chat parity).
 *
 * Export: writes the active session as a self-contained JSON file into the
 * public Downloads folder (MediaStore on API 29+, legacy File path on 26-28).
 * Import: parses a previously exported file back into session data.
 *
 * Format (versioned for forward-compat):
 * {
 *   "app": "codespace-ide-chat",
 *   "version": 1,
 *   "title": "...", "mode": "ASK", "exportedAt": 0,
 *   "messages": [ {"role":"user","text":"...","rating":"up"|absent} ]
 * }
 */
object ChatSessionIO {

    data class ImportedChat(
        val title: String,
        val mode: String,
        val entries: List<Triple<String, String, String?>>, // role, text, rating
    )

    /** @return the human-readable path/uri shown in the toast. */
    fun export(ctx: Context, title: String, mode: String,
               entries: List<Triple<String, String, String?>>, updatedAt: Long): String {
        val msgs = JSONArray()
        entries.forEach { (role, text, rating) ->
            val m = JSONObject().put("role", role).put("text", text)
            if (rating != null) m.put("rating", rating)
            msgs.put(m)
        }
        val doc = JSONObject()
            .put("app", "codespace-ide-chat")
            .put("version", 1)
            .put("title", title)
            .put("mode", mode)
            .put("exportedAt", System.currentTimeMillis())
            .put("updatedAt", updatedAt)
            .put("messages", msgs)
        val safeTitle = title.ifBlank { "chat" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val fileName = "codespace-chat-${safeTitle}-${updatedAt}.json"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Could not create the export file in Downloads.")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(doc.toString().toByteArray()) }
                ?: throw Exception("Downloads file stream unavailable.")
            "Download/$fileName"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(doc.toString())
            file.absolutePath
        }
    }

    /** @return parsed chat or null when the file isn't a valid export. */
    fun import(ctx: Context, uri: Uri): ImportedChat? {
        return try {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return null
            val doc = JSONObject(text)
            if (doc.optString("app") != "codespace-ide-chat") return null
            val arr = doc.optJSONArray("messages") ?: return null
            val entries = (0 until arr.length()).mapNotNull { i ->
                try {
                    val m = arr.getJSONObject(i)
                    Triple(m.getString("role"), m.getString("text"), m.optString("rating").ifBlank { null })
                } catch (_: Exception) { null }
            }
            if (entries.isEmpty()) return null
            ImportedChat(
                title = doc.optString("title").ifBlank { "Imported chat" },
                mode = doc.optString("mode").ifBlank { "ASK" },
                entries = entries,
            )
        } catch (_: Exception) { null }
    }
}
