package com.codespace.ide.chat.provider

import com.codespace.ide.agent.SideEffectVerifier

/**
 * PROJECT FIVE, STAGE 5.3 — "did it happen?" before "shall we try again?".
 *
 * A failed provider response is not a failed operation, and this is the type that keeps the two
 * apart. It reads the outcome the executor actually returned plus whatever
 * [SideEffectVerifier] could observe, and answers one question: may this operation be repeated?
 *
 * Nothing here executes anything: the one execution door stays `AgentTools.executeTool`.
 */
object SideEffectRecovery {

    enum class State {
        /** The call was refused before the executor was reached. */
        NEVER_STARTED,
        /** The executor returned, and the effect is verified or there was nothing to change. */
        COMPLETED,
        /** The executor failed and the target verifiably does not hold the change. */
        VERIFIED_FAILED,
        /** The executor did not come back cleanly, so the effect is unknown. Never repeated blindly. */
        UNCERTAIN,
    }

    /**
     * @param readOnly a read cannot leave a side effect, so an uncertain read is safe to repeat.
     * @param verification what [SideEffectVerifier] observed, when it was asked.
     */
    fun state(
        outcome: OutcomeView,
        verification: SideEffectVerifier.Verification? = null,
    ): State {
        if (!outcome.started) return State.NEVER_STARTED
        val verified = verification?.result
        return when {
            outcome.cancelled -> State.UNCERTAIN
            verified == SideEffectVerifier.Result.VERIFIED -> State.COMPLETED
            verified == SideEffectVerifier.Result.REFUTED -> State.VERIFIED_FAILED
            outcome.outcome == ToolOutcome.SUCCESS -> State.COMPLETED
            outcome.outcome == ToolOutcome.UNCERTAIN -> State.UNCERTAIN
            outcome.outcome == ToolOutcome.FAILED && readOnlySafe(outcome.readOnly) -> State.VERIFIED_FAILED
            outcome.outcome == ToolOutcome.FAILED -> State.VERIFIED_FAILED
            else -> State.UNCERTAIN
        }
    }

    /**
     * Whether the operation may be repeated automatically. A read may; a completed or uncertain write
     * may not; a verified failure may, because nothing landed.
     */
    fun mayRetry(state: State, readOnly: Boolean): Boolean = when (state) {
        State.NEVER_STARTED -> true
        State.VERIFIED_FAILED -> true
        State.COMPLETED -> false
        State.UNCERTAIN -> readOnly
    }

    /** The sentence to show when the outcome is genuinely unknown. */
    fun uncertainNote(tool: String): String =
        "The connection ended before $tool could be confirmed. Its result is being checked before " +
            "another attempt, so nothing is retried automatically."

    private fun readOnlySafe(readOnly: Boolean) = readOnly

    /** What the executor reported, without the gateway's own types leaking into callers. */
    data class OutcomeView(
        val started: Boolean,
        val outcome: ToolOutcome?,
        val cancelled: Boolean = false,
        val readOnly: Boolean = false,
    )
}
