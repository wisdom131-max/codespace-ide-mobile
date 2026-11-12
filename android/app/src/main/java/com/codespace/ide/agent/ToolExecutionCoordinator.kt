package com.codespace.ide.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * P4.B - bounded, deterministic execution of a planned tool batch.
 *
 * The loop in `runToolCalls` was strictly sequential. That is correct and it must stay correct for
 * anything that writes, anything that pauses for approval, and anything that owns shared state. But
 * two independent reads have no reason to queue behind each other, and on a phone each one is I/O.
 *
 * The rule is deliberately conservative: only tools on [PARALLEL_SAFE] are ever run together, the
 * ceiling is [MAX_PARALLEL_READS], results are collected by index so their order is the plan's order
 * and never the completion order, and a failure anywhere cancels the rest of its wave instead of
 * leaving orphaned work running.
 */
object ToolExecutionCoordinator {

    /** At most two at a time: the device this ships on has little RAM and a shared connection. */
    const val MAX_PARALLEL_READS = 2

    /**
     * Tools with no shared mutable state and no side effect: reading a file, grepping, listing a
     * directory, inspecting an archive, hashing. Everything else - every write, every command, every
     * connector call, every MCP call, every research call that touches the source ledger - is
     * sequential. Adding a name here is a claim that concurrent calls cannot interfere.
     */
    val PARALLEL_SAFE = setOf(
        "read_file", "grep_search", "list_dir", "file_exists", "find_files",
        "read_project_structure", "zip_list", "checksum", "get_diagnostics", "package_tree",
    )

    fun parallelSafe(name: String): Boolean = name in PARALLEL_SAFE

    /**
     * Consecutive runs of the ordered batch, split so that a run is parallel only when every member
     * is safe. Order is preserved across runs, and the concatenation of the runs is [order].
     */
    fun runs(order: List<Int>, names: List<String>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        for (idx in order) {
            val name = names.getOrNull(idx) ?: ""
            if (parallelSafe(name)) {
                current.add(idx)
            } else {
                if (current.isNotEmpty()) { out.add(current); current = ArrayList() }
                out.add(listOf(idx))
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /**
     * Run [run] for every index in [runs], never more than [maxParallel] at once inside a run, and
     * return the answers keyed by index.
     *
     * Determinism comes from the key, not the timing: the caller renders in plan order. Cancellation
     * propagates - [coroutineScope] cancels the siblings of a failing call, so a wave cannot leave
     * work running behind a turn that has already moved on.
     */
    suspend fun <T> collect(
        runs: List<List<Int>>,
        maxParallel: Int = MAX_PARALLEL_READS,
        run: suspend (Int) -> T,
    ): Map<Int, T> = coroutineScope {
        val out = LinkedHashMap<Int, T>()
        val width = maxParallel.coerceAtLeast(1)
        for (group in runs) {
            if (group.size == 1) {
                out[group[0]] = run(group[0])
                continue
            }
            group.chunked(width).forEach { chunk ->
                val answers = chunk.map { idx ->
                    async(Dispatchers.IO) { idx to run(idx) }
                }.awaitAll()
                answers.forEach { (idx, value) -> out[idx] = value }
            }
        }
        out
    }
}
