package com.codespace.ide.chat.panel

/**
 * DUPLICATE REPLY PREVENTION - WHO MAY WRITE THE ASSISTANT'S ANSWER.
 *
 * Investigated 2026-11-12 against the real lifecycle (stream deltas -> Completed -> rows ->
 * persistence -> restore). [RequestGate] already drops events from a superseded request, which
 * covers Stop, session switching and an armed 429 retry. Four gaps remained, and they are all about
 * the ASSISTANT bubble rather than the user's:
 *
 *  1. a second send started while the first request is still streaming left two live attempts
 *     appending deltas, so the answer appeared twice;
 *  2. a provider that repeats its completion event re-ran the completion handler;
 *  3. a failover attempt re-running the same user text added a second user bubble;
 *  4. restoration could land on top of a stream that had already written rows.
 *
 * The rules below are the ones ChatSession must ask. Kept pure so they can be tested without an
 * Android runtime, and deliberately conservative: it never merges two DIFFERENT replies, because a
 * genuine retry after a failure must still produce its own answer.
 */
internal object ReplyOwnership {

    /** What a send should do when it is asked to start. */
    enum class Start { FRESH, REPLACE_RUNNING, IGNORE_DUPLICATE_TAP }

    /**
     * A send arriving while a request is running.
     *
     * @param sameText true when the request is identical to the one already streaming.
     * @param elapsedMs how long the running request has been going.
     *
     * An identical request inside [DOUBLE_TAP_MS] is a double tap on Send, not a decision to ask
     * twice, so it is ignored. Anything older, or any different text, is a real second request: the
     * running one is replaced, which is what the user sees as "my new message wins". Two identical
     * messages a minute apart are two questions and both are answered.
     */
    const val DOUBLE_TAP_MS = 1_500L

    fun startAction(running: Boolean, sameText: Boolean, elapsedMs: Long): Start = when {
        !running -> Start.FRESH
        sameText && elapsedMs in 0..DOUBLE_TAP_MS -> Start.IGNORE_DUPLICATE_TAP
        else -> Start.REPLACE_RUNNING
    }

    /**
     * Whether a completion event may be applied. A provider that sends Completed twice for the same
     * request would otherwise run the tool/continuation handler twice and, on some providers, append
     * the final text a second time.
     */
    fun mayComplete(gen: Int, lastCompletedGen: Int, owns: Boolean): Boolean =
        owns && gen != lastCompletedGen

    /**
     * Whether a user bubble should be added for this send. A failover retry and a continuation are
     * the same question, so neither adds a bubble; a genuine second question does.
     */
    fun addsUserBubble(isContinuation: Boolean, isFailoverRetry: Boolean, isFreshQuestion: Boolean): Boolean =
        isFreshQuestion && !isContinuation && !isFailoverRetry

    /**
     * Whether a restore may replace the rows now on screen. A live stream already writing rows owns
     * the transcript; the stored copy is older than what the user is watching.
     */
    fun mayApplyRestore(restoreFinished: Boolean, streaming: Boolean, currentRowCount: Int, storedCount: Int): Boolean =
        restoreFinished && !streaming && (currentRowCount == 0 || storedCount > currentRowCount)
}
