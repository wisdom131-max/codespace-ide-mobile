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

    /**
     * 401/403. P0-1 (owner report 2026-11-08: "AI key rejected. Check the key in the settings" on
     * EVERY message, while Settings > Test listed the models fine): the shipped verdict named
     * neither the provider nor the slot, so a provider-side rejection was indistinguishable from
     * the app refusing a key it never sent, and the one sentence covered both.
     *
     * Split, per owner ruling: this class is ONLY for "the request carried a key and the provider
     * answered 401/403". [providerLabel] is the provider or endpoint the request went to,
     * [slotName] the slot that was read (a NAME, never a value), [detail] the provider's own
     * sanitized message. A missing or blank key never reaches here - ChatSession returns
     * [MISSING_KEY_VERDICT] before any request is made.
     */
    class Auth(
        val providerLabel: String = "",
        val slotName: String = "",
        val detail: String = "",
    ) : ChatError(
        buildString {
            if (providerLabel.isNotBlank()) append(providerLabel) else append("The provider")
            append(" rejected the key (HTTP 401)")
            if (detail.isNotBlank()) append(": ").append(detail)
            if (slotName.isNotBlank()) append(" [slot ").append(slotName).append("]")
        },
        false,
    ) {
        companion object {
            /**
             * The local case: no request was sent at all. Deliberately a different sentence from
             * [Auth], because the fix is different (add a key vs fix a key).
             */
            fun missingKeyVerdict(providerLabel: String, slotName: String): String {
                val who = if (providerLabel.isNotBlank()) providerLabel else "this provider"
                val slot = if (slotName.isNotBlank()) " (looked for slot $slotName)" else ""
                return "No key found for $who$slot. Add one in Settings > AI keys."
            }
        }
    }

    /**
     * 404. Two very different causes share this status: the model id is not served by this
     * endpoint, or the endpoint path itself is wrong (a base URL missing /v1 was the
     * classic one). The verdict names the requested PATH so the user can tell which -
     * path only, never the query string, because a key can live there.
     */
    /**
     * The request itself was too big - a 413, or a 400 that says so - split from a generic
     * BadRequest because the fix is completely different: nothing about the key, the model name or
     * the body SHAPE is wrong, the payload is simply larger than the account or the model allows.
     *
     * Six providers say it six ways, and all of them put the numbers in the text:
     *  - Groq / OpenAI TPM:  "Request too large for model `m` in organization `o` on tokens per
     *                         minute (TPM): Limit 6000, Requested 6980"
     *  - OpenAI context:     "This model's maximum context length is 128000 tokens. However, your
     *                         messages resulted in 130000 tokens"
     *  - Anthropic:          "prompt is too long: 210000 tokens > 200000 maximum"
     *  - Gemini:             "The input token count (1048577) exceeds the maximum number of tokens
     *                         allowed (1048576)"
     *  - OpenRouter:         "This endpoint's maximum context length is N tokens"
     *  - Cloudflare / misc:  HTTP 413 without numbers
     * The numbers are lifted out and shown; the provider's own sentence stays as the detail.
     */
    class PromptTooLarge(
        val limit: Int?,
        val requested: Int?,
        val scope: String,
        val providerDetail: String,
    ) : ChatError(
        buildString {
            append("This request is bigger than the ").append(scope).append(" allows.")
            if (limit != null && requested != null) {
                append(" Limit ").append(limit).append(" tokens, request is ").append(requested)
                append(" (about ").append((requested - limit).coerceAtLeast(0)).append(" over).")
            } else if (limit != null) {
                append(" Limit ").append(limit).append(" tokens.")
            }
            append(" Start a new session, or switch to a model with a bigger window.")
        },
        false,
    )

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
        providerLabel: String = "",
        keySlotName: String = "",
    ): ChatError {
        return when {
            status == 429 -> ChatError.RateLimited(retryAfterSec(headers))
            status == 402 -> ChatError.OutOfCredits(providerHint(body))
            // P0-1: name the provider and the slot, and carry the provider's own sanitized words.
            status == 401 || status == 403 -> ChatError.Auth(
                providerLabel = providerLabel,
                slotName = keySlotName,
                detail = authDetail(body),
            )
            status == 404 -> ChatError.ModelNotFound(modelIdHint(body), endpointPath)
            // A limit complaint is not a shape complaint: split it before the generic 400.
            status == 413 -> ChatError.PromptTooLarge(
                limit = tokensAfter(body, LIMIT_MARKERS),
                requested = tokensAfter(body, REQUESTED_MARKERS),
                scope = scopeOf(body),
                providerDetail = brief(redactSecrets(body)),
            )
            status in 400..422 && looksLikeTooLarge(body) -> ChatError.PromptTooLarge(
                limit = tokensAfter(body, LIMIT_MARKERS),
                requested = tokensAfter(body, REQUESTED_MARKERS),
                scope = scopeOf(body),
                providerDetail = brief(redactSecrets(body)),
            )
            status in 400..499 -> ChatError.BadRequest(brief(body))
            else -> ChatError.Server(status)
        }
    }

    /** Network-layer failures (connect/read timeouts, mid-stream EOF). */
    fun network(detail: String): ChatError = ChatError.Network(brief(detail))

    /**
     * The provider's own sentence for a 401/403, sanitized. OpenRouter-shaped bodies carry
     * {"error":{"message":"..."}}; OpenAI and Groq use {"error":{"message":"..."}} too, Anthropic
     * {"error":{"message":"..."}}, so one regex covers them. Anything that looks like a key is
     * redacted before the text can reach a card, a log, or a screenshot.
     */
    /**
     * Only phrases the providers actually use. Deliberately tight: "too large" alone would catch a
     * complaint about one field, and a wrong split here sends the owner to the wrong fix.
     */
    private fun looksLikeTooLarge(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("request too large") ||
            b.contains("context_length_exceeded") ||
            b.contains("maximum context length") ||
            b.contains("prompt is too long") ||
            b.contains("exceeds the maximum number of tokens") ||
            b.contains("too many tokens") ||
            b.contains("context length exceeded") ||
            b.contains("token limit") ||
            (b.contains("tokens per minute") && b.contains("limit"))
    }

    /** "tokens per minute" is a rate budget; anything else here is the model's window. */
    private fun scopeOf(body: String): String {
        val b = body.lowercase()
        return when {
            b.contains("tokens per minute") || b.contains("tpm") -> "account's per-minute token budget"
            b.contains("output") && b.contains("max") -> "model's output limit"
            else -> "model's context window"
        }
    }

    private val LIMIT_MARKERS = listOf(
        Regex("""limit[^0-9]{0,12}([0-9][0-9,]{2,})""", RegexOption.IGNORE_CASE),
        Regex("""maximum (?:context length|number of tokens(?: allowed)?) is ([0-9][0-9,]{2,})""", RegexOption.IGNORE_CASE),
        Regex("""exceeds the maximum number of tokens allowed \(([0-9][0-9,]{2,})\)""", RegexOption.IGNORE_CASE),
        Regex(""">\s*([0-9][0-9,]{2,})\s*maximum""", RegexOption.IGNORE_CASE),
    )

    private val REQUESTED_MARKERS = listOf(
        Regex("""requested[^0-9]{0,12}([0-9][0-9,]{2,})""", RegexOption.IGNORE_CASE),
        Regex("""resulted in ([0-9][0-9,]{2,}) tokens""", RegexOption.IGNORE_CASE),
        Regex("""(?:prompt is too long|input token count)\D{0,12}([0-9][0-9,]{2,})""", RegexOption.IGNORE_CASE),
        Regex("""request is ([0-9][0-9,]{2,})""", RegexOption.IGNORE_CASE),
    )

    private fun tokensAfter(body: String, markers: List<Regex>): Int? {
        for (r in markers) {
            val v = r.find(body)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
            if (v != null && v > 0) return v
        }
        return null
    }

    private fun authDetail(body: String): String {
        val m = Regex(""""(?:message|detail)"\s*:\s*"([^"]{1,200})"""").find(body)?.groupValues?.get(1)
        if (m.isNullOrBlank()) return ""
        return brief(redactSecrets(m))
    }

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

    /**
     * P0-1 SECURITY: a provider's error body can quote the key it disliked. Nothing that looks
     * like a credential survives this, so the sentence is safe in a card, a log line, a crash
     * dump or a screenshot.
     */
    internal fun redactSecrets(s: String): String {
        var out = s
        out = out.replace(Regex("""(?i)(sk|gsk|pk|org)[-_][A-Za-z0-9_\-]{8,}"""), "[redacted]")
        out = out.replace(Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{8,}"""), "Bearer [redacted]")
        out = out.replace(Regex("""AIza[0-9A-Za-z_\-]{20,}"""), "[redacted]")
        return out
    }

    private fun brief(s: String): String {
        val t = s.replace('\n', ' ').trim()
        return if (t.length <= 120) t else t.take(117) + "..."
    }
}
