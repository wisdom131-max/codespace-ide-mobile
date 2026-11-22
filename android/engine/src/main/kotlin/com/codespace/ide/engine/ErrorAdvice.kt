package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.61 (catalog 93/94/95) - what a failure means, and what to do about it.
 *
 * [ProviderErrors] could already name a failure precisely, and nothing rendered that name, so the
 * user met a raw exception string. A raw string is fine for a log and useless on a phone: it says
 * "400" or "invalid_request_error" and leaves the reader to work out whether the problem is their
 * key, their quota, their connection or the app.
 *
 * So each class gets a headline, one sentence of advice, and whether retrying can help. The advice
 * is deliberately concrete: "replace the key in Settings" is actionable, "authentication failed"
 * is not.
 *
 * The raw provider text is NOT hidden by this. It travels as the detail beside the advice, because
 * a message that is scrubbed of what the provider actually said cannot be diagnosed.
 */
object ErrorAdvice {

    /** Short, and in the user's terms rather than the provider's. */
    fun headline(c: ErrorClass): String = when (c) {
        ErrorClass.AUTH_MISSING -> "No API key for this provider"
        ErrorClass.AUTH_INVALID -> "The API key was rejected"
        ErrorClass.PERMISSION_DENIED -> "This key cannot use this model"
        ErrorClass.RATE_LIMITED -> "Rate limited by the provider"
        ErrorClass.QUOTA_EXHAUSTED -> "The provider quota is used up"
        ErrorClass.NETWORK -> "Could not reach the provider"
        ErrorClass.TIMEOUT -> "The provider took too long"
        ErrorClass.CONTEXT_EXCEEDED -> "The request is too large for this model"
        ErrorClass.INVALID_REQUEST -> "The provider rejected the request"
        ErrorClass.UNSUPPORTED_CAPABILITY -> "This model does not support that"
        ErrorClass.PROVIDER_UNAVAILABLE -> "The provider is unavailable"
        ErrorClass.MALFORMED_RESPONSE -> "The provider sent something unreadable"
        ErrorClass.CANCELLED -> "Cancelled"
        ErrorClass.TRUNCATED -> "The answer was cut short"
        ErrorClass.UNKNOWN -> "The send failed"
    }

    /** One sentence, and it says what to DO. Empty when there is nothing honest to suggest. */
    fun advice(c: ErrorClass): String = when (c) {
        ErrorClass.AUTH_MISSING -> "Add a key in Settings, then send again."
        ErrorClass.AUTH_INVALID -> "The key is wrong or revoked. Replace it in Settings."
        ErrorClass.PERMISSION_DENIED -> "Use a key with access to this model, or pick another model."
        ErrorClass.RATE_LIMITED -> "Wait a moment, then send again."
        ErrorClass.QUOTA_EXHAUSTED -> "Top up with this provider, or switch provider."
        ErrorClass.NETWORK -> "Check the connection, then send again."
        ErrorClass.TIMEOUT -> "Send again, or pick a faster model."
        ErrorClass.CONTEXT_EXCEEDED -> "Start a new session, or send a shorter message."
        ErrorClass.INVALID_REQUEST -> "Worth reporting: the request itself was rejected."
        ErrorClass.UNSUPPORTED_CAPABILITY -> "Switch to a model that supports it."
        ErrorClass.PROVIDER_UNAVAILABLE -> "Try again shortly, or switch provider."
        ErrorClass.MALFORMED_RESPONSE -> "Send again. If it repeats, switch model."
        ErrorClass.CANCELLED -> "Send again when ready."
        ErrorClass.TRUNCATED -> "Ask it to continue."
        ErrorClass.UNKNOWN -> ""
    }

    /**
     * Whether retrying can plausibly succeed. A wrong key and a used-up quota are NOT retryable,
     * and offering a retry button for them teaches the user that the button does nothing.
     */
    fun retryable(c: ErrorClass): Boolean = when (c) {
        ErrorClass.RATE_LIMITED,
        ErrorClass.NETWORK,
        ErrorClass.TIMEOUT,
        ErrorClass.PROVIDER_UNAVAILABLE,
        ErrorClass.MALFORMED_RESPONSE,
        ErrorClass.TRUNCATED,
        -> true
        else -> false
    }
}
