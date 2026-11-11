package com.codespace.ide.agent.research

/**
 * R5 (owner approval 2026-11-11): every claim needs a source the reader can open.
 *
 * The ledger is process-local, bounded and cleared at the start of each task. IDs are stable within
 * a task ([S1], [S2]...) so the model can cite the same page twice without inventing a new number,
 * and a source is only added when a fetch actually returned readable content: an attempt that failed
 * is not a citation.
 */
object SourceLedger {

    data class Source(
        val id: String,
        val title: String,
        val url: String,
        val domain: String,
        val retrievedAt: String,
        val publishedAt: String,
        val excerpt: String,
    )

    private const val MAX = 24
    private val sources = ArrayList<Source>()

    @Synchronized
    fun reset() {
        sources.clear()
    }

    @Synchronized
    fun note(
        url: String,
        title: String,
        retrievedAt: String,
        publishedAt: String = "",
        excerpt: String = "",
    ): Source {
        val key = UrlTools.normalize(url)
        sources.firstOrNull { UrlTools.normalize(it.url) == key }?.let { return it }
        val s = Source(
            id = "S" + (sources.size + 1),
            title = title.ifBlank { UrlTools.domain(url).ifBlank { url } }.take(120),
            url = url,
            domain = UrlTools.domain(url),
            retrievedAt = retrievedAt,
            publishedAt = publishedAt.take(31),
            excerpt = excerpt.replace(Regex("\\s+"), " ").trim().take(400),
        )
        if (sources.size < MAX) sources.add(s)
        return s
    }

    @Synchronized
    fun all(): List<Source> = sources.toList()

    @Synchronized
    fun render(from: List<Source> = sources): String {
        if (from.isEmpty()) {
            return "Sources: none retrieved. State plainly that the answer is unsourced rather " +
                "than presenting it as verified."
        }
        return buildString {
            append("Sources:\n")
            from.forEach { s ->
                append("[").append(s.id).append("] ").append(s.domain)
                append(" \u00b7 ").append(s.title)
                if (s.publishedAt.isNotBlank()) append(" \u00b7 ").append(s.publishedAt)
                append(" \u00b7 retrieved ").append(s.retrievedAt)
                append("\n    ").append(s.url).append('\n')
            }
        }.trim()
    }

    /** Markdown links the chat renderer can make tappable. */
    @Synchronized
    fun markdown(from: List<Source> = sources): String =
        from.joinToString("\n") { s -> "[" + s.id + "] " + s.title + " \u2014 " + s.url }
}
