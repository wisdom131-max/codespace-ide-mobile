package com.codespace.ide.engine

object JudgeRubric {
    val CRITERIA = listOf("correctness", "completeness", "evidence", "honesty")
    data class Verdict(val scores: Map<String, Int>, val reason: String)
    fun parse(reply: String): Verdict {
        val scores = LinkedHashMap<String, Int>()
        var reason = ""
        for (line in reply.lineSequence()) {
            val t = line.trim()
            val colon = t.indexOf(58.toChar())
            if (colon <= 0) continue
            val k = t.substring(0, colon).trim().lowercase()
            val v = t.substring(colon + 1).trim()
            if (k in CRITERIA) v.toIntOrNull()?.let { scores[k] = it.coerceIn(0, 5) }
            else if (k == "reason") reason = v
        }
        return Verdict(scores, reason)
    }
    fun passes(v: Verdict, min: Int = 3): Boolean = CRITERIA.all { (v.scores[it] ?: 0) >= min }
}
