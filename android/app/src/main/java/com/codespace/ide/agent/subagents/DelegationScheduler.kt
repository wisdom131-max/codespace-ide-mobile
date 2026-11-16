package com.codespace.ide.agent.subagents

import com.codespace.ide.chat.panel.ToolMeta

/**
 * PROJECT 3.4 (2026-11-15): delegating work without losing control of it.
 *
 * The audit found Brahma's delegation is a boolean on a step: `PlanStep.isParallel`. A flag like that
 * says "these may run together" and nothing else. It does not bound how many run, does not stop two
 * steps writing one file, does not cancel a child when the parent is cancelled, and does not stop a
 * subtask spawning subtasks until the device runs out of memory. Those are the failure modes of a
 * delegation feature, so this scheduler is built around them:
 *
 *  - **Parallel is a privilege, not a flag.** Only a task that declares no writes and is run by a
 *    read-only role may run beside anything else. A task with a write runs alone, in plan order.
 *  - **Two writers on one path is a conflict, not a race.** The later task is BLOCKED with the path
 *    and the task that holds it named, because resolving it is the user's decision.
 *  - **Budgets nest.** A child board carries strictly less than its parent: depth up, agents and tasks
 *    down, and its allowed tools are the intersection with the parent task's own role scope. Nested
 *    delegation therefore cannot be used to escape a limit or a permission.
 *  - **Cancellation is inherited.** Cancelling a board cancels everything queued or running on it.
 *  - **A repeated request is answered from the first one.** Two identical delegation requests map to
 *    one task, so a duplicate request cannot produce a duplicate side effect.
 *  - **Evidence or it did not happen.** A completion with blank evidence is refused, exactly as the
 *    plan layer refuses it.
 *
 * Pure Kotlin: the scheduler decides what may run and what a result means; it executes nothing.
 */
internal object DelegationScheduler {

    /** A child may delegate once more. Two levels is useful; three is a swarm. */
    const val MAX_DEPTH = 2
    const val MAX_AGENTS = 6
    const val MAX_TASKS = 20
    const val MAX_CONCURRENT = 3

