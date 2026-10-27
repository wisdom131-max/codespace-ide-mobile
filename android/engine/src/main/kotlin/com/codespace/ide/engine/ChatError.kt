package com.codespace.ide.engine

/**
 * Error taxonomy for the rebuilt engine (ports the shipped plain-words
 * verdicts: dedicated 402 out-of-credits branch, 429 Retry-After wait card,
 * honest network classification; the 402/429 wording split shipped as
 * classifyHttpError in the archived chat and is re-established here from
 * scratch as the engine's canonical classifier).
 */
sealed class ChatError(val userVerdict: String, val retryable: Boolean) {

    /** 429: wait card with the provider's Retry-After when present. */
    class RateLimited(val retryAfterSec: Int?) :
        ChatError("The AI provider is busy. Wait ${retryAfterSec ?: "a few seconds and try again."}", true)

    /** 402: plain out-of-credits verdict — never shared with 429 wording. */
    class OutOfCredits(val providerHint: String) :
        ChatError("AI provider out of credits. $providerHint", false)

    class Auth :
        ChatError("AI key rejected. Check the key in Settings.", false)

    class ModelNotFound(val modelId: String) :
        ChatError("Model not found: $modelId. Pick another model.", false)

    class BadRequest(val detail: String) :
        ChatError("The request was rejected: $detail", false)

    class Server(val status: Int) :
        ChatError("The AI provider had a server problem ($status). Try again shortly.", true)

    /** Transport/connection failures, incl. mid-stream drops. */
    class Network(val detail: String) :
        ChatError("Could not reach the AI provider: $detail", true)

    /** Cooperative cancellation — surfaced, never treated as a failure. */
    class Cancelled : ChatError("Stopped.", false)

    /** Over-budget payload refusal (BudgetGuard) — honest, no silent trim. */
    class OverBudget(val detail: String) :
        ChatError("Message too large to send. $detail", false)
}

object ErrorTaxonomy {

    /** Retry-After header lookup by common names (case-insensitive). */
    fun retryAfterSec(headers: Map<String, String>): Int? {
        for ((k, v) in headers) {
            if (k.equals("Retry-After", ignoreCase = true)) {
                return v.trim().toIntOrNull()
            }
        }
        return null
    }

    /**
     * Classify an HTTP response status (+ headers/body) into the taxonomy.
     * Body is only skimmed for the provider hint on 402 and detail on 400;
     * raw body text never reaches the verdict as-is.
     */
    fun classify(status: Int, headers: Map<String, String> = emptyMap(), body: String = ""): ChatError {
        return when {
            status == 429 -> ChatError.RateLimited(retryAfterSec(headers))
            status == 402 -> ChatError.OutOfCredits(providerHint(body))
            status == 401 || status == 403 -> ChatError.Auth()
            status == 404 -> ChatError.ModelNotFound(modelIdHint(body))
            status in 400..499 -> ChatError.BadRequest(brief(body))
            else -> ChatError.Server(status)
        }
    }

    /** Network-layer failures (connect/read timeouts, mid-stream EOF). */
    fun network(detail: String): ChatError = ChatError.Network(brief(detail))

    private fun providerHint(body: String): String {
        val m = Regex("""(out of credits|quota|balance|insufficient[^"{}]*)""", RegexOption.IGNORE_CASE)
            .find(body)?.value ?: return "Check the provider account."
        return brief(m)
    }

    private fun modelIdHint(body: String): String {
        val m = Regex("""model[_ ]?["']?\s*[:=]\s*["']?([A-Za-z0-9._\-/]+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1) ?: return "the selected model"
        return m
    }

    private fun brief(s: String): String {
        val t = s.replace('\n', ' ').trim()
        return if (t.length <= 120) t else t.take(117) + "..."
    }
}
