package com.codespace.ide.editor

/**
 * Find inside a file that is open as a READ-ONLY PREVIEW (owner order 2026-11-08, large-file viewer).
 *
 * The editor's Find works on the text field, which for a preview holds ONE PAGE. Searching a
 * 13,000-line file therefore searched 5,280 lines of it and silently missed the rest. This searches
 * the whole file and reports the ABSOLUTE line of every hit, so the reader can be taken to the page
 * that holds it (PreviewJump).
 *
 * Capped on purpose: 500 hits is far more than anyone reads, and an uncapped list of 100,000 matches
 * is how a search box becomes an ANR. The cap is reported, never silent.
 */
internal object PreviewSearch {

    const val MAX_HITS = 500

    data class Hit(val line: Int, val text: String)

    data class Result(
        val hits: List<Hit>,
        val totalMatches: Int,
        val capped: Boolean,
        val query: String,
    ) {
        val isEmpty: Boolean get() = hits.isEmpty()
    }

    fun find(content: String, query: String, maxHits: Int = MAX_HITS): Result {
        if (query.isEmpty()) return Result(emptyList(), 0, false, query)
        val hits = ArrayList<Hit>(minOf(maxHits, 64))
        var total = 0
        var line = 0
        // Case-insensitive: a search box that misses "Const" for "const" is a worse default on a
        // phone than the mild surprise of a case-insensitive hit.
        val needle = query.lowercase()
        for (raw in content.lineSequence()) {
            line++
            if (raw.lowercase().contains(needle)) {
                total++
                if (hits.size < maxHits) hits.add(Hit(line, raw.trim().take(160)))
            }
        }
        return Result(hits, total, total > hits.size, query)
    }

    /** The one-line outcome for the banner/notification: never silent about a cap. */
    fun summary(result: Result): String = when {
        result.query.isEmpty() -> ""
        result.totalMatches == 0 -> "No matches for \"${result.query}\" in this file."
        result.capped -> "${result.totalMatches} matches; showing the first ${result.hits.size}, starting " +
            "at line ${result.hits.first().line}."
        result.totalMatches == 1 -> "1 match for \"${result.query}\" at line ${result.hits.first().line}."
        else -> "${result.totalMatches} matches for \"${result.query}\", first at line " +
            "${result.hits.first().line}."
    }
}
