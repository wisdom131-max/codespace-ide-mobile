package com.codespace.ide.engine

/**
 * PROJECT NINE 9.70 (catalog 45 + 46 + 51 WIRED) - rank search results by meaning, not only by
 * whether the characters happen to appear.
 *
 * The search tool could only answer "does this line contain the text". A line that describes the
 * thing in different words was invisible, which is why searching for "fact retrieval" never found
 * FactRetrieval. SemanticRanker, HybridSearch and Reranker were all built and tested and nothing
 * called them.
 *
 * The order of the chain matters and is deliberate: literal matches first, then meaning, fused so a
 * line found both ways wins outright, then a bounded rerank, then one line per file.
 */
object SearchRank {

    data class Line(val id: String, val path: String, val text: String)

    fun rank(
        query: String,
        literal: List<String>,
        widened: List<String>,
        limit: Int = 60,
        maxChars: Int = 12000,
    ): String {
        if (query.isBlank()) return ""
        val byId = LinkedHashMap<String, Line>()
        for (s in literal + widened) {
            if (s.isBlank()) continue
            byId[s] = Line(s, s.substringBefore(":"), s)
        }
        if (byId.isEmpty()) return ""

        val semantic = SemanticRanker.rank(
            query = query,
            docs = byId.values.map { SemanticRanker.Doc(it.id, it.text) },
            k = limit,
        )
        val fused = HybridSearch.combine(literal, semantic)
        val terms = QueryRewriter.rewrite(query, 8)
        val candidates = fused.mapNotNull { f ->
            byId[f.id]?.let { Reranker.Candidate(it.id, it.text, f.score) }
        }
        val ordered = Reranker.rerank(candidates, terms).mapNotNull { byId[it.id] }
        if (ordered.isEmpty()) return ""

        // One line per file: three matches in the same file crowd out the other files the model
        // needs to see. The strongest line per file survives, in relevance order.
        val deduped = ResultDedupe.byPath(
            ordered.mapIndexed { i, l -> ResultDedupe.Hit(l.path, (ordered.size - i).toDouble(), l.id) },
        )
        val lineById = ordered.associateBy { it.id }
        val finalLines = deduped.mapNotNull { lineById[it.id] }
        return SearchBudget.fit(
            finalLines.map { SearchBudget.Item(it.path, it.text) },
            maxChars,
        ).joinToString("\n") { it.text }
    }
}
