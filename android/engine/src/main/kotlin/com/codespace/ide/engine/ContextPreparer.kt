package com.codespace.ide.engine

/**
 * PROJECT SIX, STAGE 6.1 (and the landing place for Project Five 5.4 Area E).
 *
 * Area E of Project Five asked for provider-aware context compatibility but explicitly did not
 * implement reduction; this is where that work belongs, and the relationship is deliberate:
 * Project Five BUILT the exchange history that must survive reduction (call/result pairs with ids and
 * statuses); Project Six decides how much of a conversation can be sent without breaking that history
 * or lying about what was left behind. Neither project may trim a tool pair apart.
 *
 * The rules this type exists to enforce:
 *  - A limit is used only when the catalogue actually knows it. An unknown model is reported as
 *    unknown, and NOTHING is trimmed on a guess.
 *  - An output-token limit is never treated as an input-context limit.
 *  - Reduction is reported, never silent: the caller gets counts and a sentence it can show.
 *  - The estimate is named as an estimate, with the constant visible, so nobody reads a char
 *    heuristic as a token count.
 */
object ContextPreparer {

    /** Chars per token. A heuristic, stated rather than hidden: English code and prose both sit near 3.5-4. */
    const val CHARS_PER_TOKEN_ESTIMATE = 4

    /** Room kept back for the system prompt, the workspace block and the new user turn. */
    const val RESERVE_FRACTION = 0.25

    enum class Verdict {
        /** Everything the mode wanted fits inside a known limit. */
        FITS,
        /** Older turns were dropped to fit, and the caller says how many. */
        REDUCED,
        /** The model's limit is not known, so no reduction was performed on a guess. */
        LIMIT_UNKNOWN,
        /** Even the newest turn cannot fit. The request should not be sent silently. */
        OVERFLOW,
    }

    data class Decision(
        val verdict: Verdict,
        val limitKnown: Boolean,
        val contextTokens: Int?,
        val inputTokensBudget: Int?,
        val estimatedTokens: Int,
        val droppedMessages: Int,
        val droppedChars: Int,
        /** One sentence for the user, or null when nothing needs saying. */
        val notice: String?,
        /** True when the request must not be sent as-is. */
        val blocking: Boolean = false,
    )

    /**
     * Decides what will be sent, from the model's real capability and the budget the caller already
     * computed. [droppedMessages]/[droppedChars] come from [HistoryBudget.fit]; this type does not
     * perform the trimming itself, it explains it and refuses to fake certainty.
     */
    fun decide(
        capability: ModelCapability?,
        charsInWindow: Int,
        droppedMessages: Int,
        droppedChars: Int,
        maxOutputTokens: Int = 0,
    ): Decision {
        val contextTokens = capability?.contextTokens?.takeIf { it > 0 }
        val estimatedTokens = charsInWindow / CHARS_PER_TOKEN_ESTIMATE
        if (contextTokens == null) {
            val notice = if (droppedMessages == 0) null else
                "This model's context window is not known, so older turns were shortened by the history " +
                    "setting (" + droppedMessages + " message(s) left out). Nothing was trimmed on a guess " +
                    "about the limit."
            return Decision(
                verdict = Verdict.LIMIT_UNKNOWN,
                limitKnown = false,
                contextTokens = null,
                inputTokensBudget = null,
                estimatedTokens = estimatedTokens,
                droppedMessages = droppedMessages,
                droppedChars = droppedChars,
                notice = notice,
            )
        }
        // The output allowance is part of the window but NOT part of the input budget: confusing the
        // two is how a request gets rejected as "too long" while the app insists it fits.
        val reserve = (contextTokens * RESERVE_FRACTION).toInt()
        val inputBudget = (contextTokens - maxOutputTokens.coerceAtLeast(0) - reserve).coerceAtLeast(0)
        val verdict = when {
            estimatedTokens > inputBudget -> Verdict.OVERFLOW
            droppedMessages > 0 -> Verdict.REDUCED
            else -> Verdict.FITS
        }
        val notice = when (verdict) {
            Verdict.OVERFLOW ->
                "This conversation is larger than the model's known context window (" +
                    contextTokens + " tokens), so it was not sent. Start a new session or shorten the " +
                    "history setting; nothing was silently cut."
            Verdict.REDUCED ->
                droppedMessages.toString() + " older message(s) were left out to fit this model's " +
                    "known context window (" + contextTokens + " tokens)."
            else -> null
        }
        return Decision(
            verdict = verdict,
            limitKnown = true,
            contextTokens = contextTokens,
            inputTokensBudget = inputBudget,
            estimatedTokens = estimatedTokens,
            droppedMessages = droppedMessages,
            droppedChars = droppedChars,
            notice = notice,
            blocking = verdict == Verdict.OVERFLOW,
        )
    }
}
