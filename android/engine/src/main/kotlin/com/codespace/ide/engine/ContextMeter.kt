package com.codespace.ide.engine

/**
 * PROJECT NINE 10.01 (catalog 92) - the numbers behind the context window panel.
 *
 * The session already knew how big each part of the request was; nothing turned that into the
 * figure a person reads. Four parts are measured because they are the four that actually grow:
 * the instructions, the tool definitions, the conversation, and the tool results. Reserved space is
 * counted separately because it is not used yet but is not available either.
 *
 * Kept out of the UI on purpose: this is arithmetic and formatting, so it can be tested without a
 * device.
 */
object ContextMeter {

    data class Segments(
        val systemChars: Int = 0,
        val toolChars: Int = 0,
        val messageChars: Int = 0,
        val toolResultChars: Int = 0,
        val reservedChars: Int = 0,
    )

    data class Section(val label: String, val tokens: Int, val percent: Int)

    data class Report(
        val usedTokens: Int,
        val windowTokens: Int,
        val reservedTokens: Int,
        val sections: List<Section>,
    ) {
        /** Share of the window in use, never above 100 even if the estimate overshoots. */
        val percent: Int get() = if (windowTokens <= 0) 0 else (usedTokens * 100 / windowTokens).coerceIn(0, 100)

        val usedLabel: String get() = format(usedTokens)
        val windowLabel: String get() = format(windowTokens)
    }

    /**
     * The most recent measurement, recorded by the engine at the moment it builds a request and read
     * by the panel. The engine is the only place that knows all the parts, so it measures once and
     * the UI reads the result rather than guessing from a transcript.
     */
    @Volatile
    var latest: Report? = null
        private set

    /**
     * DEVICE REPORT T10 (2026-11-23): the panel showed the PREVIOUS session's numbers after a
     * switch, because there was one global "latest" and the UI read it regardless of which
     * session was on screen. A measurement belongs to the session it was taken in, so it is kept
     * per session now: switching sessions shows that session's own last measurement, or nothing,
     * never another conversation's tokens.
     */
    private val bySession = java.util.concurrent.ConcurrentHashMap<String, Report>()

    fun record(segments: Segments, windowTokens: Int, charsPerToken: Int = 4): Report {
        val r = measure(segments, windowTokens, charsPerToken)
        latest = r
        return r
    }

    /** Record a measurement against the session that produced it. */
    fun record(sessionId: String, segments: Segments, windowTokens: Int, charsPerToken: Int = 4): Report {
        val r = measure(segments, windowTokens, charsPerToken)
        latest = r
        if (sessionId.isNotBlank()) bySession[sessionId] = r
        return r
    }

    /** This session's last measurement, or null when it has not sent anything yet. */
    fun latestFor(sessionId: String?): Report? =
        sessionId?.takeIf { it.isNotBlank() }?.let { bySession[it] }

    /**
     * OWNER REPORT (2026-11-23): "my context window panel that shows token usage doesn't work
     * perfectly". It did not, and the reason is what it was counting.
     *
     * The panel showed only the engine's own ESTIMATE of the outgoing prompt (characters / 4) and
     * called it the tokens used. That number is a guess in one direction only: it cannot know how
     * the provider tokenised anything, and it does not include the reply that was actually billed.
     *
     * VS Code's own widget (chatContextUsageWidget.ts) does it differently: the numerator is the
     * usage the PROVIDER reported for the last response -
     *   usedTokens = reportedContext.currentTokens ?? promptTokens + completionTokens
     * - while the denominator follows the currently selected model, so switching models updates the
     * percentage immediately instead of waiting for the next request. This type carries the reported
     * half of that, so the panel can show what the model said rather than what we guessed.
     */
    data class Usage(val promptTokens: Int, val completionTokens: Int) {
        val totalTokens: Int get() = promptTokens + completionTokens
    }

    /**
     * Reported usage per session, kept apart from [bySession] on purpose: a measurement is taken
     * BEFORE a request goes out and a usage arrives AFTER it, so they are two different facts about
     * two different moments. Merging them into one record would let a stale measurement overwrite a
     * fresh report.
     */
    private val usageBySession = java.util.concurrent.ConcurrentHashMap<String, Usage>()

    /**
     * Record what the provider said it billed. Ignored when both counts are zero, because some
     * providers send a usage block of zeros and a zero is not evidence of anything.
     */
    fun recordUsage(sessionId: String, promptTokens: Int, completionTokens: Int): Usage? {
        if (promptTokens <= 0 && completionTokens <= 0) return null
        val usage = Usage(promptTokens.coerceAtLeast(0), completionTokens.coerceAtLeast(0))
        if (sessionId.isNotBlank()) usageBySession[sessionId] = usage
        return usage
    }

    /**
     * This session's last reported usage, or null. Deliberately NO global fallback: the T10 device
     * report was exactly that bug - one global "latest" showing another session's numbers after a
     * switch.
     */
    fun usageFor(sessionId: String?): Usage? =
        sessionId?.takeIf { it.isNotBlank() }?.let { usageBySession[it] }

    /** Drop both facts for a session that no longer exists. */
    fun forget(sessionId: String) {
        bySession.remove(sessionId)
        usageBySession.remove(sessionId)
    }

    fun measure(segments: Segments, windowTokens: Int, charsPerToken: Int = 4): Report {
        val per = charsPerToken.coerceAtLeast(1)
        fun tokens(chars: Int) = (chars.coerceAtLeast(0) + per - 1) / per
        val window = windowTokens.coerceAtLeast(0)
        val parts = listOf(
            "System Instructions" to tokens(segments.systemChars),
            "Tool Definitions" to tokens(segments.toolChars),
            "Messages" to tokens(segments.messageChars),
            "Tool Results" to tokens(segments.toolResultChars),
        )
        val reserved = tokens(segments.reservedChars)
        val used = parts.sumOf { it.second } + reserved
        val sections = parts.map { (label, t) ->
            Section(label, t, if (window <= 0) 0 else (t * 1000 / window) / 10)
        }
        return Report(usedTokens = used, windowTokens = window, reservedTokens = reserved, sections = sections)
    }

    /** 133_600 reads as "133.6K"; 200_000 as "200K"; 940 as "940". */
    fun format(tokens: Int): String {
        if (tokens < 0) return "0"
        if (tokens < 1000) return tokens.toString()
        val tenths = (tokens + 50) / 100
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0) whole.toString() + "K" else whole.toString() + "." + frac + "K"
    }
}
