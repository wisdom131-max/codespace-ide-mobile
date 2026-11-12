package com.codespace.ide.agent.research

/**
 * PROJECT 1.1 / 1.5 - WHAT TO FETCH NEXT, AND WHEN TO STOP.
 *
 * The existing research system already searches, fetches, extracts and cites. What it did not do was
 * decide *which* of the returned hits are actually different from each other, or notice that a
 * question's key terms were never covered. This planner is that decision, kept pure so it can be
 * tested without a network.
 *
 * Two rules it enforces, both from the programme spec:
 *
 *  - DEDUPE IS BY IDENTITY AND BY CONTENT. [UrlTools.normalize] already collapses tracking params
 *    and trailing slashes; here the same page reached through two engines is also caught by title
 *    token overlap, because a mirror or a syndicated copy is one source, not two.
 *  - DIVERSITY IS PER DOMAIN. Two hits from one host are usually one page and one of its sections.
 *    The cap keeps the second only when nothing else is available.
 *
 * It never invents evidence: gaps are question terms with no support in what was actually read.
 */
object ResearchPlanner {

    /** Hard ceiling on search rounds for one question. A loop that cannot stop is a runaway. */
    const val MAX_ROUNDS = 3

    data class Candidate(
        val url: String,
        val title: String,
        val snippet: String = "",
        /** Engine order; lower is better. Never treated as credibility. */
        val rank: Int = 0,
    )

    /** What a fetch actually returned, used only to judge coverage of the question's own terms. */
    data class Evidence(val url: String, val text: String)

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "for", "to", "in", "on", "is", "are", "how", "what",
        "why", "when", "which", "with", "from", "that", "this", "it", "be", "can", "do", "does",
        "vs", "into", "using", "use", "about", "not", "you", "your",
    )

    /**
     * Choose what to fetch, in order. Already-seen URLs are dropped, equivalent ones are collapsed,
     * and no host may take more than [maxPerDomain] slots while other hosts are still available.
     */
    fun select(
        candidates: List<Candidate>,
        seenUrls: Set<String> = emptySet(),
        limit: Int = 6,
        maxPerDomain: Int = 2,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        val seenKeys = seenUrls.map { UrlTools.normalize(it) }.toHashSet()
        val kept = ArrayList<Candidate>(limit)
        val deferred = ArrayList<Candidate>()
        for (c in candidates.sortedBy { it.rank }) {
            val key = UrlTools.normalize(c.url)
            if (key.isBlank() || !seenKeys.add(key)) continue
            if (kept.any { sameContent(it, c) }) continue
            val host = UrlTools.domain(c.url)
            val used = kept.count { UrlTools.domain(it.url) == host }
            if (used >= maxPerDomain) { deferred.add(c); continue }
            kept.add(c)
            if (kept.size == limit) return kept
        }
        // Only now is a second page from one host better than an empty slot.
        for (c in deferred) {
            if (kept.size == limit) break
            if (kept.any { sameContent(it, c) }) continue
            kept.add(c)
        }
        return kept
    }

    /** Same page by title: token sets that agree at [OVERLAP] or better. */
    fun sameContent(a: Candidate, b: Candidate): Boolean {
        if (UrlTools.normalize(a.url) == UrlTools.normalize(b.url)) return true
        val x = tokens(a.title)
        val y = tokens(b.title)
        if (x.isEmpty() || y.isEmpty()) return false
        val shared = x.intersect(y).size
        return shared.toDouble() / minOf(x.size, y.size) >= OVERLAP
    }

    private const val OVERLAP = 0.8

    internal fun tokens(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9+#.]+"))
            .filter { it.length > 1 && it !in STOPWORDS }
            .toSet()

    /**
     * Question terms with no support in the evidence actually read, as follow-up queries.
     *
     * Empty when the question is covered: an extra search that cannot change the answer is a cost
     * with no return, and the spec says not to retrieve the same information twice.
     */
    fun gapQueries(
        question: String,
        evidence: List<Evidence>,
        limit: Int = 2,
    ): List<String> {
        val asked = tokens(question)
        if (asked.isEmpty()) return emptyList()
        val covered = tokens(evidence.joinToString(" ") { it.text })
        val missing = asked.filter { it !in covered && it.length > 3 }
        if (missing.isEmpty()) return emptyList()
        return missing.take(limit).map { term -> "$question $term" }
    }

    /** Whether another round is allowed: bounded by rounds, searches and the caller's own budget. */
    fun allowAnotherRound(round: Int, searchesUsed: Int, searchBudget: Int): Boolean =
        round < MAX_ROUNDS && searchesUsed < searchBudget

    /**
     * How complete the evidence set is, in words the answer can use. Sourced honesty beats a
     * confident summary built on one page.
     */
    fun coverageNote(evidence: List<Evidence>): String {
        val hosts = evidence.map { UrlTools.domain(it.url) }.filter { it.isNotBlank() }.distinct()
        return when {
            evidence.isEmpty() -> "No source was read, so nothing below is sourced."
            hosts.size == 1 -> "One source (" + hosts.first() + ") supports this answer. Treat it as " +
                "a single account, not as confirmation."
            hosts.size == 2 -> "Two independent sources support this answer."
            else -> hosts.size.toString() + " independent sources support this answer."
        }
    }
}
