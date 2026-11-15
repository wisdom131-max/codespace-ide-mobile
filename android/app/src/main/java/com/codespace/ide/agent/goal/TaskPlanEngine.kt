package com.codespace.ide.agent.goal

/**
 * PROJECT 3.1 (2026-11-15): the task plan Copilot keeps while it works.
 *
 * [com.codespace.ide.agent.ToolPlanner] plans *calls*: given the tools a single request needs, it
 * orders them into waves. That is the wrong grain for "port this screen to Compose, then make the
 * build pass", because such a task survives many calls, several failures and one or two changes of
 * mind. What was missing is a plan for the *objective*: steps with owners, milestones, a status per
 * step, and a revision when reality disagrees.
 *
 * Rules this model enforces, each because the failure it prevents is common and expensive:
 *
 *  - A step is only DONE with evidence. "The tool returned" is not evidence, so [complete] refuses a
 *    blank one. That is the same rule the repair work already applies at the tool layer, lifted to
 *    the plan.
 *  - Failing a step marks its dependents SKIPPED, transitively. They did not run, and pretending
 *    otherwise is how an agent reports a finished job that never happened.
 *  - A step may be retried once through a recovery step. A second failure of the same step is
 *    recorded, not retried: endless retry loops are the other half of that failure mode.
 *  - Revisions are counted and bounded, so a plan that keeps being rewritten is visible as such.
 *
 * Pure Kotlin: no session, no context, no Android. The chat layer owns persistence and display.
 */
object TaskPlanEngine {

    const val MAX_STEPS = 40
    const val MAX_REVISIONS = 6
    const val MAX_RECOVERIES_PER_STEP = 1
    const val RECOVERY_SUFFIX = ".retry"

    enum class Status { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    data class Step(
        val id: String,
        val title: String,
        val dependsOn: List<String> = emptyList(),
        /** Optional grouping shown as a heading; a milestone is complete when all its steps are. */
        val milestone: String? = null,
        val status: Status = Status.PENDING,
        val evidence: String = "",
        val note: String = "",
        val attempts: Int = 0,
    )

