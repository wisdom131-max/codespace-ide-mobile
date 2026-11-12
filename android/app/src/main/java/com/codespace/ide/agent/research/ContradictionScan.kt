package com.codespace.ide.agent.research

/**
 * PROJECT 1.3 - WHERE THE SOURCES DISAGREE.
 *
 * Different wording is not a contradiction, so this looks only for claims that can be compared:
 * a version or quantity attached to a named subject, and the polarity of a statement about a
 * subject ("supports" against "does not support"). Two sources only dispute each other when they
 * name the SAME subject and give DIFFERENT values in the SAME unit - anything else is left alone
 * rather than dressed up as a finding.
 *
 * Nothing here picks a winner. A dispute keeps every source and quote on both sides so the answer
 * must present the disagreement instead of quietly trusting the first result.
 */
object ContradictionScan {

    data class Claim(
        /** Normalized subject, e.g. "min sdk android". */
        val subject: String,
        /** The value compared: a number with unit, or "yes"/"no" for polarity. */
        val value: String,
        val sourceId: String,
        val quote: String,
    )

    data class Dispute(val subject: String, val claims: List<Claim>)

    data class Report(
        /** Same value, two or more distinct sources. */
        val confirmed: List<Claim> = emptyList(),
        /** Sources disagree and both sides are preserved. */
        val disputed: List<Dispute> = emptyList(),
        /** One source only: usable, unconfirmed. */
        val unconfirmed: List<Claim> = emptyList(),
    )

    private const val MAX_SENTENCES_PER_SOURCE = 12
    private const val MAX_SENTENCE_CHARS = 320
    private const val MAX_CLAIMS = 200

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "for", "to", "in", "on", "is", "are", "was", "were",
        "with", "from", "that", "this", "it", "be", "as", "by", "at", "we", "you", "they",
    )

    /**
     * Extract comparable claims from [evidence], a list of sourceId to readable text.
     * Bounded: at most 12 sentences and 320 characters per source, at most 200 claims overall.
     */
    fun claims(evidence: List<Pair<String, String>>): List<Claim> {
        val out = ArrayList<Claim>()
        for ((id, text) in evidence) {
            if (out.size >= MAX_CLAIMS) break
            val flat = text.replace(Regex("\\s+"), " ")
            sentences(flat).take(MAX_SENTENCES_PER_SOURCE).forEach { s ->
                if (out.size >= MAX_CLAIMS) return@forEach
                claimFrom(s, id)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length in 12..MAX_SENTENCE_CHARS }

    /**
     * One claim per sentence. Numbers win: "Android 14 requires min sdk 34" is more useful than
     * its polarity, and a number plus a unit is the only comparison that can be checked exactly.
     */
    private fun claimFrom(sentence: String, sourceId: String): Claim? {
        val lower = sentence.lowercase()
        val num = Regex("([a-z][a-z0-9_.+#-]{1,}(?:\\s+[a-z][a-z0-9_.+#-]{1,}){0,2})\\s+" +
            "(?:version\\s+|is\\s+|requires\\s+|needs\\s+)?(\\d+(?:\\.\\d+){0,2})\\s*(ms|mb|gb|kb|%|s)?\\b")
            .find(lower)
        if (num != null) {
            val subject = key(num.groupValues[1])
            if (subject.isBlank()) return null
            val value = num.groupValues[2] + num.groupValues[3]
            return Claim(subject, value, sourceId, sentence)
        }
        val neg = Regex("\\b" + "(does not|doesn't|do not|don't|no longer|cannot|can't|is not|isn't|" +
            "never|unsupported|not supported)" + "\\b").find(lower)
        if (neg == null) return null
        // Subject = the content words after the negation, so "does not support X" keys on X.
        val after = lower.substring(neg.range.last + 1).trim()
        val subject = key(after.split(Regex("[^a-z0-9_.+#-]+")).take(4).joinToString(" "))
        if (subject.isBlank()) return null
        return Claim(subject, "no", sourceId, sentence)
    }

    private fun key(raw: String): String =
        raw.split(Regex("[^a-z0-9_.+#-]+"))
            .filter { it.length > 1 && it !in STOP }
            .distinct()
            .joinToString(" ")

    /**
     * Compare the claims. A dispute needs two DIFFERENT values from two DIFFERENT sources on the
     * same subject; a subject whose sources agree is confirmed, and one source alone is unconfirmed.
     */
    fun report(evidence: List<Pair<String, String>>): Report {
        val all = claims(evidence)
        if (all.isEmpty()) return Report()
        val bySubject = all.groupBy { it.subject }
        val confirmed = ArrayList<Claim>()
        val disputed = ArrayList<Dispute>()
        val unconfirmed = ArrayList<Claim>()
        for ((subject, group) in bySubject) {
            val distinctSources = group.map { it.sourceId }.distinct()
            val values = group.map { it.value }.distinct()
            when {
                distinctSources.size == 1 -> unconfirmed.add(group.first())
                values.size > 1 && comparableValues(values) -> disputed.add(Dispute(subject, group))
                else -> confirmed.add(group.first())
            }
        }
        return Report(confirmed, disputed, unconfirmed)
    }

    /**
     * Only values of the same shape are comparable: "34" against "35", "200 ms" against "300 ms".
     * A version against a duration, or a bare number against a percentage, is not a conflict.
     */
    private fun comparableValues(values: List<String>): Boolean {
        val shapes = values.map { v -> v.filter { it.isDigit() || it == '.' }.takeIf { it.isNotBlank() }.let { it != null } }
            .distinct()
        if (shapes.size > 1) return false
        val units = values.map { v -> v.filter { !it.isDigit() && it != '.' } }.distinct()
        return units.size == 1
    }

    /** Text for the answer: what is confirmed, what is disputed, what rests on one source. */
    fun render(report: Report): String {
        if (report.confirmed.isEmpty() && report.disputed.isEmpty() && report.unconfirmed.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        if (report.disputed.isNotEmpty()) {
            sb.append("Sources disagree. Present both positions; do not pick one silently.\n")
            report.disputed.take(5).forEach { d ->
                sb.append("  ").append(d.subject).append(": ")
                d.claims.groupBy { it.value }.forEach { (value, group) ->
                    sb.append(value).append(" (")
                        .append(group.map { it.sourceId }.distinct().joinToString(", ")).append(")  ")
                }
                sb.append('\n')
                d.claims.take(3).forEach { c ->
                    sb.append("    [").append(c.sourceId).append("] ").append(c.quote.take(160)).append('\n')
                }
            }
        }
        if (report.confirmed.isNotEmpty()) {
            sb.append("Corroborated by more than one source: ")
            sb.append(report.confirmed.take(5).joinToString("; ") { it.subject + " = " + it.value })
            sb.append('\n')
        }
        if (report.unconfirmed.isNotEmpty()) {
            sb.append("Rests on a single source, so say so rather than presenting it as settled: ")
            sb.append(report.unconfirmed.take(5).joinToString("; ") { it.subject + " = " + it.value + " [" + it.sourceId + "]" })
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}
