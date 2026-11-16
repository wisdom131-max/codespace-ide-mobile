package com.codespace.ide.engine

/**
 * PROJECT FIVE, STAGE 5.3 — why a provider request failed, in words that survive a provider change.
 *
 * Three providers state failure three ways: Gemini `{"error":{"code","status","message"}}`, Anthropic
 * `{"type":"error","error":{"type","message"}}`, OpenAI-compatible `{"error":{"message","type","code"}}`.
 * A custom endpoint may state it any of those ways or none of them, so extraction never throws and
 * never guesses: a field that is absent stays absent, and an error that cannot be read is UNKNOWN.
 *
 * This type is the *evidence*. [RecoveryPlanner] is the judgement, and it delegates the arithmetic of
 * retrying to the existing [ProviderRetry] rather than re-implementing a second policy.
 */
enum class ErrorClass {
    AUTH_INVALID,
    AUTH_MISSING,
    PERMISSION_DENIED,
    RATE_LIMITED,
    QUOTA_EXHAUSTED,
    NETWORK,
    TIMEOUT,
    CONTEXT_EXCEEDED,
    INVALID_REQUEST,
    UNSUPPORTED_CAPABILITY,
    PROVIDER_UNAVAILABLE,
    MALFORMED_RESPONSE,
    CANCELLED,
    TRUNCATED,
    UNKNOWN,
}

/**
 * What the provider actually said. Every field is optional because providers omit them, and a value
 * that is absent must never be invented downstream.
 */
data class ProviderErrorFacts(
    val providerId: String = "",
    val status: Int = 0,
    val code: String = "",
    val type: String = "",
    val message: String = "",
    val retryAfterSec: Int? = null,
    val requestId: String = "",
    val modelId: String = "",
    /** Where the shape came from: "gemini", "anthropic", "openai", "custom", or "throwable". */
    val shape: String = "unknown",
) {
    /** The fact set with anything credential-bearing stripped out of the message. */
    val safeMessage: String get() = Redaction.sanitize(message)
}

object ProviderErrors {

    /**
     * Reads one provider error body. Unknown or malformed input yields facts with an empty message
     * and `shape = "unknown"`; it never throws, because a crash here would take the chat down for a
     * reason the user cannot act on.
     */
    fun extract(
        providerId: String,
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
        modelId: String = "",
    ): ProviderErrorFacts {
        val root = JsonLite.obj(JsonLite.parse(body))
        val retryAfter = headers.entries
            .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
            ?.value?.trim()?.toIntOrNull()
        val err = root?.let { JsonLite.obj(it["error"]) }
        if (root == null) {
            return ProviderErrorFacts(providerId, status, retryAfterSec = retryAfter, modelId = modelId,
                shape = "unknown")
        }
        // Anthropic names the outer type; OpenAI and Gemini keep everything under "error".
        val type = JsonLite.str(err?.get("type")).ifBlank { JsonLite.str(root["type"]) }
        // Gemini repeats the number in "code" and the symbolic name in "status"; the name is what
        // classifies, so a symbolic code always wins over a numeric one.
        val code = listOf(JsonLite.str(err?.get("status")), JsonLite.str(err?.get("code")))
            .firstOrNull { it.isNotBlank() && !it.all(Char::isDigit) }
            ?: JsonLite.str(err?.get("code")).ifBlank { JsonLite.str(err?.get("status")) }
        val message = JsonLite.str(err?.get("message")).ifBlank { JsonLite.str(root["message"]) }
        val shape = when {
            root["candidates"] != null -> "gemini"
            err != null && err["code"] != null && err["status"] != null -> "gemini"
            JsonLite.str(root["type"]) == "error" && err != null -> "anthropic"
            err != null -> "openai"
            else -> "custom"
        }
        return ProviderErrorFacts(
            providerId = providerId,
            status = status,
            code = code,
            type = type,
            message = message,
            retryAfterSec = retryAfter,
            requestId = JsonLite.str(err?.get("request_id")),
            modelId = modelId,
            shape = shape,
        )
    }