    data class Plan(
        val objective: String,
        val steps: List<Step>,
        val revision: Int = 1,
        val revisions: List<String> = emptyList(),
    ) {
        fun step(id: String): Step? = steps.firstOrNull { it.id == id }

        /** Steps whose dependencies are all DONE, in declaration order. Nothing else may run. */
        fun ready(): List<Step> {
            val done = steps.filter { it.status == Status.DONE }.map { it.id }.toSet()
            return steps.filter { step ->
                step.status == Status.PENDING && step.dependsOn.all { it in done }
            }
        }

        /** Steps that can never run now, and why, so the report is specific rather than "incomplete". */
        fun unreachable(): List<Step> {
            val dead = steps.filter { it.status == Status.FAILED || it.status == Status.SKIPPED }
                .map { it.id }.toMutableSet()
            var changed = true
            while (changed) {
                changed = false
                steps.forEach { step ->
                    if (step.status == Status.PENDING && step.dependsOn.any { it in dead } && step.id !in dead) {
                        dead.add(step.id)
                        changed = true
                    }
                }
            }
            return steps.filter { it.status == Status.PENDING && it.id in dead }
        }

        fun progress(): Progress {
            val done = steps.count { it.status == Status.DONE }
            val failed = steps.count { it.status == Status.FAILED }
            val skipped = steps.count { it.status == Status.SKIPPED }
            val running = steps.count { it.status == Status.RUNNING }
            val total = steps.size
            return Progress(
                total = total,
                done = done,
                failed = failed,
                skipped = skipped,
                running = running,
                pending = total - done - failed - skipped - running,
                fraction = if (total == 0) 1f else done.toFloat() / total,
            )
        }

        fun milestones(): List<Milestone> = steps
            .mapNotNull { it.milestone?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .distinct()
            .map { name ->
                val members = steps.filter { it.milestone?.trim() == name }
                Milestone(
                    name = name,
                    total = members.size,
                    done = members.count { it.status == Status.DONE },
                    failed = members.count { it.status == Status.FAILED },
                )
            }

        /** Complete only because every step really finished, not because nothing is left to run. */
        fun isFinished(): Boolean = steps.isNotEmpty() && steps.all { it.status == Status.DONE }

        /** One line per step for the chat surface: pending, running, done, and what is dead. */
        fun lines(): List<String> = ready().map { "pending: " + it.title } +
            steps.filter { it.status == Status.RUNNING }.map { "running: " + it.title } +
            steps.filter { it.status == Status.DONE }.map { "done: " + it.title } +
            steps.filter { it.status == Status.FAILED }
                .map { "failed: " + it.title + (if (it.note.isBlank()) "" else " (" + it.note + ")") } +
            steps.filter { it.status == Status.SKIPPED }.map { "skipped: " + it.title }
    }

    data class Progress(
        val total: Int,
        val done: Int,
        val failed: Int,
        val skipped: Int,
        val running: Int,
        val pending: Int,
        val fraction: Float,
    ) {
        fun summary(): String = "$done of $total steps done" +
            (if (failed > 0) ", $failed failed" else "") +
            (if (skipped > 0) ", $skipped skipped" else "")
    }

    data class Milestone(val name: String, val total: Int, val done: Int, val failed: Int) {
        val complete: Boolean get() = total > 0 && done == total
    }

    sealed class Outcome {
        data class Ok(val plan: Plan) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }

    /**
     * Build a plan from what the model proposed. Rejections are specific: a plan with a cycle, a
     * dangling dependency or duplicate ids is a broken plan, not a plan to repair silently.
     */
    fun create(objective: String, proposed: List<Step>): Outcome {
        if (objective.isBlank()) return Outcome.Rejected("A plan needs an objective.")
        if (proposed.isEmpty()) return Outcome.Rejected("A plan needs at least one step.")
        if (proposed.size > MAX_STEPS) {
            return Outcome.Rejected("That is " + proposed.size + " steps; the limit is " + MAX_STEPS +
                ". Split the work into separate objectives instead.")
        }
        val ids = proposed.map { it.id }
        if (ids.any { it.isBlank() }) return Outcome.Rejected("Every step needs an id.")
        if (ids.size != ids.toSet().size) return Outcome.Rejected("Two steps share an id.")
        val known = ids.toSet()
        proposed.forEach { step ->
            val missing = step.dependsOn.filter { it !in known }
            if (missing.isNotEmpty()) {
                return Outcome.Rejected("Step '" + step.id + "' depends on unknown step(s): " +
                    missing.joinToString(", ") + ".")
            }
            if (step.id in step.dependsOn) {
                return Outcome.Rejected("Step '" + step.id + "' depends on itself.")
            }
            if (step.title.isBlank()) return Outcome.Rejected("Step '" + step.id + "' has no title.")
        }
        cycleOf(proposed)?.let { cycle ->
            return Outcome.Rejected("The plan has a cycle: " + cycle.joinToString(" then ") + ".")
        }
        val cleaned = proposed.map { it.copy(status = Status.PENDING, evidence = "", note = "", attempts = 0) }
        return Outcome.Ok(Plan(objective = objective.trim(), steps = cleaned))
    }

    /** Start a step. Starting something whose dependencies are not done is refused, not forgiven. */
    fun start(plan: Plan, id: String): Outcome {
        val step = plan.step(id) ?: return Outcome.Rejected("No step called '" + id + "'.")
        if (step.status != Status.PENDING) {
            return Outcome.Rejected("Step '" + id + "' is already " + step.status.name.lowercase() + ".")
        }
        if (!plan.ready().any { it.id == id }) {
            val waiting = step.dependsOn.filter { plan.step(it)?.status != Status.DONE }
            return Outcome.Rejected("Step '" + id + "' is still waiting on " + waiting.joinToString(", ") + ".")
        }
        return Outcome.Ok(plan.copy(steps = plan.steps.map {
            if (it.id == id) it.copy(status = Status.RUNNING, attempts = it.attempts + 1) else it
        }))
    }

    /**
     * Finish a step. [evidence] is required and must say what was actually observed, because a step
     * that finishes without it is exactly the "it did not throw, so it worked" claim this project
     * exists to stop making.
     */
    fun complete(plan: Plan, id: String, evidence: String): Outcome {
        val step = plan.step(id) ?: return Outcome.Rejected("No step called '" + id + "'.")
        if (step.status == Status.DONE) return Outcome.Rejected("Step '" + id + "' is already done.")
        if (step.status == Status.SKIPPED || step.status == Status.FAILED) {
            return Outcome.Rejected("Step '" + id + "' cannot complete because it is " + step.status.name.lowercase() + ".")
        }
        if (evidence.isBlank()) {
            return Outcome.Rejected("Step '" + id + "' needs evidence that it worked: say what you saw " +
                "(a command, a file, a line of output), not that the tool returned.")
        }
        return Outcome.Ok(plan.copy(steps = plan.steps.map {
            if (it.id == id) it.copy(status = Status.DONE, evidence = evidence.trim(), note = "") else it
        }))
    }

    /**
     * Record a failure and repair the rest of the plan around it.
     *
     * Dependents become SKIPPED transitively: they were never attempted, and leaving them PENDING
     * would let a later `ready()` run a step whose input does not exist. When the caller supplies a
     * recovery step, it is inserted after the failed step with the same dependencies, at most
     * [MAX_RECOVERIES_PER_STEP] times; a step that has already used its retry is not given another.
     */
    fun fail(
        plan: Plan,
        id: String,
        reason: String,
        recoveryTitle: String? = null,
        recoveryId: String = id + RECOVERY_SUFFIX,
    ): Outcome {
        val step = plan.step(id) ?: return Outcome.Rejected("No step called '" + id + "'.")
        if (step.status == Status.DONE) {
            return Outcome.Rejected("Step '" + id + "' is already done; a failure cannot be invented for it.")
        }
        if (plan.revision >= MAX_REVISIONS) {
            return Outcome.Rejected("This plan has been revised " + plan.revision + " times. Stop and " +
                "report what is blocked instead of trying again.")
        }
        val failed = step.copy(status = Status.FAILED, note = reason.trim().ifBlank { "no reason given" })
        val withFailure = plan.steps.map { if (it.id == id) failed else it }
        val dead = newDeadSet(withFailure)
        val marked = withFailure.map {
            if (it.status == Status.PENDING && it.id in dead) it.copy(status = Status.SKIPPED) else it
        }
        val attemptCount = step.attempts
        // A recovery step may not spawn its own recovery: retry chains are how an agent spends twenty
        // minutes failing at the same thing instead of reporting the blocker.
        val isRecoveryStep = id.endsWith(RECOVERY_SUFFIX)
        val mayRecover = recoveryTitle != null && !isRecoveryStep &&
            attemptCount <= MAX_RECOVERIES_PER_STEP &&
            marked.none { it.id == recoveryId } &&
            marked.size < MAX_STEPS
        val steps = if (mayRecover) {
            insertAfter(marked, id, Step(
                id = recoveryId,
                title = recoveryTitle!!,
                dependsOn = step.dependsOn,
                milestone = step.milestone,
            ))
        } else {
            marked
        }
        val note = "revision " + (plan.revision + 1) + ": step '" + id + "' failed (" +
            failed.note + ")" + (if (mayRecover) "; added recovery step" else "")
        return Outcome.Ok(
            plan.copy(
                steps = steps,
                revision = plan.revision + 1,
                revisions = plan.revisions + note,
            )
        )
    }

    /** Steps that can no longer run because something they need is dead. */
    private fun newDeadSet(steps: List<Step>): Set<String> {
        val dead = steps.filter { it.status == Status.FAILED || it.status == Status.SKIPPED }
            .map { it.id }.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            steps.forEach { step ->
                if (step.status == Status.PENDING && step.dependsOn.any { it in dead } && step.id !in dead) {
                    dead.add(step.id)
                    changed = true
                }
            }
        }
        return dead
    }

    private fun insertAfter(steps: List<Step>, id: String, added: Step): List<Step> {
        val out = ArrayList<Step>(steps.size + 1)
        steps.forEach { step ->
            out.add(step)
            if (step.id == id) out.add(added)
        }
        if (out.none { it.id == added.id }) out.add(added)
        return out
    }

    /** Depth-first cycle hunt, returning the offending path so the message can name it. */
    private fun cycleOf(steps: List<Step>): List<String>? {
        val byId = steps.associateBy { it.id }
        val state = HashMap<String, Int>() // 0 unvisited, 1 on stack, 2 finished
        fun visit(id: String, path: MutableList<String>): List<String>? {
            when (state[id]) {
                1 -> return path.dropWhile { it != id } + id
                2 -> return null
            }
            state[id] = 1
            path.add(id)
            byId[id]?.dependsOn?.forEach { next ->
                visit(next, path)?.let { return it }
            }
            path.removeAt(path.size - 1)
            state[id] = 2
            return null
        }
        steps.forEach { step ->
            visit(step.id, ArrayList())?.let { return it }
        }
        return null
    }
}
