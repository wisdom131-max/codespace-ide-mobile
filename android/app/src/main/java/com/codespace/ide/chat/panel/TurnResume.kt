package com.codespace.ide.chat.panel

/**
 * PHASE 2b (owner decision 3, 2026-11-14): a turn that died with the process gets an explicit
 * Resume, and nothing is resent on the user's behalf.
 *
 * The distinction that matters: an OUTPUT-LIMIT cut-off offers Continue ([ContinuationState]),
 * because the provider stopped talking and can be asked to keep going. A turn killed by process
 * death is different - the app owes an acknowledgement and an action, never a silent replay of
 * the request that would run its side effects a second time.
 *
 * Pure, so the rule is tested without a session, a store or Compose.
 */
internal object TurnResume {

    enum class Action { NONE, OFFER_RESUME }

    /**
     * @param lastAssistantComplete the stored `complete` flag of the last assistant message;
     *   null when the conversation has no assistant message at all.
     */
    fun afterRestore(lastAssistantComplete: Boolean?): Action =
        if (lastAssistantComplete == false) Action.OFFER_RESUME else Action.NONE

    /** A Resume must not start while a turn is already running, or it becomes a second turn. */
    fun canResume(action: Action, streaming: Boolean): Boolean =
        action == Action.OFFER_RESUME && !streaming

    const val TITLE: String =
        "This reply was cut short when the app closed. Nothing has been resent."

    const val BUTTON: String = "Resume"
}
