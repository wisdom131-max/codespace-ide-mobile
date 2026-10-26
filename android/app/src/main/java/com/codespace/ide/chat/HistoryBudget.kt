package com.codespace.ide.chat

/**
 * 1c (advisor 2026-10-26): TOKEN-BUDGETED HISTORY for the send path.
 *
 * Problem (READ): convMsgsOf walked EVERY message since the session began and
 * re-injected EVERY restored user message's attachment block — the request
 * grew without bound, so long conversations produced multi-hundred-K payloads
 * that (a) cost time-to-first-byte on Gemini and (b) burned quota faster (429s).
 *
 * Contract (pure, JVM-testable; no network, no disk):
 *  - Below [DEFAULT_BUDGET_CHARS] nothing changes: every entry comes back
 *    kept=true and the caller sends the ORIGINAL messages. No behavior change.
 *  - A TURN is a user message plus every following non-user message up to the
 *    next user message. Turns are evaluated NEWEST first.
 *  - The NEWEST turn is NEVER trimmed, whatever its size.
 *  - A kept turn is kept WHOLE — tool-call pairs (assistant + tool results)
 *    live inside a turn, so they can never be split from their request.
 *  - Attachment cost is ESTIMATED at the send cap per file
 *    ([ChatAttachmentInjector.SEND_CAP_PER_FILE]) — the injector may inject up
 *    to that much per attachment, so budgeting on the estimate is honest and
 *    needs no file reads here. Trimmed turns lose their attachment blocks
 *    (blocks are injected only for kept/recent turns).
 *  - Entries that do not fit the remaining budget are REDUCED to one line-note
 *    each (role kept, content dropped) — the model still sees that something
 *    was said; a turn is never silently missing.
 *  - Kept entries are byte-identical: previously redacted markers ride along.
 *
 * The caller shows an honest "older messages trimmed" row when trimmed > 0.
 */
object HistoryBudget {

    /** Documented default: ~60k chars (~15k tokens) of conversation history. */
    const val DEFAULT_BUDGET_CHARS: Int = 60_000

    data class Entry(val role: String, val text: String, val kept: Boolean)

    data class Result(val entries: List<Entry>, val trimmed: Int, val budgetChars: Int)

    /** Cost estimate of one message: text plus attachment blocks at the send cap. */
    fun costOf(text: String, attachmentCount: Int): Int =
        text.length + attachmentCount * ChatAttachmentInjector.SEND_CAP_PER_FILE

    /**
     * [entries] are (role, text, attachmentCount) triples. Returns SAME-SIZE
     * list of [Entry]; kept entries carry the original text, trimmed ones a
     * one-line note. Pure function.
     */
    fun trim(
        entries: List<Triple<String, String, Int>>,
        budgetChars: Int = DEFAULT_BUDGET_CHARS,
    ): Result {
        if (entries.isEmpty()) return Result(emptyList(), 0, budgetChars)
        val total = entries.sumOf { (r, t, a) -> costOf(t, a) }
        if (total <= budgetChars) {
            return Result(entries.map { (r, t, _) -> Entry(r, t, true) }, 0, budgetChars)
        }
        // Split into turns: [start, end) ranges; a user entry opens a turn.
        val turnRanges = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < entries.size) {
            val start = i
            if (entries[i].first == "user") i++
            while (i < entries.size && entries[i].first != "user") i++
            turnRanges.add(start to i)
        }
        val keep = BooleanArray(entries.size)
        var remaining = budgetChars
        var trimmed = 0
        for (ti in turnRanges.indices.reversed()) {
            val (a, b) = turnRanges[ti]
            var cost = 0
            for (k in a until b) cost += costOf(entries[k].second, entries[k].third)
            val isNewest = ti == turnRanges.size - 1
            if (cost <= remaining || isNewest) {
                for (k in a until b) keep[k] = true
                remaining = (remaining - cost).coerceAtLeast(0)
            } else {
                trimmed += b - a
            }
        }
        if (trimmed == 0) {
            return Result(entries.map { (r, t, _) -> Entry(r, t, true) }, 0, budgetChars)
        }
        val out = ArrayList<Entry>(entries.size)
        entries.forEachIndexed { idx, (r, t, _) ->
            if (keep[idx]) {
                out.add(Entry(r, t, true))
            } else {
                out.add(Entry(
                    r,
                    "(trimmed: older " + r + " message, " + t.length +
                        " chars \u2014 left out of the request to fit the context budget)",
                    false,
                ))
            }
        }
        return Result(out, trimmed, budgetChars)
    }
}
