package com.codespace.ide.engine

object EpisodicMemory {
    data class Episode(val id: String, val startedAt: Long, val summary: String, val files: List<String> = emptyList())
    private fun clean(s: String) = s.replace("\t", " ").replace("\n", " ")
    fun render(e: Episode): String =
        listOf(e.id, e.startedAt.toString(), e.files.joinToString(","), e.summary).joinToString("\t") { clean(it) }
    fun parse(line: String): Episode? {
        val p = line.split("\t")
        if (p.size < 4) return null
        val at = p[1].toLongOrNull() ?: return null
        return Episode(p[0], at, p[3], if (p[2].isBlank()) emptyList() else p[2].split(","))
    }
}
