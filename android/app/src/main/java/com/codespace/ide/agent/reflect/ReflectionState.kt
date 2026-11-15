package com.codespace.ide.agent.reflect

import com.codespace.ide.agent.goal.PlanState
import com.codespace.ide.agent.goal.TaskPlanEngine
import com.codespace.ide.agent.subagents.DelegationScheduler

/**
 * PROJECT 3.7 (2026-11-15): the observation record reflection reads.
 *
 * [ReflectionEngine] is pure: it judges what it is given. This is the thing that gives it, without
 * asking the model. Every tool result is recorded at the one place tools return, so "what actually
 * ran" is a fact about the session rather than a sentence in the transcript. Evidence and acceptance
 * criteria are recorded deliberately, because a claim needs to name its observation.
 *
 * Scoped per conversation, like [PlanState], and bounded: a long session must not grow without limit
 * on a phone.
 */
internal object ReflectionState {

    private const val CAP = 200

    data class Snapshot(
        val actions: List<ReflectionEngine.Action> = emptyList(),
        val evidence: List<ReflectionEngine.Evidence> = emptyList(),
        val criteria: List<ReflectionEngine.Criterion> = emptyList(),
        val delegation: DelegationScheduler.Board? = null,
        val recoveryLines: List<String> = emptyList(),
    )

    private val bySession = HashMap<String, Snapshot>()
    private var currentSession: String? = null

    /** The conversation the chat layer is currently working in. */
    fun claim(sessionId: String?) {
        currentSession = sessionId
        if (sessionId != null && !bySession.containsKey(sessionId)) bySession[sessionId] = Snapshot()
    }

    fun current(): Snapshot = currentSession?.let { bySession[it] } ?: Snapshot()

    /** Record one tool result. Called from the single place tools return, never by the model. */
    fun recordTool(tool: String, output: String): ReflectionEngine.Action {
        val failed = output.startsWith("Error executing ") || output.startsWith("Refused:") ||
            output.startsWith("Blocked:") || output.startsWith("Denied:")
        val running = PlanState.plan()?.steps?.firstOrNull { it.status == TaskPlanEngine.Status.RUNNING }
        val summary = output.lineSequence().firstOrNull()?.take(160) ?: ""
        val action = ReflectionEngine.Action(
            tool = tool,
            planStepId = running?.id,
            ok = !failed,
            summary = summary,
        )
        val session = currentSession
        val snapshot = current()
        val actions = (snapshot.actions + action).takeLast(CAP)
        if (session != null) bySession[session] = snapshot.copy(actions = actions)
        return action
    }

    /** The model's claim, with the observation that supports it. */
    fun addEvidence(claim: String, level: ReflectionEngine.Level, observed: String, subject: String = "") {
        if (claim.isBlank()) return
        val session = currentSession
        val snapshot = current()
        val evidence = (snapshot.evidence + ReflectionEngine.Evidence(claim.trim(), level, observed, subject))
            .takeLast(CAP)
        if (session != null) bySession[session] = snapshot.copy(evidence = evidence)
    }

    fun addCriterion(text: String, level: ReflectionEngine.Level) {
        if (text.isBlank()) return
        val session = currentSession
        val snapshot = current()
        val criteria = (snapshot.criteria + ReflectionEngine.Criterion(text.trim(), level)).takeLast(40)
        if (session != null) bySession[session] = snapshot.copy(criteria = criteria)
    }

    fun setDelegation(board: DelegationScheduler.Board?) {
        val session = currentSession ?: return
        bySession[session] = current().copy(delegation = board)
    }

    fun setRecoveryLines(lines: List<String>) {
        val session = currentSession ?: return
        bySession[session] = current().copy(recoveryLines = lines.takeLast(40))
    }

    fun clear() {
        val session = currentSession ?: return
        bySession[session] = Snapshot()
    }

    /** Reflection over the live plan and this session's record. */
    fun reflect(objectiveOverride: String? = null): ReflectionEngine.Reflection {
        val plan = PlanState.plan()
        val snapshot = current()
        return ReflectionEngine.reflect(
            objective = objectiveOverride?.takeIf { it.isNotBlank() } ?: plan?.objective.orEmpty(),
            plan = plan,
            actions = snapshot.actions,
            evidence = snapshot.evidence,
            criteria = snapshot.criteria,
            delegation = snapshot.delegation,
            recoveryLines = snapshot.recoveryLines,
        )
    }
}
