package com.codespace.ide.chat.panel

/**
 * PROJECT FOUR, STAGE 4.2 (2026-11-16): a plan with edges, not just a list.
 *
 * [ToolCapabilityPlan] says whether each capability exists. This turns a set of capabilities into an
 * order: which must run first, which are free to run at any time, and which can never run because
 * something they need is not there. It is pure: the caller supplies what is available, and the
 * result is a plan the caller may execute.
 *
 * The three rules that make it worth having:
 *
 *  - A cycle is refused before anything runs. Two capabilities that each need the other are a bug in
 *    the metadata, and discovering it mid-execution costs a broken half-done task.
 *  - A REQUIRED prerequisite that cannot be met makes its dependent IMPOSSIBLE rather than merely
 *    late. "You cannot do this here" said up front is the honest answer.
 *  - Independent capabilities stay independent. The waves are computed from the edges, so nothing
 *    gets serialised out of caution.
 */
internal object DependencyPlanner {

    /** Where a capability stands once the whole graph is known. */
    enum class State {
        /** Every required prerequisite is already satisfied by evidence. */
        READY,

        /** Can run, but only after other capabilities in this plan have succeeded. */
        WAITING,

        /** A required prerequisite has no tool in this mode, so it can never run. */
        IMPOSSIBLE,

        /** A required prerequisite already failed; this can never run now. */
        BLOCKED,
    }

    data class Node(
        val capability: String,
        val state: State,
        val requires: List<String>,
        val optional: List<String>,
        /** The wave this node belongs to: 0 runs first, then 1, and so on. */
        val level: Int,
        val why: String = "",
    )

    data class Plan(
        val nodes: List<Node>,
        /** Capabilities in dependency order, one list per wave. Empty when the graph is invalid. */
        val waves: List<List<String>>,
        /** True only when there is no cycle and nothing is impossible or blocked. */
        val executable: Boolean,
        val cycle: List<String> = emptyList(),
        val notes: List<String>,
    ) {
        fun node(capability: String): Node? = nodes.firstOrNull { it.capability == capability }
    }

    /**
     * @param capabilities what the request needs, in the order the user said them.
     * @param satisfied capabilities already proven done this turn (see the ledger).
     * @param available capabilities this mode can actually reach, offered or loadable. Null means
     * the caller does not know yet, and an unknown is never treated as unavailable: a plan that
     * refuses work because nobody told it about a tool is worse than a plan that tries.
     * @param failed capabilities that already failed this turn.
     */
    fun plan(
        capabilities: List<String>,
        satisfied: Set<String> = emptySet(),
        available: Set<String>? = null,
        failed: Set<String> = emptySet(),
        prereqs: (String) -> List<ToolPrerequisites.Prereq> = { ToolPrerequisites.prereqsOf(it) },
    ): Plan {
        val wanted = capabilities.distinct()
        if (wanted.isEmpty()) {
            return Plan(emptyList(), emptyList(), true, emptyList(), emptyList())
        }

        // Every capability the plan has to reason about: the request itself, plus whatever it pulls
        // in through required and optional edges.
        val closure = LinkedHashSet<String>()
        var frontier = wanted
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < 64) {
            val next = ArrayList<String>()
            frontier.forEach { cap ->
                if (closure.add(cap)) {
                    prereqs(cap).forEach { edge ->
                        if (edge.strength == ToolPrerequisites.Strength.REQUIRED) next.add(edge.of)
                    }
                }
            }
            frontier = next
        }

        val cycle = findCycle(wanted, prereqs)
        if (cycle.isNotEmpty()) {
            return Plan(
                nodes = wanted.map { cap ->
                    Node(cap, State.WAITING, requiredOf(cap, prereqs), optionalOf(cap, prereqs), 0)
                },
                waves = emptyList(),
                executable = false,
                cycle = cycle,
                notes = listOf(
                    "the plan cannot run: " + cycle.joinToString(" -> ") +
                        " each waits on the other, so no order exists",
                ),
            )
        }

        val notes = ArrayList<String>()
        val state = HashMap<String, State>()
        val level = HashMap<String, Int>()

