package com.codespace.ide.engine

/**
 * A turn's progress as an APPEND-ONLY list with an applied index and a completion flag, instead of a
 * single "streaming" boolean (owner request, 2026-11-23; the stuck Stop button).
 *
 * Why the shape matters: a boolean cannot say "the model finished, but a follow-up dispatch is still
 * running". Whichever side of that race loses, the button sticks. VS Code's chat service tracks a
 * progress array plus `lastProgressLength` plus `isComplete` for exactly this reason, and the state
 * here is the same three pieces.
 */
class TurnProgress {

    data class Snapshot(
        val items: List<String>,
        val applied: Int,
        val complete: Boolean,
        val pending: List<String>,
    )

    private val items = mutableListOf<String>()
    private var applied = 0
    private var complete = false

    /** A turn is running while it is not complete. Derived, never stored separately. */
    val streaming: Boolean get() = !complete

    fun start() {
        complete = false
    }

    /** Adds one progress line and returns its index. */
    fun append(item: String): Int {
        items.add(item)
        return items.size - 1
    }

    /** The lines the view has not applied yet, oldest first. */
    fun pending(): List<String> = items.drop(applied)

    /** Marks everything appended so far as applied; returns how many lines that was. */
    fun markApplied(): Int {
        val n = items.size - applied
        applied = items.size
        return n
    }

    /**
     * Ends the turn. Safe to call twice: the second call changes nothing, which is what makes it
     * usable from a finally block without guarding the call site.
     */
    fun finish() {
        applied = items.size
        complete = true
    }

    fun snapshot(): Snapshot = Snapshot(items.toList(), applied, complete, pending())
}
