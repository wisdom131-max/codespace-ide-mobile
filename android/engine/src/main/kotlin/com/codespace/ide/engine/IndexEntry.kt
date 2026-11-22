package com.codespace.ide.engine

/**
 * PROJECT NINE 9.39 - one canonical form for what gets indexed.
 *
 * Indexing and searching were written separately, so a path stored one way and queried another way
 * simply never matched. One function that both sides call is what stops that class of bug.
 *
 * Truncation happens on a line boundary: half a line in the index produces a match on text that
 * does not exist, which is worse than not indexing that tail at all.
 */
object IndexEntry {

    data class Entry(val path: String, val body: String)

    fun of(path: String, source: String, maxChars: Int = 40_000): Entry {
        val clean = normalisePath(path)
        if (source.length <= maxChars) return Entry(clean, source)
        val lines = source.lines()
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.length + line.length + 1 > maxChars) break
            sb.append(line).append('\n')
        }
        return Entry(clean, sb.toString())
    }

    fun normalisePath(path: String): String {
        var p = path.trim().replace('\\', '/')
        while (p.startsWith("./")) p = p.removePrefix("./")
        return p
    }
}
