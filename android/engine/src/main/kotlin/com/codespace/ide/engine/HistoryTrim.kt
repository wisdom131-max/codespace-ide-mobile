package com.codespace.ide.engine

/**
 * PROJECT NINE 9.1 (catalog 22) - priority-based trimming for the CONVERSATION transcript.
 *
 * ToolContextTrim already trims tool output. Nothing trimmed the transcript, so a long
 * conversation was cut by dropping whatever was oldest, which is the wrong rule: the oldest turn
 * often holds the instruction the user opened with.
 *
 * So the caller labels each turn and this decides what goes. Lowest priority drops first, oldest
 * before newest inside a priority, and a pinned turn is never dropped at all.
 */
object HistoryTrim {

    enum class Priority { MUST_KEEP, HIGH, NORMAL, LOW }

    data class Entry(
        val index: Int,
        val priority: Priority,
        val tokens: Int,
        val pinned: Boolean = false,
    )

    data class Plan(
        val dropped: Set<Int>,
        val keptTokens: Int,
        val droppedTokens: Int,
        /** False when even dropping everything droppable leaves the transcript over budget. */
        val fits: Boolean,
    )

    fun plan(entries: List<Entry>, budgetTokens: Int): Plan {
        val total = entries.sumOf { it.tokens }
        if (total <= budgetTokens) return Plan(emptySet(), total, 0, true)
        var over = total - budgetTokens
        val dropped = LinkedHashSet<Int>()
        val order = entries
            .filter { !it.pinned && it.priority != Priority.MUST_KEEP }
            .sortedWith(compareByDescending<Entry> { it.priority.ordinal }.thenBy { it.index })
        for (e in order) {
            if (over <= 0) break
            dropped.add(e.index)
            over -= e.tokens
        }
        val droppedTokens = entries.filter { dropped.contains(it.index) }.sumOf { it.tokens }
        val kept = total - droppedTokens
        return Plan(dropped, kept, droppedTokens, kept <= budgetTokens)
    }
}
