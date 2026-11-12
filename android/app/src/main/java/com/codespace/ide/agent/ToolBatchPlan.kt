package com.codespace.ide.agent

/**
 * P4.A - the batch the model asked for, put in the order the plan says and stripped of repeats.
 *
 * Until now `runToolCalls` executed calls in arrival order and would happily run the same
 * `(name, arguments)` twice if the model emitted it twice. [ToolPlanner] existed and computed
 * dependency waves, but nothing on the request path ever asked it. These two rules are what makes
 * the planner decide something real: the sequence of execution, and which duplicate is answered from
 * the first result instead of being repeated (a repeated `write_file` is a side effect, not a
 * redundancy).
 *
 * Pure on purpose: no session, no context, no Android. The session supplies names and arguments.
 */
object ToolBatchPlan {

    /**
     * Indices of [names] in plan order.
     *
     * Each planner step claims the first call with its tool name that has not been claimed yet, wave
     * by wave, so a dependency wave runs before what depends on it. Calls the plan says nothing about
     * keep their original order at the end: the planner's silence is not a reason to drop work.
     * The result is always a permutation of `names.indices`, so nothing can be lost.
     */
    fun order(plan: ToolPlanner.Plan, names: List<String>): List<Int> {
        if (names.size < 2) return names.indices.toList()
        val claimed = HashSet<Int>()
        val out = ArrayList<Int>(names.size)
        for (wave in plan.waves()) {
            for (step in wave) {
                val idx = names.indices.firstOrNull { it !in claimed && names[it] == step.tool } ?: continue
                claimed.add(idx)
                out.add(idx)
            }
        }
        names.indices.filter { it !in claimed }.forEach { out.add(it) }
        return out
    }

    /**
     * Later index -> index of the identical earlier call, for the pairs that repeat exactly.
     *
     * Identity is tool name plus the argument text the model produced. Anything less exact is a
     * different call and is left alone.
     */
    fun duplicates(names: List<String>, args: List<String>): Map<Int, Int> {
        if (names.size < 2) return emptyMap()
        val firstSeen = HashMap<String, Int>()
        val out = LinkedHashMap<Int, Int>()
        for (i in names.indices) {
            val key = names[i] + "\u0000" + (args.getOrNull(i) ?: "")
            val seen = firstSeen[key]
            if (seen == null) firstSeen[key] = i else out[i] = seen
        }
        return out
    }

    /** One line for the Thinking Tree: what the plan decided, or null when it decided nothing. */
    fun note(plan: ToolPlanner.Plan, names: List<String>): String? {
        val waves = plan.waves()
        if (waves.isEmpty() || names.size < 2) return null
        if (waves.size == 1) return null
        return "Planned " + names.size + " step(s) in " + waves.size + " wave(s): " +
            waves.joinToString(" then ") { w -> w.joinToString(", ") { it.tool } }
    }
}
