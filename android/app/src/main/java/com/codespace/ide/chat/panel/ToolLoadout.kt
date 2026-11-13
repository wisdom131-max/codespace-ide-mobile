package com.codespace.ide.chat.panel

/**
 * L4 (owner approval 2026-11-11): the loaded set for the CURRENT task.
 *
 * Scoped deliberately: cleared when a new user message starts, survives every internal continuation
 * of that message, capped so a model cannot pull the whole registry back in one loop, and queried by
 * version so the session knows exactly when to rebuild the system instruction.
 */
internal object ToolLoadout {

    const val MAX_EXTRA = 12

    data class LoadResult(val accepted: List<String>, val rejected: List<String>, val atCap: Boolean, val version: Int)

    private val loaded = LinkedHashSet<String>()
    private var versionCounter = 0
    private var currentMode: ChatMode = ChatMode.AGENT

    /**
     * A new user message starts a new task: the loadout resets, and the mode is recorded so
     * tool_search can enforce it without the model ever naming a mode (which it could lie about).
     */
    @Synchronized
    fun beginTask(mode: ChatMode) {
        currentMode = mode
        if (loaded.isNotEmpty()) versionCounter++
        loaded.clear()
    }

    @Synchronized
    fun mode(): ChatMode = currentMode

    @Synchronized
    fun version(): Int = versionCounter

    @Synchronized
    fun extra(): Set<String> = LinkedHashSet(loaded)

    /**
     * Load names for this task. Mode is enforced HERE, not only at call time: a name the mode does
     * not allow is refused, so discovery can never widen what a mode may do.
     */
    @Synchronized
    fun load(mode: ChatMode, names: List<String>, mcpNames: List<String> = emptyList()): LoadResult {
        val accepted = ArrayList<String>()
        val rejected = ArrayList<String>()
        val allowed = ToolRegistry.exposed(mode).map { it.name }.toSet() + mcpNames
        var atCap = false
        // POST-IMPLEMENTATION (2026-11-13): the version is a change stamp for the loaded SET, so a
        // replay of the same load (a retry, a continuation, a re-executed turn) must not bump it.
        // It used to bump whenever anything was accepted, including names already loaded, which
        // rebuilt the system instruction for a set that had not changed.
        var changed = false
        for (raw in names.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (raw in loaded) { accepted.add(raw); continue }
            if (raw !in allowed) { rejected.add(raw); continue }
            if (loaded.size >= MAX_EXTRA) { atCap = true; rejected.add(raw); continue }
            loaded.add(raw); accepted.add(raw); changed = true
        }
        if (changed) versionCounter++
        return LoadResult(accepted, rejected, atCap, versionCounter)
    }

    /** For tests and for a deliberate reset (new conversation). */
    @Synchronized
    fun clear() {
        loaded.clear(); versionCounter++
    }
}
