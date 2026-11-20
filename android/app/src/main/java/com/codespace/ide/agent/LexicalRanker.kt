package com.codespace.ide.agent

/**
 * P3 (2026-11-20) — lexical scoring for retrieval.
 *
 * Complements the existing semantic/word-overlap scoring rather than replacing it. The point is
 * that an exact identifier, filename or error fragment should beat a vague topical match, and that
 * the reason for a ranking should be explainable rather than a single opaque number.
 *
 * Pure and deterministic: no IO, no model, no network. The caller keeps its own store and scope
 * filters; this only ranks the candidates it is given.
 */
object LexicalRanker {

    /** Weight of an exact phrase hit versus a token hit. */
    private const val PHRASE_BONUS = 3.0
    private const val IDENTIFIER_BONUS = 2.0
    private const val PATH_BONUS = 2.5

    data class Candidate(val id: String, val text: String, val path: String? = null)

    data class Scored(
        val id: String,
        val score: Double,
        /** Human-readable reasons, in the order they contributed. */
        val reasons: List<String>,
    )

    /** Split on identifiers, paths and punctuation without losing the original terms. */
    fun tokens(s: String): List<String> {
        val out = LinkedHashSet<String>()
        s.split(Regex("[^A-Za-z0-9_]+")).forEach { raw ->
            if (raw.isEmpty()) return@forEach
            out.add(raw.lowercase())
            // camelCase and snake_case parts, so `runInTerminal` matches `terminal`.
            raw.split(Regex("_")).forEach { part ->
                if (part.isEmpty()) return@forEach
                out.add(part.lowercase())
                Regex("[A-Z]?[a-z0-9]+|[A-Z]+").findAll(part).forEach { out.add(it.value.lowercase()) }
            }
        }
        return out.toList()
    }

    private fun normalised(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /**
     * Score one candidate against a query. Exact phrase and exact identifier hits dominate;
     * token overlap only breaks ties.
     */
    fun score(query: String, c: Candidate): Scored {
        val q = normalised(query)
        val reasons = ArrayList<String>()
        var total = 0.0

        // A one- or two-character query matches almost every document by substring, which is the
        // false-positive bias this ranker exists to avoid. Short queries score on tokens only.
        if (q.length >= 3 && normalised(c.text).contains(q)) {
            total += PHRASE_BONUS
            reasons.add("exact phrase")
        }
        val qTokens = tokens(query)
        val cTokens = tokens(c.text).toSet()
        val hits = qTokens.count { cTokens.contains(it) }
        if (hits > 0) {
            total += hits.toDouble()
            reasons.add(hits.toString() + " token hit(s)")
        }
        // An identifier-shaped query term (has _ . / or camel hump) matching exactly is strong.
        qTokens.forEach { t ->
            if (t.length >= 4 && (t.contains('_') || t.contains('.') || t.contains('/')) && cTokens.contains(t)) {
                total += IDENTIFIER_BONUS
                reasons.add("identifier " + t)
            }
        }
        val path = c.path
        if (path != null && q.isNotEmpty() && normalised(path).contains(q)) {
            total += PATH_BONUS
            reasons.add("path match")
        }
        return Scored(c.id, total, reasons)
    }

    /** Ranks, drops zero scores, and keeps one row per id (first wins). */
    fun rank(query: String, candidates: List<Candidate>, limit: Int = 8): List<Scored> {
        val seen = HashSet<String>()
        return candidates
            .map { score(query, it) }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.id })
            .filter { seen.add(it.id) }
            .take(limit)
    }

    /**
     * Fusion with an existing semantic score. Kept explainable: the caller sees both halves and the
     * weights, so a surprising order can be traced rather than guessed at.
     */
    fun fuse(
        lexical: Double,
        semantic: Double,
        lexicalWeight: Double = 1.0,
        semanticWeight: Double = 1.0,
    ): Double = lexical * lexicalWeight + semantic * semanticWeight
}
