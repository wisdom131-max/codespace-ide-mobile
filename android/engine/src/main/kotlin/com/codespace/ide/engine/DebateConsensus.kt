package com.codespace.ide.engine

/**
 * PROJECT NINE 9.17 (catalog 11) - when several drafts disagree, take the one most of them agree on.
 *
 * A single draft has no way to say how sure it is. Asking more than once and comparing gives a
 * cheap confidence signal: the same answer reached independently is stronger evidence than one
 * answer alone.
 *
 * The comparison is on normalised text, because whitespace and case differences are not
 * disagreements. A tie goes to the earliest candidate, so the result never depends on map order.
 */
object DebateConsensus {

    data class Outcome(val answer: String, val votes: Int, val of: Int) {
        val unanimous: Boolean get() = votes == of
    }

    fun decide(candidates: List<String>): Outcome? {
        val usable = candidates.filter { it.isNotBlank() }
        if (usable.isEmpty()) return null
        val counts = LinkedHashMap<String, Int>()
        val firstSeen = LinkedHashMap<String, String>()
        for (c in usable) {
            val key = normalise(c)
            counts[key] = (counts[key] ?: 0) + 1
            if (!firstSeen.containsKey(key)) firstSeen[key] = c.trim()
        }
        var bestKey: String? = null
        var bestVotes = -1
        for ((key, votes) in counts) {
            if (votes > bestVotes) {
                bestVotes = votes
                bestKey = key
            }
        }
        val key = bestKey ?: return null
        return Outcome(firstSeen.getValue(key), bestVotes, usable.size)
    }

    private fun normalise(s: String): String = s.trim().replace(Regex("\\s+"), " ").lowercase()
}
