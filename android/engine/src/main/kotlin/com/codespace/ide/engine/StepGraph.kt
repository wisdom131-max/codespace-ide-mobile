package com.codespace.ide.engine

/**
 * PROJECT NINE 9.13 (catalog 5) - order steps by what depends on what.
 *
 * The plan was a list, so it ran top to bottom and a step could run before the step it needed.
 * A graph answers the two questions a list cannot: what may run now, and is this plan even
 * finishable.
 *
 * A cycle is reported rather than thrown away, because a plan that cannot finish should say so
 * instead of silently dropping the steps it could not place.
 */
object StepGraph {

    data class Node(val id: String, val dependsOn: List<String> = emptyList())

    sealed interface Result {
        data class Order(val ids: List<String>) : Result
        data class Cycle(val ids: List<String>) : Result
    }

    fun order(nodes: List<Node>): Result {
        val known = nodes.associateBy { it.id }
        // A dependency naming something outside the plan is ignored rather than treated as a cycle:
        // it usually means an already-finished step from an earlier turn.
        val pending = LinkedHashMap<String, MutableSet<String>>()
        for (n in nodes) {
            pending[n.id] = n.dependsOn.filter { known.containsKey(it) }.toMutableSet()
        }
        val out = ArrayList<String>(nodes.size)
        while (pending.isNotEmpty()) {
            val ready = pending.filterValues { it.isEmpty() }.keys.sorted()
            if (ready.isEmpty()) {
                return Result.Cycle(pending.keys.sorted())
            }
            for (id in ready) {
                out.add(id)
                pending.remove(id)
            }
            for (deps in pending.values) deps.removeAll(ready.toSet())
        }
        return Result.Order(out)
    }
}
