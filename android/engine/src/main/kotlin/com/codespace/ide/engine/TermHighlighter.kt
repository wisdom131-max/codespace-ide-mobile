package com.codespace.ide.engine

/**
 * PROJECT NINE 9.37 - mark which part of the snippet is why it matched.
 *
 * A snippet shows five lines and the model has to guess which word earned it. Marking the terms
 * removes that guess, and it is cheap because it is one pass over a short string.
 *
 * One pass matters: replacing term by term would let a later term match inside an earlier mark and
 * produce nested garbage, so the terms are combined into a single pattern.
 */
object TermHighlighter {

    private const val OPEN = "\u00ab"
    private const val CLOSE = "\u00bb"

    fun mark(text: String, terms: List<String>): String {
        val clean = terms.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return text
        val pattern = Regex(clean.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        return pattern.replace(text) { m -> OPEN + m.value + CLOSE }
    }
}
