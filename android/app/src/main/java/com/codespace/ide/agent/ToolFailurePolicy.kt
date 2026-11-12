package com.codespace.ide.agent

/**
 * PROJECT 4.4 - WHAT A FAILURE MEANS, AND WHETHER TRYING AGAIN IS HONEST.
 *
 * A failure is not one thing. A missing prerequisite is fixed by running the prerequisite, a
 * permission problem needs the owner, invalid arguments need different arguments, and a timeout may
 * simply work next time. Retrying all of them equally is how a side effect happens twice.
 *
 * The hard rule: a side-effecting call whose outcome is unknown is NEVER retried here. That decision
 * belongs to [ToolDispatchGuard] and its reconciler, because only they can prove what already
 * happened.
 */
object ToolFailurePolicy {

    enum class Kind(val phrase: String) {
        PREREQUISITE("something it depends on is missing"),
        PERMISSION("permission was refused"),
        UNAVAILABLE_TOOL("that tool is not available here"),
        INVALID_ARGUMENTS("the arguments were not accepted"),
        TIMEOUT("the operation timed out"),
        RESOURCE_LIMIT("a size or memory limit was hit"),
        NETWORK("the network request failed"),
        APPROVAL_NEEDED("it needs approval"),
        UNKNOWN("the failure was not recognised"),
    }

    enum class Action { RETRY, ALTERNATIVE, ASK_USER, STOP }

    data class Decision(
        val kind: Kind,
        val retryable: Boolean,
        val action: Action,
        val message: String,
    )

    /** Strict ceiling: a retry that has failed twice is not a coincidence. */
    const val MAX_ATTEMPTS = 2

    fun classify(output: String, threw: Throwable? = null): Decision {
        val t = (output + " " + (threw?.message ?: "") + " " + (threw?.let { it::class.simpleName } ?: "")).lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        val kind = when {
            has("approval", "needs confirmation", "not approved", "approve") -> Kind.APPROVAL_NEEDED
            has("permission denied", "eacces", "operation not permitted", "not authorized", "401", "403") -> Kind.PERMISSION
            has("no such file", "not a git repository", "missing", "not found in project", "enoent") -> Kind.PREREQUISITE
            has("unknown tool", "not available", "no such tool", "unsupported tool", "not enabled") -> Kind.UNAVAILABLE_TOOL
            has("invalid argument", "unknown option", "usage:", "expected", "malformed", "cannot parse") -> Kind.INVALID_ARGUMENTS
            has("timeout", "timed out", "etimedout") -> Kind.TIMEOUT
            has("out of memory", "too large", "exceeds", "limit reached", "no space") -> Kind.RESOURCE_LIMIT
            has("connection", "network", "unreachable", "host not found", "dns", "socket") -> Kind.NETWORK
            else -> Kind.UNKNOWN
        }
        return decision(kind)
    }

    private fun decision(kind: Kind): Decision = when (kind) {
        Kind.PREREQUISITE -> Decision(kind, false, Action.ALTERNATIVE,
            "A prerequisite is missing. Do the step it depends on first, then retry once.")
        Kind.PERMISSION -> Decision(kind, false, Action.ASK_USER,
            "The device refused permission. This needs the owner, not another attempt.")
        Kind.UNAVAILABLE_TOOL -> Decision(kind, false, Action.ALTERNATIVE,
            "That tool is not offered in this mode. Use a tool that is, or explain why none fits.")
        Kind.INVALID_ARGUMENTS -> Decision(kind, false, Action.STOP,
            "The arguments were rejected. Re-read the tool's schema; the same call will fail again.")
        Kind.TIMEOUT -> Decision(kind, true, Action.RETRY,
            "It timed out. One retry is reasonable; narrow the request if it times out again.")
        Kind.RESOURCE_LIMIT -> Decision(kind, false, Action.ALTERNATIVE,
            "A size limit was hit. Ask for a range or a narrower target instead of the whole thing.")
        Kind.NETWORK -> Decision(kind, true, Action.RETRY,
            "The network failed. One retry, then report it rather than looping.")
        Kind.APPROVAL_NEEDED -> Decision(kind, false, Action.ASK_USER,
            "This needs the owner's approval. Do not work around it.")
        Kind.UNKNOWN -> Decision(kind, false, Action.STOP,
            "The failure was not recognised. Report it plainly instead of guessing at a retry.")
    }

    /**
     * Whether another attempt is allowed. [outcomeKnown] is false when the call may have reached the
     * side effect before failing: that case is refused regardless of kind.
     */
    fun mayRetry(
        decision: Decision,
        attemptsSoFar: Int,
        sideEffecting: Boolean,
        outcomeKnown: Boolean,
    ): Boolean {
        if (!decision.retryable) return false
        if (attemptsSoFar >= MAX_ATTEMPTS) return false
        if (sideEffecting && !outcomeKnown) return false
        return true
    }

    /** Text for the model: what still holds, and what to do next. */
    fun guidance(decision: Decision, keptResults: Int): String = buildString {
        append(decision.message)
        if (keptResults > 0) {
            append(" The ").append(keptResults).append(" earlier result(s) in this task are still valid; ")
            append("do not repeat those operations.")
        }
    }
}
