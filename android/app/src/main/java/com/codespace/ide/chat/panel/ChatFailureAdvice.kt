package com.codespace.ide.chat.panel

import com.codespace.ide.engine.ChatError
import com.codespace.ide.engine.ProviderErrorFacts
import com.codespace.ide.engine.ProviderRetry
import com.codespace.ide.engine.RecoveryAction
import com.codespace.ide.engine.RecoveryPlan
import com.codespace.ide.engine.RecoveryPlanner

/**
 * Item 36 - WHAT A FAILURE MEANS FOR THE NEXT ATTEMPT, in the user's own words.
 *
 * The engine already renders the provider's verdict. What was missing is the second sentence a user
 * actually needs: "is pressing Retry worth it?" Today the button is always offered, including for
 * failures that will fail identically (a rejected key, a model this endpoint does not serve, a
 * payload that is too large). Offering a retry that cannot work is not neutral: it turns one clear
 * error into a loop.
 *
 * This is deliberately NOT an automatic retry ladder. The owner ruled on 2026-11-01 that VN Code
 * matches VS Code here: render the error, offer Retry, resend only when it is pressed. So the policy
 * decides what to TELL the user, never to send on its own - [ProviderRetry] is consulted, its
 * decision is not acted on silently.
 *
 * Null means "nothing to add": a retryable failure keeps the plain row, because the user may well
 * have fixed the cause (a key replaced, a model renamed) and a warning would be noise.
 */
object ChatFailureAdvice {

    /**
     * @param attempt the retry that is being considered. 1 = the first press after the failure.
     */
    fun noteFor(err: ChatError, attempt: Int = 1, externalWriteDone: Boolean = false): String? {
        val decision = ProviderRetry.decide(err, attempt = attempt, sideEffectDone = externalWriteDone)
        if (decision.retry) return null
        return decision.why
    }

    /**
     * STAGE 5.3: the same question, answered with the provider error model.
     *
     * [ProviderRetry] can only ask "is this retryable", because [ChatError] carries no status, code or
     * provider type. Where the verdict itself implies those facts (a 429 with its Retry-After, an
     * out-of-credits 402, a rejected key 401) they are handed to [RecoveryPlanner], which can tell a
     * quota exhaustion from a temporary limit, and a context overrun from a malformed request. The
     * result is still only a sentence: nothing is sent on the user's behalf.
     */
    fun adviceFor(
        err: ChatError,
        attempt: Int = 1,
        externalWriteDone: Boolean = false,
        alternativeModel: String? = null,
    ): String? {
        val facts = factsFor(err) ?: return noteFor(err, attempt, externalWriteDone)
        val plan = RecoveryPlanner.plan(
            facts, attempt,
            sideEffectPossible = externalWriteDone,
            alternativeModel = alternativeModel,
        )
        return when (plan.action) {
            RecoveryAction.USER_ACTION_REQUIRED,
            RecoveryAction.CONFIGURATION_REQUIRED,
            RecoveryAction.CONTEXT_REDUCTION_REQUIRED,
            RecoveryAction.ALTERNATIVE_MODEL_AVAILABLE,
            RecoveryAction.OPERATION_OUTCOME_UNKNOWN,
            RecoveryAction.PERMANENT_FAILURE,
            -> plan.why
            RecoveryAction.RETRY_SAFE, RecoveryAction.WAIT_AND_RETRY ->
                // The plain row is kept for a failure the user can simply retry.
                if (plan.retryable) null else plan.why
            RecoveryAction.CANCELLED -> null
        }
    }

    /** The facts a verdict already proves, or null when it proves none. */
    private fun factsFor(err: ChatError): ProviderErrorFacts? = err.facts ?: when (err) {
        is ChatError.RateLimited -> ProviderErrorFacts(status = 429, retryAfterSec = err.retryAfterSec)
        is ChatError.OutOfCredits -> ProviderErrorFacts(status = 402, code = "insufficient_quota")
        is ChatError.Auth -> ProviderErrorFacts(status = 401, message = err.detail)
        else -> null
    }

    /**
     * The full decision, for callers that want the action and not only the sentence. The engine's
     * facts win; only a verdict with no evidence falls back to what the verdict itself proves.
     */
    fun planFor(
        err: ChatError,
        attempt: Int = 1,
        externalWriteDone: Boolean = false,
        alternativeModel: String? = null,
    ): RecoveryPlan? {
        val facts = factsFor(err) ?: return null
        return RecoveryPlanner.plan(facts, attempt, externalWriteDone, alternativeModel)
    }

    /**
     * The one case where the note must be louder than a note: a service write already ran, so
     * pressing Retry re-sends it. The sentence says what happened, not just "are you sure".
     */
    fun externalWriteNote(service: String): String =
        "A " + service + " write already ran in this turn. Retrying resends it; check the service " +
            "before you do, because the first one may have landed."
}