    /**
     * Classifies from the evidence. Status alone is never enough — a 400 can be a context overrun
     * (recoverable by shortening) or a malformed request (not recoverable at all) — so the provider's
     * own code, type and message are read first and the status is the fallback.
     */
    fun classify(facts: ProviderErrorFacts): ErrorClass {
        val hay = (facts.code + " " + facts.type + " " + facts.message).lowercase()
        return when {
            hay.isBlank() && facts.status == 0 -> ErrorClass.UNKNOWN
            has(hay, "insufficient_quota", "quota", "billing", "credit balance") -> ErrorClass.QUOTA_EXHAUSTED
            has(hay, "rate_limit", "rate limit", "too many requests", "requests per minute", "tokens per minute")
                -> ErrorClass.RATE_LIMITED
            has(hay, "context_length", "context length", "maximum context", "too many tokens",
                "input is too long", "prompt is too long", "request too large") -> ErrorClass.CONTEXT_EXCEEDED
            has(hay, "invalid api key", "incorrect api key", "api key not valid", "unauthenticated",
                "invalid_api_key", "invalid x-api-key") -> ErrorClass.AUTH_INVALID
            has(hay, "permission", "forbidden", "not authorized", "does not have access")
                -> ErrorClass.PERMISSION_DENIED
            has(hay, "model not found", "not supported", "unsupported", "does not support", "no such model")
                -> ErrorClass.UNSUPPORTED_CAPABILITY
            has(hay, "timeout", "timed out", "deadline exceeded") -> ErrorClass.TIMEOUT
            has(hay, "overloaded", "unavailable", "maintenance", "try again later", "internal error")
                -> ErrorClass.PROVIDER_UNAVAILABLE
            has(hay, "invalid", "malformed", "bad request", "required field") -> ErrorClass.INVALID_REQUEST
            facts.status == 401 -> ErrorClass.AUTH_INVALID
            facts.status == 403 -> ErrorClass.PERMISSION_DENIED
            facts.status == 402 -> ErrorClass.QUOTA_EXHAUSTED
            facts.status == 408 -> ErrorClass.TIMEOUT
            facts.status == 413 -> ErrorClass.CONTEXT_EXCEEDED
            facts.status == 429 -> ErrorClass.RATE_LIMITED
            facts.status == 404 -> ErrorClass.UNSUPPORTED_CAPABILITY
            // A 400 means "the request was rejected", which is NOT the same as "the request was
            // malformed": without the provider's own code or words, the reason is unknown and must
            // stay unknown rather than become a permanent-failure claim about the user's message.
            facts.status == 400 || facts.status == 422 ->
                if (hay.isBlank()) ErrorClass.UNKNOWN else ErrorClass.INVALID_REQUEST
            facts.status in 500..599 -> ErrorClass.PROVIDER_UNAVAILABLE
            else -> ErrorClass.UNKNOWN
        }
    }

    /** Transport-layer failures, which never carry a provider body. */
    fun fromThrowable(t: Throwable): ProviderErrorFacts {
        val m = (t.message ?: "").lowercase()
        val kind = when {
            "timeout" in m || "timed out" in m -> "timeout"
            "ssl" in m || "tls" in m || "certificate" in m -> "tls"
            "unknown host" in m || "unable to resolve" in m || "dns" in m -> "dns"
            "reset" in m || "eof" in m || "closed" in m -> "reset"
            "offline" in m || "unreachable" in m || "failed to connect" in m -> "offline"
            else -> "transport"
        }
        return ProviderErrorFacts(type = kind, message = t.message ?: "connection failed", shape = "throwable")
    }

    /** The class for a throwable, routing through the same vocabulary as a provider body. */
    fun classify(t: Throwable): ErrorClass = when (fromThrowable(t).type) {
        "timeout" -> ErrorClass.TIMEOUT
        "transport", "dns", "reset", "offline", "tls" -> ErrorClass.NETWORK
        else -> ErrorClass.UNKNOWN
    }

    private fun has(hay: String, vararg needles: String) = needles.any { it in hay }
}

/**
 * The last line of defence for log safety: no key, token or credential-bearing URL may reach a
 * verdict, a row, a log or an error message.
 */
object Redaction {

