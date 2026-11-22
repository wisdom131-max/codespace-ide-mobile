package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.23 (catalog 50) - two search engines disagree, and neither score means anything
 * to the other.
 *
 * A lexical index scores by term frequency in an arbitrary range; a vector index scores by cosine
 * distance between 0 and 1. Adding those numbers is nonsense, which is why every naive hybrid
 * search underperforms. Reciprocal rank fusion sidesteps it: only the ORDER from each list is used,
 * so no score ever has to be comparable.
 *
 * Formula: score(d) = sum over lists of 1 / (k + rank(d)), rank starting at 1. k dampens the top of
 * each list so one confident list cannot monopolise the result. k=60 is the value from the original
 * paper and the one every implementation uses; it is exposed only so tests can pin the arithmetic.
 */
object ReciprocalRankFusion {

    const val DEFAULT_K: Int = 60

    data class Hit(val id: String, val score: Double, val ranks: List<Int>)

    /**
     * @param lists ranked id lists, best first. A document missing from a list simply earns nothing
     *              from it - it is not penalised, which is what makes a partial match survive.
     */
    fun fuse(lists: List<List<String>>, k: Int = DEFAULT_K): List<Hit> {
        require(k > 0) { "k must be positive" }
        val totals = LinkedHashMap<String, Double>()
        val ranks = LinkedHashMap<String, MutableList<Int>>()
        for (list in lists) {
            // A duplicated id inside one list must count once, or a sloppy list could vote twice.
            val seen = HashSet<String>()
            list.forEachIndexed { index, id ->
                if (!seen.add(id)) return@forEachIndexed
                val rank = index + 1
                totals[id] = (totals[id] ?: 0.0) + 1.0 / (k + rank)
                ranks.getOrPut(id) { ArrayList() }.add(rank)
            }
        }
        return totals.entries
            .map { Hit(it.key, it.value, ranks[it.key] ?: emptyList()) }
            // Ties broken by id so the output is stable: an unstable ordering makes a regression
            // test flap, and makes "why did this come first" unanswerable.
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id })
    }
}
