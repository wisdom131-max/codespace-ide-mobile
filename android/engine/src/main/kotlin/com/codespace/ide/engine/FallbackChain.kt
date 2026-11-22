package com.codespace.ide.engine

/**
 * PROJECT NINE 9.18 (catalog 15) - move to the next model when this one is genuinely unusable.
 *
 * Failover existed only for key slots, so an exhausted quota on the chosen model ended the turn
 * even when another configured model could have answered.
 *
 * Off by default. Switching models silently changes the shape and cost of an answer, and the owner
 * asked for this visible and toggleable rather than automatic. It also refuses to advance on a
 * failure that is not the model's fault: a bad key or a malformed request fails identically
 * everywhere, so hopping models only hides the real problem and burns quota doing it.
 */
object FallbackChain {

    data class Chain(val models: List<String>, val enabled: Boolean = false)

    fun nextAfter(chain: Chain, current: String, retryable: Boolean, tried: Set<String>): String? {
        if (!chain.enabled || !retryable) return null
        val already = tried + current
        return chain.models.firstOrNull { it !in already && it != current }
    }
}
