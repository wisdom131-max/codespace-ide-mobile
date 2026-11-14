package com.codespace.ide.chat.panel

/**
 * PHASE 2 (owner approval 2026-11-14): what happens when the last chat window closes.
 *
 * The panel used to release the runtime on dispose and the runtime closed the session manager,
 * which cancelled the turn: closing chat killed the answer. A stream belongs to the conversation,
 * not to the view showing it, so a close with work in flight PARKS the manager instead of closing
 * it, and the parked manager is closed as soon as the last turn finishes with nobody watching.
 *
 * Pure on purpose: the decision is testable without a Context, a Handler or a coroutine.
 */
internal object RuntimeLifecycle {

    enum class Action { KEEP, PARK, CLOSE }

    /**
     * @param remainingHosts windows still holding the runtime after this release.
     * @param anyStreaming true while any cached conversation still has a turn in flight.
     */
    fun onRelease(remainingHosts: Int, anyStreaming: Boolean): Action = when {
        remainingHosts > 0 -> Action.KEEP
        anyStreaming -> Action.PARK
        else -> Action.CLOSE
    }

    /** True when a parked runtime may now be closed: nobody watching and nothing streaming. */
    fun canCloseWhileParked(remainingHosts: Int, anyStreaming: Boolean): Boolean =
        remainingHosts <= 0 && !anyStreaming
}