    enum class State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, BLOCKED }

    data class Budget(
        val depth: Int = 0,
        val agentsLeft: Int = MAX_AGENTS,
        val tasksLeft: Int = MAX_TASKS,
        val maxConcurrent: Int = MAX_CONCURRENT,
    )

    data class Request(
        val roleId: String,
        val title: String,
        /** The plan step this work serves, so dependencies stay the plan's business. */
        val planStepId: String? = null,
        val reads: Set<String> = emptySet(),
        val writes: Set<String> = emptySet(),
        /** Caller-supplied identity for dedupe; blank derives one from the request. */
        val dedupe: String = "",
        /**
         * PROJECT THREE RUNTIME: the tool this task runs and its arguments, JSON-encoded.
         *
         * The scheduler is still a scheduler: it never executes anything. Carrying the work on the
         * task is what lets the caller's executor run exactly what the board approved, in the order
         * the board allowed, instead of re-deriving it from prose.
         */
        val tool: String = "",
        val args: String = "",
    )

    data class Task(
        val id: String,
        val roleId: String,
        val title: String,
        val planStepId: String?,
        val reads: Set<String>,
        val writes: Set<String>,
        val dedupeKey: String,
        val tools: Set<String>,
        val state: State,
        val parentId: String? = null,
        val evidence: String = "",
        val note: String = "",
        val verified: Boolean = false,
        /** The work this task stands for. Blank on a task created before the runtime existed. */
        val tool: String = "",
        val args: String = "",
        val enqueuedAt: Long = 0L,
        val startedAt: Long? = null,
        val endedAt: Long? = null,
        val epochAtEnqueue: Int = 0,
    ) {
        val isWriter: Boolean get() = writes.isNotEmpty()
        val isOpen: Boolean get() = state == State.QUEUED || state == State.RUNNING
    }

    data class Board(
        val tasks: List<Task> = emptyList(),
        val budget: Budget = Budget(),
        /** Bumped whenever the project changes; a read result from an older epoch is stale. */
        val epoch: Int = 0,
        val allowedTools: Set<String> = emptySet(),
        val cancelled: Boolean = false,
        val cancelReason: String = "",
        val seq: Int = 1,
    ) {
        fun task(id: String): Task? = tasks.firstOrNull { it.id == id }
        fun running(): List<Task> = tasks.filter { it.state == State.RUNNING }
        fun of(state: State): List<Task> = tasks.filter { it.state == state }
    }

    data class Enqueued(
        val board: Board,
        val queued: List<Task>,
        val deduped: List<Task>,
        val refused: List<Pair<Request, String>>,
    )

    sealed class Child {
        data class Ok(val board: Board) : Child()
        data class Refused(val reason: String) : Child()
    }

    /** Deterministic identity for a request, so the same request twice is one task. */
    fun dedupeKey(request: Request): String = request.dedupe.trim().ifBlank {
        request.roleId.trim().lowercase() + "|" + request.title.trim() + "|" +
            "w:" + request.writes.map { it.trim() }.sorted().joinToString(",") + "|" +
            "r:" + request.reads.map { it.trim() }.sorted().joinToString(",")
    }

    /**
     * Admit [requests] onto [board].
     *
     * [permitted] is the session's own permitted tool set and [metaOf] the registry metadata; a role's
     * scope is narrowed through [SubagentRoles], so a request for a role that has nothing usable here
     * is refused rather than quietly given fewer tools than it needs to finish.
     */
    fun enqueue(
        board: Board,
        requests: List<Request>,
        permitted: Set<String>,
        metaOf: (String) -> ToolMeta?,
        now: Long = 0L,
    ): Enqueued {
        var current = board
        val queued = ArrayList<Task>()
        val deduped = ArrayList<Task>()
        val refused = ArrayList<Pair<Request, String>>()

        requests.forEach { request ->
            if (current.cancelled) {
                refused.add(request to ("This delegation was cancelled (" + current.cancelReason + ")."))
                return@forEach
            }
            if (current.tasks.size >= current.budget.tasksLeft) {
                refused.add(request to ("Task budget reached (" + current.budget.tasksLeft +
                    " tasks at depth " + current.budget.depth + "). Finish what is queued."))
                return@forEach
            }
            if (current.budget.agentsLeft - queued.size <= 0) {
                refused.add(request to ("Agent budget reached (" + current.budget.agentsLeft +
                    " agents at depth " + current.budget.depth + ")."))
                return@forEach
            }
            val scope = when (val result = SubagentRoles.scope(request.roleId, permitted, metaOf)) {
                is SubagentRoles.Result.Refused -> {
                    refused.add(request to result.reason)
                    return@forEach
                }
                is SubagentRoles.Result.Ok -> result.scope
            }
            val key = dedupeKey(request)
            val already = current.tasks.firstOrNull { it.dedupeKey == key }
            if (already != null) {
                val answered = already.state == State.COMPLETED
                deduped.add(
                    already.copy(
                        note = if (answered) {
                            "duplicate request answered from the first task '" + already.id + "'"
                        } else {
                            "duplicate request already " + already.state.name.lowercase() +
                                " as '" + already.id + "'"
                        },
                    )
                )
                return@forEach
            }
            val blockers = writeConflicts(current, request).toMutableList()
            // A task names the tool it will run. If that tool is not in the role's own narrowed set,
            // the request is refused here rather than discovered by the executor, because "the
            // scheduler approved it" must mean "the role may do this".
            val asked = request.tool.trim()
            if (asked.isNotEmpty() && asked !in scope.tools) {
                blockers.add("role '" + scope.role.id + "' may not use '" + asked + "' in this session")
            }
            val id = "sa" + current.seq
            val task = Task(
                id = id,
                roleId = scope.role.id,
                title = request.title.trim().ifBlank { scope.role.label },
                planStepId = request.planStepId?.trim()?.takeIf { it.isNotEmpty() },
                reads = request.reads.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                writes = request.writes.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                dedupeKey = key,
                tools = scope.tools,
                state = if (blockers.isEmpty()) State.QUEUED else State.BLOCKED,
                note = blockers.joinToString("; "),
                tool = request.tool.trim(),
                args = request.args,
                enqueuedAt = now,
                epochAtEnqueue = current.epoch,
            )
            current = current.copy(tasks = current.tasks + task, seq = current.seq + 1)
            if (blockers.isEmpty()) queued.add(task) else refused.add(request to blockers.joinToString("; "))
        }
        return Enqueued(current, queued, deduped, refused)
    }

    /** Paths and the open task holding them, for a request that wants to write. */
    private fun writeConflicts(board: Board, request: Request): List<String> {
        if (request.writes.isEmpty()) return emptyList()
        val wanted = request.writes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val out = ArrayList<String>()
        board.tasks.filter { it.isOpen }.forEach { other ->
            val clash = wanted.intersect(other.writes)
            if (clash.isNotEmpty()) {
                out.add("writes " + clash.sorted().joinToString(", ") + " which '" + other.id +
                    "' (" + other.title + ") already holds")
            }
            val readClash = wanted.intersect(other.reads)
            if (other.isOpen && readClash.isNotEmpty() && other.state == State.RUNNING) {
                out.add("would change " + readClash.sorted().joinToString(", ") + " while '" + other.id +
                    "' is reading it")
            }
        }
        return out
    }

    /**
     * What may start now. Read-only tasks share the lane up to the concurrency cap; a task with a
     * write waits until nothing else is running and then runs alone, in plan order.
     *
     * [readySteps] is the plan's own readiness (dependencies DONE). A task tied to a step that is not
     * ready does not start, which keeps dependency policy in one place.
     */
    fun wave(board: Board, readySteps: Set<String>? = null): List<Task> {
        if (board.cancelled) return emptyList()
        val running = board.running()
        val capacity = board.budget.maxConcurrent - running.size
        if (capacity <= 0) return emptyList()
        val queued = board.of(State.QUEUED).filter { task ->
            val step = task.planStepId
            step == null || readySteps == null || step in readySteps
        }
        if (queued.isEmpty()) return emptyList()

        val runningWrites = running.filter { it.isWriter }
        if (runningWrites.isNotEmpty()) {
            // A write in flight owns the project. Nothing else starts, reads included.
            return emptyList()
        }
        val reads = queued.filter { !it.isWriter }
        val writes = queued.filter { it.isWriter }
        if (reads.isNotEmpty()) {
            val started = ArrayList<Task>()
            reads.forEach { task ->
                if (started.size >= capacity) return@forEach
                val blockedByWrite = writes.any { write ->
                    write.writes.intersect(task.reads).isNotEmpty()
                }
                if (!blockedByWrite) started.add(task)
            }
            if (started.isNotEmpty()) return started
        }
        if (writes.isNotEmpty() && running.isEmpty()) {
            // Exclusive and deterministic: the first write in declaration order, alone.
            return listOf(writes.first())
        }
        return emptyList()
    }

    fun start(board: Board, id: String, now: Long): Board {
        val task = board.task(id) ?: return board
        if (task.state != State.QUEUED) return board
        return board.copy(tasks = board.tasks.map {
            if (it.id == id) it.copy(state = State.RUNNING, startedAt = now) else it
        })
    }

    /** A completion needs evidence. Blank evidence is refused, not recorded as a success. */
    fun complete(
        board: Board,
        id: String,
        evidence: String,
        verified: Boolean,
        now: Long,
        epoch: Int = board.epoch,
    ): Board {
        val task = board.task(id) ?: return board
        if (task.state != State.RUNNING) return board
        val text = evidence.trim()
        if (text.isEmpty()) {
            return fail(board, id, "no evidence was offered for the result", now)
        }
        val stale = !task.isWriter && epoch != task.epochAtEnqueue
        val note = when {
            stale -> "stale: the project changed while this ran; re-read before relying on it"
            else -> task.note
        }
        return board.copy(tasks = board.tasks.map {
            if (it.id == id) {
                it.copy(
                    state = State.COMPLETED,
                    evidence = text,
                    verified = verified && !stale,
                    note = note,
                    endedAt = now,
                )
            } else {
                it
            }
        })
    }

    fun fail(board: Board, id: String, reason: String, now: Long): Board {
        val task = board.task(id) ?: return board
        val open = task.state == State.RUNNING || task.state == State.QUEUED
        if (!open) return board
        return board.copy(tasks = board.tasks.map {
            if (it.id == id) {
                it.copy(
                    state = State.FAILED,
                    note = reason.trim().ifBlank { "no reason given" },
                    verified = false,
                    endedAt = now,
                )
            } else {
                it
            }
        })
    }

    /** Cancellation is inherited: nothing queued or running survives it. */
    fun cancel(board: Board, reason: String, now: Long): Board = board.copy(
        tasks = board.tasks.map {
            if (it.isOpen) {
                it.copy(state = State.CANCELLED, note = reason.trim(), endedAt = now)
            } else {
                it
            }
        },
        cancelled = true,
        cancelReason = reason.trim(),
    )

    /**
     * A child board for delegating from [parentTaskId].
     *
     * Strictly less than the parent, on every axis: one more level, one fewer agent, the parent's
     * remaining task budget, and tools intersected with the parent task's own scope. A caller asking
     * for tools the parent task never held is refused, so delegation cannot raise privilege.
     */
    fun childBoard(board: Board, parentTaskId: String, inheritedTools: Set<String>): Child {
        val parent = board.task(parentTaskId)
            ?: return Child.Refused("No delegated task '" + parentTaskId + "' to delegate from.")
        if (parent.state != State.RUNNING) {
            return Child.Refused("'" + parentTaskId + "' is " + parent.state.name.lowercase() +
                "; only a running task may delegate.")
        }
        if (board.cancelled) return Child.Refused("This delegation was cancelled (" + board.cancelReason + ").")
        val depth = board.budget.depth + 1
        if (depth > MAX_DEPTH) {
            return Child.Refused("Delegation is " + MAX_DEPTH + " levels deep already; " +
                "do this work yourself instead of delegating again.")
        }
        val widened = inheritedTools - parent.tools
        if (widened.isNotEmpty()) {
            return Child.Refused("A delegated agent cannot hold tools its parent lacks: " +
                widened.sorted().joinToString(", ") + ".")
        }
        val agentsLeft = board.budget.agentsLeft - 1
        if (agentsLeft <= 0) return Child.Refused("No agents left to delegate to at this level.")
        return Child.Ok(
            Board(
                budget = Budget(
                    depth = depth,
                    agentsLeft = agentsLeft,
                    tasksLeft = board.budget.tasksLeft - board.tasks.size,
                    maxConcurrent = minOf(board.budget.maxConcurrent, MAX_CONCURRENT),
                ),
                epoch = board.epoch,
                allowedTools = parent.tools,
            )
        )
    }

    /** The honest summary: what finished, what did not, what is stale, what is blocked. */
    fun report(board: Board): String {
        val done = board.of(State.COMPLETED)
        val verified = done.count { it.verified }
        val failed = board.of(State.FAILED)
        val blocked = board.of(State.BLOCKED)
        val cancelled = board.of(State.CANCELLED)
        val running = board.of(State.RUNNING)
        val queued = board.of(State.QUEUED)
        return buildString {
            append(done.size).append(" completed (").append(verified).append(" verified, ")
            append(done.size - verified).append(" unverified)")
            if (failed.isNotEmpty()) append(", ").append(failed.size).append(" failed")
            if (blocked.isNotEmpty()) append(", ").append(blocked.size).append(" blocked")
            if (running.isNotEmpty()) append(", ").append(running.size).append(" running")
            if (queued.isNotEmpty()) append(", ").append(queued.size).append(" queued")
            if (cancelled.isNotEmpty()) append(", ").append(cancelled.size).append(" cancelled")
            if (board.cancelled) append(" (delegation cancelled: ").append(board.cancelReason).append(")")
            blocked.forEach { append("\nblocked: ").append(it.title).append(" - ").append(it.note) }
            done.filterNot { it.verified }.forEach {
                append("\nunverified: ").append(it.title)
                if (it.note.isNotBlank()) append(" (").append(it.note).append(")")
            }
            failed.forEach {
                append("\nfailed: ").append(it.title).append(" - ").append(it.note)
            }
        }
    }
}
