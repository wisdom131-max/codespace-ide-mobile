package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.15 (catalog 91): one structured record per send.
 *
 * `CopilotDiag` already logged, but as free text: nothing could answer "which provider, how big, how
 * many tokens, how did it end, and was it the same failure as last time" without reading the log.
 * This is the record a trace collector needs, and it is deliberately PLAIN DATA: no formatting, no
 * transport, no storage decision, so the owner can shape the UI however they want.
 *
 * Nothing here is invented. A token count that the provider did not report is NULL, never zero:
 * "we do not know" and "it was free" are different facts, and a cost display that conflates them is
 * worse than one that shows nothing.
 */
data class TurnTrace(
    val providerId: String,
    val modelId: String,
    /** Characters actually sent, measured, not estimated. */
    val payloadChars: Int,
    /** Tools advertised on this send. */
    val toolsOffered: Int,
    /** Reported by the provider, or null when it did not report. */
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    /** How the turn ended: "stop", "length", "tool_call_incomplete", "error"... */
    val finish: String = "",
    /** Set when the turn failed, so a run of failures is countable rather than retypeable. */
    val cluster: FailureCluster? = null,
    /** Tool rounds this turn consumed. 0 for a turn that never called a tool. */
    val toolRounds: Int = 0,
) {
    /** True when the provider told us enough to talk about cost at all. */
    val hasUsage: Boolean get() = inputTokens != null || outputTokens != null

    /**
     * One line, for the chat log. Says "unknown" rather than 0 for unreported usage, because a
     * trace that quietly reports 0 tokens teaches the reader to distrust the trace.
     */
    fun render(): String {
        val usage = when {
            inputTokens != null && outputTokens != null -> "$inputTokens in / $outputTokens out"
            inputTokens != null -> "$inputTokens in / unknown out"
            outputTokens != null -> "unknown in / $outputTokens out"
            else -> "usage not reported"
        }
        val tail = buildString {
            append(providerId).append('/').append(modelId)
            append(" | ").append(payloadChars).append(" chars")
            append(" | ").append(toolsOffered).append(" tools")
            append(" | ").append(usage)
            if (toolRounds > 0) append(" | ").append(toolRounds).append(" rounds")
            if (finish.isNotBlank()) append(" | ").append(finish)
            cluster?.let { append(" | failed: ").append(it.id) }
        }
        return tail
    }
}
