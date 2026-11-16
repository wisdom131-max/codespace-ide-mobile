package com.codespace.ide.agent.runtime

/**
 * PROJECT FOUR, STAGE 4.2 (2026-11-16): which prerequisites have actually happened.
 *
 * A plan is a claim about the future until something proves a prerequisite ran. This ledger is that
 * proof, and it is deliberately separate from the plan and from permissions:
 *
 *  - it records EVIDENCE (the tool's real output, trimmed) next to the capability it satisfied, so a
 *    dependent call can be refused with "find never ran" instead of "try something else";
 *  - it records failures too, because a failed prerequisite must block its dependents rather than
 *    quietly leave them "pending";
 *  - it never grants anything. A satisfied capability permits a dependent call to reach the existing
 *    gate, where the mode, approval, MCP and role checks all still apply exactly as before.
 *
 * Session-scoped and bounded: this is a record of the current turn, not a history of the project.
 */
internal object PrereqLedger {

    data class Entry(
        val capability: String,
        val solvedBy: String,
        val evidence: String,
        val failed: Boolean = false,
    )

    private const val CAP = 64

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun record(capability: String, solvedBy: String, evidence: String) {
        val key = capability.trim()
        if (key.isEmpty()) return
        if (entries.size >= CAP && !entries.containsKey(key)) {
            entries.remove(entries.keys.first())
        }
        entries[key] = Entry(key, solvedBy, evidence.trim().take(240))
    }

    @Synchronized
    fun recordFailure(capability: String, solvedBy: String, reason: String) {
        val key = capability.trim()
        if (key.isEmpty()) return
        if (entries.size >= CAP && !entries.containsKey(key)) {
            entries.remove(entries.keys.first())
        }
        entries[key] = Entry(key, solvedBy, reason.trim().take(240), failed = true)
    }

    @Synchronized
    fun satisfied(capability: String): Boolean = entries[capability]?.failed == false

    @Synchronized
    fun failed(capability: String): Boolean = entries[capability]?.failed == true

    @Synchronized
    fun entry(capability: String): Entry? = entries[capability]

    @Synchronized
    fun satisfiedSet(): Set<String> = entries.values.filter { !it.failed }.map { it.capability }.toSet()

    @Synchronized
    fun failedSet(): Set<String> = entries.values.filter { it.failed }.map { it.capability }.toSet()

    @Synchronized
    fun lines(): List<String> = entries.values.map { entry ->
        (if (entry.failed) "failed: " else "done: ") + entry.capability + " via " + entry.solvedBy
    }
}
