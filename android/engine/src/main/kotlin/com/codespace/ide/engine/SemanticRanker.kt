package com.codespace.ide.engine

/**
 * PROJECT NINE 9.31 (catalog 45/46 into the ranking path) - rank by meaning, not just by word.
 *
 * The embedding maths and the vector store existed and nothing called them, so search was still
 * word matching with extra code sitting next to it. This turns a query into a vector and orders
 * documents by cosine similarity, which is the shape the fusion step expects.
 *
 * It returns ids only, never text: the caller already has the documents and should not pay to hold
 * them twice.
 */
object SemanticRanker {

    data class Doc(val id: String, val text: String)

    fun rank(query: String, docs: List<Doc>, k: Int = 20): List<String> {
        if (docs.isEmpty() || k <= 0 || query.isBlank()) return emptyList()
        val q = VectorIndex.embedQuery(query)
        return docs
            .map { it.id to VectorIndex.cosine(q, VectorIndex.embed(it.text)) }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }
}
