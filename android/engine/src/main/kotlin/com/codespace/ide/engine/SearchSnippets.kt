package com.codespace.ide.engine

/**
 * PROJECT NINE 9.35 - show the line that matched, not the top of the file.
 *
 * A result that names a path and nothing else makes the model open the file to find out why it was
 * a result, which costs the round the search was meant to save. A window around the first matching
 * line answers that immediately.
 *
 * The line number is reported so the model can go straight there, and the window is bounded so one
 * enormous file cannot dominate the result list.
 */
object SearchSnippets {

    data class Snippet(val startLine: Int, val text: String)

    fun around(source: String, terms: List<String>, context: Int = 2): Snippet? {
        val wanted = terms.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return null
        val lines = source.lines()
        val hit = lines.indexOfFirst { line -> wanted.any { line.contains(it, ignoreCase = true) } }
        if (hit < 0) return null
        val from = maxOf(0, hit - maxOf(0, context))
        val to = minOf(lines.size - 1, hit + maxOf(0, context))
        return Snippet(from + 1, lines.subList(from, to + 1).joinToString("\n"))
    }
}
