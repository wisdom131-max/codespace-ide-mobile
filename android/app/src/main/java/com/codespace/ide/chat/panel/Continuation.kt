package com.codespace.ide.chat.panel

import com.codespace.ide.engine.FinishReason

/**
 * Area 6 - CONTINUING A CUT-OFF ANSWER.
 *
 * Continuation is a new request that carries the identity of the turn it continues, so a late
 * event from the first attempt is dropped by [RequestGate] and an older continuation can never
 * overwrite a newer turn.
 *
 * What it must NOT do: repeat the answer. The prompt below states the boundary explicitly and
 * the already-delivered text is echoed back as context, not as something to re-say.
 */
enum class ContinuationState {
    COMPLETE,
    NEEDS_CONTINUATION,
    CONTINUING,
    CONTINUED,
    CONTINUATION_FAILED,
    /** Provider stopped for a reason we cannot continue around (filter, transport, silence). */
    UNKNOWN_OR_INTERRUPTED,
}

data class ContinuationPlan(
    val instruction: String,
    val state: ContinuationState,
    /** Text carried forward for context, tail-bounded. Never re-emitted as new content. */
    val carriedContext: String,
    val allowed: Boolean,
)

object Continuation {

    /** How much of the tail travels as context. Bounded: the point is to join the seam. */
    const val TAIL_CHARS = 1200

    /** Ask/Edit get a tighter carry than Agent, mirroring their prompt budgets. */
    fun tailBudgetFor(mode: String): Int = when (mode.lowercase()) {
        "ask" -> 600
        "edit" -> 900
        else -> TAIL_CHARS
    }

    fun plan(
        finish: FinishReason,
        deliveredText: String,
        mode: String = "agent",
        providerSupportsContinuation: Boolean = true,
    ): ContinuationPlan {
        val tail = deliveredText.takeLast(tailBudgetFor(mode))
        return when {
            finish.complete -> ContinuationPlan(
                instruction = "",
                state = ContinuationState.COMPLETE,
                carriedContext = "",
                allowed = false,
            )
            !finish.continuable -> ContinuationPlan(
                instruction = "",
                state = ContinuationState.UNKNOWN_OR_INTERRUPTED,
                carriedContext = tail,
                allowed = false,
            )
            !providerSupportsContinuation -> ContinuationPlan(
                // Honest: this provider cannot be asked to carry on.
                instruction = "The previous answer was cut off by the output limit. Ask the user to send \"continue\".",
                state = ContinuationState.NEEDS_CONTINUATION,
                carriedContext = tail,
                allowed = false,
            )
            else -> ContinuationPlan(
                instruction = buildString {
                    append("The previous response was stopped by the provider's output limit at a ")
                    append("point where the answer was not finished.\n\n")
                    append("Continue from exactly that point. Do not repeat, restate or summarise ")
                    append("anything already delivered, and do not re-open the answer with a ")
                    append("heading or a greeting. Do not run any tool call that has already run ")
                    append("in this turn: reuse its result if you need it again.\n\n")
                    append("The text already delivered ends with:\n<<<\n")
                    append(tail)
                    append("\n>>>")
                },
                state = ContinuationState.CONTINUING,
                carriedContext = tail,
                allowed = true,
            )
        }
    }

    /**
     * MAY A CONTINUE START RIGHT NOW?
     *
     * Two ways to double-answer: the previous stream is still running, or a continuation was already
     * sent and has not produced its first event yet. The second is invisible to `streaming`, which is
     * exactly the window a double tap lands in.
     */
    fun mayStart(streaming: Boolean, inFlight: Boolean): Boolean = !streaming && !inFlight
}
