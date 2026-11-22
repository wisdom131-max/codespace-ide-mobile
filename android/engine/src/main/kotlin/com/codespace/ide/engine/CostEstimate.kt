package com.codespace.ide.engine

/**
 * PROJECT NINE 9.30 (catalog 92 support) - what a turn actually cost, in money.
 *
 * TokenAccounting counted tokens and the over-budget card spoke in tokens. Tokens are not the unit
 * anyone reasons about; money is. The screen itself is the owner's to design, but the arithmetic
 * behind it belongs here so the display cannot disagree with the accounting.
 *
 * Prices are passed in rather than hardcoded: a table of provider prices baked into the engine is
 * wrong the week after it ships.
 */
object CostEstimate {

    data class Price(val inputPerMillion: Double, val outputPerMillion: Double)

    fun cost(price: Price, inputTokens: Int, outputTokens: Int): Double {
        if (inputTokens < 0 || outputTokens < 0) return 0.0
        return inputTokens / 1_000_000.0 * price.inputPerMillion +
            outputTokens / 1_000_000.0 * price.outputPerMillion
    }

    fun format(usd: Double): String {
        if (usd <= 0.0) return "$0.00"
        if (usd < 0.01) return String.format(java.util.Locale.US, "$%.4f", usd)
        return String.format(java.util.Locale.US, "$%.2f", usd)
    }
}
