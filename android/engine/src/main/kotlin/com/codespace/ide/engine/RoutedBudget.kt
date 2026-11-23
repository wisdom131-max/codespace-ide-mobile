package com.codespace.ide.engine

/**
 * PROJECT NINE 9.91 (catalog 87 WIRED) - the reasoning allowance follows the question.
 *
 * The router was complete and called by nothing, so a one-word question and a full
 * refactor were charged the same thinking budget. A light question now spends none,
 * a standard one keeps what the caller asked for, and a heavy one gets double - capped,
 * because an unbounded doubling is how a single question becomes expensive.
 *
 * Written as a plain function on purpose: the previous two attempts inserted a new
 * variable into the send path and landed it in a scope that could not see its own
 * uses. An inline call has no scope to get wrong.
 */
object RoutedBudget {

    /** Never let the doubling run away, even if the caller's base is already large. */
    const val MAX_HEAVY = 8192

    fun forTier(tier: ComplexityRouter.Tier, base: Int): Int = when (tier) {
        ComplexityRouter.Tier.LIGHT -> 0
        ComplexityRouter.Tier.STANDARD -> base
        ComplexityRouter.Tier.HEAVY -> (base * 2).coerceAtMost(MAX_HEAVY)
    }

    fun forRequest(request: String, base: Int): Int =
        forTier(ComplexityRouter.route(request, "AGENT").tier, base)
}
