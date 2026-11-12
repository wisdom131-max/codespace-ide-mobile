package com.codespace.ide.engine

/**
 * Item 36 - WHEN TO TRY AGAIN, decided once, in one place.
 *
 * The engine already knows whether a failure was retryable ([ChatError.retryable]) and whether a
 * stream was cut short ([FinishReason.INTERRUPTED]). What it did not have is a policy: the answer
 * to "again, or not?" was spread across the call site, which is how a rate limit becomes a busy
 * loop and how a completed tool call becomes a duplicated one.
 *
 * Three rules, in the order they are applied:
 *
 *  1. **A side effect already happened, so no.** If the turn executed a tool that changed
 *     something outside the app - a write, a push, a service call - a retry would run the same
 *     request again. The failure is reported instead. This is the rule that has to win over every
 *     other rule, because it is the only one whose mistake is invisible to the user.
 *  2. **The provider said no, so no.** A non-retryable error (bad key, model not served, out of
 *     credits, payload too large) does not become retryable by waiting.
 *  3. **Otherwise, bounded backoff.** At most [MAX_ATTEMPTS] tries, a growing delay, and a
 *     `Retry-After` from the provider honoured when it is short enough to wait for.
 *
 * Pure: no clock, no sleep, no network. It returns a decision; the caller waits. That is what makes
 * the policy testable with no provider stubbed at all.
 */
object ProviderRetry {

    const val MAX_ATTEMPTS = 3
    const val BASE_DELAY_MS = 500L
    const val MAX_DELAY_MS = 8_000L

    /** Longer than this and waiting is worse than telling the user to come back. */
    const val MAX_HONOURED_RETRY_AFTER_SEC = 60

    data class Decision(
        val retry: Boolean,
        val delayMs: Long,
        /** One sentence, shown to the user when [retry] is false. Never a raw provider body. */
        val why: String,
    )

    /**
     * @param error the classified failure, or null when the stream simply stopped
     *   ([FinishReason.INTERRUPTED] with no error body - a dropped connection).
     * @param attempt 1 for the first retry after the original request.
     * @param sideEffectDone true when a tool in this turn already changed something outside the app.
     */
    fun decide(
        error: ChatError?,
        attempt: Int,
        sideEffectDone: Boolean,
    ): Decision {
        if (sideEffectDone) {
            return Decision(
                retry = false,
                delayMs = 0,
                why = "A tool in this turn already changed something, so it is not retried automatically: " +
                    "check the result before asking again.",
            )
        }
        if (attempt < 1) return Decision(false, 0, "Nothing to retry.")
        if (attempt >= MAX_ATTEMPTS) {
            return Decision(
                retry = false,
                delayMs = 0,
                why = "Gave up after " + MAX_ATTEMPTS + " attempts." + (error?.let { " " + it.userVerdict } ?: ""),
            )
        }
        if (error != null && !error.retryable) {
            return Decision(retry = false, delayMs = 0, why = error.userVerdict)
        }
        // A dropped stream with no error body is a transport interruption: retry it, because no
        // provider verdict was received.
        val askedWait = (error as? ChatError.RateLimited)?.retryAfterSec ?: 0
        if (askedWait > MAX_HONOURED_RETRY_AFTER_SEC) {
            return Decision(
                retry = false,
                delayMs = 0,
                why = "The provider asked for a wait of " + askedWait + " seconds. That is too long to hold " +
                    "the message open; send it again when the wait is over.",
            )
        }
        val delay = when {
            askedWait > 0 -> askedWait * 1000L
            else -> backoff(attempt)
        }
        return Decision(retry = true, delayMs = delay, why = "Retrying")
    }

    /** 500 ms, 1 s, 2 s, 4 s, capped. Deterministic on purpose: a jittered delay is untestable. */
    fun backoff(attempt: Int): Long {
        var ms = BASE_DELAY_MS
        var i = 1
        while (i < attempt) {
            ms *= 2
            if (ms >= MAX_DELAY_MS) return MAX_DELAY_MS
            i++
        }
        return ms
    }

    /**
     * Whether a finished turn may be retried at all, from the verdict alone. A ceiling is
     * CONTINUED (the answer exists, it is just incomplete), not retried - retrying a ceiling throws
     * away the part the user can already read.
     */
    fun retryableVerdict(finish: FinishReason): Boolean = when (finish) {
        FinishReason.INTERRUPTED, FinishReason.UNKNOWN -> true
        FinishReason.NATURAL, FinishReason.STOP_SEQUENCE, FinishReason.TOOL_CALLS -> false
        FinishReason.OUTPUT_LIMIT -> false
        FinishReason.CONTENT_FILTER, FinishReason.CANCELLED -> false
    }
}
