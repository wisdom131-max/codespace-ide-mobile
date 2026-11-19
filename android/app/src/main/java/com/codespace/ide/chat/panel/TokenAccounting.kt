package com.codespace.ide.chat.panel

/**
 * P3-A (2026-11-19): cumulative token accounting for ONE session.
 *
 * Rules that matter, each of which has a test:
 *  - Only REPORTED values are counted. A zero/negative report is treated as "nothing reported",
 *    never as "zero tokens", so the totals cannot be quietly deflated or invented.
 *  - A provider may repeat usage within one attempt, and a retry reports the same turn again, so a
 *    turn keeps the LAST report it saw and adds that once at the turn boundary. Adding every event
 *    would inflate the totals; keeping the first would under-report a retry that sent more.
 *  - The counters belong to the session instance, so two sessions cannot share a total.
 *
 * This is accounting, not pricing: no monetary cost is derived, because the app has no reliable
 * per-model price table.
 */
internal class TokenAccounting {

    data class Totals(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        /** Turns that reported usage. */
        val reportedTurns: Int = 0,
        /** Turns that finished without any usage report. */
        val unreportedTurns: Int = 0,
    ) {
        val total: Long get() = inputTokens + outputTokens
    }

    private var turnInput = 0
    private var turnOutput = 0
    private var turnReported = false
    private var inTurn = false

    var totals: Totals = Totals()
        private set

    fun beginTurn() {
        turnInput = 0
        turnOutput = 0
        turnReported = false
        inTurn = true
    }

    /** Last report wins within the turn; ignored entirely when nothing usable was reported. */
    fun onUsage(inputTokens: Int, outputTokens: Int) {
        if (!inTurn) return
        if (inputTokens <= 0 && outputTokens <= 0) return
        turnInput = if (inputTokens > 0) inputTokens else 0
        turnOutput = if (outputTokens > 0) outputTokens else 0
        turnReported = true
    }

    /** Fold the turn into the session totals. Idempotent: a second call without beginTurn adds nothing. */
    fun endTurn() {
        if (!inTurn) return
        totals = if (turnReported) {
            totals.copy(
                inputTokens = totals.inputTokens + turnInput,
                outputTokens = totals.outputTokens + turnOutput,
                reportedTurns = totals.reportedTurns + 1,
            )
        } else {
            totals.copy(unreportedTurns = totals.unreportedTurns + 1)
        }
        inTurn = false
        turnInput = 0
        turnOutput = 0
        turnReported = false
    }

    /** One line for the internal diagnostics surface. Never states a cost. */
    fun summary(): String {
        val t = totals
        if (t.reportedTurns == 0 && t.unreportedTurns == 0) return "tokens: none reported yet"
        return "tokens: in " + t.inputTokens + " / out " + t.outputTokens +
            " (turns " + t.reportedTurns + " reported, " + t.unreportedTurns + " without usage)"
    }
}
