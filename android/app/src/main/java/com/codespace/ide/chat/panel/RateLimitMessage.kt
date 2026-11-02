package com.codespace.ide.chat.panel

/**
 * The 429 sentence, copied from the product this app is measured against.
 *
 * Source studied: microsoft/vscode-copilot-chat `src/platform/chat/common/commonTypes.ts`,
 * `getRateLimitMessage()`, and `src/util/common/time.ts`, `secondsToHumanReadableTime()`.
 *
 * Two facts decided this file:
 *
 * 1. VS Code does NOT retry a 429 automatically. `chatMLFetcher.ts` retries only the statuses in
 *    `chat.advanced.retryServerErrorStatusCodes`, whose default is "500,502". A 429 comes back as
 *    ChatFetchResponseType.RateLimited and the turn ends as an error.
 * 2. It is still surfaced as prose, not as a countdown widget: the wait is humanised and put in a
 *    sentence. So this app's earlier "Rate limited (429), retrying in Ns" card was wrong in both
 *    directions - it retried when the reference does not, and it counted down where the reference
 *    writes a sentence.
 *
 * The one deliberate deviation: VS Code's copies end with "or consider switching to Auto", because
 * it has an Auto model. This app has no Auto, so the isAuto wording is used instead - the same
 * sentence minus advice the user cannot follow. "Auto" would be a lie, not parity.
 */
internal object RateLimitMessage {

    /** VS Code uses this link in every rate-limit copy. */
    const val LEARN_MORE = "https://aka.ms/github-copilot-rate-limit-error"

    /**
     * `secondsToHumanReadableTime` from `src/util/common/time.ts`, thresholds and all.
     *
     * Under 90 seconds stays in seconds; up to 90 minutes becomes minutes; beyond that, hours and
     * minutes. The upstream pluralisation is kept exactly ("1 minutes"), because a copy that reads
     * differently from the reference is no longer the reference.
     */
    fun humanize(seconds: Int): String {
        val s = if (seconds < 0) 0 else seconds
        if (s < 90) return s.toString() + " seconds"
        val minutes = s / 60
        if (s <= 5400) return minutes.toString() + " minutes"
        val hours = minutes / 60
        val remaining = minutes % 60
        var result = hours.toString() + " hours"
        if (remaining > 0) result = result + " " + remaining + " minutes"
        return result
    }

    /**
     * The generic rate-limit copy. VS Code falls back to this when the provider sent no CAPI error
     * code, which is the case for every BYOK endpoint - and every endpoint in this app is BYOK.
     */
    fun detail(retryAfterSec: Int?): String {
        val wait = if (retryAfterSec == null || retryAfterSec <= 0) "a moment" else humanize(retryAfterSec)
        return "Sorry, your request was rate-limited. Please wait " + wait +
            " before trying again. [Learn More](" + LEARN_MORE + ")"
    }

    /** The heading above [detail]; the reference has no title, so this stays two words. */
    const val VERDICT = "Rate limited"
}
