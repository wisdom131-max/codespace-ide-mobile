package com.codespace.ide.engine

object EvalDataset {
    data class EvalCase(val id: String, val request: String, val expected: String, val tags: List<String> = emptyList())
    private fun clean(s: String) = s.replace("\t", " ").replace("\n", " ")
    fun render(cases: List<EvalCase>): String = cases.joinToString("\n") { c ->
        listOf(c.id, c.tags.joinToString(","), c.request, c.expected).joinToString("\t") { clean(it) }
    }
    fun parse(text: String): List<EvalCase> = text.lineSequence().mapNotNull { line ->
        val p = line.split("\t")
        if (p.size < 4) null
        else EvalCase(p[0], p[2], p[3], if (p[1].isBlank()) emptyList() else p[1].split(","))
    }.toList()
}
