package com.codespace.ide.engine

/**
 * History budget: how much of the session transcript rides along on a send.
 * Compact/Balanced/Full keep the most recent N messages within a char cap
 * (Compact 6 / 12k, Balanced 12 / 24k, Full 24 / 48k) — the surviving modes
 * from the archived engine, rebuilt without its history-budget code.
 * The newest messages always survive; oldest are dropped first.
 */
enum class HistoryMode(val keepMessages: Int, val maxChars: Int) {
    COMPACT(6, 12_000),
    BALANCED(12, 24_000),
    FULL(24, 48_000);

    companion object {
        fun parse(name: String?): HistoryMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BALANCED
    }
}

object HistoryBudget {

    /**
     * Select the outgoing message window: last keepMessages messages, then the
     * oldest dropped until the char cap fits. Returns the same list instance
     * when it already fits (cheap path — allocation-free for typical sends).
     */
    fun select(history: List<ChatMessage>, mode: HistoryMode): List<ChatMessage> {
        val recent = if (history.size <= mode.keepMessages) history
                     else history.takeLast(mode.keepMessages)
        var total = recent.sumOf { it.text.length }
        if (total <= mode.maxChars) return recent
        // Over cap: keep from the newest backward, oldest dropped first.
        val kept = ArrayList<ChatMessage>()
        var chars = 0
        for (i in recent.indices.reversed()) {
            val m = recent[i]
            if (chars + m.text.length > mode.maxChars && kept.isNotEmpty()) break
            kept.add(m)
            chars += m.text.length
        }
        kept.reverse()
        return kept
    }
}
