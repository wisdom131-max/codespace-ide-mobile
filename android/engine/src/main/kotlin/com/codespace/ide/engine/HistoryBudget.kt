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
    /**
     * The result of fitting history to a HARD request budget (not the mode preference above).
     * Reported, not logged silently: the owner is entitled to know that older turns did not travel.
     */
    data class Fit(val messages: List<ChatMessage>, val droppedMessages: Int, val droppedChars: Int)

    /**
     * Fit the window inside a hard character budget, oldest turns dropped first.
     *
     * Priority, and the order is the point:
     *  1. The NEWEST message always travels, even alone and even if it busts the budget. Dropping
     *     the sentence the owner just typed would answer a question nobody asked; refusing and
     *     saying "too large" is the honest outcome, and that is a verdict we now have.
     *  2. A turn's assistant reply is dropped together with the user message that produced it, so
     *     the model never sees an answer to a question that is no longer in the window.
     *  3. Oldest goes first. Recent context is worth more than the start of the conversation.
     *
     * budgetChars <= 0 means "no hard budget": the call is a no-op and today's behaviour is kept
     * byte for byte, which is what every existing endpoint gets until a limit is actually learned.
     */
    fun fit(
        history: List<ChatMessage>,
        mode: HistoryMode,
        systemChars: Int,
        budgetChars: Int,
    ): Fit {
        val selected = select(history, mode)
        if (budgetChars <= 0) return Fit(selected, 0, 0)
        val space = (budgetChars - systemChars).coerceAtLeast(0)
        var total = selected.sumOf { it.text.length }
        if (total <= space) return Fit(selected, 0, 0)
        // Group into TURNS first: a user message and everything the model answered belong together.
        // Dropping one without the other leaves the model reading an answer to a question it can no
        // longer see, which is worse than having less history.
        val groups = ArrayList<MutableList<ChatMessage>>()
        for (m in selected) {
            if (m.role == ChatRole.USER || groups.isEmpty()) groups.add(mutableListOf(m))
            else groups.last().add(m)
        }
        val keptGroups = ArrayList<MutableList<ChatMessage>>()
        var chars = 0
        for (g in groups.indices.reversed()) {
            val gChars = groups[g].sumOf { it.text.length }
            val isNewest = keptGroups.isEmpty()
            if (!isNewest && chars + gChars > space) break
            keptGroups.add(groups[g])
            chars += gChars
        }
        keptGroups.reverse()
        val result: List<ChatMessage> = keptGroups.flatten()
        val droppedMessages = selected.size - result.size
        val droppedChars = selected.sumOf { it.text.length } - result.sumOf { it.text.length }
        return Fit(result, droppedMessages, droppedChars)
    }

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
