package com.codespace.ide.engine

/**
 * Payload budget guard. Refuses oversized sends with an honest OverBudget
 * error — never a silent trim of the user's message. Char-based (providers
 * count tokens; chars are the honest, dependency-free proxy, ~4 chars/token).
 */
object BudgetGuard {

    const val CHARS_PER_TOKEN_ESTIMATE = 4

    fun estimateTokens(text: String): Int = text.length / CHARS_PER_TOKEN_ESTIMATE

    /**
     * Check the assembled outgoing payload. Returns null when within budget,
     * or the classified refusal. The verdict names the real limit so the
     * caller can surface a plain number to the user.
     */
    fun checkPayload(
        payloadChars: Int,
        maxPayloadChars: Int,
        price: CostEstimate.Price? = null,
    ): ChatError.OverBudget? {
        if (payloadChars > maxPayloadChars) {
            // PROJECT EIGHT 8.62 (catalog 92 wiring): TokenEstimator existed and nothing used it.
            // A character count is not what the user is paying for, and "45k chars" says nothing
            // about whether that is a lot. Tokens are the unit that answers the question.
            val overTokens = TokenEstimator.estimate(payloadChars)
            val limitTokens = TokenEstimator.estimate(maxPayloadChars)
            // PROJECT NINE 9.44 (catalog 92 support WIRED): tokens are not the unit anyone reasons
            // about, money is. When a price is known the refusal says what this send would cost,
            // which is the number that decides whether to press send. Absent a price the message is
            // byte-identical to before, so nothing changes for callers that do not supply one.
            val money = price?.let {
                " That would cost about " + CostEstimate.format(CostEstimate.cost(it, overTokens, 0)) + "."
            } ?: ""
            return ChatError.OverBudget(
                "payload is ${payloadChars / 1000}k chars (~${overTokens / 1000}k tokens); " +
                    "limit ${maxPayloadChars / 1000}k chars (~${limitTokens / 1000}k tokens)." + money
            )
        }
        return null
    }
}
