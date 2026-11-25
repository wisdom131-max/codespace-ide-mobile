package com.codespace.ide.chat.panel

/**
 * OUR CAPS, REBUILT ON VS CODE'S METHOD (owner request 2026-11-25).
 *
 * Ours was a fixed character count per mode: Ask 5000, everything else 12500. That is deterministic
 * and cheap, but it is blind to the model - a 200k-window model was given the same room as an 8k one,
 * and nothing bounded the tool loop at all.
 *
 * VS Code does two different things and they are both better here:
 *  - the prompt is bounded by the MODEL'S OWN WINDOW, not by a number we invented;
 *  - the agent loop is bounded by chat.agent.maxRequests, default 25.
 *
 * So this keeps our floors as the fallback (byte-identical behaviour when the window is unknown, which
 * is why no existing budget test moves) and scales between floor and ceiling once a window is known.
 * Ask stays deliberately smaller than Agent: it answers, it does not act.
 */
internal object PromptBudget {

    /** The selected model's window in tokens, or 0 when nobody has told us. */
    @Volatile private var windowTokens: Int = 0

    fun observeWindow(tokens: Int) {
        if (tokens > 0) windowTokens = tokens
    }

    internal fun windowForTest(): Int = windowTokens

    private const val ASK_FRACTION = 0.12
    private const val AGENT_FRACTION = 0.25
    private const val ASK_FLOOR = 5_000
    private const val ASK_CEILING = 8_000
    private const val AGENT_FLOOR = 12_500
    private const val AGENT_CEILING = 24_000
    /** Characters per token, the same rough ratio the context panel already uses. */
    private const val CHARS_PER_TOKEN = 4

    /**
     * The instruction cap for [mode]. [fallback] is returned untouched while no window is known, so
     * every existing test keeps the exact numbers it was written against.
     */
    fun capFor(mode: ChatMode, fallback: Int): Int {
        val window = windowTokens
        if (window <= 0) return fallback
        val ask = mode == ChatMode.ASK
        val scaled = (window.toDouble() * CHARS_PER_TOKEN * (if (ask) ASK_FRACTION else AGENT_FRACTION)).toInt()
        return scaled.coerceIn(if (ask) ASK_FLOOR else AGENT_FLOOR, if (ask) ASK_CEILING else AGENT_CEILING)
    }

    /** VS Code's chat.agent.maxRequests default. One turn may not exceed it. */
    const val MAX_TOOL_REQUESTS = 25

    private var requestsThisTurn = 0

    fun beginTurn() {
        requestsThisTurn = 0
    }

    /** Counts one tool request; false when this turn has already spent its ceiling. */
    @Synchronized
    fun toolRequest(): Boolean {
        if (requestsThisTurn >= MAX_TOOL_REQUESTS) return false
        requestsThisTurn++
        return true
    }

    internal fun spentForTest(): Int = requestsThisTurn

    fun exhaustedMessage(): String = "STOP - this turn has used its " + MAX_TOOL_REQUESTS +
        " tool requests, which is VS Code's default ceiling. Do not call another tool. Summarise what " +
        "you have, say exactly what is left, and ask the owner to continue."
}
