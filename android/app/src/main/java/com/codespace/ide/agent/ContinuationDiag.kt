package com.codespace.ide.agent

/**
 * P4.3: continuation terminal events, in one pure place so the decision is testable without a
 * live ChatSession. The call sites in ChatSession pass whether the turn that just ended was a
 * continuation; an ordinary turn emits nothing, so the log is not flooded and a continuation is
 * never reported merely because it started.
 */
object ContinuationDiag {

    /** A continuation finished with a provider Completed event. */
    fun completed(finishName: String) {
        CopilotDiag.record(CopilotDiag.Kind.CONTINUATION_COMPLETED, "turn", true, finishName)
    }

    /** A continuation ended in a provider failure. Never recorded as completed. */
    fun failed(reason: String) {
        CopilotDiag.record(CopilotDiag.Kind.CONTINUATION_FAILED, "turn", false, reason)
    }

    /** A continuation was stopped by the user or by cancellation. */
    fun cancelled() {
        CopilotDiag.record(CopilotDiag.Kind.CONTINUATION_CANCELLED, "turn", false, "cancelled")
    }

    /** A turn that was NOT a continuation emits nothing. */
    fun none() = Unit
}