        fun resolve(cap: String, seen: MutableSet<String>): State {
            state[cap]?.let { return it }
            if (!seen.add(cap)) return State.WAITING
            val required = requiredOf(cap, prereqs)
            // A capability that already failed this turn cannot be scheduled again; its dependents
            // are blocked by that failure, which is what the caller needs to see.
            var worst = when {
                cap in failed -> State.BLOCKED
                cap in satisfied -> State.READY
                else -> State.WAITING
            }
            var rank = if (cap in satisfied) -1 else 0
            for (dep in required) {
                val depState = resolve(dep, seen)
                if (dep in failed) {
                    worst = State.BLOCKED
                    notes.add(cap + " is blocked: " + dep + " failed")
                } else if (depState == State.IMPOSSIBLE) {
                    if (worst != State.BLOCKED) worst = State.IMPOSSIBLE
                    notes.add(cap + " can never run: it needs " + dep + ", which is not available here")
                } else if (depState == State.BLOCKED && worst != State.BLOCKED) {
                    worst = State.BLOCKED
                } else {
                    rank = maxOf(rank, levelOf(dep, prereqs, satisfied) + 1)
                }
            }
            // A capability that cannot run is not ready whatever its own evidence says.
            val resolved = when {
                worst == State.BLOCKED -> State.BLOCKED
                worst == State.IMPOSSIBLE -> State.IMPOSSIBLE
                cap in satisfied -> State.READY
                rank > 0 -> State.WAITING
                else -> State.READY
            }
            state[cap] = resolved
            // WAITING keeps its rank: that number IS the wave it runs in.
            level[cap] = if (resolved == State.READY || resolved == State.WAITING) maxOf(rank, 0) else -1
            return resolved
        }

        closure.forEach { resolve(it, HashSet()) }

        // Anything the request needs that this mode cannot reach is impossible, and says so plainly.
        closure.forEach { cap ->
            val reachable = available == null || cap in satisfied || cap in available
            if (!reachable && state[cap] == State.READY) {
                state[cap] = State.IMPOSSIBLE
                notes.add(cap + " is not available in this mode")
            }
        }

        val nodes = closure.map { cap ->
            Node(
                capability = cap,
                state = state[cap] ?: State.WAITING,
                requires = requiredOf(cap, prereqs),
                optional = optionalOf(cap, prereqs),
                level = level[cap] ?: -1,
                why = prereqs(cap).firstOrNull()?.why.orEmpty(),
            )
        }

        // Something already satisfied this turn has nothing left to run.
        val runnable = nodes.filter {
            (it.state == State.READY || it.state == State.WAITING) && it.capability !in satisfied
        }
        val waves = if (runnable.isEmpty()) emptyList() else {
            val byLevel = runnable.groupBy { maxOf(it.level, 0) }
            (0..(byLevel.keys.maxOrNull() ?: 0)).map { index ->
                byLevel[index].orEmpty().map { it.capability }
            }.filter { it.isNotEmpty() }
        }

        val executable = nodes.none { it.state == State.IMPOSSIBLE || it.state == State.BLOCKED } &&
            wanted.all { cap -> nodes.first { it.capability == cap }.state != State.IMPOSSIBLE }

        return Plan(nodes, waves, executable, emptyList(), notes.distinct())
    }

    private fun requiredOf(
        cap: String,
        prereqs: (String) -> List<ToolPrerequisites.Prereq>,
    ): List<String> = prereqs(cap).filter { it.strength == ToolPrerequisites.Strength.REQUIRED }
        .map { it.of }.distinct()

    private fun optionalOf(
        cap: String,
        prereqs: (String) -> List<ToolPrerequisites.Prereq>,
    ): List<String> = prereqs(cap).filter { it.strength == ToolPrerequisites.Strength.OPTIONAL }
        .map { it.of }.distinct()

    private fun levelOf(
        cap: String,
        prereqs: (String) -> List<ToolPrerequisites.Prereq>,
        satisfied: Set<String>,
        depth: Int = 0,
    ): Int {
        if (cap in satisfied || depth > 32) return -1
        val deps = requiredOf(cap, prereqs)
        if (deps.isEmpty()) return 0
        return 1 + (deps.maxOfOrNull { levelOf(it, prereqs, satisfied, depth + 1) } ?: -1)
    }

    /** A cycle among the requested capabilities and their required edges, or empty. */
    private fun findCycle(
        requested: List<String>,
        prereqs: (String) -> List<ToolPrerequisites.Prereq>,
    ): List<String> {
        val visiting = LinkedHashSet<String>()
        val done = HashSet<String>()

        fun walk(cap: String): List<String>? {
            if (cap in done) return null
            if (!visiting.add(cap)) {
                return visiting.dropWhile { it != cap } + cap
            }
            requiredOf(cap, prereqs).forEach { dep ->
                val found = walk(dep)
                if (found != null) return found
            }
            visiting.remove(cap)
            done.add(cap)
            return null
        }

        requested.forEach { cap ->
            val found = walk(cap)
            if (found != null) return found
        }
        return emptyList()
    }

    /** One line per node, for the transcript: what is next, what waits, and what cannot happen. */
    fun lines(plan: Plan): List<String> = plan.nodes.map { node ->
        val state = when (node.state) {
            State.READY -> "ready"
            State.WAITING -> "waits on " + node.requires.joinToString(", ").ifBlank { "an earlier step" }
            State.BLOCKED -> "blocked: a prerequisite failed"
            State.IMPOSSIBLE -> "cannot run here"
        }
        node.capability + ": " + state
    }
}
