package com.codespace.ide.agent.research

/**
 * 2026-11-11 (owner: sources must be tappable): the one place that decides whether a source may be
 * OPENED, and the one place that rebuilds sources for a restored conversation.
 *
 * Two jobs, both pure so they are asserted rather than discovered:
 *  - sanitise: keep http/https only, drop duplicates by normalised URL, fill a missing title from
 *    the domain, and keep the ledger's IDs so a source keeps its identity between tool result,
 *    transcript row and restored conversation;
 *  - parse: the answer text already carries a "Sources:" block with markdown links, because that is
 *    what the model was shown. Restoration reads that block instead of adding a second stored
 *    format, so an OLD conversation (written before this row existed) also restores its sources.
 */
object ResearchSources {

    /** One tappable row. `id` is the ledger's stable id (S1, S2...), never an index. */
    data class Item(val id: String, val title: String, val domain: String, val url: String)

    private val OPENABLE = Regex("(?i)^https?://[^\\s<>\"']+$")

    fun isOpenable(url: String): Boolean {
        val u = url.trim()
        if (!OPENABLE.matches(u)) return false
        // A scheme-relative or javascript: URL never reaches here, but a URL with control
        // characters can still confuse an Intent, so require a printable host.
        if (u.any { it.code < 32 }) return false
        return UrlTools.domain(u).isNotBlank()
    }

    fun fromLedger(sources: List<SourceLedger.Source>): List<Item> = sanitise(
        sources.map { Item(it.id, it.title, it.domain, it.url) }
    )

    fun sanitise(items: List<Item>): List<Item> {
        val seen = HashSet<String>()
        val out = ArrayList<Item>(items.size)
        for (raw in items) {
            val url = raw.url.trim()
            if (!isOpenable(url)) continue
            val key = UrlTools.normalize(url)
            if (!seen.add(key)) continue
            val domain = raw.domain.ifBlank { UrlTools.domain(url) }
            val title = raw.title.trim().ifBlank { domain.ifBlank { url } }.take(120)
            out.add(Item(raw.id.ifBlank { "S" + (out.size + 1) }, title, domain, url))
        }
        return out
    }

    /**
     * Read the "Sources:" block out of an assistant message.
     * Accepted line shapes, both produced by the ledger and by older builds:
     *   [1] Title — domain — retrieved 12:30 — [url](https://...)
     *   1. Title - https://...
     */
    fun parse(answer: String): List<Item> {
        if (answer.isBlank()) return emptyList()
        val tail = answer.substring(answer.lastIndexOf("Sources:", ignoreCase = false).coerceAtLeast(0))
        if (!tail.contains("Sources:")) return emptyList()
        val items = ArrayList<Item>()
        var n = 0
        for (line in tail.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Sources:")) continue
            val url = Regex("\\((https?://[^)\\s]+)\\)").find(trimmed)?.groupValues?.get(1)
                ?: Regex("(https?://[^\\s)\\]|]+)").find(trimmed)?.groupValues?.get(1)
                ?: continue
            if (!isOpenable(url)) continue
            n++
            val title = trimmed
                .substringBefore("—").substringBefore(" - ")
                .replaceFirst(Regex("^\\[\\d+]\\s*"), "")
                .replaceFirst(Regex("^\\d+[.)]\\s*"), "")
                .trim()
            items.add(Item("S" + n, title, UrlTools.domain(url), url))
        }
        return sanitise(items)
    }
}
