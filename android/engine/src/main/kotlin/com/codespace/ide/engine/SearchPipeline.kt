package com.codespace.ide.engine

/**
 * PROJECT NINE 9.40 - the chain itself: one call that runs search the way it was designed.
 *
 * Every piece of this existed and none of them were connected, which is why search still behaved
 * like a single word match. This runs them in the order that makes sense: widen the query, rank by
 * meaning, fuse with literal hits, collapse to one result per file, then cut to the limit.
 *
 * Ranking order is the contract. Deduplication happens AFTER fusion and before the limit, so the
 * limit counts distinct files rather than repeated hits on one file.
 */
object SearchPipeline {

    data class Doc(val id: String, val path: String, val text: String)

    data class Outcome(val ids: List<String>, val lexicalCount: Int, val semanticCount: Int)

    fun run(query: String, docs: List<Doc>, lexical: List<String>, limit: Int = 10): Outcome {
        if (docs.isEmpty() || limit <= 0) return Outcome(emptyList(), lexical.size, 0)
        val semantic = SemanticRanker.rank(query, docs.map { SemanticRanker.Doc(it.id, it.text) })
        val byId = docs.associateBy { it.id }
        val fused = HybridSearch.combine(lexical, semantic)
            .mapNotNull { f -> byId[f.id]?.let { d -> ResultDedupe.Hit(d.path, f.score, f.id) } }
        val ids = ResultDedupe.byPath(fused).map { it.id }.take(limit)
        return Outcome(ids, lexical.size, semantic.size)
    }
}
