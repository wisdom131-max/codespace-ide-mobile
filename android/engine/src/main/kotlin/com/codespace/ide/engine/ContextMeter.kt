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

    fun record(segments: Segments, windowTokens: Int, charsPerToken: Int = 4): Report {
        val r = measure(segments, windowTokens, charsPerToken)
        latest = r
        return r
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