    private val PATTERNS = listOf(
        Regex("""(?i)\b(?:sk|rk)-[A-Za-z0-9_\-]{12,}"""),
        Regex("""(?i)\bgh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""(?i)\bAIza[A-Za-z0-9_\-]{20,}"""),
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-]{8,}"""),
        Regex("""(?i)\bAQ\.[A-Za-z0-9_\-]{20,}"""),
        // A credential in a query string: key=..., api_key=..., access_token=...
        Regex("""(?i)([?&](?:key|api_key|apikey|access_token|token)=)[^&\s"']+"""),
    )

    /** Strips anything that looks like a credential, keeping the sentence readable. */
    fun sanitize(text: String): String {
        var out = text
        for (p in PATTERNS) {
            out = p.replace(out) { m ->
                if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] + "<redacted>"
                else "<redacted>"
            }
        }
        return out.take(400)
    }
}

/** What the app may do next. Deliberately explicit: no silent provider or model switching. */
enum class RecoveryAction {
    RETRY_SAFE,
    WAIT_AND_RETRY,
    USER_ACTION_REQUIRED,
    CONFIGURATION_REQUIRED,
    CONTEXT_REDUCTION_REQUIRED,
    ALTERNATIVE_MODEL_AVAILABLE,
    OPERATION_OUTCOME_UNKNOWN,
    PERMANENT_FAILURE,
    CANCELLED,
}

/**
 * @param waitMs how long the caller should wait before the retry it was told to make, or null.
 * @param alternativeModel an OFFER, never an applied switch: the user's selection stands until they say otherwise.
 */
data class RecoveryPlan(
    val action: RecoveryAction,
    val why: String,
    val waitMs: Long? = null,
    val retryable: Boolean = false,
    val alternativeModel: String? = null,
    val technical: String = "",
)

/**
 * Turns evidence into a decision. The retry arithmetic (attempt ceiling, backoff, the cap on a
 * provider's Retry-After) stays in [ProviderRetry]; this type only decides which question applies.
 */
object RecoveryPlanner {

