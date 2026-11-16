package com.codespace.ide.chat.panel

/**
 * PROJECT 4.1 (2026-11-16): a tool that keeps failing should stop looking attractive.
 *
 * Ranking has no memory. A tool that failed twice in a row this session scores exactly as it did
 * before, so the model rediscovers the same failure, and the third attempt costs the same again. This
 * is a small, bounded record of real dispatch outcomes, and a demotion that the ranker can read.
 *
 * Rules, each chosen to fail safe:
 *
 *  - A success clears the streak entirely. One good call is evidence the tool works.
 *  - Two consecutive failures demote it by [DEMOTION]; a third does not demote it further, because
 *    a tool that is truly broken is caught by the refusal path, not by ever-deeper penalties.
 *  - The map is capped and evicts the oldest entry, so a long session cannot grow it without bound.
 *  - It only reorders proposals. It never disables a tool and never changes a permission decision:
 *    a tool the user asked for by name still runs, and failure is judged by [ToolFailurePolicy].
 */
internal object ToolHealth {

    /** How much a demoted tool loses in the relevance score. Enough to lose a tie, not a request. */
    const val DEMOTION = 30

    /** Failures in a row before demotion. One failure is a coincidence. */
    const val THRESHOLD = 2

    private const val CAP = 40

    private class Record(var failures: Int = 0, var lastTouched: Long = 0L)

    private val records = LinkedHashMap<String, Record>()

    private var clock = 0L

    @Synchronized
    fun recordFailure(name: String) {
        val record = records.getOrPut(name) { Record() }
        record.failures += 1
        record.lastTouched = ++clock
        prune()
    }

    @Synchronized
    fun recordSuccess(name: String) {
        records.remove(name)
    }

    @Synchronized
    fun failures(name: String): Int = records[name]?.failures ?: 0

    /** The score adjustment a ranker adds: 0 while the tool is trusted, negative once it is not. */
    @Synchronized
    fun adjustment(name: String): Int =
        if (failures(name) >= THRESHOLD) -DEMOTION else 0

    @Synchronized
    fun demoted(): Set<String> = records.filterValues { it.failures >= THRESHOLD }.keys.toSet()

    @Synchronized
    fun clear() {
        records.clear()
    }

    private fun prune() {
        while (records.size > CAP) {
            val oldest = records.entries.minByOrNull { it.value.lastTouched } ?: return
            records.remove(oldest.key)
        }
    }
}
