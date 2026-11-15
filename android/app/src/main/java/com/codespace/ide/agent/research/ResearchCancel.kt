package com.codespace.ide.agent.research

import java.util.concurrent.atomic.AtomicInteger

/**
 * PROJECT 1.9 (2026-11-15): Stop must stop the spending too.
 *
 * Stop cancels the turn's coroutine, but a research round is blocking socket reads: the coroutine
 * stops awaiting while the connection finishes on its own schedule, and the two subquestion
 * searches queued behind it would still go out. On a phone every one of those is the owner's
 * mobile data, so the loop asks a generation counter before each request whether the turn that
 * started it is still wanted.
 *
 * A counter rather than a boolean, because the next turn must not inherit a stale "cancelled".
 */
object ResearchCancel {

    private val generation = AtomicInteger(0)

    /** The current turn was cancelled. */
    fun bump() {
        generation.incrementAndGet()
    }

    /** Read once at the start of a research round. */
    fun generation(): Int = generation.get()

    /** Whether the round that started at [startedAt] may still spend a request. */
    fun stillWanted(startedAt: Int): Boolean = generation.get() == startedAt
}
