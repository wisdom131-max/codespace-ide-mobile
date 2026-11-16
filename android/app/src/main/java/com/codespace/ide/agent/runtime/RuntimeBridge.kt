package com.codespace.ide.agent.runtime

import com.codespace.ide.agent.goal.TaskPlanEngine
import com.codespace.ide.agent.heal.RecoveryPlanner
import com.codespace.ide.agent.reflect.ReflectionEngine
import com.codespace.ide.agent.reflect.ReflectionState
import com.codespace.ide.agent.subagents.DelegationScheduler
import com.codespace.ide.agent.subagents.SubagentRoles
import com.codespace.ide.chat.panel.ToolMeta

/**
 * PROJECT THREE RUNTIME (2026-11-16): the policy layers, connected to real work.
 *
 * Stages 3.3 to 3.6 produced four tested policies that nothing called: a role system, a delegation
 * scheduler, a recovery planner and a reflection engine. A policy nothing calls is a document. This
 * object is the only place that turns them into behaviour, and it is deliberately a *bridge*, not a
 * framework: every decision still belongs to the policy, and every effect still belongs to the
 * caller's executor. Nothing here executes a tool itself.
 *
 * The three connections:
 *
 *  - DISPATCH. [beforeDispatch] refuses work the plan has already written off, and [afterDispatch]
 *    records what actually happened so reflection has observed evidence rather than a claim.
 *  - RECOVERY. [recover] classifies a real failure, asks the planner, applies at most one repair that
 *    the current mode and the tool's own destructive flag permit, then demands evidence before it
 *    calls the repair a success.
 *  - DELEGATION. [delegate] narrows a role to the session's permitted tools, then runs the tasks the
 *    scheduler allows in the order it allows, with results going back to the plan and the reflection
 *    record.
 *
 * Security posture: this bridge can never widen authority. It holds no permission store, no approval
 * dialog and no bypass; the caller passes in the tools that are already permitted and the mode that
 * is already in force, and every refusal is expressed as text the model must read.
 */
internal object RuntimeBridge {

    /** What a tool call produced, for the two callers that care: the plan and the reflection record. */
    data class Dispatch(val name: String, val output: String, val failed: Boolean)

    // ── Dispatch gates ──────────────────────────────────────────────────────

    /**
     * May this call run at all?
     *
     * Two refusals only, both about the plan the user is watching: a cancelled plan stops taking
     * work, and a step whose prerequisites failed cannot be advanced by calling its tools. Nothing
     * here can approve or deny a tool: that is [com.codespace.ide.agent.ToolDispatchGuard] and the
     * approval UI, and a second answer to "may this run" is how two answers start disagreeing.
     */
    /**
     * STAGE 4.2: a capability with an unsatisfied REQUIRED prerequisite is refused here, by name.
     *
     * This is a dependency check, not a permission. The call would still have to pass the mode,
     * approval, MCP and role gates afterwards; this only stops work that cannot succeed yet, so the
     * model is told "find the match first" instead of silently changing nothing.
     */
    fun prerequisiteRefusal(toolName: String): String? {
        val capability = com.codespace.ide.chat.panel.ToolPrerequisites.capabilityOf(toolName)
            ?: return null
        val missing = com.codespace.ide.chat.panel.ToolPrerequisites.requiredOf(capability)
            .filter { !PrereqLedger.satisfied(it.of) }
        if (missing.isEmpty()) return null
        val blocked = missing.firstOrNull { PrereqLedger.failed(it.of) }
        if (blocked != null) {
            val detail = PrereqLedger.entry(blocked.of)?.evidence ?: "no detail"
            return "Refused: " + capability + " cannot run because " + blocked.of + " failed this turn (" +
                detail + "). Report what is blocked instead of retrying."
        }
        val first = missing.first()
        return "Refused: " + capability + " needs " + first.of + " first" +
            (if (first.why.isBlank()) "" else " (" + first.why + ")") +
            ". Do that, then call " + toolName + " again."
    }

