package com.codespace.ide.engine

/**
 * Error taxonomy for the rebuilt engine (ports the shipped plain-words
 * verdicts: dedicated 402 out-of-credits branch, 429 Retry-After wait card,
 * honest network classification; the 402/429 wording split shipped as
 * classifyHttpError in the archived chat and is re-established here from
 * scratch as the engine's canonical classifier).
 */
sealed class ChatError(val userVerdict: String, val retryable: Boolean) {

    /**
     * 429: wait card with the provider's Retry-After when present. The earlier copy said
     * "Wait 30" - the raw number, no unit - whenever the header was present.
     */
    class RateLimited(val retryAfterSec: Int?) :
        ChatError(
            if (retryAfterSec != null && retryAfterSec > 0)
                "The AI provider is busy. Try again in $retryAfterSec seconds."
            else
                "The AI provider is busy. Try again in a few seconds.",
            true,
        )

    /** 402: plain out-of-credits verdict — never shared with 429 wording. */
    class OutOfCredits(val providerHint: String) :
        ChatError("AI provider out of credits. $providerHint", false)

    class Auth :
        ChatError("AI key rejected. Check the key in Settings.", false)

    /**
     * 404. Two very different causes share this status: the model id is not served by this
     * endpoint, or the endpoint path itself is wrong (a base URL missing /v1 was the
     * classic one). The verdict names the requested PATH so the user can tell which -
     * path only, never the query string, because a key can live there.
     */
    class ModelNotFound(val modelId: String, val endpointPath: String? = null) :
        ChatError(
            if (endpointPath.isNullOrBlank())
                "Model not found: $modelId. Pick another model."
            else
                "Model not found: $modelId - or that path is wrong. The request went to " +
                    "$endpointPath. Check the model id and the endpoint's base URL.",
            false,
        )

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

    /**
     * Retry-After header lookup by common names (case-insensitive).
     *
     * RFC 7231 allows TWO forms and providers use both: delta-seconds ("30") and an HTTP-date
     * ("Wed, 21 Oct 2026 07:28:00 GMT"). Only the first was understood, so every date-form header
     * silently became "no wait given" and the card asked for a wait it did not name. A date in the
     * past is a zero wait, not a missing one.
     */
    fun retryAfterSec(headers: Map<String, String>, nowMs: Long = System.currentTimeMillis()): Int? {
        for ((k, v) in headers) {
            if (!k.equals("Retry-After", ignoreCase = true)) continue
            val raw = v.trim()
            raw.toIntOrNull()?.let { return if (it < 0) 0 else it }
            val at = parseHttpDate(raw) ?: continue
            val delta = ((at - nowMs) / 1000L).toInt()
            return if (delta < 0) 0 else delta
        }
        return null
    }

    private fun parseHttpDate(value: String): Long? =
        HTTP_DATE_FORMATS.firstNotNullOfOrNull { fmt ->
            try {
                fmt.parse(value)?.time
            } catch (_: java.text.ParseException) {
                null
            }
        }

    private val HTTP_DATE_FORMATS = listOf(
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US),
        java.text.SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss zzz", java.util.Locale.US),
        java.text.SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", java.util.Locale.US),
    ).onEach { it.isLenient = false }

    /**
     * Classify an HTTP response status (+ headers/body) into the taxonomy.
     * Body is only skimmed for the provider hint on 402 and detail on 400;
     * raw body text never reaches the verdict as-is.
     */
    fun classify(
        status: Int,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        endpointPath: String? = null,
    ): ChatError {
        return when {
            status == 429 -> ChatError.RateLimited(retryAfterSec(headers))
            status == 402 -> ChatError.OutOfCredits(providerHint(body))
            status == 401 || status == 403 -> ChatError.Auth()
            status == 404 -> ChatError.ModelNotFound(modelIdHint(body), endpointPath)
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
        // Handles JSON ("model": "gpt-4"), key=value (model=gpt-4) AND prose
        // error messages like: model 'gemini-x' not found. The separator is
        // optional so the quoted-prose form also matches.
        val m = Regex("""model["']?\s*[:=]?\s*["']?([A-Za-z0-9._\-/]+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1) ?: return "the selected model"
        return m
    }

    private fun brief(s: String): String {
        val t = s.replace('\n', ' ').trim()
        return if (t.length <= 120) t else t.take(117) + "..."
    }
}
