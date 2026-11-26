package com.codespace.ide.chat.panel

/**
 * STAGE 5 (Checkpoint 0.5) - a stream that keeps its socket warm but never answers.
 *
 * The transport's read timeout is idle-BETWEEN-BYTES, and an SSE keepalive is a line starting with
 * ':' that the parser swallows without emitting anything. A provider that sends a comment every few
 * seconds therefore holds the socket open for ever: no deltas, no completion, no exception, and the
 * spinner never stops. That is the one stall the existing timeouts cannot see.
 *
 * The rule that fixes it, and the rule VS Code and Cline both use, is progress rather than traffic:
 * only a delta that actually adds visible text counts. A usage-only frame, a keepalive comment and a
 * re-sent identical chunk are all silence as far as this class is concerned.
 *
 * Deliberately generous. A reasoning model can think for a long time before its first token, and a
 * slow provider on a bad connection can take a while between chunks, so the default is two minutes
 * of NO new text - not two minutes of no bytes. Pure and clock-injected, so the policy is testable
 * without a socket or a real delay.
 */
internal class StreamProgressWatchdog(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private var lastProgressMs: Long = 0L
    private var armed: Boolean = false

    /** Called when the attempt starts. Until this happens the watchdog can never fire. */
    fun arm(nowMs: Long) {
        armed = true
        lastProgressMs = nowMs
    }

    /**
     * Called only for MEANINGFUL progress: a delta that added visible text. Returns true when this
     * call is what re-armed the clock, which the caller uses to know a stall was escaped.
     */
    fun noteProgress(nowMs: Long): Boolean {
        if (!armed) return false
        lastProgressMs = nowMs
        return true
    }

    /** True once the attempt has gone [timeoutMs] without a single new visible character. */
    fun expired(nowMs: Long): Boolean =
        armed && nowMs - lastProgressMs >= timeoutMs

    /** Milliseconds since the last meaningful progress, for the diagnostic line. */
    fun silentForMs(nowMs: Long): Long =
        if (!armed || lastProgressMs <= 0L) 0L else (nowMs - lastProgressMs).coerceAtLeast(0L)

    /** Disarmed on every terminal path, so a finished attempt can never be reported as stalled. */
    fun disarm() {
        armed = false
        lastProgressMs = 0L
    }

    companion object {
        /**
         * Two minutes. Chosen from our own transport analysis rather than copied: the socket read
         * timeout is 120 s of byte silence, so the watchdog must not fire EARLIER than that or it
         * would kill streams the transport still considers healthy. It fires on the same span, but
         * on the signal that actually matters.
         */
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
