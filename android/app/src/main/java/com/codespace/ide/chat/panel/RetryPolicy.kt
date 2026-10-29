package com.codespace.ide.chat.panel

import com.codespace.ide.engine.ChatError

/**
 * BATCH 6 — BOUNDED 429 RETRIES (owner mission part 6).
 *
 * The engine already knows exactly when a provider asked for a wait (ChatError.RateLimited
 * carries the parsed Retry-After), but nothing acted on it: the user got a card and had to
 * press Retry themselves, guessing when.
 *
 * The policy below is the whole decision, pure, so every limit is asserted in a JVM test
 * rather than discovered in the wild:
 *
 *  - ONLY 429 IS RETRIED. An auth failure, an out-of-credits account, a bad request and a
 *    model that does not exist are NOT time problems; retrying them just burns the user's
 *    credits and hides the real message. Connectivity failures are also excluded: a dead
 *    network is the user's to fix (reconnect), not something to hammer.
 *  - THE PROVIDER'S NUMBER WINS WHEN IT IS USABLE. Retry-After is honoured as given, but
 *    only inside a window we can actually wait inside ([MAX_AUTO_WAIT_SEC]); a provider
 *    asking for ten minutes is shown to the user instead of silently holding the request
 *    open, because an app that waits 600s looks hung.
 *  - WHEN THERE IS NO NUMBER, a fixed, short ladder is used (5s then 15s) - not exponential
 *    guesswork at 1s intervals, which is the classic way to get rate limited harder.
 *  - IT STOPS. [DEFAULT_MAX_ATTEMPTS] automatic attempts per user message, after which the
 *    card comes back with Retry (the manual escape hatch that already existed).
 */
internal object RetryPolicy {

    /** Automatic re-attempts allowed per user message (so at most three wire attempts). */
    const val DEFAULT_MAX_ATTEMPTS = 2

    /** A provider wait longer than this is surfaced, not waited: the app must not look hung. */
    const val MAX_AUTO_WAIT_SEC = 30

    /** Fixed ladder for a 429 with no usable Retry-After. */
    private val FALLBACK_WAITS_SEC = intArrayOf(5, 15)

    data class Decision(
        val retry: Boolean,
        /** Seconds to wait before the next attempt (0 when not retrying). */
        val waitSec: Int,
        /** 1-based number of the attempt that will run next (0 when not retrying). */
        val attempt: Int,
        val maxAttempts: Int,
        /** Honest note for the transcript when the policy declines to retry. */
        val refusal: String? = null,
    )

    fun decide(
        err: ChatError,
        attemptsUsed: Int,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): Decision {
        val no = { why: String? -> Decision(false, 0, 0, maxAttempts, why) }

        if (err !is ChatError.RateLimited) return no(null)
        if (attemptsUsed >= maxAttempts) {
            return no("Automatic retries used up (" + maxAttempts + "). Press Retry to try again.")
        }
        val providerWait = err.retryAfterSec
        if (providerWait != null && providerWait > MAX_AUTO_WAIT_SEC) {
            return no(
                "The provider asked for " + providerWait + "s. That is too long to wait " +
                    "automatically; press Retry when the wait is over.",
            )
        }
        val wait = when {
            providerWait != null && providerWait > 0 -> providerWait
            else -> FALLBACK_WAITS_SEC.getOrElse(attemptsUsed) { FALLBACK_WAITS_SEC.last() }
        }
        return Decision(retry = true, waitSec = wait, attempt = attemptsUsed + 1, maxAttempts = maxAttempts)
    }
}