    /**
     * @param sideEffectPossible true when something outside the app may already have changed in this
     *   turn. It outranks every other consideration: an unknown write is never retried automatically.
     */
    fun plan(
        facts: ProviderErrorFacts?,
        attempt: Int,
        sideEffectPossible: Boolean = false,
        alternativeModel: String? = null,
        contextLimitsKnown: Boolean = true,
    ): RecoveryPlan {
        if (sideEffectPossible) {
            return RecoveryPlan(
                RecoveryAction.OPERATION_OUTCOME_UNKNOWN,
                "The connection ended after a step that changes files. Its result is being checked before another attempt.",
                retryable = false,
                technical = facts?.let { "class=${ProviderErrors.classify(it)} status=${it.status}" } ?: "",
            )
        }
        if (facts == null) {
            return RecoveryPlan(
                RecoveryAction.RETRY_SAFE,
                "The stream ended without a provider verdict, so nothing was refused and nothing was retried yet.",
                waitMs = waitOrNull(attempt, null),
                retryable = attempt < ProviderRetry.MAX_ATTEMPTS,
            )
        }
        val cls = ProviderErrors.classify(facts)
        val technical = "class=$cls status=${facts.status} shape=${facts.shape} code=${facts.code}"
        return when (cls) {
            ErrorClass.AUTH_INVALID, ErrorClass.AUTH_MISSING -> RecoveryPlan(
                RecoveryAction.USER_ACTION_REQUIRED,
                "Your API key was rejected. Check the key configured for this provider.",
                retryable = false, technical = technical,
            )
            ErrorClass.PERMISSION_DENIED -> RecoveryPlan(
                RecoveryAction.CONFIGURATION_REQUIRED,
                "This key is not allowed to use that model or feature. Check the key's permissions or pick a model it can use.",
                retryable = false, technical = technical,
            )
            ErrorClass.QUOTA_EXHAUSTED -> RecoveryPlan(
                RecoveryAction.USER_ACTION_REQUIRED,
                "This provider account is out of quota for now. Top it up or choose another provider when you are ready.",
                retryable = false, technical = technical,
            )
            ErrorClass.RATE_LIMITED -> {
                val wait = waitOrNull(attempt, facts.retryAfterSec)
                if (wait == null) {
                    RecoveryPlan(
                        RecoveryAction.USER_ACTION_REQUIRED,
                        "The provider is limiting requests and asked for a wait longer than this app will hold a message open. Send it again when the wait is over.",
                        retryable = false, technical = technical,
                    )
                } else {
                    RecoveryPlan(
                        RecoveryAction.WAIT_AND_RETRY,
                        "The provider is temporarily limiting requests. Waiting a moment and trying again is safe.",
                        waitMs = wait, retryable = true, technical = technical,
                    )
                }
            }
            ErrorClass.CONTEXT_EXCEEDED -> RecoveryPlan(
                RecoveryAction.CONTEXT_REDUCTION_REQUIRED,
                if (contextLimitsKnown)
                    "This conversation is too large for the selected model. You can shorten the history or continue in a new session."
                else
                    "The provider refused the request as too large, but this model's limit is not known here, so nothing was trimmed automatically.",
                retryable = false, technical = technical,
            )
            ErrorClass.INVALID_REQUEST -> RecoveryPlan(
                RecoveryAction.PERMANENT_FAILURE,
                "The provider rejected the request itself. Sending the identical request again would fail the same way.",
                retryable = false, technical = technical,
            )
            ErrorClass.UNSUPPORTED_CAPABILITY -> RecoveryPlan(
                action = if (alternativeModel != null) RecoveryAction.ALTERNATIVE_MODEL_AVAILABLE
                         else RecoveryAction.CONFIGURATION_REQUIRED,
                why = if (alternativeModel != null)
                    "The selected model does not support this request. $alternativeModel can do it, if you switch."
                else
                    "The selected model does not support this request, and no checked alternative is known here.",
                retryable = false, alternativeModel = alternativeModel, technical = technical,
            )
            ErrorClass.TIMEOUT, ErrorClass.NETWORK, ErrorClass.PROVIDER_UNAVAILABLE -> {
                val wait = waitOrNull(attempt, facts.retryAfterSec)
                if (wait == null) {
                    RecoveryPlan(
                        RecoveryAction.PERMANENT_FAILURE,
                        "The provider did not answer after ${ProviderRetry.MAX_ATTEMPTS} attempts. Nothing was changed, so you can send it again later.",
                        retryable = false, technical = technical,
                    )
                } else {
                    RecoveryPlan(
                        RecoveryAction.WAIT_AND_RETRY,
                        "The provider did not answer. Nothing was changed, so trying again after a short wait is safe.",
                        waitMs = wait, retryable = true, technical = technical,
                    )
                }
            }
            ErrorClass.MALFORMED_RESPONSE -> RecoveryPlan(
                RecoveryAction.PERMANENT_FAILURE,
                "The provider's answer could not be read. Trying the identical request again is unlikely to help.",
                retryable = false, technical = technical,
            )
            ErrorClass.CANCELLED -> RecoveryPlan(
                RecoveryAction.CANCELLED, "Stopped.", retryable = false, technical = technical,
            )
            ErrorClass.TRUNCATED -> RecoveryPlan(
                RecoveryAction.CONTEXT_REDUCTION_REQUIRED,
                "The answer was cut off at the output limit, not finished. Continuing is the safe next step; it is not retried from the start.",
                retryable = false, technical = technical,
            )
            ErrorClass.UNKNOWN -> RecoveryPlan(
                RecoveryAction.PERMANENT_FAILURE,
                "The provider failed in a way this app cannot classify. Nothing was retried automatically.",
                retryable = false, technical = technical,
            )
        }
    }

    /**
     * The wait to use, or null when no retry should happen. Honours the provider's Retry-After up to
     * [ProviderRetry.MAX_HONOURED_RETRY_AFTER_SEC], and stops at the shared attempt ceiling.
     */
    fun waitOrNull(attempt: Int, retryAfterSec: Int?): Long? {
        if (attempt >= ProviderRetry.MAX_ATTEMPTS) return null
        if (attempt < 1) return null
        val asked = retryAfterSec ?: 0
        if (asked > ProviderRetry.MAX_HONOURED_RETRY_AFTER_SEC) return null
        return if (asked > 0) asked * 1000L else ProviderRetry.backoff(attempt)
    }
}
