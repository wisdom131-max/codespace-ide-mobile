package com.codespace.ide.agent.runtime

/**
 * PROJECT THREE RUNTIME (2026-11-16): the mode the session is actually in.
 *
 * The recovery planner refuses to apply anything in Ask, because Ask is read-only by definition. To
 * make that refusal mean something at runtime, the dispatch path needs to know which mode is in
 * force. The chat surface claims it once when the mode changes, exactly like [com.codespace.ide.agent.goal.PlanState]
 * claims the session.
 *
 * Defaults to ASK, deliberately: a dispatch that happens before any surface has claimed a mode is
 * treated as the most restrictive one. A missing claim must never be the permissive answer.
 */
internal object RuntimeSession {

    const val MODE_ASK = "ASK"
    const val MODE_EDIT = "EDIT"
    const val MODE_AGENT = "AGENT"

    @Volatile
    private var claimed: String = MODE_ASK

    fun claimMode(mode: String) {
        claimed = when (mode.trim().uppercase()) {
            MODE_AGENT -> MODE_AGENT
            MODE_EDIT -> MODE_EDIT
            else -> MODE_ASK
        }
    }

    fun mode(): String = claimed

    /** The planner's own vocabulary, so the bridge does not invent a second one. */
    fun healMode(): com.codespace.ide.agent.heal.RecoveryPlanner.Mode = when (claimed) {
        MODE_AGENT -> com.codespace.ide.agent.heal.RecoveryPlanner.Mode.AGENT
        MODE_EDIT -> com.codespace.ide.agent.heal.RecoveryPlanner.Mode.EDIT
        else -> com.codespace.ide.agent.heal.RecoveryPlanner.Mode.ASK
    }
}
