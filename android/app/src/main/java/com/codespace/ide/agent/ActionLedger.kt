package com.codespace.ide.agent

import java.io.File

/**
 * PROJECT 4 (2026-11-20): the single six-state action ledger.
 *
 * Every action the agent intends to take gets one stable id and one state, in one place:
 *
 *     PLANNED -> APPROVED -> EXECUTING -> COMPLETED
 *                    |           |  \\-> FAILED
 *                    |           \\----> CANCELLED
 *                    \\----------------> CANCELLED / FAILED
 *
 * The rules this file exists to enforce:
 *
 *  - PLANNED is recorded before execution, never after.
 *  - APPROVED is only ever written by an actual approval decision.
 *  - EXECUTING may only begin from APPROVED, or from PLANNED when the action needs no approval.
 *    An action that requires approval can never begin from PLANNED, so a permissive agent setting
 *    cannot become an execution permission by editing ledger state.
 *  - Terminal states are final. A repeated, late or out-of-order event cannot rewrite them.
 *  - PLANNED and APPROVED are not evidence of execution: only EXECUTING, COMPLETED and FAILED mean
 *    anything ran.
 *  - Each action has a stable identity; recording the same id twice changes nothing, so concurrent
 *    callers cannot overwrite one another.
 *
 * This is a record, not a gate. The authoritative gate stays AgentTools.executeTool; this ledger is
 * written by that path and can never authorize execution on its own.
 */
class ActionLedger(private val file: File?) {

    enum class State { PLANNED, APPROVED, EXECUTING, COMPLETED, FAILED, CANCELLED }

    data class Action(
        val id: String,
        val tool: String,
        val sessionId: String,
        val state: State,
        val requiresApproval: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val detail: String = "",
    ) {
        val terminal: Boolean get() = state == State.COMPLETED || state == State.FAILED ||
            state == State.CANCELLED
        /** True only when this action actually began. PLANNED and APPROVED do not count. */
        val ran: Boolean get() = state == State.EXECUTING || state == State.COMPLETED ||
            state == State.FAILED
    }

    companion object {
        const val MAX_ACTIONS = 200

        fun isTerminal(state: State): Boolean =
            state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED

        /** The only legal transitions. Everything else is refused, not coerced. */
        fun allowed(from: State, to: State): Boolean = when (from) {
            State.PLANNED -> to == State.APPROVED || to == State.EXECUTING ||
                to == State.CANCELLED || to == State.FAILED
            State.APPROVED -> to == State.EXECUTING || to == State.CANCELLED || to == State.FAILED
            State.EXECUTING -> to == State.COMPLETED || to == State.FAILED || to == State.CANCELLED
            State.COMPLETED, State.FAILED, State.CANCELLED -> false
        }

        fun forSession(file: File?): ActionLedger = ActionLedger(file)
    }

    private val lock = Any()
    private val actions = LinkedHashMap<String, Action>()

    init {
        restore()
    }

    // ── recording ───────────────────────────────────────────────────────────

    /**
     * Record an intention. Returns the action, or null when this id already exists (a duplicate
     * plan is not a new action) or when the id is blank (no stable identity, no record).
     */
    fun plan(
        id: String,
        tool: String,
        sessionId: String,
        requiresApproval: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Action? = synchronized(lock) {
        val key = id.trim()
        if (key.isEmpty()) return null
        if (actions.containsKey(key)) return null
        val action = Action(key, tool, sessionId, State.PLANNED, requiresApproval, now, now)
        actions[key] = action
        trim()
        persist()
        action
    }

    /** Written only when a real approval decision arrived. */
    fun approve(id: String, now: Long = System.currentTimeMillis()): Boolean =
        move(id, State.APPROVED, now)

    /** Execution may begin. Refused for an approval-gated action that was never approved. */
    fun begin(id: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val action = actions[id] ?: return false
        if (action.state == State.PLANNED && action.requiresApproval) return false
        move(id, State.EXECUTING, now)
    }

    fun complete(id: String, now: Long = System.currentTimeMillis(), detail: String = ""): Boolean =
        move(id, State.COMPLETED, now, detail)

    fun fail(id: String, now: Long = System.currentTimeMillis(), detail: String = ""): Boolean =
        move(id, State.FAILED, now, detail)

    fun cancel(id: String, now: Long = System.currentTimeMillis(), detail: String = ""): Boolean =
        move(id, State.CANCELLED, now, detail)

    private fun move(
        id: String,
        to: State,
        now: Long,
        detail: String = "",
    ): Boolean = synchronized(lock) {
        val action = actions[id] ?: return false
        if (!allowed(action.state, to)) return false
        actions[id] = action.copy(
            state = to,
            updatedAt = now,
            detail = detail.ifBlank { action.detail },
        )
        persist()
        true
    }

    // ── reading ─────────────────────────────────────────────────────────────

    fun action(id: String): Action? = synchronized(lock) { actions[id] }

    fun all(): List<Action> = synchronized(lock) { actions.values.toList() }

    fun inState(state: State): List<Action> = all().filter { it.state == state }

    /** The only question execution should ask of this ledger. */
    fun mayExecute(id: String): Boolean = synchronized(lock) {
        val action = actions[id] ?: return false
        action.state == State.APPROVED ||
            (action.state == State.PLANNED && !action.requiresApproval)
    }

    /** Keep the newest MAX_ACTIONS, dropping the oldest finished actions first. */
    private fun trim() {
        if (actions.size <= MAX_ACTIONS) return
        val removable = actions.values.filter { it.terminal }.map { it.id }
        for (id in removable) {
            if (actions.size <= MAX_ACTIONS) break
            actions.remove(id)
        }
        while (actions.size > MAX_ACTIONS) {
            val oldest = actions.keys.firstOrNull() ?: break
            actions.remove(oldest)
        }
    }

    fun clear() = synchronized(lock) { actions.clear(); persist() }

    // ── persistence ─────────────────────────────────────────────────────────

    private fun persist() {
        val target = file ?: return
        val body = buildString {
            for (a in actions.values) {
                append(a.id.replace('|', '_')).append('|')
                append(a.tool.replace('|', '_')).append('|')
                append(a.sessionId.replace('|', '_')).append('|')
                append(a.state.name).append('|')
                append(if (a.requiresApproval) "1" else "0").append('|')
                append(a.createdAt).append('|').append(a.updatedAt).append('|')
                append(a.detail.replace('\n', ' ').replace('|', '_')).append('\n')
            }
        }
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                target.writeText(body)
                tmp.delete()
            }
        }
    }

    private fun restore() {
        val target = file ?: return
        val raw = runCatching { if (target.isFile) target.readText() else "" }.getOrNull().orEmpty()
        if (raw.isBlank()) return
        for (line in raw.lineSequence()) {
            val parts = line.split('|')
            if (parts.size < 8) continue
            val state = runCatching { State.valueOf(parts[3]) }.getOrNull() ?: continue
            val action = Action(
                id = parts[0],
                tool = parts[1],
                sessionId = parts[2],
                state = state,
                requiresApproval = parts[4] == "1",
                createdAt = parts[5].toLongOrNull() ?: 0L,
                updatedAt = parts[6].toLongOrNull() ?: 0L,
                detail = parts[7],
            )
            actions[action.id] = action
        }
        // Bounded: keep the newest MAX_ACTIONS, drop the oldest finished first.
        if (actions.size > MAX_ACTIONS) {
            val keep = actions.values.toList().takeLast(MAX_ACTIONS)
            actions.clear()
            for (a in keep) actions[a.id] = a
        }
    }
}
