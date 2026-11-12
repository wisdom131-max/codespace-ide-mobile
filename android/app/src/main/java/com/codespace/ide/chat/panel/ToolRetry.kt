package com.codespace.ide.chat.panel

/**
 * Area 5 - MAY THIS TOOL CALL RUN AGAIN?
 *
 * The dangerous case is not "a tool failed". It is "a side-effecting tool ran and we cannot
 * prove it did not". Retrying that duplicates a write. So the decision is made from two facts
 * only: what we know happened, and whether the tool can change the world.
 *
 * Effect is declared by the caller from the tool registry's own side-effect metadata. It is
 * NEVER inferred from a description: a tool described as "read-only" that shells out is not
 * read-only.
 */
enum class ToolOutcome {
    /** Call was formed but never dispatched. */
    NEVER_STARTED,
    /** Dispatched, failed before the tool body ran (validation, approval refusal, dispatch error). */
    FAILED_BEFORE_EXECUTION,
    /** Body may have run; we have no verdict. */
    UNKNOWN_AFTER_START,
    COMPLETED_OK,
    COMPLETED_ERROR,
    /** Body completed but the provider never received the result. */
    COMPLETED_RESULT_LOST,
    /** A result for this call id is already on disk. */
    RESULT_PERSISTED,
    CANCELLED_BEFORE_EXECUTION,
    CANCELLED_DURING_EXECUTION,
}

enum class ToolEffect { READ_ONLY, SIDE_EFFECTING }

enum class RetryDecision {
    /** Safe to dispatch now. */
    EXECUTE,
    /** Reuse the stored result; do not dispatch. */
    REUSE_PERSISTED,
    /** Ask the world what happened before doing anything else. */
    RECONCILE_FIRST,
    /** Do not dispatch. Surface the uncertainty to the user. */
    REFUSE,
}

object ToolRetry {

    fun decide(outcome: ToolOutcome, effect: ToolEffect): RetryDecision = when (outcome) {
        // Nothing ran. Approval gates still apply to the retry itself.
        ToolOutcome.NEVER_STARTED -> RetryDecision.EXECUTE
        ToolOutcome.FAILED_BEFORE_EXECUTION -> RetryDecision.EXECUTE
        ToolOutcome.CANCELLED_BEFORE_EXECUTION -> RetryDecision.EXECUTE
        ToolOutcome.CANCELLED_DURING_EXECUTION ->
            // Cancellation is NOT evidence that nothing happened.
            if (effect == ToolEffect.READ_ONLY) RetryDecision.EXECUTE else RetryDecision.RECONCILE_FIRST
        ToolOutcome.UNKNOWN_AFTER_START ->
            if (effect == ToolEffect.READ_ONLY) RetryDecision.EXECUTE else RetryDecision.RECONCILE_FIRST
        ToolOutcome.RESULT_PERSISTED -> RetryDecision.REUSE_PERSISTED
        ToolOutcome.COMPLETED_OK ->
            if (effect == ToolEffect.READ_ONLY) RetryDecision.REUSE_PERSISTED else RetryDecision.REUSE_PERSISTED
        ToolOutcome.COMPLETED_ERROR ->
            if (effect == ToolEffect.READ_ONLY) RetryDecision.EXECUTE else RetryDecision.REFUSE
        ToolOutcome.COMPLETED_RESULT_LOST ->
            if (effect == ToolEffect.READ_ONLY) RetryDecision.EXECUTE else RetryDecision.RECONCILE_FIRST
    }

    /**
     * True when repeating this call could apply a side effect twice. Used by the continuation
     * path, which must never re-run a side-effecting call merely because the answer was cut off.
     */
    fun dangerousToRepeat(outcome: ToolOutcome, effect: ToolEffect): Boolean =
        effect == ToolEffect.SIDE_EFFECTING && decide(outcome, effect) != RetryDecision.REUSE_PERSISTED
}
