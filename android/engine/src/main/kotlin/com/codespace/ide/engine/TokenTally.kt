package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.16 (catalog 92): running token totals for a session.
 *
 * The usage numbers were already arriving and going nowhere. This keeps the running total so a
 * surface can show it, and nothing more: it holds no prices, because a price table baked into the
 * engine would go stale silently and would be wrong the moment a model is repriced.
 *
 * Longs, because a long session of a million-token model overflows an Int in about two turns.
 * Counts only what the provider actually reported: a turn with no usage does not silently add 0.
 */
class TokenTally {

    private var input = 0L
    private var output = 0L
    private var reported = 0
    private var missing = 0

    data class Totals(
        val inputTokens: Long,
        val outputTokens: Long,
        /** Turns that reported usage. */
        val turnsReported: Int,
        /** Turns that did not, so a total can be read with the right amount of trust. */
        val turnsUnreported: Int,
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    @Synchronized
    fun record(inputTokens: Int?, outputTokens: Int?) {
        if (inputTokens == null && outputTokens == null) {
            missing++
            return
        }
        reported++
        if (inputTokens != null && inputTokens > 0) input += inputTokens.toLong()
        if (outputTokens != null && outputTokens > 0) output += outputTokens.toLong()
    }

    @Synchronized
    fun totals(): Totals = Totals(input, output, reported, missing)

    @Synchronized
    fun clear() {
        input = 0L
        output = 0L
        reported = 0
        missing = 0
    }
}
