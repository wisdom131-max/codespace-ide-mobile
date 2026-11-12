package com.codespace.ide.chat.panel

import com.codespace.ide.engine.ChatError
import com.codespace.ide.engine.ProviderRetry

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
     * The one case where the note must be louder than a note: a service write already ran, so
     * pressing Retry re-sends it. The sentence says what happened, not just "are you sure".
     */
    fun externalWriteNote(service: String): String =
        "A " + service + " write already ran in this turn. Retrying resends it; check the service " +
            "before you do, because the first one may have landed."
}
