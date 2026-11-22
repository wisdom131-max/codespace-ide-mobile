package com.codespace.ide.engine

/**
 * PROJECT NINE 9.38 - did the second search method actually earn its place.
 *
 * Fusing two rankings is only worth its cost if the two disagree usefully. If everything the vector
 * side finds is also found literally, the extra work buys nothing and should be switched off; if it
 * finds things literal search never could, that is the evidence for keeping it.
 *
 * Counting that is the whole point, so the numbers are kept as counts rather than a vague score.
 */
object SearchTelemetry {

    data class Counts(val lexicalOnly: Int, val semanticOnly: Int, val both: Int) {
        val total: Int get() = lexicalOnly + semanticOnly + both
        val overlapPercent: Int get() = if (total == 0) 0 else both * 100 / total
    }

    fun compare(lexical: List<String>, semantic: List<String>): Counts {
        val l = lexical.toSet()
        val s = semantic.toSet()
        return Counts(
            lexicalOnly = (l - s).size,
            semanticOnly = (s - l).size,
            both = (l intersect s).size,
        )
    }
}
