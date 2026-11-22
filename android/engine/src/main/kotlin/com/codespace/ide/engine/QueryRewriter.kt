package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.21 (catalog 52) - "how does the fact retrieval work" should find
 * `FactRetrieval.kt`.
 *
 * A lexical index matches TOKENS, and code names are written as one word: FactRetrieval,
 * memory_block, ChatEngineImpl. A user typing the same thing in prose produces three separate
 * tokens that the index has never seen. This turns one question into the terms an index would
 * actually hold, by splitting identifiers at their boundaries and keeping both forms.
 *
 * Deliberately deterministic and offline: no model call, because a rewrite that costs a round trip
 * before every search is worse than a slightly worse query. Nothing is invented - every term
 * emitted is a substring of the input or a case-split part of it.
 */
object QueryRewriter {

    private val STOPWORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "do", "does", "did",
        "how", "what", "where", "when", "why", "which", "who", "to", "of", "in", "on", "at",
        "for", "with", "and", "or", "but", "it", "this", "that", "there", "here", "i", "we",
        "you", "my", "our", "me", "can", "could", "should", "would", "please", "tell", "show",
    )

    /** Split a run of alphanumerics at camelCase, snake_case and digit boundaries. */
    internal fun splitIdentifier(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var prevLower = false
        for (c in token) {
            when {
                c == '_' || c == '-' || c == '.' || c == '/' -> {
                    if (current.isNotEmpty()) { parts.add(current.toString()); current.setLength(0) }
                    prevLower = false
                }
                c.isUpperCase() && prevLower -> {
                    if (current.isNotEmpty()) { parts.add(current.toString()); current.setLength(0) }
                    current.append(c)
                    prevLower = false
                }
                else -> {
                    current.append(c)
                    prevLower = c.isLowerCase()
                }
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }

    /**
     * The terms to search for, in priority order: the original identifiers first, then their parts.
     * Bounded by [maxTerms] so a pasted paragraph cannot become a thousand-term query.
     */
    fun rewrite(raw: String, maxTerms: Int = 12): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val tokens = raw.split(Regex("[^A-Za-z0-9_.\\-/]+"))
        // Pass 1: identifiers as written. A file or class name is the strongest signal there is.
        for (t in tokens) {
            if (t.length < 3) continue
            if (t.lowercase() in STOPWORDS) continue
            out.add(t)
            if (out.size >= maxTerms) return out.toList()
        }
        // Pass 2: their split parts, so prose can match the parts of a code name.
        for (t in tokens) {
            for (p in splitIdentifier(t)) {
                if (p.length < 3) continue
                if (p.lowercase() in STOPWORDS) continue
                out.add(p)
                if (out.size >= maxTerms) return out.toList()
            }
        }
        return out.toList()
    }

    /** The same terms as one space-separated string, for an FTS5 MATCH expression. */
    fun rewriteToQuery(raw: String, maxTerms: Int = 12): String = rewrite(raw, maxTerms).joinToString(" ")
}
