package com.codespace.ide.agent.reflect

import com.codespace.ide.agent.goal.TaskPlanEngine
import com.codespace.ide.agent.subagents.DelegationScheduler

/**
 * PROJECT 3.6 (2026-11-15): did we actually do it?
 *
 * The audit found Brahma's `reflectAndVerify(goal, args, verified: Boolean, output, context)` takes its
 * own verdict as an argument. A reflection that is told the answer cannot find a wrong one. This one
 * derives the answer, from four independent things compared against each other:
 *
 *  1. **The objective** as the user stated it, not as the agent restated it.
 *  2. **The plan** and what each step claims.
 *  3. **The actions** that were actually observed, with their outputs.
 *  4. **The evidence**, graded by what it proves: a file written is IMPLEMENTED, a test passing is
 *     TESTED, a green CI run is CI_GREEN, and only something seen on the device is DEVICE_VERIFIED.
 *
 * The rules it enforces, each because the opposite is a lie an agent will tell:
 *
 *  - A step marked done with no evidence, or with "ok"/"done"/"no error", is UNSUPPORTED. Returning
 *    without an exception is not an outcome.
 *  - "The subagent reported it worked" is not evidence either; the subagent's own claim is the thing
 *    under review.
 *  - A step marked done before its dependencies were done is an ordering violation, and the result is
 *    not accepted whatever else it says.
 *  - Work that failed, was skipped, or can never run stays visible in the report. Deleting it from the
 *    summary is the difference between an honest report and a successful-looking one.
 *  - The objective is satisfied only when every acceptance criterion is met at the level it asks for,
 *    at the requested level, with evidence.
 *
 * Pure Kotlin. Reflection reads; it never edits a file or runs a tool.
 */
internal object ReflectionEngine {

    /** What a piece of evidence actually proves, weakest first. */
    enum class Level { IMPLEMENTED, TESTED, CI_GREEN, DEVICE_VERIFIED }

    data class Criterion(
        val text: String,
        /** The lowest level of proof this criterion accepts. */
        val required: Level = Level.TESTED,
    )

    data class Evidence(
        /** What is claimed, e.g. "MainScreen.kt compiles". */
        val claim: String,
        val level: Level,
        /** The observation that supports it. Blank means there is none. */
        val observed: String = "",
        /** The plan step or delegated task this belongs to, when known. */
        val subject: String = "",
    )

    /** One thing the agent actually did, as the tool layer saw it. */
    data class Action(
        val tool: String,
        val planStepId: String? = null,
        val ok: Boolean,
        val summary: String = "",
    )

    data class Reflection(
        val objective: String,
        val planned: List<String>,
        val completed: List<String>,
        val unsupported: List<String>,
        val failed: List<String>,
        val skipped: List<String>,
        val contradictions: List<String>,
        val remaining: List<String>,
        val needsApproval: List<String>,
        val satisfied: Boolean,
        val verdict: String,
    ) {
        fun lines(): List<String> = buildString {
            append("objective: ").append(objective)
            append("\nplanned: ").append(planned.size).append(" steps")
            completed.forEach { append("\ndone: ").append(it) }
            unsupported.forEach { append("\nunverified: ").append(it) }
            failed.forEach { append("\nfailed: ").append(it) }
            skipped.forEach { append("\nskipped: ").append(it) }
            contradictions.forEach { append("\nconflict: ").append(it) }
            remaining.forEach { append("\nremains: ").append(it) }
            needsApproval.forEach { append("\nneeds you: ").append(it) }
            append("\nverdict: ").append(verdict)
        }.split("\n")
    }

    /** Words that look like evidence and are not. */
    private val WEAK = setOf(
        "ok", "okay", "done", "complete", "completed", "success", "succeeded", "works", "no error",
        "no errors", "no exception", "passed", "looks good", "finished", "sure",
    )

    private fun isWeak(evidence: String): Boolean {
        val text = evidence.trim().lowercase().trim('.', '!', ' ')
        if (text.isEmpty()) return true
        if (text in WEAK) return true
        // "It returned without throwing" is the canonical false positive: an absence, not an outcome.
        if (text.contains("no exception") || text.contains("without exception") ||
            text.contains("no error") || text.contains("did not throw") ||
            text.contains("didn't throw") || text.contains("returned without")
        ) {
            return true
        }
        // A sentence made only of "it worked" words is the same as no sentence. "BUILD SUCCESSFUL"
        // is not: it names what succeeded, so it carries an observation.
        val filler = setOf(
            "ok", "okay", "done", "complete", "completed", "success", "successful", "succeeded",
            "works", "worked", "no", "error", "errors", "exception", "thrown", "it", "was", "is",
            "fine", "looks", "good", "passed", "finished", "sure", "all", "and", "the", "without",
            "there", "were", "returned", "nothing", "went", "wrong",
        )
        val words = text.split(' ', '\n', '\t', ',', ';', '.', '!')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { it in filler }
    }

