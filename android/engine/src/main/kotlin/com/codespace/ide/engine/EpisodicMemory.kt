package com.codespace.ide.engine

object EpisodicMemory {

    /**
     * Where episodes are kept. The type could render and parse an episode and there was nowhere to
     * put one, which is exactly why item 36 sat unwired: the gap was storage, not logic.
     *
     * Bounded, because a phone should not accumulate every turn forever.
     */
    class Shelf(private val maxEpisodes: Int = 50) {

        private val items = ArrayDeque<Episode>()

        fun save(e: Episode) {
            items.addLast(e)
            while (items.size > maxEpisodes) items.removeFirst()
        }

        fun recent(): List<Episode> = items.toList()

        fun size(): Int = items.size
    }

    private val shared = Shelf()

    fun record(id: String, startedAt: Long, summary: String, files: List<String> = emptyList()) {
        shared.save(Episode(id, startedAt, summary, files))
    }

    fun recentEpisodes(): List<Episode> = shared.recent()
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
