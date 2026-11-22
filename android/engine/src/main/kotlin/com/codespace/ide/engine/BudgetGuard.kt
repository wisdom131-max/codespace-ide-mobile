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
    fun checkPayload(payloadChars: Int, maxPayloadChars: Int): ChatError.OverBudget? {
        if (payloadChars > maxPayloadChars) {
            // PROJECT EIGHT 8.62 (catalog 92 wiring): TokenEstimator existed and nothing used it.
            // A character count is not what the user is paying for, and "45k chars" says nothing
            // about whether that is a lot. Tokens are the unit that answers the question.
            val overTokens = TokenEstimator.estimate(payloadChars)
            val limitTokens = TokenEstimator.estimate(maxPayloadChars)
            return ChatError.OverBudget(
                "payload is ${payloadChars / 1000}k chars (~${overTokens / 1000}k tokens); " +
                    "limit ${maxPayloadChars / 1000}k chars (~${limitTokens / 1000}k tokens)."
            )
        }
        return null
    }
}
