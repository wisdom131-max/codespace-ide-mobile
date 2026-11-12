package com.codespace.ide.chat.panel

/**
 * Area 5 - WHICH REQUEST OWNS THE STREAM.
 *
 * A turn gets a number when it starts. Every event that arrives carries the number it was
 * started with, and an event whose number is not the current one is DROPPED. That single rule
 * covers the cases that used to leak into the transcript:
 *
 *  - Stop pressed, but the provider already had bytes in flight: they arrive stale.
 *  - A new send replaces an old one: the old stream must not append to the new answer.
 *  - A 429 retry armed a minute ago waking up after the user moved on.
 *  - Session switched away: [stopForSwitch] bumps first, so nothing lands afterwards.
 *
 * Deliberately pure and tiny: this is the rule, asserted by RequestGateTest, and ChatSession
 * only has to ask it.
 */
internal class RequestGate(initial: Int = 0) {

    @Volatile
    var current: Int = initial
        private set

    /** Starts a new turn, or invalidates the running one. Returns the number to carry. */
    fun bump(): Int {
        current += 1
        return current
    }

    /** True when [gen] still owns the stream. An unnumbered event (0) never owns anything. */
    fun owns(gen: Int): Boolean = gen != 0 && gen == current

    /** True when an event numbered [gen] must be discarded. */
    fun stale(gen: Int): Boolean = !owns(gen)
}
