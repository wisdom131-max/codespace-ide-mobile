package com.codespace.ide.engine

/**
 * PROJECT NINE 9.4 (catalog 19) - pick examples that resemble the actual request.
 *
 * Examples were fixed, so a question about gradle got a tutorial about compose. Selection is by
 * word overlap with the query, and an example sharing no word at all is NOT used: a bad example
 * teaches the wrong shape, which is worse than teaching none.
 */
object FewShotSelector {

    data class Example(val id: String, val text: String, val tags: Set<String> = emptySet())

    fun select(query: String, examples: List<Example>, k: Int): List<Example> {
        if (k <= 0 || examples.isEmpty()) return emptyList()
        val wanted = terms(query)
        if (wanted.isEmpty()) return emptyList()
        return examples
            .map { ex -> ex to (terms(ex.text) + ex.tags.map { it.lowercase() }).count { wanted.contains(it) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    private fun terms(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 3 }.toSet()
}
