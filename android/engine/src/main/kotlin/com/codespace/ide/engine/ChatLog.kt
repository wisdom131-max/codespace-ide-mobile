package com.codespace.ide.engine

/**
 * CHAT LOG: what was actually sent, what came back, and nothing secret.
 *
 * The owner has hit failures the transcript could not explain - a key that passed Test and was then
 * "rejected", a model that refused even "hi", a request that grew until it stopped answering. A log
 * that shows the provider's own words, with credentials redacted, is the difference between guessing
 * and knowing.
 *
 * REDACTION IS NOT OPTIONAL. Provider bodies echo headers and URLs back surprisingly often, and a
 * log the owner copies into a bug report must be safe to paste in public. Everything that looks like
 * a credential is replaced by a marker that still says WHERE it was, never what it was.
 */
object ChatLogSanitizer {

    /**
     * ORDER MATTERS: a header or query rule must consume the WHOLE value before a key-shape rule
     * rewrites part of it, otherwise "x-api-key: <redacted key>" leaves a dangling fragment that
     * looks like corruption in a pasted report. Structured containers first, loose key shapes last.
     */
    private val PATTERNS = listOf(
        Regex("""(?i)\b(x-api-key|api-key|x-goog-api-key)\s*[:=]\s*[^\s"',}]+""") to "$1: <redacted>",
        Regex("""(?i)([?&](?:key|api_key|apikey|access_token|token)=)[^&\s"']+""") to "$1<redacted>",
        Regex("""(Bearer)\s+[A-Za-z0-9._\-]{6,}""", RegexOption.IGNORE_CASE) to "$1 <redacted>",
        Regex("""\b(sk-or-v1-[A-Za-z0-9_\-]{8,})""") to "<redacted key>",
        Regex("""\b(sk-[A-Za-z0-9_\-]{8,})""") to "<redacted key>",
        Regex("""\b(gh[pousr]_[A-Za-z0-9]{10,})""") to "<redacted key>",
        Regex("""\b(AIza[0-9A-Za-z_\-]{20,})""") to "<redacted key>",
        Regex("""\b(gsk_[A-Za-z0-9]{10,})""") to "<redacted key>",
    )

    /** The one place text becomes safe to log. */
    fun redact(text: String): String {
        var out = text
        for ((pattern, replacement) in PATTERNS) {
            out = pattern.replace(out, replacement)
        }
        return out
    }

    /** True when [text] still appears to carry a credential. Used by the tests, not at runtime. */
    fun looksUnsafe(text: String): Boolean = PATTERNS.any { it.first.containsMatchIn(text) }
}

/** One recorded exchange. Size fields are estimates and labelled as such in the report. */
data class ChatLogEntry(
    val at: Long,
    val mode: String,
    val providerId: String,
    val modelId: String,
    val endpointPath: String,
    val requestChars: Int,
    val httpStatus: Int?,
    val outcome: String,
    val providerMessage: String,
    val toolsOffered: Int,
    val historyDropped: Int,
    val learnedLimitChars: Int,
    val retriedCeilingField: Boolean,
    /** The provider's own end reason ("length", "max_tokens", "MAX_TOKENS"), or "" when clean. */
    val endReason: String = "",
) {
    val estTokens: Int get() = requestChars / 4

    /** The copyable report. Plain text, one fact per line, safe to paste in public. */
    fun report(): String = buildString {
        appendLine("VN Code - chat diagnostic")
        appendLine("when: " + javaTimeLabel(at))
        appendLine("mode: " + mode + "  provider: " + providerId + "  model: " + modelId)
        appendLine("path: " + endpointPath)
        appendLine("request: " + requestChars + " chars (~" + estTokens + " tokens), tools offered: " + toolsOffered)
        if (historyDropped > 0) appendLine("history: " + historyDropped + " older messages not sent (fitted to a learned limit)")
        if (learnedLimitChars > 0) appendLine("learned limit: " + learnedLimitChars + " chars")
        if (retriedCeilingField) appendLine("retry: resent once with the other answer-ceiling field")
        appendLine("result: " + outcome + (httpStatus?.let { " (HTTP " + it + ")" } ?: ""))
        if (endReason.isNotBlank()) appendLine("end reason: " + endReason + " (reply ended early)")
        if (providerMessage.isNotBlank()) {
            appendLine("provider says:")
            appendLine(ChatLogSanitizer.redact(providerMessage).trim().take(1200))
        }
        append("(no keys, tokens or headers are included)")
    }

    private fun javaTimeLabel(ts: Long): String {
        val mins = (System.currentTimeMillis() - ts) / 60_000
        return when {
            mins <= 0 -> "just now"
            mins < 60 -> mins.toString() + " min ago"
            else -> (mins / 60).toString() + " h ago"
        }
    }
}

/**
 * Bounded, newest-first, in memory only. A log that grows without limit is a leak; a log that
 * survives a crash is a file with credentials in it. 200 entries covers a long debugging session.
 */
object ChatLog {

    const val MAX_ENTRIES = 200

    private val entries = ArrayDeque<ChatLogEntry>()

    @Synchronized
    fun record(entry: ChatLogEntry) {
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
    }

    @Synchronized
    fun recent(limit: Int = 50): List<ChatLogEntry> = entries.take(limit)

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    /** The whole log as one copyable text block, newest first. */
    fun report(): String = recent(MAX_ENTRIES).joinToString("\n\n---\n\n") { it.report() }
}
