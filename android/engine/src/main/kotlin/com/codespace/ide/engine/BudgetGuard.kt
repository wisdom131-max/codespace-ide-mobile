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
            return ChatError.OverBudget(
                "payload is ${payloadChars / 1000}k chars; limit ${maxPayloadChars / 1000}k."
            )
        }
        return null
    }
}
