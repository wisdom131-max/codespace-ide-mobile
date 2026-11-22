package com.codespace.ide.engine

/**
 * PROJECT NINE 9.5 (catalog 28) - restate the standing rules where the model is actually looking.
 *
 * A rule stated once at the top of a long conversation stops being followed long before the
 * conversation ends. Restating it near the request is the cheapest fix there is, but it has to
 * stay bounded: an unbounded reminder block grows with every rule the user ever saved.
 *
 * No rules means an empty string, so a caller with nothing to anchor produces a prompt that is
 * byte-identical to the one built without this.
 */
object AttentionAnchors {

    fun block(rules: List<String>, maxRules: Int = 5, maxChars: Int = 400): String {
        val kept = LinkedHashSet<String>()
        var used = 0
        for (rule in rules) {
            val line = rule.trim()
            if (line.isEmpty()) continue
            if (kept.size >= maxRules) break
            val remaining = maxChars - used
            if (remaining <= 0) break
            // Truncate rather than give up. Breaking on an over-long rule meant one wordy saved rule
            // silently disabled every anchor after it, which is the opposite of the point.
            val fitted = when {
                line.length <= remaining -> line
                remaining > 4 -> line.take(remaining - 3).trimEnd() + "..."
                else -> line.take(remaining)
            }
            kept.add(fitted)
            used += fitted.length
        }
        if (kept.isEmpty()) return ""
        return kept.joinToString("\n") { "- " + it }
    }
}
