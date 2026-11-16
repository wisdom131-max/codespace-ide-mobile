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
/** One replan per run. A second failure is reported, not retried. */
private const val MAX_REPLANS = 1

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
    suspend fun run(
        waves: List<List<Step>>,
        prior: List<PrereqLedger.Entry> = emptyList(),
        session: String = "",
        revision: Int = -1,
    ): Report {
        val results = LinkedHashMap<String, StepResult>()
        val cancelled = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = HashSet<String>()
        val held = ArrayList<String>()
        val claimed = HashSet<String>()
        var peak = 1
        var replans = 0

        // STAGE 4.3: what an earlier run already proved. A capability that is DONE is not run again,
        // which is how a resumed turn avoids repeating a write that already landed; a capability
        // whose side effect is uncertain waits for the owner instead of being guessed at.
        val resume = OrchestrationResume.plan(prior, session, revision)
        val settled = resume.settled().map { it.capability }.toSet()
        val needsOwner = resume.needsOwner().map { it.capability }.toSet()

        fun block(step: Step): String? = when {
            !claimed.add(step.id) -> "duplicate: this run already dispatched " + step.id
            step.capability in settled -> "already done: " + step.capability + " was proven before"
            step.capability in needsOwner && step.writes.isNotEmpty() ->
                "held: an earlier " + step.capability + " may already have landed, so it needs the owner"
            step.dependsOn.any { it in failed } -> "blocked: " + step.capability + " needs " + step.dependsOn.filter { it in failed }.joinToString(", ")
            else -> null
        }

        for ((index, wave) in waves.withIndex()) {
            if (isCancelled()) {
                wave.forEach { cancelled.add(it.id) }
                waves.drop(index + 1).flatten().forEach { cancelled.add(it.id) }
                trace("cancelled before wave " + (index + 1))
                break
            }
            val ready = ArrayList<Step>()
            wave.forEach { step ->
                val why = block(step)
                if (why == null) {
                    ready.add(step)
                } else {
                    if (why.startsWith("held")) held.add(step.id) else skipped.add(step.id)
                    if (why.startsWith("blocked")) failed.add(step.capability)
                    trace(step.id + ": " + why)
                }
            }
            if (ready.isEmpty()) continue

            val (exclusive, shared) = ready.partition { it.exclusive || it.writes.isNotEmpty() }
            var waveFailed = false

            if (shared.isNotEmpty()) {
                val gate = Semaphore(maxParallel.coerceAtLeast(1))
                val active = shared.size.coerceAtMost(maxParallel)
                if (active > peak) peak = active
                trace("wave " + (index + 1) + ": " + shared.size + " read-only step(s)" +
                    if (active > 1) ", up to " + active + " at once" else "")
                val done = coroutineScope {
                    shared.map { step ->
                        async { gate.withPermit { runOne(step, results, cancelled, failed) } }
                    }.awaitAll()
                }
                if (done.any { !it }) waveFailed = true
            }

            // Writes and destructive calls are serial on purpose: one at a time, in plan order.
            for (step in exclusive) {
                if (isCancelled()) {
                    cancelled.add(step.id)
                    waveFailed = true
                    break
                }
                trace("running " + step.id + " alone (it changes something)")
                if (!runOne(step, results, cancelled, failed)) {
                    waveFailed = true
                    break
                }
            }

            if (waveFailed) {
                // A failure stops the chain, not the turn. Everything later that depends on what
                // failed is skipped and named; everything independent still gets its one chance.
                trace("wave " + (index + 1) + " failed; replanning what is left")
                val later = waves.drop(index + 1).flatten()
                val runnable = ArrayList<Step>()
                later.forEach { step ->
                    val why = block(step)
                    if (why == null) runnable.add(step) else {
                        if (why.startsWith("held")) {
                            if (step.id !in held) held.add(step.id)
                        } else if (step.id !in skipped) {
                            skipped.add(step.id)
                        }
                        if (why.startsWith("blocked")) failed.add(step.capability)
                        trace(step.id + ": " + why)
                    }
                }
                if (replans++ >= MAX_REPLANS) {
                    trace("stopped: one replan is the limit")
                    runnable.forEach { if (it.id !in skipped) skipped.add(it.id) }
                    break
                }
                trace("replanned: " + runnable.size + " independent step(s) still stand")
                for (step in runnable) {
                    if (isCancelled()) {
                        cancelled.add(step.id)
                        continue
                    }
                    runOne(step, results, cancelled, failed)
                }
                break
            }
        }
        // Held work is not pending: it is waiting on a person, and saying so is the point.
        held.forEach { if (it !in skipped) skipped.add(it) }
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
