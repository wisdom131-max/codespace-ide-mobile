package com.codespace.ide.agent

/**
 * OWNER REQUEST (2026-11-25): the ranked symbol slice, Aider's idea on our index.
 *
 * Aider builds a graph and ranks it with PageRank, then renders a compressed tree of lines of interest
 * with bodies elided, capped at map_tokens (1024 by default). We already rank with bm25 inside SQLite,
 * so what we lacked was the second half: the CAP and the COMPRESSION. Raw search hits are file paths
 * with a snippet each, which grows without limit and spends tokens on prose the model did not ask for.
 *
 * This keeps the ranking the index produced (order is preserved, never re-sorted), strips each hit to
 * its densest line, and stops when the budget is spent. 1024 tokens is Aider's default and roughly
 * four characters per token, so the character cap is derived rather than guessed.
 */
object SymbolSlice {

    const val TOKEN_BUDGET = 1024

    /** Roughly four characters per token, the same ratio the rest of the app uses for estimates. */
    fun charBudget(tokens: Int = TOKEN_BUDGET): Int = tokens * 4

    /**
     * [hits] are "path: snippet" lines in the index's own order. Nothing is re-ranked here: the point
     * is to spend the budget on the top of a ranking that already exists, not to invent a new one.
     */
    fun compress(hits: List<String>, tokens: Int = TOKEN_BUDGET): String {
        val cap = charBudget(tokens)
        if (hits.isEmpty() || cap <= 0) return ""
        val sb = StringBuilder()
        var used = 0
        for (raw in hits) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val cut = if (line.length > MAX_LINE) line.take(MAX_LINE).trimEnd() + "..." else line
            val cost = cut.length + 1
            if (used + cost > cap) break
            sb.append(cut).append('\n')
            used += cost
        }
        return sb.toString().trimEnd()
    }

    /** One hit should never be a whole file: Aider elides bodies, and so do we. */
    private const val MAX_LINE = 220
}
