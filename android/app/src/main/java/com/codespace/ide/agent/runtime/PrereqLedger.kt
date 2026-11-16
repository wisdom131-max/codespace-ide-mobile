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
        /** Stage 4.3: which conversation and plan revision this belongs to. */
        val session: String = "",
        val revision: Int = 0,
        /** True when the call changed something, so repeating it would duplicate the change. */
        val sideEffect: Boolean = false,
        val at: Long = 0L,
    )

    private const val CAP = 64

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun clear() {
        entries.clear()
        store?.clear()
    }

    /** Stage 4.3: the durable copy. Null means "this run keeps its evidence in memory only". */
    @Volatile
    private var store: PrereqStore? = null

    @Volatile
    private var session: String = ""

    @Volatile
    private var revision: Int = 0

    /** Bind the durable store and adopt anything it already holds for this conversation. */
    @Synchronized
    fun attach(durable: PrereqStore?, sessionId: String?, planRevision: Int, adopt: Boolean = true) {
        store = durable
        session = sessionId.orEmpty()
        revision = planRevision
        if (adopt && durable != null) {
            durable.load().entries.forEach { restored ->
                if (restored.session.isNotBlank() && session.isNotBlank() && restored.session != session) return@forEach
                entries[restored.capability] = restored.copy(session = session.ifBlank { restored.session }, revision = if (planRevision > 0) planRevision else restored.revision)
            }
        }
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.values.toList()

    @Synchronized
    fun record(capability: String, solvedBy: String, evidence: String, sideEffect: Boolean = false) {
        val key = capability.trim()
        if (key.isEmpty()) return
        if (entries.size >= CAP && !entries.containsKey(key)) {
            entries.remove(entries.keys.first())
        }
        entries[key] = Entry(
            capability = key,
            solvedBy = solvedBy,
            evidence = evidence.trim().take(240),
            session = session,
            revision = revision,
            sideEffect = sideEffect,
            at = System.currentTimeMillis(),
        )
        store?.save(entries.values.toList())
    }

    @Synchronized
    fun recordFailure(capability: String, solvedBy: String, reason: String) {
        val key = capability.trim()
        if (key.isEmpty()) return
        if (entries.size >= CAP && !entries.containsKey(key)) {
            entries.remove(entries.keys.first())
        }
        entries[key] = Entry(
            capability = key,
            solvedBy = solvedBy,
            evidence = reason.trim().take(240),
            failed = true,
            session = session,
            revision = revision,
            sideEffect = true,
            at = System.currentTimeMillis(),
        )
        store?.save(entries.values.toList())
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
