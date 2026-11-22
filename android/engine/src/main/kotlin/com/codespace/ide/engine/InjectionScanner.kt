package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.15 (catalog 77) - pasted content is DATA, never instructions.
 *
 * The app is now for other people, and strangers paste anything: issue threads, README files,
 * stack traces, error pages. Any of those can contain a line addressed to the model. The old
 * defence was a handful of `contains` checks scattered at the call sites, which meant a new call
 * site silently had no defence at all.
 *
 * This is a SCORE, not a verdict about intent. A security article that says "ignore previous
 * instructions" while explaining prompt injection is CLEAN text that happens to contain the
 * phrase; scoring rather than blocking is what lets the caller decide (label it, ask, or refuse)
 * instead of destroying a legitimate paste. Nothing here executes or removes anything.
 */
object InjectionScanner {

    enum class Verdict { CLEAN, SUSPICIOUS, HOSTILE }

    /** One matched signal and what it cost. [weight] is why the score moved. */
    data class Hit(val signal: String, val weight: Int)

    data class Finding(val verdict: Verdict, val score: Int, val hits: List<Hit>) {
        val isClean: Boolean get() = verdict == Verdict.CLEAN
        /** One line for the chat log; says WHAT matched, never the matched text itself. */
        fun describe(): String =
            if (isClean) "no injection signals"
            else verdict.name.lowercase() + " (score " + score + "): " +
                hits.joinToString(", ") { it.signal }
    }

    private const val SUSPICIOUS_AT = 4
    private const val HOSTILE_AT = 8

    // Ordered most specific first: the phrase "ignore previous instructions" must not also be
    // counted by the weaker "ignore" rule, or one sentence would score twice.
    private val RULES: List<Pair<String, Int>> = listOf(
        "ignore all previous instructions" to 6,
        "ignore previous instructions" to 6,
        "ignore the above instructions" to 6,
        "disregard all previous" to 6,
        "disregard the above" to 5,
        "forget your instructions" to 6,
        "forget all previous" to 5,
        "reveal your system prompt" to 6,
        "print your system prompt" to 6,
        "show your instructions" to 4,
        "repeat your instructions" to 4,
        "your new instructions" to 4,
        "new system prompt" to 4,
        "you are now" to 3,
        "act as if you" to 2,
        "override your" to 4,
        "jailbreak" to 3,
        "developer mode" to 2,
        "do anything now" to 4,
        "system:" to 2,
        "<|im_start|>" to 5,
        "<|system|>" to 5,
    )

    /**
     * Scores [text] once. [extraContext] is scanned too but never changes the verdict on its own,
     * so a caller can pass a filename without a long path tipping a clean document over.
     */
    fun scan(text: String): Finding {
        if (text.isBlank()) return Finding(Verdict.CLEAN, 0, emptyList())
        val lower = text.lowercase()
        val hits = ArrayList<Hit>()
        var consumed = lower
        for ((signal, weight) in RULES) {
            if (consumed.contains(signal)) {
                hits.add(Hit(signal, weight))
                // Remove so a weaker overlapping rule cannot double-count the same words.
                consumed = consumed.replace(signal, " ")
            }
        }
        val score = hits.sumOf { it.weight }
        val verdict = when {
            score >= HOSTILE_AT -> Verdict.HOSTILE
            score >= SUSPICIOUS_AT -> Verdict.SUSPICIOUS
            else -> Verdict.CLEAN
        }
        return Finding(verdict, score, hits)
    }
}
