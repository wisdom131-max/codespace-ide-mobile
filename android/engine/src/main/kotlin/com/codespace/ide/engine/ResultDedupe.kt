package com.codespace.ide.engine

/**
 * PROJECT NINE 9.33 - one result per file.
 *
 * Two chunks of the same file, or a file matched by both lists, arrives as several hits on one
 * path. Showing the same file four times wastes the answer's limited space and hides the fourth
 * distinct file that never got shown.
 *
 * The best-scoring hit per path is kept, and paths stay in the order they were first seen, so
 * ranking survives the collapse.
 */
object ResultDedupe {

    data class Hit(val path: String, val score: Double, val id: String)

    fun byPath(hits: List<Hit>): List<Hit> {
        val best = LinkedHashMap<String, Hit>()
        for (h in hits) {
            val current = best[h.path]
            if (current == null || h.score > current.score) best[h.path] = h
        }
        return best.values.toList()
    }
}