    fun beforeDispatch(plan: TaskPlanEngine.Plan?, toolName: String): String? {
        val prerequisite = prerequisiteRefusal(toolName)
        if (prerequisite != null) return prerequisite
        if (plan == null) return null
        val running = plan.steps.filter { it.status == TaskPlanEngine.Status.RUNNING }
        val dead = plan.unreachable().map { it.id }.toSet()
        if (running.any { it.id in dead }) {
            return "Refused: step '" + running.first().id +
                "' can no longer run because a step it depends on failed. Mark it failed or revise the plan."
        }
        // The other way a plan goes nowhere: nothing is running, nothing is ready, and the plan is
        // not finished. Every remaining step is failed, skipped, or waiting on one of those, so
        // another tool call cannot move it. Say so instead of letting the agent flail.
        //
        // `task_plan` itself stays dispatchable: it is how the model revises or clears a dead plan,
        // and a gate that refused it would deadlock the only exit.
        if (toolName != "task_plan" && running.isEmpty() && plan.ready().isEmpty() &&
            !plan.isFinished()
        ) {
            val stalled = plan.steps.filter { it.status != TaskPlanEngine.Status.DONE }.map { it.id }
            return "Refused: no step can run now (" + stalled.joinToString(", ") +
                " cannot run after a failure). Revise the plan with task_plan or report what is blocked."
        }
        return null
    }

    /** Re-exported so callers do not need to know which failure text counts as a failure. */
    fun failureText(output: String): Boolean = ToolFailureProbe.looksFailed(output)

    /**
     * Record an outcome against the running step and the session's reflection record.
     *
     * A step is never completed here. Completion is the model's deliberate act, and it requires
     * evidence; this only makes sure the evidence that exists is written down when it is produced,
     * so "I finished it" cannot be invented later from nothing.
     */
    fun afterDispatch(
        name: String,
        output: String,
        failed: Boolean,
        sessionId: String? = null,
    ): Dispatch {
        // 4.2: the ledger remembers what actually succeeded, and what failed, because a dependent
        // capability is gated on evidence rather than on intent.
        com.codespace.ide.chat.panel.ToolPrerequisites.capabilityOf(name)?.let { capability ->
            if (failed) {
                PrereqLedger.recordFailure(capability, name, output)
            } else {
                PrereqLedger.record(capability, name, output)
            }
        }
        ReflectionState.recordTool(name, output)
        val plan = com.codespace.ide.agent.goal.PlanState.plan(sessionId) ?: return Dispatch(name, output, failed)
        val running = plan.steps.firstOrNull { it.status == TaskPlanEngine.Status.RUNNING }
            ?: return Dispatch(name, output, failed)
        ReflectionState.addEvidence(
            claim = if (failed) "step failed: " + running.id else "step work: " + running.id,
            level = ReflectionEngine.Level.IMPLEMENTED,
            observed = name + ": " + output.take(240),
            subject = running.id,
        )
        return Dispatch(name, output, failed)
    }

    // ── Recovery ────────────────────────────────────────────────────────────

    /** What the recovery path decided and did, kept in parts so the four steps stay distinguishable. */
    data class Recovery(
        val proposal: RecoveryPlanner.Plan,
        val applied: Boolean,
        val appliedKind: RecoveryPlanner.RepairKind?,
        val validated: Boolean,
        val output: String,
        val refusal: String = "",
    )

    /**
     * Ask the planner about a real failure, apply at most one permitted repair, and validate it.
     *
     * [execute] is the caller's own dispatch, so a repair is a normal tool call that goes through
     * every gate it would have anyway. A repair is never repeated: one attempt, one validation, then
     * the original error stands beside whatever the repair achieved.
     */
    suspend fun recover(
        failure: RecoveryPlanner.Failure,
        execute: suspend (name: String, args: Map<String, String>) -> String,
        argsFor: Map<String, String> = emptyMap(),
        repairsAlreadyTried: Int = 0,
    ): Recovery {
        val plan = RecoveryPlanner.plan(failure, repairsAlreadyTried)
        val chosen = plan.auto()
        if (chosen == null) {
            return Recovery(
                proposal = plan,
                applied = false,
                appliedKind = null,
                validated = false,
                output = failure.error,
                refusal = plan.withheld.ifBlank { "no repair was permitted for this failure" },
            )
        }
        // A repair is a normal dispatch. If it fails or throws, the original error is what the model
        // reads, with the attempt recorded underneath it.
        val repaired = runCatching {
            execute(failure.tool, argsFor)
        }.getOrElse { "recovery attempt failed: " + it.message }
        // A repair is only "recovered" if the retried call produced something that is not another
        // failure. The observation is what makes that checkable later.
        val validated = repaired.isNotBlank() && !ToolFailureProbe.looksFailed(repaired)
        ReflectionState.addEvidence(
            claim = "recovery " + chosen.kind + " for " + failure.tool,
            level = ReflectionEngine.Level.IMPLEMENTED,
            observed = (if (validated) "validated: " else "NOT validated: ") + repaired.take(200),
            subject = "recovery",
        )
        return Recovery(
            proposal = plan,
            applied = true,
            appliedKind = chosen.kind,
            validated = validated,
            output = repaired,
        )
    }

