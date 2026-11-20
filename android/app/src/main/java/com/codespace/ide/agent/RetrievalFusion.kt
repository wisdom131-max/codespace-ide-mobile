package com.codespace.ide.agent

/**
 * P3 completion (2026-11-20) — lexical/semantic fusion.
 *
 * `LexicalRanker` scored text in isolation. This is the piece that was missing: one place where a
 * semantic hit count and a lexical score are put on a comparable scale, combined with explicit
 * weights, and returned with the reasons that produced the order.
 *
 * Design constraints that matter here:
 *  - Semantic retrieval is not replaced. Its hit count is normalised, never discarded.
 *  - A weak substring match must not outrank a strong semantic one, so the semantic half is
 *    normalised to 0..1 and carries the larger weight by default.
 *  - Nothing is filtered out for a low lexical score. The caller decides limits, as before.
 *  - Deduplication is by source id, so one file or fact cannot appear twice.
 */
object RetrievalFusion {

    /** Semantic hits dominate the default blend; lexical exactness is the tie-breaker. */
    const val SEMANTIC_WEIGHT = 1.0
    const val LEXICAL_WEIGHT = 0.45

    data class Hit(
        val id: String,
        val semantic: Double,
        val lexical: Double,
        val total: Double,
        val reasons: List<String>,
    )

    /**
     * @param semanticHits how many query terms this source contains.
     * @param semanticMax the best hit count in the candidate set, used to normalise.
     */
    fun score(
        query: String,
        id: String,
        text: String,
        semanticHits: Int,
        semanticMax: Int,
        sourcePath: String? = null,
    ): Hit {
        val lex = LexicalRanker.score(query, LexicalRanker.Candidate(id, text, sourcePath))
        val normalisedSemantic =
            if (semanticMax <= 0) 0.0 else semanticHits.toDouble() / semanticMax.toDouble()
        val total = normalisedSemantic * SEMANTIC_WEIGHT + lex.score * LEXICAL_WEIGHT
        val reasons = ArrayList<String>()
        if (semanticHits > 0) reasons.add("semantic " + semanticHits + "/" + semanticMax)
        reasons.addAll(lex.reasons)
        return Hit(id, normalisedSemantic, lex.score, total, reasons)
    }

    /** Ranks by total, keeps one row per id, and never drops a row for a low lexical score. */
    fun merge(hits: List<Hit>, limit: Int = 40): List<Hit> {
        val seen = HashSet<String>()
        return hits
            .sortedWith(compareByDescending<Hit> { it.total }.thenBy { it.id })
            .filter { seen.add(it.id) }
            .take(limit)
    }

    /** One line a developer can read to see why something led. */
    fun explain(h: Hit): String =
        h.id + " -> " + String.format("%.2f", h.total) +
            " (semantic " + String.format("%.2f", h.semantic) +
            ", lexical " + String.format("%.2f", h.lexical) + ": " +
            h.reasons.joinToString(", ") + ")"
}
