package com.codespace.ide.engine

/**
 * PROJECT NINE 9.32 (catalog 47 + 50 + 45) - word matches and meaning matches, combined.
 *
 * Literal search finds a symbol by name and misses a paraphrase. Vector search finds the
 * paraphrase and can miss the exact symbol. Neither is wrong; they fail differently, which is
 * exactly why fusing them works.
 *
 * A document found by both lists outranks one found by either alone, because two independent
 * methods agreeing is the strongest signal available here.
 */
object HybridSearch {

    data class Result(val id: String, val score: Double)

    fun combine(lexical: List<String>, semantic: List<String>): List<Result> {
        if (lexical.isEmpty() && semantic.isEmpty()) return emptyList()
        return ReciprocalRankFusion.fuse(listOf(lexical, semantic)).map { Result(it.id, it.score) }
    }
}
