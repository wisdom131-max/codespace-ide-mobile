package com.codespace.ide.engine

/**
 * PROJECT NINE 10.10 (catalog 51) - reorder the shortlist by how much of the query it really holds.
 *
 * A trained cross-encoder reads query and document together and is the better answer; it is also a
 * model download and a per-candidate cost. This is the honest stand-in, and it now looks at three
 * things instead of one: how many distinct query terms appear, whether the query appears as a
 * phrase, and whether the terms sit near each other. Scattered words in a long file are weak
 * evidence; the same words adjacent are strong.
 *
 * It still SCALES the fused score rather than adding to it, and the multiplier is capped, so
 * reranking can settle near-ties but can never lift a weak result over a much stronger one.
 */
object Reranker {

    data class Candidate(val id: String, val text: String, val score: Double)

    private const val MAX_MULTIPLIER = 2.0
    private const val PROXIMITY_WINDOW = 120

    fun rerank(candidates: List<Candidate>, terms: List<String>): List<Candidate> {
        val wanted = terms.map { it.lowercase().trim() }.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return candidates
        val phrase = wanted.joinToString(" ")
        return candidates
            .map { c ->
                val lower = c.text.lowercase()
                val present = wanted.count { lower.contains(it) }
                val coverage = present.toDouble() / wanted.size
                val phraseHit = if (phrase.length > 3 && lower.contains(phrase)) 1.0 else 0.0
                val proximity = proximityOf(lower, wanted)
                val boost = 1.0 + 0.5 * coverage + 0.3 * phraseHit + 0.2 * proximity
                c to (c.score * boost.coerceAtMost(MAX_MULTIPLIER))
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * 1.0 when every wanted term sits inside one short span, 0.0 when they are spread out or
     * missing. A term that does not appear at all makes proximity meaningless, so it scores zero
     * rather than pretending the remaining terms are close.
     */
    private fun proximityOf(lower: String, wanted: List<String>): Double {
        val firsts = ArrayList<Int>(wanted.size)
        for (w in wanted) {
            val at = lower.indexOf(w)
            if (at < 0) return 0.0
            firsts.add(at)
        }
        if (firsts.isEmpty()) return 0.0
        val span = (firsts.maxOrNull() ?: 0) - (firsts.minOrNull() ?: 0)
        if (span > PROXIMITY_WINDOW) return 0.0
        return 1.0 - span.toDouble() / PROXIMITY_WINDOW
    }
}
