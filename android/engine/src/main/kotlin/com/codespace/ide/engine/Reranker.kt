package com.codespace.ide.engine

/**
 * PROJECT NINE 9.34 (catalog 51, stand-in) - reorder the shortlist by how much of the query it holds.
 *
 * A real cross-encoder reads query and document together and is the better answer; it is also a
 * model download and a per-candidate cost. This is the honest stand-in: it counts how many distinct
 * query terms a candidate actually contains and lifts those, which fixes the common case of a
 * vector hit that is topically close but does not contain the thing asked for.
 *
 * It scales the fused score rather than adding to it, so reranking can reorder near-ties but can
 * never lift a weak result over a strong one.
 */
object Reranker {

    data class Candidate(val id: String, val text: String, val score: Double)

    fun rerank(candidates: List<Candidate>, terms: List<String>): List<Candidate> {
        val wanted = terms.map { it.lowercase() }.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return candidates
        return candidates
            .map { c ->
                val lower = c.text.lowercase()
                val present = wanted.count { lower.contains(it) }
                // Proportional, not additive. A flat bonus big enough to matter is also big enough
                // to overturn a much better result, which is the opposite of reranking. Scaling the
                // candidate's own score means a weak candidate stays weak however well it matches.
                c to (c.score * (1.0 + present.toDouble() / wanted.size))
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