    private fun isAgentSelfReport(evidence: String): Boolean {
        val text = evidence.trim().lowercase()
        return text.startsWith("subagent reported") || text.startsWith("the agent reports") ||
            text.startsWith("agent reported") || text.startsWith("it said")
    }

    /**
     * Compare the objective, the plan, what ran, the evidence offered and the acceptance criteria.
     *
     * [delegation] is the scheduler's board when the work was delegated; its blocked, failed and
     * unverified tasks feed the same verdict as locally-run steps.
     */
    fun reflect(
        objective: String,
        plan: TaskPlanEngine.Plan?,
        actions: List<Action>,
        evidence: List<Evidence>,
        criteria: List<Criterion> = emptyList(),
        delegation: DelegationScheduler.Board? = null,
        recoveryLines: List<String> = emptyList(),
    ): Reflection {
        val steps = plan?.steps.orEmpty()
        val done = steps.filter { it.status == TaskPlanEngine.Status.DONE }
        val unsupported = ArrayList<String>()
        val contradictions = ArrayList<String>()
        val needsApproval = ArrayList<String>()
        val remaining = ArrayList<String>()

        // 1. Every completion claim must rest on something observed.
        done.forEach { step ->
            // Evidence with no subject is general support for the run; evidence naming another step is
            // not allowed to prop up this one.
            val stepEvidence = evidence.filter { it.subject == step.id || it.subject.isBlank() }
            val strong = stepEvidence.any { !isWeak(it.observed) && !isAgentSelfReport(it.observed) }
            when {
                isAgentSelfReport(step.evidence) || isWeak(step.evidence) && !strong -> unsupported.add(
                    step.id + " '" + step.title + "': " +
                        if (isAgentSelfReport(step.evidence)) {
                            "the only support is the executor's own claim"
                        } else {
                            "no evidence was recorded"
                        }
                )
                stepEvidence.isEmpty() && strong.not() && !isWeak(step.evidence) -> Unit
            }
        }

        // 2. Ordering: a step cannot be done before what it depends on.
        done.forEach { step ->
            val broken = step.dependsOn.filter { dep ->
                steps.firstOrNull { it.id == dep }?.status != TaskPlanEngine.Status.DONE
            }
            if (broken.isNotEmpty()) {
                contradictions.add(
                    step.id + " '" + step.title + "' is marked done but depends on " +
                        broken.joinToString(", ") + ", which is not done",
                )
            }
        }

        // 3. Actions that failed are never evidence of success, and an action nobody planned is noted.
        val failedActions = actions.filter { !it.ok }
        failedActions.forEach { action ->
            if (action.planStepId != null) {
                val step = steps.firstOrNull { it.id == action.planStepId }
                if (step?.status == TaskPlanEngine.Status.DONE) {
                    contradictions.add(
                        "step '" + step.title + "' is marked done but " + action.tool +
                            " failed against it" + (if (action.summary.isBlank()) "" else ": " + action.summary.take(120)),
                    )
                }
            }
        }

        // 4. Delegated work is part of the same audit.
        if (delegation != null) {
            delegation.of(DelegationScheduler.State.BLOCKED).forEach {
                needsApproval.add("delegated '" + it.title + "' is blocked: " + it.note)
            }
            delegation.of(DelegationScheduler.State.COMPLETED).filterNot { it.verified }.forEach {
                unsupported.add("delegated '" + it.title + "' (" + it.roleId + ") is completed but unverified" +
                    (if (it.note.isBlank()) "" else ": " + it.note))
            }
            delegation.of(DelegationScheduler.State.FAILED).forEach {
                contradictions.add("delegated '" + it.title + "' failed: " + it.note)
            }
            val byStep = delegation.tasks.filter { it.planStepId != null }.groupBy { it.planStepId!! }
            byStep.forEach { (step, tasks) ->
                val ok = tasks.filter { it.state == DelegationScheduler.State.COMPLETED }
                val bad = tasks.filter { it.state == DelegationScheduler.State.FAILED }
                if (ok.isNotEmpty() && bad.isNotEmpty()) {
                    contradictions.add(
                        "subagents disagree about step '" + step + "': " +
                            ok.joinToString(", ") { it.id + " completed" } + " but " +
                            bad.joinToString(", ") { it.id + " failed" },
                    )
                }
            }
        }

        // 5. Acceptance criteria decide the verdict, at the level they ask for.
        val unmet = criteria.filterNot { criterion ->
            evidence.any { it.claim == criterion.text && it.observed.isNotBlank() && it.level.ordinal >= criterion.required.ordinal }
        }
        unmet.forEach { remaining.add("criterion not met at " + it.required.name.lowercase() + ": " + it.text) }

        // 6. Everything the plan itself says is unfinished stays in the report.
        val failed = steps.filter { it.status == TaskPlanEngine.Status.FAILED }
            .map { it.id + " '" + it.title + "': " + it.note }
        val skipped = steps.filter { it.status == TaskPlanEngine.Status.SKIPPED }
            .map { it.id + " '" + it.title + "' (dependency skipped)" }
        plan?.unreachable()?.forEach { remaining.add(it.id + " '" + it.title + "' can no longer run") }
        plan?.ready()?.forEach { remaining.add(it.id + " '" + it.title + "' is ready but not done") }
        // A step that was started and never finished is remaining work even though it is not ready
        // and not unreachable, which is exactly the case a "did it work?" report must not lose.
        plan?.steps?.filter { it.status == TaskPlanEngine.Status.RUNNING }?.forEach {
            remaining.add(it.id + " '" + it.title + "' is still running")
        }

        val plannedTitles = steps.map { it.id + " " + it.title }
        val completedTitles = done.map { step ->
            val strongest = evidence.filter { it.subject == step.id }.maxByOrNull { it.level.ordinal }
            step.id + " '" + step.title + "'" + (strongest?.let { " (" + it.level.name.lowercase() + ")" } ?: "")
        }

        // 7. Weak evidence anywhere in the record, and a recovery that did not hold, also count against it.
        evidence.filter { isWeak(it.observed) && it.claim.isNotBlank() }.forEach {
            unsupported.add("'" + it.claim + "' rests on '" + it.observed.trim() + "', which proves nothing")
        }
        recoveryLines.filter { it.startsWith("not recovered") }.forEach {
            contradictions.add("recovery did not hold: " + it)
        }

        // 8. The verdict. All of these must hold; any single one failing means "not done".
        val distinctUnsupported = unsupported.distinct()
        val distinctConflicts = contradictions.distinct()
        val planFinished = plan != null && plan.isFinished()
        val satisfied = distinctUnsupported.isEmpty() && distinctConflicts.isEmpty() &&
            failed.isEmpty() && skipped.isEmpty() && unmet.isEmpty() && planFinished
        val verdict = when {
            plan == null -> "No plan was recorded for this objective, so nothing supports a completion claim."
            satisfied -> "Satisfied: every step is done with evidence and every criterion is met."
            distinctUnsupported.isNotEmpty() ->
                "Not satisfied: " + distinctUnsupported.size + " completion claim(s) have no evidence."
            distinctConflicts.isNotEmpty() ->
                "Not satisfied: " + distinctConflicts.size + " contradiction(s) in the record."
            failed.isNotEmpty() || skipped.isNotEmpty() ->
                "Not satisfied: " + (failed.size + skipped.size) + " step(s) failed or were skipped."
            unmet.isNotEmpty() -> "Not satisfied: " + unmet.size + " acceptance criterion(s) are not met."
            else -> "Not satisfied: " + (plan!!.steps.size - done.size) + " step(s) are still open."
        }

        return Reflection(
            objective = objective.trim(),
            planned = plannedTitles,
            completed = completedTitles,
            unsupported = distinctUnsupported,
            failed = failed,
            skipped = skipped,
            contradictions = distinctConflicts,
            remaining = remaining.distinct(),
            needsApproval = needsApproval.distinct(),
            satisfied = satisfied,
            verdict = verdict,
        )
    }

    /** A one-line status for the chat surface, never more confident than the reflection itself. */
    fun headline(reflection: Reflection): String = when {
        reflection.satisfied -> "Objective satisfied"
        reflection.completed.isEmpty() -> "Objective not started"
        else -> "Objective partly done (" + reflection.completed.size + " of " + reflection.planned.size + " steps)"
    }
}