    // ── Delegation ──────────────────────────────────────────────────────────

    data class Delegated(
        val board: DelegationScheduler.Board,
        val results: Map<String, String>,
        val refusals: List<String>,
    ) {
        fun summary(): String = buildString {
            append("delegated ").append(results.size).append(" of ")
            append(board.tasks.size).append(" task(s)")
            if (refusals.isNotEmpty()) append(", refused ").append(refusals.size)
        }
    }

    /**
     * Run the work the scheduler allows, and only that work.
     *
     * Roles are narrowed here rather than trusted: [permitted] is the session's own tool set, and a
     * role can only ever intersect it. Reads that share an allowed lane run together; a task that
     * writes runs alone. A failure inside one task does not cancel its siblings, but it does mark the
     * task so the plan and the reflection see it.
     */
    suspend fun delegate(
        board: DelegationScheduler.Board,
        requests: List<DelegationScheduler.Request>,
        permitted: Set<String>,
        metaOf: (String) -> ToolMeta?,
        execute: suspend (DelegationScheduler.Task) -> String,
        now: () -> Long = { System.currentTimeMillis() },
    ): Delegated {
        val enqueued = DelegationScheduler.enqueue(board, requests, permitted, metaOf)
        var current = enqueued.board
        val results = LinkedHashMap<String, String>()
        val refusals = enqueued.refused.map { it.first.title + ": " + it.second }.toMutableList()
        // A bounded loop: every pass either starts work or is the last pass, so a scheduler that
        // returns nothing runnable cannot spin here.
        var spins = 0
        // STAGE 4.3: the wave the scheduler computes is now the wave that RUNS. Read-only tasks in
        // one wave go together (bounded); a task that writes runs alone, after the readers. Every
        // task still goes through `execute`, which is the real dispatch path, so mode, approval, MCP
        // and role checks are untouched by running two things at once.
        while (spins++ < DelegationScheduler.MAX_TASKS + 2) {
            val wave = DelegationScheduler.wave(current)
            if (wave.isEmpty()) break
            val steps = wave.map { task ->
                Orchestrator.Step(
                    id = task.id,
                    capability = task.id,
                    tool = task.tool,
                    reads = task.reads,
                    writes = task.writes,
                    exclusive = task.isWriter,
                )
            }
            val byId = wave.associateBy { it.id }
            val run = Orchestrator(
                execute = { step ->
                    val task = byId[step.id]!!
                    current = DelegationScheduler.start(current, task.id, now())
                    val output = runCatching { execute(task) }
                        .getOrElse { "task failed: " + (it.message ?: "no message") }
                    val ok = !ToolFailureProbe.looksFailed(output)
                    current = if (ok) {
                        DelegationScheduler.complete(
                            board = current,
                            id = task.id,
                            evidence = output.take(400),
                            verified = true,
                            now = now(),
                        )
                    } else {
                        DelegationScheduler.fail(current, task.id, output.take(400), now())
                    }
                    results[task.id] = output
                    ReflectionState.recordTool("subagent:" + task.roleId, output)
                    ReflectionState.addEvidence(
                        claim = "delegated " + task.roleId + ": " + task.title,
                        level = ReflectionEngine.Level.IMPLEMENTED,
                        observed = task.tool + ": " + output.take(160),
                        subject = task.planStepId.orEmpty(),
                    )
                    Orchestrator.StepResult(ok, output.take(400), verified = ok)
                },
            ).run(listOf(steps))
            if (!run.ok) break
        }
        val leftovers = current.tasks.filter {
            it.state == DelegationScheduler.State.QUEUED || it.state == DelegationScheduler.State.BLOCKED
        }
        leftovers.forEach { task ->
            refusals.add(task.title + ": " + (task.note.ifBlank { "never became runnable" }))
        }
        return Delegated(current, results, refusals)
    }

    /** Tool names a role may reach, given what this session already permits. */
    fun roleTools(roleId: String, permitted: Set<String>, metaOf: (String) -> ToolMeta?): Set<String> =
        when (val scope = SubagentRoles.scope(roleId, permitted, metaOf)) {
            is SubagentRoles.Result.Ok -> scope.scope.tools
            is SubagentRoles.Result.Refused -> emptySet()
        }
}
