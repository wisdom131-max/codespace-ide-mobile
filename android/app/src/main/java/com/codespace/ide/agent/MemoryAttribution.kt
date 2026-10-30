package com.codespace.ide.agent

import android.content.Context
import java.io.File

/**
 * M5 — who wrote each memory, stored beside the values so `memory.json` keeps its old shape and
 * an older build can still read every value.
 *
 * Format: one `key<TAB>sessionId` line per memory. Blank sessionId = global. Same escaping as the
 * voice transcripts: plain text so it is testable without an Android framework, and an unparsable
 * line is skipped rather than silently repaired.
 */
object MemoryAttribution {

    private fun fileFor(context: Context): File {
        val dir = File(context.filesDir, "agent_memory")
        dir.mkdirs()
        return File(dir, "attribution.tsv")
    }

    fun read(context: Context): Map<String, String> = readFile(fileFor(context))

    fun readFile(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return try {
            val out = LinkedHashMap<String, String>()
            file.forEachLine { raw ->
                val parsed = parseLine(raw)
                if (parsed != null) out[parsed.first] = parsed.second
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /** Records (or replaces) one key's owner. Blank sessionId = global. */
    fun put(key: String, sessionId: String, context: Context): Boolean {
        val file = fileFor(context)
        val map = LinkedHashMap(readFile(file))
        map[key] = sessionId
        return writeMap(file, map)
    }

    fun remove(key: String, context: Context): Boolean {
        val file = fileFor(context)
        val map = LinkedHashMap(readFile(file))
        if (!map.containsKey(key)) return true
        map.remove(key)
        return writeMap(file, map)
    }

    private fun writeMap(file: File, map: Map<String, String>): Boolean = try {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val sb = StringBuilder()
        for ((k, v) in map) sb.append(escape(k)).append('\t').append(escape(v)).append('\n')
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        true
    } catch (_: Throwable) {
        false
    }

    fun parseLine(raw: String): Pair<String, String>? {
        if (raw.isBlank()) return null
        val tab = raw.indexOf('\t')
        val key = if (tab < 0) unescape(raw) else unescape(raw.substring(0, tab))
        if (key.isBlank()) return null
        val owner = if (tab < 0) "" else unescape(raw.substring(tab + 1))
        return key to owner
    }

    fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    fun unescape(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(ch); i++ }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
