package com.codespace.ide.agent.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * STAGE 4.3: the dependency plan, actually executed.
 *
 * Stage 4.2 computed waves and then the model still called one tool at a time, which made the waves
 * a diagram. This runs them: ready operations go through [execute] (which is the real dispatch path),
 * results come back as evidence, and the next wave is only attempted once the previous one has
 * actually succeeded.
 *
 * The rules that matter, each because the opposite is a known way to corrupt a project:
 *
 *  - Read-only work in a wave may run together, bounded by [maxParallel]; anything that writes or is
 *    destructive runs ALONE, after the readers in that wave have finished. Two writers on one file
 *    is a merge nobody can review.
 *  - A failed operation satisfies nothing. Its dependents are skipped by name, not quietly retried.
 *  - Cancellation is checked before every start, and a cancelled operation is reported CANCELLED, so
 *    no caller can mistake it for a success.
 *  - Parallelism is a device setting, not an ambition: the default is 2 on a small phone.
 */
internal class Orchestrator(
    private val maxParallel: Int = defaultParallelism(),
    private val isCancelled: () -> Boolean = { false },
    private val execute: suspend (Step) -> StepResult,
    private val trace: (String) -> Unit = {},
) {

    data class Step(
        val id: String,
        val capability: String,
        val tool: String,
        val reads: Set<String> = emptySet(),
        val writes: Set<String> = emptySet(),
        /** A destructive or externally visible call: never run beside anything else. */
        val exclusive: Boolean = false,
        val dependsOn: Set<String> = emptySet(),
    )

    data class StepResult(
        val ok: Boolean,
        val output: String,
        val verified: Boolean = ok,
    )

    data class Report(
        val results: Map<String, StepResult>,
        val cancelled: List<String>,
        val skipped: List<String>,
        val peakParallel: Int,
    ) {
        val ok: Boolean get() = cancelled.isEmpty() && skipped.isEmpty() && results.values.all { it.ok }
        fun summary(): String = buildString {
            append(results.count { it.value.ok }).append(" done")
            if (results.any { !it.value.ok }) append(", ").append(results.count { !it.value.ok }).append(" failed")
            if (skipped.isNotEmpty()) append(", ").append(skipped.size).append(" skipped")
            if (cancelled.isNotEmpty()) append(", ").append(cancelled.size).append(" cancelled")
            if (peakParallel > 1) append(" (up to ").append(peakParallel).append(" at a time)")
        }
    }

    /** Results so far, plus the peak number of operations that ran at once. */
    suspend fun run(waves: List<List<Step>>): Report {
        val results = LinkedHashMap<String, StepResult>()
        val cancelled = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = HashSet<String>()
        var peak = 1

        var stop = false
        for ((index, wave) in waves.withIndex()) {
            if (isCancelled()) {
                wave.forEach { cancelled.add(it.id) }
                waves.drop(index + 1).flatten().forEach { cancelled.add(it.id) }
                trace("cancelled before wave " + (index + 1))
                break
            }
            val ready = ArrayList<Step>()
            wave.forEach { step ->
                if (step.dependsOn.any { it in failed }) {
                    skipped.add(step.id)
                    failed.add(step.capability)
                    trace("blocked: " + step.id + " needs " + step.dependsOn.filter { it in failed }.joinToString(", "))
                } else {
                    ready.add(step)
                }
            }
            if (ready.isEmpty()) continue

            val (exclusive, shared) = ready.partition { it.exclusive || it.writes.isNotEmpty() }

            if (shared.isNotEmpty()) {
                val gate = Semaphore(maxParallel.coerceAtLeast(1))
                val active = shared.size.coerceAtMost(maxParallel)
                if (active > peak) peak = active
                trace("running " + shared.size + " read-only step(s)" + if (active > 1) " in parallel" else "")
                val done = coroutineScope {
                    shared.map { step ->
                        async {
                            gate.withPermit { runOne(step, results, cancelled, failed) }
                        }
                    }.awaitAll()
                }
                if (done.any { !it }) stop = true
            }

            // Writes and destructive calls are serial on purpose. One at a time, in plan order.
            for (step in exclusive) {
                if (isCancelled()) {
                    cancelled.add(step.id)
                    break
                }
                trace("running " + step.id + " alone (it changes something)")
                if (!runOne(step, results, cancelled, failed)) {
                    stop = true
                    break
                }
            }
            if (stop) {
                // Nothing later is attempted: the remaining waves are recorded as skipped rather
                // than left looking like work that merely has not started yet.
                waves.drop(index + 1).flatten().forEach { skipped.add(it.id) }
                trace("stopped after wave " + (index + 1) + ": a step failed")
                break
            }
        }
        return finish(results, cancelled, skipped, peak)
    }

    private suspend fun runOne(
        step: Step,
        results: MutableMap<String, StepResult>,
        cancelled: MutableList<String>,
        failed: MutableSet<String>,
    ): Boolean {
        if (isCancelled()) {
            cancelled.add(step.id)
            return false
        }
        val result = runCatching { execute(step) }.getOrElse {
            StepResult(false, "the step failed before it returned: " + (it.message ?: "no reason"), verified = false)
        }
        results[step.id] = result
        if (!result.ok) {
            failed.add(step.capability)
            trace("failed: " + step.id + " (" + result.output.take(120) + ")")
            return false
        }
        trace("done: " + step.id)
        return true
    }

    private fun finish(
        results: Map<String, StepResult>,
        cancelled: List<String>,
        skipped: List<String>,
        peak: Int,
    ): Report = Report(results, cancelled, skipped, peak)

    companion object {
        /** Small phones get 2; nothing here ever asks for more than 3. */
        fun defaultParallelism(): Int =
            if (Runtime.getRuntime().availableProcessors() <= 4) 2 else 3
    }
}
