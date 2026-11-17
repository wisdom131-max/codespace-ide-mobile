package com.codespace.ide.chat.ui

import com.codespace.ide.agent.research.ResearchSources

/**
 * PROJECT SEVEN 7.8 — what the research surface says, derived only from what the runtime actually returned.
 *
 * The sanitising that matters was already there: `ResearchSources.sanitise` drops unopenable URLs, removes
 * duplicates, derives a missing domain from the URL and falls back to the domain when a title is blank. It
 * was verifiably NOT rebuilt here. What it lacked was a bound for very large lists: forty rows is already
 * more than a reader scans, and a hundred is a wall. Bounding must never look like the rest were fine to
 * lose, so the count of what is not drawn is stated.
 */
object ResearchPresentation {

    /** Rows rendered before the list is summarised. */
    const val MAX_ROWS = 40

    data class Bounded(val shown: List<ResearchSources.Item>, val hidden: Int)

    fun bound(items: List<ResearchSources.Item>, max: Int = MAX_ROWS): Bounded =
        if (items.size <= max) Bounded(items, 0)
        else Bounded(items.take(max), items.size - max)

    /** Null when nothing was omitted - the ordinary answer draws exactly as before. */
    fun overflowLine(hidden: Int): String? =
        if (hidden <= 0) null
        else "+" + hidden + " more sources are cited in the answer but not listed here."

    /** One TalkBack sentence per source, including the fact that the metadata comes from the URL. */
    fun label(item: ResearchSources.Item): String {
        val domain = item.domain.ifBlank { "domain unknown" }
        val opens = if (ResearchSources.isOpenable(item.url)) "opens the source" else "cannot be opened"
        return item.title.ifBlank { "Untitled source" } + ", " + domain + ", " + opens
    }

    fun header(count: Int): String = when (count) {
        0 -> "No sources"
        1 -> "1 source"
        else -> count.toString() + " sources"
    }

    /** Research that produced nothing must say so rather than render an empty block. */
    fun emptyNote(): String = "Research returned no usable source. That is a result, not a success."
}
